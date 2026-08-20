package com.ticketmaster.auth.revocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.jwt.TokenMinting;
import com.ticketmaster.auth.user.Role;
import com.ticketmaster.auth.user.User;
import com.ticketmaster.auth.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-012 revocation, exercised through the real HTTP layer plus a direct
 * query of the `outbox` table - there is no real Kafka/Debezium in this
 * test environment, and per the task's own scope, the outbox row landing
 * correctly IS the contract this service is responsible for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevocationTest {

    private static final String EMAIL = "revoke@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    @Autowired TokenMinting tokens;
    @Autowired JdbcTemplate jdbc;

    private User user;

    @BeforeEach
    void seedUser() {
        jdbc.update("DELETE FROM outbox");
        users.deleteAll();
        user = users.saveAndFlush(new User(EMAIL, passwordEncoder.encode(PASSWORD), Instant.now()));
    }

    record LoginPayload(String email, String password) {}

    private record Session(String accessToken, String refreshCookie) {}

    private Session login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(EMAIL, PASSWORD))))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertNotNull(cookie);
        return new Session(body.path("accessToken").asText(), cookie.getValue());
    }

    private List<Map<String, Object>> outboxRowsFor(String aggregateId) {
        return jdbc.queryForList(
                "SELECT * FROM outbox WHERE aggregate_id = ?", aggregateId);
    }

    @Test
    void logoutRevokesOnlyTheCurrentSessionAndWritesOneOutboxRow() throws Exception {
        Session phone = login();
        Session laptop = login();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + phone.accessToken()))
                .andReturn();

        assertEquals(204, result.getResponse().getStatus());
        assertTrue(result.getResponse().getHeader("Set-Cookie").contains("refresh_token="));
        assertTrue(result.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));

        // Exactly one outbox row, scoped to the session that logged out.
        List<Map<String, Object>> allRows = jdbc.queryForList("SELECT * FROM outbox");
        assertEquals(1, allRows.size(), "logout should write exactly one outbox row");

        Map<String, Object> row = allRows.get(0);
        assertEquals("auth.revocation", row.get("event_type"));
        assertTrue(row.get("aggregate_id").toString().startsWith("session:"));
        assertNotNull(row.get("traceparent"));

        JsonNode payload = objectMapper.readTree(row.get("payload").toString());
        assertTrue(payload.has("revokeBefore"));
        assertTrue(payload.get("revokeBefore").isNumber());
        assertEquals("user logout", payload.get("reason").asText());

        // The phone's own refresh token is now dead; the laptop's is untouched.
        assertEquals(401, refresh(phone.refreshCookie()).getResponse().getStatus());
        assertEquals(200, refresh(laptop.refreshCookie()).getResponse().getStatus());
    }

    @Test
    void logoutEverywhereRevokesEverySessionAndScopesTheOutboxRowToTheUser() throws Exception {
        Session phone = login();
        Session laptop = login();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout-everywhere")
                        .header("Authorization", "Bearer " + phone.accessToken()))
                .andReturn();

        assertEquals(204, result.getResponse().getStatus());

        List<Map<String, Object>> rows = outboxRowsFor("user:" + user.getId());
        assertEquals(1, rows.size());
        JsonNode payload = objectMapper.readTree(rows.get(0).get("payload").toString());
        assertEquals("logout everywhere", payload.get("reason").asText());

        assertEquals(401, refresh(phone.refreshCookie()).getResponse().getStatus());
        assertEquals(401, refresh(laptop.refreshCookie()).getResponse().getStatus());
    }

    @Test
    void logoutWithoutABearerTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void banRequiresTheAdminRole() throws Exception {
        login();
        String nonAdminToken = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.USER));

        mockMvc.perform(post("/api/v1/auth/admin/users/" + user.getId() + "/ban")
                        .header("Authorization", "Bearer " + nonAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"fraud\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void banRevokesAllSessionsAndWritesAnOutboxRowWithTheAdrShape() throws Exception {
        Session victimSession = login();
        String adminToken = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.ADMIN));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/admin/users/" + user.getId() + "/ban")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"chargeback fraud\"}"))
                .andReturn();

        assertEquals(204, result.getResponse().getStatus());

        List<Map<String, Object>> rows = outboxRowsFor("user:" + user.getId());
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("auth.revocation", row.get("event_type"));

        JsonNode payload = objectMapper.readTree(row.get("payload").toString());
        assertEquals(2, payload.size(), "payload should carry exactly revokeBefore and reason");
        assertTrue(payload.get("revokeBefore").isNumber());
        assertEquals("chargeback fraud", payload.get("reason").asText());

        assertEquals(401, refresh(victimSession.refreshCookie()).getResponse().getStatus());
    }

    @Test
    void banWithABlankReasonIsRejected() throws Exception {
        String adminToken = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.ADMIN));

        mockMvc.perform(post("/api/v1/auth/admin/users/" + UUID.randomUUID() + "/ban")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    private MvcResult refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", token)))
                .andReturn();
    }
}
