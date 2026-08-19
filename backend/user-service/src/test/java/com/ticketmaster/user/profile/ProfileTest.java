package com.ticketmaster.user.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.user.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileTest {

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
    @Autowired ObjectMapper objectMapper;

    @Test
    void firstGetAutoCreatesAnEmptyProfileInsteadOf404() throws Exception {
        UUID userId = UUID.randomUUID();

        var result = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", TestTokens.bearerTokenFor(userId)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(userId.toString(), json.path("userId").asText());
        assertTrue(json.path("displayName").isNull());
        assertFalse(json.path("createdAt").asText().isBlank());
    }

    @Test
    void putUpdatesAndSubsequentGetReflectsIt() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestTokens.bearerTokenFor(userId);

        String body = objectMapper.writeValueAsString(
                new UpdateProfileRequest("Alice", "+15551234567", "https://example.com/avatar.png"));

        var putResult = mockMvc.perform(put("/api/v1/users/me")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertEquals(200, putResult.getResponse().getStatus());

        var getResult = mockMvc.perform(get("/api/v1/users/me").header("Authorization", token)).andReturn();
        JsonNode json = objectMapper.readTree(getResult.getResponse().getContentAsString());

        assertEquals("Alice", json.path("displayName").asText());
        assertEquals("+15551234567", json.path("phoneNumber").asText());
        assertEquals("https://example.com/avatar.png", json.path("avatarUrl").asText());
    }

    @Test
    void missingBearerTokenIsRejected() throws Exception {
        assertEquals(401, mockMvc.perform(get("/api/v1/users/me")).andReturn().getResponse().getStatus());
    }
}
