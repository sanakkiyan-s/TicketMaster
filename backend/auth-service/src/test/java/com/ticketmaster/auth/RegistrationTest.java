package com.ticketmaster.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.user.Role;
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
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RegistrationTest {

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
    void clearUsers() {
        users.deleteAll();
    }

    private String body(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new RegisterPayload(email, password));
    }

    record RegisterPayload(String email, String password) {}

    @Test
    void registersAUserAndReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("fan@example.com", "correct-horse-battery")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.email").value("fan@example.com"))
                .andExpect(jsonPath("$.roles[0]").value(Role.USER));

        assertTrue(users.existsByEmail("fan@example.com"));
    }

    @Test
    void neverStoresOrReturnsThePlaintextPassword() throws Exception {
        String password = "correct-horse-battery";

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("secret@example.com", password)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(password), "response echoed the password");

        User stored = users.findByEmail("secret@example.com").orElseThrow();
        assertNotEquals(password, stored.getPasswordHash());
        assertTrue(stored.getPasswordHash().startsWith("$2"), "not a bcrypt hash");
        assertTrue(passwordEncoder.matches(password, stored.getPasswordHash()));
    }

    @Test
    void rejectsADuplicateEmailWith409() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupe@example.com", "correct-horse-battery")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupe@example.com", "different-password-xx")))
                .andExpect(status().isConflict());

        assertEquals(1, users.count());
    }

    @Test
    void treatsDifferentlyCasedEmailAsTheSameAccount() throws Exception {
        // The CITEXT column is what enforces this, not application code.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Case@Example.com", "correct-horse-battery")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("case@example.com", "correct-horse-battery")))
                .andExpect(status().isConflict());

        assertEquals(1, users.count());

        // And the original casing is preserved, not silently lower-cased.
        Optional<User> found = users.findByEmail("CASE@EXAMPLE.COM");
        assertTrue(found.isPresent());
        assertEquals("Case@Example.com", found.get().getEmail());
    }

    @Test
    void rejectsAMalformedEmailWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email", "correct-horse-battery")))
                .andExpect(status().isBadRequest());

        assertEquals(0, users.count());
    }

    @Test
    void rejectsAShortPasswordWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("short@example.com", "tooshort")))
                .andExpect(status().isBadRequest());

        assertEquals(0, users.count());
    }

    @Test
    void assignsTheUserRoleOnly() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("roles@example.com", "correct-horse-battery")))
                .andExpect(status().isCreated());

        // Self-service registration must never grant ORGANIZER or ADMIN
        // (ADR-030) — those are the roles that bypass ownership checks.
        User stored = users.findByEmail("roles@example.com").orElseThrow();
        assertEquals(java.util.Set.of(Role.USER), stored.getRoles());
    }

    @Test
    void reportsErrorsAsProblemDetail() throws Exception {
        // One error shape for the whole API (RFC 9457). ADR-034 publishes
        // this contract via generated OpenAPI, so the shape must not vary
        // per endpoint.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupe2@example.com", "correct-horse-battery")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dupe2@example.com", "correct-horse-battery")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Email already registered"));
    }

    @Test
    void validationErrorNamesTheFieldButNeverEchoesTheValue() throws Exception {
        String password = "tooshort";

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("valid@example.com", password)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.password").exists())
                .andReturn().getResponse().getContentAsString();

        // A rejected password must never come back in the body — it would
        // then sit in every access log between here and the browser.
        assertFalse(response.contains(password), "response echoed the rejected password");
    }
}
