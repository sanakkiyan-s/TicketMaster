package com.ticketmaster.gateway.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Redis, not a mock. The thing actually worth proving -
 * that the Lua script's INCR+EXPIRE is atomic and the Nth request is the
 * exact boundary where 200 becomes 429 - only means something against real
 * INCR/EXPIRE/TTL semantics a mock would have to reimplement anyway.
 */
class RateLimitFilterTest {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static ReactiveStringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    /** A fresh path prefix per test, so tests never share a counter key. */
    private static int testCounter = 0;

    private String uniquePrefix;

    @BeforeEach
    void freshPrefix() {
        uniquePrefix = "/test-" + (testCounter++) + "/";
    }

    private RateLimitProperties propertiesFor(int limit, Duration window) {
        return new RateLimitProperties(true, List.of(new RateLimitRule(uniquePrefix, limit, window)));
    }

    private MockServerWebExchange requestFrom(String ip) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(uniquePrefix + "login")
                        .remoteAddress(new InetSocketAddress(ip, 12345))
                        .build());
    }

    @Test
    void allowsRequestsUpToTheLimit() {
        RateLimitFilter filter = new RateLimitFilter(redis, propertiesFor(3, Duration.ofMinutes(1)));
        AtomicInteger reached = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            filter.filter(requestFrom("10.0.0.1"), e -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
        }

        assertEquals(3, reached.get(), "all 3 requests within the limit should have reached the chain");
    }

    @Test
    void the4thRequestInAWindowOf3IsRejected() {
        // The exact boundary this filter exists to enforce.
        RateLimitFilter filter = new RateLimitFilter(redis, propertiesFor(3, Duration.ofMinutes(1)));
        AtomicInteger reached = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            filter.filter(requestFrom("10.0.0.2"), e -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
        }

        MockServerWebExchange fourth = requestFrom("10.0.0.2");
        filter.filter(fourth, e -> {
            reached.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(3, reached.get(), "the 4th request reached the chain - the limit did not hold");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, fourth.getResponse().getStatusCode());
        assertNotNull(fourth.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER),
                "429 response is missing Retry-After");
    }

    @Test
    void differentIpsHaveIndependentCounters() {
        // The whole reason keying is per-IP: one attacker exhausting their
        // own budget must not affect anyone else's ability to log in.
        RateLimitFilter filter = new RateLimitFilter(redis, propertiesFor(1, Duration.ofMinutes(1)));

        MockServerWebExchange first = requestFrom("10.0.0.3");
        filter.filter(first, e -> Mono.empty()).block();

        MockServerWebExchange second = requestFrom("10.0.0.3");
        filter.filter(second, e -> Mono.empty()).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());

        MockServerWebExchange fromAnotherIp = requestFrom("10.0.0.4");
        AtomicInteger reached = new AtomicInteger();
        filter.filter(fromAnotherIp, e -> {
            reached.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(1, reached.get(), "a different IP was blocked by another IP's counter");
    }

    @Test
    void pathsWithNoMatchingRuleAreNeverThrottled() {
        RateLimitProperties properties = new RateLimitProperties(
                true, List.of(new RateLimitRule("/api/v1/auth/login", 1, Duration.ofMinutes(1))));
        RateLimitFilter filter = new RateLimitFilter(redis, properties);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/events")
                        .remoteAddress(new InetSocketAddress("10.0.0.5", 1))
                        .build());

        AtomicInteger reached = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            filter.filter(exchange, e -> {
                reached.incrementAndGet();
                return Mono.empty();
            }).block();
        }

        assertEquals(20, reached.get(), "an unrelated path was throttled by the login rule");
    }

    @Test
    void theWindowResetsAfterItExpires() throws InterruptedException {
        RateLimitFilter filter = new RateLimitFilter(redis, propertiesFor(1, Duration.ofSeconds(1)));

        MockServerWebExchange first = requestFrom("10.0.0.6");
        filter.filter(first, e -> Mono.empty()).block();

        MockServerWebExchange second = requestFrom("10.0.0.6");
        filter.filter(second, e -> Mono.empty()).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());

        // No way to fake Redis's clock from here; a short real sleep past the
        // 1s window is the only way to observe the reset.
        Thread.sleep(1200);

        MockServerWebExchange third = requestFrom("10.0.0.6");
        AtomicInteger reached = new AtomicInteger();
        filter.filter(third, e -> {
            reached.incrementAndGet();
            return Mono.empty();
        }).block();

        assertEquals(1, reached.get(), "the window did not reset after expiry");
    }

    @Test
    void redisBeingUnreachableFailsOpenRatherThanBlockingLogin() {
        // A Redis outage narrows defense in depth; it must not become a
        // second way to deny every user the ability to log in at all.
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1));
        brokenFactory.afterPropertiesSet();
        ReactiveStringRedisTemplate brokenRedis = new ReactiveStringRedisTemplate(brokenFactory);

        RateLimitFilter filter = new RateLimitFilter(brokenRedis, propertiesFor(1, Duration.ofMinutes(1)));
        AtomicInteger reached = new AtomicInteger();

        filter.filter(requestFrom("10.0.0.7"), e -> {
            reached.incrementAndGet();
            return Mono.empty();
        }).block(Duration.ofSeconds(5));

        assertTrue(reached.get() > 0, "the request was blocked instead of failing open on a Redis outage");
        brokenFactory.destroy();
    }
}
