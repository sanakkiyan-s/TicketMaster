package com.ticketmaster.inventory.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * ADR-002 Option D's fast admission gate: a single-instance Redis SETNX
 * (not Redlock) that rejects a losing hold attempt in microseconds,
 * before it ever touches a Postgres connection. Postgres remains the
 * correctness authority regardless of this gate's outcome — see
 * SeatInventoryService.holdSeat, which still does the real
 * {@code SELECT ... FOR UPDATE} even after this gate says "proceed".
 *
 * Fail-open on any Redis error (timeout, connection refused, blackhole):
 * a Redis outage degrades this service to Option A's behavior (every
 * request pays a Postgres row-lock wait) rather than corrupting data or
 * refusing all holds. ADR-002's amendment calls for a ~50ms command
 * timeout + Resilience4j circuit breaker on top of this; only the
 * timeout is wired here today (spring.data.redis.timeout in
 * application.yml) — the circuit breaker is a deliberate, noted
 * follow-up, not silently dropped: without it, a blackholed Redis still
 * costs every request the same ~50ms wait rather than failing instantly,
 * which is correct-but-slower, not broken.
 */
@Component
public class SeatLockGate {

    private static final Logger log = LoggerFactory.getLogger(SeatLockGate.class);
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final String KEY_PREFIX = "seat-lock:";

    private final StringRedisTemplate redis;

    public SeatLockGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @return true if this caller won the fast gate (or Redis was
     *         unavailable — fail open), false if someone else already
     *         holds the gate for this seat.
     */
    public boolean tryAcquire(UUID sessionId, UUID seatId, UUID holderId) {
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key(sessionId, seatId), holderId.toString(), LOCK_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            log.warn("ALERT seat-lock Redis gate unavailable, failing open to Postgres row-lock "
                    + "for session={} seat={} - see ADR-002's fail-open path", sessionId, seatId, e);
            return true;
        }
    }

    /** Best-effort release once the real Postgres decision is made — a stale key just expires via TTL either way. */
    public void release(UUID sessionId, UUID seatId) {
        try {
            redis.delete(key(sessionId, seatId));
        } catch (RuntimeException e) {
            log.warn("seat-lock Redis release failed for session={} seat={} - harmless, TTL will clear it",
                    sessionId, seatId, e);
        }
    }

    private String key(UUID sessionId, UUID seatId) {
        return KEY_PREFIX + sessionId + ":" + seatId;
    }
}
