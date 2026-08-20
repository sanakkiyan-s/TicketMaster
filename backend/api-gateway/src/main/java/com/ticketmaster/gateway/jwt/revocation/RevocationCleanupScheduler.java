package com.ticketmaster.gateway.jwt.revocation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Runs {@link RevocationStore}'s tombstone sweep on a timer. Split out from
 * {@link RevocationStore} itself so the store stays a plain thread-safe
 * collection with no scheduling concerns, and this class stays a single
 * responsibility that is trivial to disable in a test context.
 */
@Component
class RevocationCleanupScheduler {

    private final RevocationStore store;
    private final RevocationProperties properties;

    RevocationCleanupScheduler(RevocationStore store, RevocationProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${gateway.revocation.sweep-interval}",
            fixedDelayString = "${gateway.revocation.sweep-interval}")
    void sweep() {
        store.sweep(Instant.now(), properties.maxTokenLifetime());
    }
}
