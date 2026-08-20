package com.ticketmaster.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Backs the routes' {@code RequestRateLimiter} filter (application.yml).
 *
 * Deliberately the remote socket address, not a header: X-Forwarded-For is
 * attacker-controlled input on any request that reaches this filter
 * directly, so trusting it here would let a client set a fresh value on
 * every request and rate-limit as nobody. Honouring it correctly needs a
 * trusted-proxy allowlist once a real load balancer sits in front
 * (ADR-019); until then the socket address is the only value the request
 * itself cannot forge.
 *
 * IP-keyed only, and stays that way - this is the volumetric shield, not
 * the account-abuse control. It cannot see the request body (WebFlux/Netty
 * streams it; buffering here to key by username would defeat the point of
 * a non-blocking gateway and open a memory-exhaustion DoS surface). The
 * tight, per-username defense lives in auth-service's LoginAttemptLimiter,
 * where the body is already safely parsed - see wiki/projects/api-gateway.md.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            var remote = exchange.getRequest().getRemoteAddress();
            // Grouping a null address under one shared bucket is a
            // deliberate fail-safe, matching the previous filter's own
            // convention: skipping the check would treat any request with
            // no resolvable address as unlimited rather than merely
            // coarse-grained.
            String ip = (remote == null || remote.getAddress() == null)
                    ? "unknown"
                    : remote.getAddress().getHostAddress();
            return Mono.just(ip);
        };
    }
}
