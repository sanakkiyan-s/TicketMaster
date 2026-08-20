package com.ticketmaster.gateway.jwt.revocation;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The fail-closed carve-out itself (ADR-012, ADR-032).
 *
 * Mirrors {@code JwksHealthIndicator}'s shape, but with the opposite
 * philosophy behind it: JWKS readiness is "can this instance verify
 * anything at all" (fail-closed for the same reason every request would 401
 * otherwise); revocation readiness is a DELIBERATE, named exception to this
 * project's normal fail-open convention. Reports DOWN until the consumer
 * has finished its initial catch-up read of {@code auth.revocation} -
 * serving traffic before that would mean validating tokens against an
 * incomplete revocation map, silently un-revoking whichever bans landed in
 * the unread tail. Once true it never reports DOWN again for a later Kafka
 * disconnect - see {@code RevocationConsumer} for that half of the rule.
 */
@Component("revocation")
class RevocationHealthIndicator implements HealthIndicator {

    private final RevocationConsumer consumer;

    RevocationHealthIndicator(RevocationConsumer consumer) {
        this.consumer = consumer;
    }

    @Override
    public Health health() {
        return consumer.isCaughtUp()
                ? Health.up().build()
                : Health.down()
                        .withDetail("reason", "revocation consumer has not finished its initial "
                                + "catch-up read of auth.revocation (ADR-012 fail-closed carve-out)")
                        .build();
    }
}
