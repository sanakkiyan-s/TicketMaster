package com.ticketmaster.gateway.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.Key;
import java.security.KeyFactory;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public keys from auth-service, held in memory (ADR-012).
 *
 * The refresh is a BACKGROUND timer and nothing else. There is deliberately no
 * method here that fetches on demand, because the dangerous design is the
 * obvious one: fetch whenever an unknown `kid` arrives. That turns
 * unauthenticated traffic into an amplified attack - an attacker sends
 * requests carrying random `kid` values and every one of them becomes an
 * outbound request to auth-service, from every gateway instance at once,
 * precisely when auth-service is least able to absorb it.
 *
 * A correctly-run rotation never produces an unknown `kid`: ADR-012's phase 1
 * publishes the new key at least one cache TTL before anything signs with it,
 * so every gateway already holds it. An unknown `kid` therefore means either
 * an attack or a botched rotation, and neither is improved by hammering
 * auth-service.
 */
@Component
public class JwksCache {

    private static final Logger log = LoggerFactory.getLogger(JwksCache.class);

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final JwtValidationProperties properties;

    /** Replaced wholesale on refresh, so a reader never sees a half-built map. */
    private final AtomicReference<Map<String, Key>> keys = new AtomicReference<>(Map.of());

    private final AtomicReference<Boolean> everLoaded = new AtomicReference<>(false);

    JwksCache(WebClient.Builder http, ObjectMapper objectMapper, JwtValidationProperties properties) {
        this.http = http.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Key find(String kid) {
        return keys.get().get(kid);
    }

    /**
     * False until the first successful fetch. The gateway must not report
     * ready while it cannot verify a single token: it would answer every
     * authenticated request with a 401, which looks to a client exactly like
     * "your credentials are wrong" rather than "this instance is broken".
     */
    public boolean isReady() {
        return everLoaded.get();
    }

    /**
     * fixedDelay, not fixedRate: fixedRate keeps queueing runs while a slow
     * fetch is still in flight, so an auth-service outage would be met with a
     * growing pile of concurrent retries.
     */
    // A property placeholder, not SpEL against the properties bean: a record
    // registered by @ConfigurationPropertiesScan gets a prefixed bean name
    // ("gateway.jwt-com.ticketmaster...."), so @jwtValidationProperties does
    // not resolve and the context fails to start. Spring parses an ISO-8601
    // duration here directly.
    @Scheduled(initialDelay = 0, fixedDelayString = "${gateway.jwt.refresh-interval}")
    public void refresh() {
        try {
            String body = http.get()
                    .uri(properties.jwksUri())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            Map<String, Key> parsed = parse(body);

            if (parsed.isEmpty()) {
                // Never replace a working key set with an empty one. An
                // auth-service that answers with an empty JWKS - mid-deploy,
                // say - would otherwise take every gateway down with it.
                log.warn("JWKS fetch returned no usable keys; keeping the previous set");
                return;
            }

            keys.set(parsed);
            everLoaded.set(true);
            log.debug("refreshed JWKS: {} key(s)", parsed.size());

        } catch (RuntimeException e) {
            // Serving from the last known good set is correct: those keys are
            // still valid, and a rotation cannot invalidate them faster than
            // ADR-012's phases allow.
            log.warn("could not refresh JWKS from {}; serving the cached set", properties.jwksUri(), e);
        }
    }

    /**
     * Fast retry until the FIRST successful load, then silent.
     *
     * Without this the gateway is unusable for a full refresh interval after
     * any start that beats auth-service to the punch: the initial fetch gets
     * "connection refused", and the steady-state timer does not come round
     * again for five minutes. Observed exactly that on the first end-to-end
     * run - both services launched together, and the gateway answered 503 for
     * everything while auth-service sat there perfectly healthy.
     *
     * Only the cold-start gap is worth retrying aggressively. Once a key set
     * is in memory a failed refresh is harmless, because the cached keys stay
     * valid and ADR-012's phases guarantee a rotation cannot outrun them.
     */
    @Scheduled(fixedDelay = 5000)
    public void retryUntilFirstLoad() {
        if (everLoaded.get()) {
            return;
        }
        refresh();
    }

    private Map<String, Key> parse(String body) {
        Map<String, Key> parsed = new HashMap<>();
        try {
            JsonNode keysNode = objectMapper.readTree(body).path("keys");

            for (JsonNode key : keysNode) {
                if (!"RSA".equals(key.path("kty").asText()) || !"sig".equals(key.path("use").asText())) {
                    continue;
                }

                // base64URL, not base64: the JOSE alphabet uses - and _ and
                // carries no padding. Decoding with the standard alphabet
                // silently produces the wrong modulus on roughly half of all
                // keys, and the only symptom is that every signature fails.
                Base64.Decoder decoder = Base64.getUrlDecoder();
                BigInteger modulus = new BigInteger(1, decoder.decode(key.path("n").asText()));
                BigInteger exponent = new BigInteger(1, decoder.decode(key.path("e").asText()));

                parsed.put(key.path("kid").asText(),
                        KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
        } catch (Exception e) {
            log.warn("could not parse JWKS document", e);
            return Map.of();
        }
        return Map.copyOf(parsed);
    }
}
