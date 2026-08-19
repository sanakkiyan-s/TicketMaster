package com.ticketmaster.auth.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LoginTest {

    private static final String EMAIL = "login@example.com";
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

    private String body(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new Payload(email, password));
    }

    record Payload(String email, String password) {}

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, password)))
                .andReturn();
    }

    @Test
    void validCredentialsReturnAnAccessTokenAndSetTheRefreshCookie() throws Exception {
        MvcResult result = login(EMAIL, PASSWORD);

        assertEquals(200, result.getResponse().getStatus());

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertFalse(json.path("accessToken").asText().isBlank());
        assertEquals("Bearer", json.path("tokenType").asText());
        assertEquals(600, json.path("expiresIn").asLong());
        assertEquals(EMAIL, json.path("user").path("email").asText());

        // The refresh token must NOT be in the body — that is the whole point
        // of shipping it as a cookie. If it appears here, any XSS can read it.
        assertTrue(json.path("refreshToken").isMissingNode(),
                "refresh token leaked into the response body");

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie, "no refresh cookie set");
        assertTrue(setCookie.startsWith("refresh_token="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), "cookie is readable by JavaScript: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/api/v1/auth"), setCookie);

        // An access token in a shared cache is an access token handed to the
        // next person through that cache.
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
    }

    @Test
    void emailIsCaseInsensitiveOnLogin() throws Exception {
        // CITEXT plus the citext cast in UserRepository. If this regresses, a
        // user who typed their address with different capitalisation than at
        // signup is locked out of their own account.
        assertEquals(200, login("LOGIN@EXAMPLE.COM", PASSWORD).getResponse().getStatus());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        MvcResult wrongPassword = login(EMAIL, "wrong-but-long-enough");
        MvcResult unknownEmail = login("nobody@example.com", PASSWORD);

        assertEquals(401, wrongPassword.getResponse().getStatus());
        assertEquals(401, unknownEmail.getResponse().getStatus());

        // Byte-identical bodies. Any difference — a message, a field, an
        // ordering — is an account-enumeration oracle.
        assertEquals(
                wrongPassword.getResponse().getContentAsString(),
                unknownEmail.getResponse().getContentAsString());
    }

    @Test
    void failedLoginNeverEchoesTheSubmittedPassword() throws Exception {
        String secret = "this-should-never-come-back";
        String responseBody = login(EMAIL, secret).getResponse().getContentAsString();

        assertFalse(responseBody.contains(secret),
                "the rejected password was echoed into the response: " + responseBody);
    }

    @Test
    void malformedLoginDoesNotRevealThePasswordPolicy() throws Exception {
        // A short password must fail as 401, not 400-with-"minimum 12 chars".
        // Registration owns the policy; restating it here tells an attacker
        // the rule and distinguishes "malformed" from "wrong".
        assertEquals(401, login(EMAIL, "short").getResponse().getStatus());
    }

    @Test
    void eachLoginStartsItsOwnSessionAndFamily() throws Exception {
        var first = objectMapper.readTree(login(EMAIL, PASSWORD).getResponse().getContentAsString());
        var second = objectMapper.readTree(login(EMAIL, PASSWORD).getResponse().getContentAsString());

        // Distinct sid per login, or "log out this device" would log out all of
        // them (ADR-012 selective logout).
        String firstToken = first.path("accessToken").asText();
        String secondToken = second.path("accessToken").asText();
        assertNotEquals(firstToken, secondToken);
    }
}
