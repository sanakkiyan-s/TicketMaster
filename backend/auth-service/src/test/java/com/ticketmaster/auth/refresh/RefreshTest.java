package com.ticketmaster.auth.refresh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class RefreshTest {

    private static final String EMAIL = "refresh@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedUser() {
        users.deleteAll();
        users.saveAndFlush(new User(EMAIL, passwordEncoder.encode(PASSWORD), Instant.now()));
    }

    /** Logs in and returns the refresh cookie value. */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new Payload(EMAIL, PASSWORD))))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertNotNull(cookie, "login did not set a refresh cookie");
        return cookie.getValue();
    }

    record Payload(String email, String password) {}

    private MvcResult refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token)))
                .andReturn();
    }

    @Test
    void exchangesARefreshTokenForANewPair() throws Exception {
        String first = login();

        MvcResult result = refresh(first);
        assertEquals(200, result.getResponse().getStatus());

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(json.path("accessToken").asText().isBlank());
        assertEquals(600, json.path("expiresIn").asLong());

        Cookie rotated = result.getResponse().getCookie("refresh_token");
        assertNotNull(rotated);
        assertNotEquals(first, rotated.getValue(), "the refresh token was not rotated");
        assertTrue(result.getResponse().getHeader("Set-Cookie").contains("HttpOnly"));
    }

    @Test
    void theOldTokenStopsWorkingImmediately() throws Exception {
        String first = login();
        refresh(first);

        // Single-use is what makes theft detectable at all: if a used token
        // still worked, a thief's use would look exactly like the owner's.
        assertEquals(401, refresh(first).getResponse().getStatus());
    }

    @Test
    void reusingATokenRevokesTheWholeFamily() throws Exception {
        // The scenario: an attacker steals refresh token A and uses it. The
        // legitimate user later presents their own rotated token B.
        String stolen = login();

        MvcResult attackerRotation = refresh(stolen);
        String attackerToken = attackerRotation.getResponse().getCookie("refresh_token").getValue();
        assertEquals(200, attackerRotation.getResponse().getStatus());

        // Replay of the already-used token trips detection.
        assertEquals(401, refresh(stolen).getResponse().getStatus());

        // And the attacker's freshly rotated token is dead too, because the
        // whole family was revoked - not just the replayed row. Revoking one
        // row would leave the thief holding a working credential.
        assertEquals(401, refresh(attackerToken).getResponse().getStatus(),
                "family revocation did not kill the rest of the chain");
    }

    @Test
    void aRevokedFamilyDoesNotAffectOtherSessions() throws Exception {
        // Two logins = two families = two devices. Burning one must not sign
        // the user out everywhere, or "log out this phone" is unimplementable.
        String phone = login();
        String laptop = login();

        refresh(phone);
        assertEquals(401, refresh(phone).getResponse().getStatus());

        assertEquals(200, refresh(laptop).getResponse().getStatus(),
                "revoking one family took down an unrelated session");
    }

    @Test
    void unknownTokenAndMissingCookieBothReturnTheSame401() throws Exception {
        MvcResult unknown = refresh("not-a-real-token");
        MvcResult missing = mockMvc.perform(post("/api/v1/auth/refresh")).andReturn();

        assertEquals(401, unknown.getResponse().getStatus());
        assertEquals(401, missing.getResponse().getStatus());
        assertEquals(
                unknown.getResponse().getContentAsString(),
                missing.getResponse().getContentAsString());
    }

    @Test
    void theResponseNeverRevealsWhyTheTokenFailed() throws Exception {
        String used = login();
        refresh(used);

        String body = refresh(used).getResponse().getContentAsString();

        // "reuse detected" in the body would tell an attacker their stolen
        // token was noticed; "expired" would confirm the token was real.
        assertFalse(body.toLowerCase().contains("reuse"), body);
        assertFalse(body.toLowerCase().contains("revoked"), body);
        assertFalse(body.toLowerCase().contains("expired"), body);
    }
}
