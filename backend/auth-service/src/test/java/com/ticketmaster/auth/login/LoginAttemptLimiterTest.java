package com.ticketmaster.auth.login;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Redis, not a mock - the thing actually worth
 * proving here (the atomic INCR+EXPIRE in login_attempt.lua, and the
 * exact boundary where the 5th recorded failure trips the limiter) only
 * means something against real Redis semantics.
 */
class LoginAttemptLimiterTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    private static int testCounter = 0;
    private String email;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void freshLimiter() {
        // A fresh email per test, so tests never share a Redis key.
        email = "user-" + (testCounter++) + "@example.com";
        limiter = new LoginAttemptLimiter(redis,
                new LoginSecurityProperties(5, Duration.ofMinutes(1), 10, Duration.ofMinutes(15)));
    }

    @Test
    void notBlockedBeforeAnyFailures() {
        assertFalse(limiter.isBlocked(email));
    }

    @Test
    void notBlockedBelowTheLimit() {
        // LoginService checks isBlocked() BEFORE processing the current
        // attempt, so a count of (limit - 1) must still pass through - it
        // is the NEXT attempt, the one that would push the count to the
        // limit, that gets rejected.
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(email);
        }
        assertFalse(limiter.isBlocked(email));
    }

    @Test
    void blockedOnceTheCountReachesTheLimit() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(email);
        }
        assertTrue(limiter.isBlocked(email));
    }

    @Test
    void resetClearsTheCounterEvenAfterBlocking() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(email);
        }
        assertTrue(limiter.isBlocked(email));

        limiter.reset(email);

        assertFalse(limiter.isBlocked(email));
    }

    @Test
    void countIsKeyedCaseInsensitively() {
        // A bare Redis key has none of CITEXT's built-in case folding -
        // LoginAttemptLimiter must lower-case itself, or "User@x.com" vs
        // "user@x.com" would let an attacker dodge the counter for free.
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(email.toUpperCase());
        }
        assertTrue(limiter.isBlocked(email));
    }

    @Test
    void differentEmailsHaveIndependentCounters() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(email);
        }
        assertTrue(limiter.isBlocked(email));
        assertFalse(limiter.isBlocked("someone-else@example.com"));
    }

    @Test
    void redisBeingUnreachableFailsOpenRatherThanBlockingLogin() {
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1));
        brokenFactory.afterPropertiesSet();
        StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);

        LoginAttemptLimiter brokenLimiter = new LoginAttemptLimiter(brokenRedis,
                new LoginSecurityProperties(5, Duration.ofMinutes(1), 10, Duration.ofMinutes(15)));

        // Neither call should throw, and neither should report blocked -
        // an outage narrows defense in depth, it must not become a second
        // way to lock every user out of login.
        assertFalse(brokenLimiter.isBlocked(email));
        brokenLimiter.recordFailure(email);
        brokenLimiter.reset(email);

        brokenFactory.destroy();
    }
}
