package com.ticketmaster.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end through the real HTTP layer, deliberately not a unit test of
 * LoginService alone - what matters here is that LoginController,
 * LoginService, LoginAttemptLimiter and User's DB-persisted lock all agree
 * with each other on the same login attempt, which only a real request
 * through the whole stack can prove.
 *
 * Separate file from LoginTest, not a shared Redis container there,
 * because these tests deliberately exhaust a real rate limit - sharing a
 * Redis instance with unrelated login tests would make them flaky against
 * each other's counters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginSecurityTest {

    private static final String EMAIL = "security@example.com";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String WRONG = "wrong-but-long-enough";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired org.springframework.test.web.servlet.MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void seedUserAndClearState() {
        users.deleteAll();
        users.saveAndFlush(new User(EMAIL, passwordEncoder.encode(PASSWORD), Instant.now()));
        // Every test in this class hits the SAME email against a SHARED
        // Redis - without this, failures recorded by one test bleed into
        // the next and the boundary assertions below stop meaning anything.
        redis.delete("login-fail:" + EMAIL);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Payload(email, password))))
                .andReturn();
    }

    record Payload(String email, String password) {}

    @Test
    void theSixthFailedAttemptInAMinuteIsRateLimitedNotAuthenticated() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(401, login(EMAIL, WRONG).getResponse().getStatus(),
                    "attempt " + (i + 1) + " should still be a normal wrong-password rejection");
        }

        MvcResult sixth = login(EMAIL, WRONG);
        assertEquals(429, sixth.getResponse().getStatus());
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() throws Exception {
        for (int i = 0; i < 4; i++) {
            login(EMAIL, WRONG);
        }

        assertEquals(200, login(EMAIL, PASSWORD).getResponse().getStatus());

        // Without the reset, this 5th wrong password would be the 5th
        // failure recorded overall and the next one would trip the limiter
        // early - proving the counter actually cleared, not just that one
        // more attempt happens to still fit under it.
        assertEquals(401, login(EMAIL, WRONG).getResponse().getStatus());
    }

    @Test
    void theTenthFailureAcrossManyWindowsLocksTheAccountInTheDatabase() throws Exception {
        // 10 failures spread across two separate Redis windows (resetting
        // the Redis counter between them) - proving the DB-persisted lock
        // survives what an attacker pacing under the Redis limit would
        // otherwise dodge entirely.
        for (int i = 0; i < 5; i++) {
            login(EMAIL, WRONG);
        }
        redis.delete("login-fail:" + EMAIL);
        for (int i = 0; i < 5; i++) {
            login(EMAIL, WRONG);
        }
        redis.delete("login-fail:" + EMAIL);

        // Correct password, but the account itself is now locked - must
        // fail the SAME way as a wrong password (401, identical body),
        // never a distinct response that would reveal the lock exists.
        MvcResult lockedAttempt = login(EMAIL, PASSWORD);
        assertEquals(401, lockedAttempt.getResponse().getStatus());

        User locked = users.findByEmail(EMAIL).orElseThrow();
        assertTrue(locked.isLocked(Instant.now()), "account should be locked after 10 failures");
    }
}
