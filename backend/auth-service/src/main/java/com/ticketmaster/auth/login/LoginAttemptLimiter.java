package com.ticketmaster.auth.login;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Per-username failed-login throttle (ADR-040), two independent Redis
 * windows keyed by the raw input email rather than a resolved user id -
 * it must count identically whether the email belongs to a real account
 * or not, otherwise the counter itself becomes an enumeration oracle (a
 * 429 appearing only for real accounts would leak exactly what
 * InvalidCredentialsException's identical 401 is designed to hide).
 *
 * Fast window (default 5/1min) is burst protection. Slow window (default
 * 15/24h) catches an attacker pacing guesses just under the fast
 * window's rate to dodge it - the same job {@code User.failedLoginAttempts}
 * used to do as a DB column, but self-decaying via TTL instead of
 * accumulating forever until a successful login cleared it.
 */
@Component
class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String FAST_PREFIX = "login-fail:fast:";
    private static final String SLOW_PREFIX = "login-fail:slow:";

    private static final RedisScript<List> RECORD_FAILURE_SCRIPT = loadScript();

    private static RedisScript<List> loadScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/login_attempt_windows.lua"));
        script.setResultType(List.class);
        return script;
    }

    private final StringRedisTemplate redis;
    private final LoginSecurityProperties properties;

    LoginAttemptLimiter(StringRedisTemplate redis, LoginSecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Cheap Redis-only pre-check, read before any DB lookup or BCrypt
     * call - an already-over-budget username shouldn't cost either.
     * Fails open on a Redis outage, matching the gateway's own
     * convention: losing this layer narrows defense in depth, but a
     * Redis blip must never become a second way to lock every user out
     * of login.
     */
    boolean isBlocked(String email) {
        try {
            List<String> counts = redis.opsForValue().multiGet(List.of(fastKey(email), slowKey(email)));
            long fast = parse(counts.get(0));
            long slow = parse(counts.get(1));
            return fast >= properties.fastWindowLimit() || slow >= properties.slowWindowLimit();
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; allowing the request through", e);
            return false;
        }
    }

    /**
     * Increments both windows atomically in one round trip.
     *
     * @return true if THIS failure just pushed either window to its
     *         limit - the caller's signal to also persist a DB-backed
     *         lock (User#lock), not just reject the request. False on a
     *         Redis outage (fails open, same as {@link #isBlocked}).
     */
    boolean recordFailure(String email) {
        try {
            List<Object> counts = redis.execute(RECORD_FAILURE_SCRIPT,
                    List.of(fastKey(email), slowKey(email)),
                    String.valueOf(properties.fastWindowTtl().toSeconds()),
                    String.valueOf(properties.slowWindowTtl().toSeconds()));
            long fast = asLong(counts.get(0));
            long slow = asLong(counts.get(1));
            return fast >= properties.fastWindowLimit() || slow >= properties.slowWindowLimit();
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; failure not counted", e);
            return false;
        }
    }

    void reset(String email) {
        try {
            redis.delete(List.of(fastKey(email), slowKey(email)));
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; counters not cleared", e);
        }
    }

    private long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private long parse(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private String fastKey(String email) {
        return FAST_PREFIX + normalize(email);
    }

    private String slowKey(String email) {
        return SLOW_PREFIX + normalize(email);
    }

    /**
     * Lower-cased so "User@x.com" and "user@x.com" share one budget -
     * the same case-insensitivity the users table's CITEXT column
     * already gives the real lookup; a bare Redis key has no such
     * built-in behaviour and casing it differently each attempt would
     * let an attacker dodge the counter for free.
     */
    private String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
