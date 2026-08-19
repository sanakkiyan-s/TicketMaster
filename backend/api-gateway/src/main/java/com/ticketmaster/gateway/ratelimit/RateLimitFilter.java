package com.ticketmaster.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Per-IP throttling on unauthenticated endpoints (ADR-014's rate-limiting
 * plank, concretely implemented here for the first time).
 *
 * Deliberately IP-keyed, not user-keyed: login and register are exactly the
 * requests made before any identity exists to key by. Runs ahead of
 * JwtAuthenticationFilter (order HIGHEST_PRECEDENCE + 50 vs + 100), so a
 * flooding client is turned away before the token-parsing work runs at all.
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final RedisScript<List<Long>> SCRIPT = loadScript();

    @SuppressWarnings("unchecked")
    private static RedisScript<List<Long>> loadScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/rate_limit.lua"));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties properties;

    RateLimitFilter(ReactiveStringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        Optional<RateLimitRule> rule = properties.rules().stream()
                .filter(r -> path.startsWith(r.pathPrefix()))
                .findFirst();

        if (rule.isEmpty()) {
            return chain.filter(exchange);
        }

        String key = "ratelimit:" + rule.get().pathPrefix() + ":" + clientIp(exchange);
        String windowSeconds = String.valueOf(rule.get().window().toSeconds());

        return redis.execute(SCRIPT, List.of(key), List.of(windowSeconds))
                .single()
                .flatMap(counts -> {
                    long count = counts.get(0);
                    long ttl = counts.get(1);

                    int remaining = (int) Math.max(0, rule.get().limit() - count);
                    exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(rule.get().limit()));
                    exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(remaining));

                    if (count > rule.get().limit()) {
                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().add(HttpHeaders.RETRY_AFTER, String.valueOf(ttl));
                        return exchange.getResponse().writeWith(Mono.just(
                                exchange.getResponse().bufferFactory().wrap(
                                        "{\"title\":\"Too Many Requests\",\"status\":429}"
                                                .getBytes(StandardCharsets.UTF_8))));
                    }

                    return chain.filter(exchange);
                })
                // Redis unreachable must not take the login path down with it.
                // Rate limiting is a defense-in-depth control on top of BCrypt's
                // own cost and the account-enumeration protections already in
                // auth-service; losing it during a Redis outage is a narrowed
                // defense, not an open door, and failing closed here would mean
                // a Redis blip locks every user out of logging in at all.
                .onErrorResume(e -> {
                    log.warn("rate limiter could not reach Redis for path {}; allowing the request through", path, e);
                    return chain.filter(exchange);
                });
    }

    /**
     * The remote address of the actual TCP connection, not a header.
     *
     * X-Forwarded-For is attacker-controlled input on any request that
     * reaches this filter directly - trusting it here would let a client
     * simply set a fresh value on every request and rate-limit as nobody.
     * The right way to honour it is a trusted-proxy allowlist once a real
     * load balancer sits in front (ADR-019); until then, the socket address
     * is the only value that cannot be spoofed by the request itself.
     */
    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        // Grouping a null address under one shared bucket is a deliberate
        // fail-safe: skipping the check on null would be an easy bypass if
        // any code path (a test harness, a future transport) ever produced
        // one, since the filter would then treat every such request as
        // unlimited rather than merely coarse-grained.
        return remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
    }
}
