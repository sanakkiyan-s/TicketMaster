package com.ticketmaster.gateway.jwt;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness must reflect whether this instance can verify a token at all.
 *
 * ADR-032 keeps liveness dependency-blind (a gateway that cannot reach
 * auth-service is not broken and must not be restarted) while allowing
 * readiness to be dependency-aware. Without this, Kubernetes would route
 * traffic to an instance whose key cache is empty, and every authenticated
 * request would get a 503 that no probe ever noticed.
 */
@Component("jwks")
class JwksHealthIndicator implements HealthIndicator {

    private final JwksCache jwks;

    JwksHealthIndicator(JwksCache jwks) {
        this.jwks = jwks;
    }

    @Override
    public Health health() {
        return jwks.isReady()
                ? Health.up().build()
                : Health.down().withDetail("reason", "JWKS has never loaded").build();
    }
}
