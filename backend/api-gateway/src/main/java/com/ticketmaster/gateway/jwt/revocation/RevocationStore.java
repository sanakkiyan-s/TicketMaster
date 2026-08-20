package com.ticketmaster.gateway.jwt.revocation;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory revocation map (ADR-012), materialized from {@code
 * auth.revocation} by {@link RevocationConsumer} and read by {@code
 * JwtAuthenticationFilter} on every request.
 *
 * Mirrors {@code JwksCache}'s shape - a thread-safe, background-refreshed
 * cache - but a {@link ConcurrentHashMap} is the right structure here
 * (instead of JwksCache's atomic wholesale swap) because revocation entries
 * arrive and expire independently of one another; there is no "whole set
 * replaced at once" moment to protect a reader from.
 *
 * Keys are exactly the Kafka record keys: {@code "user:<uuid>"} or
 * {@code "session:<sid>"}. Callers (the consumer, the sweep, the filter) all
 * agree on that string shape rather than each re-deriving it.
 */
@Component
public class RevocationStore {

    private final Map<String, RevocationEntry> entries = new ConcurrentHashMap<>();

    /** Applied for every non-tombstone record read off the topic. */
    public void apply(String key, RevocationEntry entry) {
        entries.put(key, entry);
    }

    /** Applied for a Kafka tombstone (null value) on a compacted topic. */
    public void remove(String key) {
        entries.remove(key);
    }

    /**
     * @param key      {@code "user:<uuid>"} or {@code "session:<sid>"} -
     *                 exactly the gateway's own claim reads, reused rather
     *                 than re-derived (see JwtAuthenticationFilter).
     * @param issuedAt the token's {@code iat}.
     * @return true if this subject/session was banned at or after the token
     *         was minted - a token issued AFTER the ban is a fresh login and
     *         must not be rejected by a stale entry.
     */
    public boolean isRevoked(String key, Instant issuedAt) {
        RevocationEntry entry = entries.get(key);
        return entry != null && issuedAt.isBefore(entry.revokeBefore());
    }

    /**
     * Tombstones entries whose {@code revokeBefore} is far enough in the
     * past that no token issued before it could still be alive - ADR-012's
     * "bounded memory" argument. Only {@code revokeBefore + maxTokenLifetime
     * < now} entries are eligible: anything newer might still be actively
     * rejecting a live token.
     */
    void sweep(Instant now, Duration maxTokenLifetime) {
        entries.entrySet().removeIf(e -> e.getValue().revokeBefore().plus(maxTokenLifetime).isBefore(now));
    }

    /** Exposed for alarming/logging context, not a correctness dependency. */
    public int size() {
        return entries.size();
    }
}
