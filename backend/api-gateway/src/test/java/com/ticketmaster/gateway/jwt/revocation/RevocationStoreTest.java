package com.ticketmaster.gateway.jwt.revocation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure decision logic behind ADR-012's revocation check, isolated from
 * Kafka entirely - what needs proving here is the iat/revokeBefore
 * arithmetic and the tombstone boundary, not broker behaviour.
 */
class RevocationStoreTest {

    @Test
    void aTokenIssuedBeforeTheBanIsRevoked() {
        RevocationStore store = new RevocationStore();
        Instant revokeBefore = Instant.now();
        store.apply("user:abc", new RevocationEntry(revokeBefore, "banned"));

        assertTrue(store.isRevoked("user:abc", revokeBefore.minusSeconds(1)));
    }

    @Test
    void aTokenIssuedAfterTheBanIsNotRevoked() {
        // A fresh login/refresh that happens after the ban must keep
        // working - otherwise a banned-then-unbanned user could never log
        // back in.
        RevocationStore store = new RevocationStore();
        Instant revokeBefore = Instant.now();
        store.apply("user:abc", new RevocationEntry(revokeBefore, "banned"));

        assertFalse(store.isRevoked("user:abc", revokeBefore.plusSeconds(1)));
    }

    @Test
    void anUnknownKeyIsNeverRevoked() {
        RevocationStore store = new RevocationStore();
        assertFalse(store.isRevoked("user:never-banned", Instant.now()));
    }

    @Test
    void sessionRevocationDoesNotAffectAUserKey() {
        RevocationStore store = new RevocationStore();
        Instant revokeBefore = Instant.now();
        store.apply("session:device-1", new RevocationEntry(revokeBefore, "single device logout"));

        assertTrue(store.isRevoked("session:device-1", revokeBefore.minusSeconds(1)));
        assertFalse(store.isRevoked("user:abc", revokeBefore.minusSeconds(1)));
    }

    @Test
    void sweepTombstonesEntriesOlderThanMaxTokenLifetime() {
        RevocationStore store = new RevocationStore();
        Duration maxTokenLifetime = Duration.ofMinutes(10);
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(30));
        store.apply("user:stale", new RevocationEntry(longAgo, "old ban"));

        store.sweep(Instant.now(), maxTokenLifetime);

        assertEquals(0, store.size());
        // A token could not possibly still be alive from before `longAgo` -
        // any token issued that early expired long ago - so removal must
        // not resurrect the ban.
        assertFalse(store.isRevoked("user:stale", longAgo.minusSeconds(1)));
    }

    @Test
    void sweepKeepsEntriesWhoseWindowHasNotElapsed() {
        RevocationStore store = new RevocationStore();
        Duration maxTokenLifetime = Duration.ofMinutes(10);
        Instant recent = Instant.now().minus(Duration.ofMinutes(2));
        store.apply("user:recent", new RevocationEntry(recent, "recent ban"));

        store.sweep(Instant.now(), maxTokenLifetime);

        assertEquals(1, store.size());
        assertTrue(store.isRevoked("user:recent", recent.minusSeconds(1)));
    }

    @Test
    void removeTombstonesImmediately() {
        RevocationStore store = new RevocationStore();
        store.apply("user:abc", new RevocationEntry(Instant.now(), "banned"));

        store.remove("user:abc");

        assertFalse(store.isRevoked("user:abc", Instant.now().minusSeconds(1)));
    }
}
