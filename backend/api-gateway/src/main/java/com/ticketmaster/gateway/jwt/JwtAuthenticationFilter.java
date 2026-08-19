package com.ticketmaster.gateway.jwt;

import com.ticketmaster.gateway.jwt.revocation.RevocationStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies every token at the edge, in process (ADR-012).
 *
 * The verification is local arithmetic against a cached public key - no call
 * to auth-service on the request path. That is the entire reason auth-service
 * does not have to scale with total system traffic.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwksCache jwks;
    private final JwtValidationProperties properties;
    private final RevocationStore revocations;

    /** Unknown kid -> when it was first seen, purely to keep the log readable. */
    private final Map<String, Instant> unknownKids = new ConcurrentHashMap<>();

    JwtAuthenticationFilter(JwksCache jwks, JwtValidationProperties properties, RevocationStore revocations) {
        this.jwks = jwks;
        this.properties = properties;
        this.revocations = revocations;
    }

    /**
     * Ahead of routing, so an invalid token is rejected before any connection
     * to a downstream service is opened. A filter that ran after routing would
     * let unauthenticated traffic reach the services it is meant to protect.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isPublic(path)) {
            return chain.filter(exchange);
        }

        if (!jwks.isReady()) {
            // 503, never 401. This instance cannot verify anything, which is
            // our failure, not the caller's - and a 401 would tell a client
            // with a perfectly good token to throw it away and re-login.
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE);
        }

        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        try {
            Claims claims = Jwts.parser()
                    // The key is chosen by the token's own kid, but ONLY from
                    // keys already in the cache. A missing kid throws, and
                    // nothing here fetches - see JwksCache for why lazily
                    // fetching on unknown kid is an amplification vector.
                    .keyLocator(header1 -> {
                        String kid = (String) header1.get("kid");
                        Key key = kid == null ? null : jwks.find(kid);
                        if (key == null) {
                            recordUnknownKid(kid);
                            throw new JwtException("unknown kid");
                        }
                        return key;
                    })
                    .requireIssuer(properties.issuer())
                    .requireAudience(properties.audience())
                    .clockSkewSeconds(properties.clockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(header.substring(BEARER.length()))
                    .getPayload();

            // Revocation (ADR-012) - strictly AFTER local signature
            // validation succeeded above, never a substitute for it. `sub`
            // is already "user:<uuid>", exactly the revocation topic's key
            // shape for a whole-user ban; `sid` is read the same way this
            // token's session id is read anywhere else in the gateway, so
            // there is exactly one place that knows the claim name.
            Instant issuedAt = claims.getIssuedAt().toInstant();
            String subject = claims.getSubject();
            String sessionId = claims.get("sid", String.class);

            boolean userRevoked = subject != null && revocations.isRevoked(subject, issuedAt);
            boolean sessionRevoked = sessionId != null
                    && revocations.isRevoked("session:" + sessionId, issuedAt);

            if (userRevoked || sessionRevoked) {
                // Same 401 shape as every other JWT rejection below -
                // revocation is not distinguishable from "bad token" to the
                // caller, on purpose (see the catch block's own comment).
                return reject(exchange, HttpStatus.UNAUTHORIZED);
            }

            // The Authorization header is forwarded UNCHANGED rather than
            // stripped and replaced with something like X-User-Id. A trusted
            // identity header is only safe when downstream services are
            // unreachable except through this gateway; on a flat network any
            // workload could set that header and become any user. ADR-009's
            // mesh mTLS is the prerequisite, and it is not in place yet.
            ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("X-Request-Subject", claims.getSubject())
                    .build();

            return chain.filter(exchange.mutate().request(request).build());

        } catch (JwtException | IllegalArgumentException e) {
            // One 401 for every cause - bad signature, expired, wrong
            // audience, unknown kid. Distinguishing them tells an attacker
            // which part of a forged token to fix next.
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
    }

    private boolean isPublic(String path) {
        return properties.publicPaths().stream().anyMatch(path::startsWith);
    }

    /**
     * Logged once per kid per window. A flood of forged kids would otherwise
     * turn the log itself into the outage - the amplification moves from
     * auth-service to disk.
     */
    private void recordUnknownKid(String kid) {
        Instant now = Instant.now();
        Duration ttl = properties.unknownKidTtl();

        unknownKids.entrySet().removeIf(entry -> entry.getValue().plus(ttl).isBefore(now));

        if (unknownKids.putIfAbsent(String.valueOf(kid), now) == null) {
            log.warn("token presented with unknown kid={} - no JWKS fetch was made "
                    + "(ADR-012); a correct rotation never produces this", kid);
        }
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return exchange.getResponse().setComplete();
    }
}
