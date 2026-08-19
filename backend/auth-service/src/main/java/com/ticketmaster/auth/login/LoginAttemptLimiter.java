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
 * Per-username failed-login throttle, keyed by the raw input email rather
 * than a resolved user id - it must count identically whether the email
 * belongs to a real account or not, otherwise the counter itself becomes
 * an enumeration oracle (a 429 appearing only for real accounts would leak
 * exactly what InvalidCredentialsException's identical 401 is designed to
 * hide).
 */
@Component
class LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);
    private static final String KEY_PREFIX = "login-fail:";

    private static final RedisScript<Long> RECORD_FAILURE_SCRIPT = loadScript();

    private static RedisScript<Long> loadScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/login_attempt.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redis;
    private final LoginSecurityProperties properties;

    LoginAttemptLimiter(StringRedisTemplate redis, LoginSecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Fails open on a Redis outage, matching RateLimitFilter's own
     * convention at the gateway: losing this layer narrows defense in
     * depth, but a Redis blip must never be a second way to lock every
     * user out of login.
     */
    boolean isBlocked(String email) {
        try {
            String count = redis.opsForValue().get(key(email));
            return count != null && Long.parseLong(count) >= properties.redisMaxAttempts();
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; allowing the request through", e);
            return false;
        }
    }

    void recordFailure(String email) {
        try {
            redis.execute(RECORD_FAILURE_SCRIPT, List.of(key(email)),
                    String.valueOf(properties.redisWindow().toSeconds()));
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; failure not counted", e);
        }
    }

    void reset(String email) {
        try {
            redis.delete(key(email));
        } catch (DataAccessException e) {
            log.warn("login attempt limiter could not reach Redis; counter not cleared", e);
        }
    }

    private String key(String email) {
        // Lower-cased so "User@x.com" and "user@x.com" share one budget -
        // the same case-insensitivity the users table's CITEXT column
        // already gives the real lookup; a bare Redis key has no such
        // built-in behaviour and casing it differently each attempt would
        // let an attacker dodge the counter for free.
        return KEY_PREFIX + email.toLowerCase(Locale.ROOT);
    }
}
