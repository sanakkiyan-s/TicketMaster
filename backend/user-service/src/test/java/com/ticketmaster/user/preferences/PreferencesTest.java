package com.ticketmaster.user.preferences;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
class PreferencesTest {

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
    void firstGetReturnsTheDefaults() throws Exception {
        UUID userId = UUID.randomUUID();

        var result = mockMvc.perform(get("/api/v1/users/me/preferences")
                        .header("Authorization", TestTokens.bearerTokenFor(userId)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus());

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(true, json.path("emailOptIn").asBoolean());
        assertEquals(false, json.path("smsOptIn").asBoolean());
    }

    @Test
    void putUpdatesAndSubsequentGetReflectsIt() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestTokens.bearerTokenFor(userId);

        String body = objectMapper.writeValueAsString(new UpdatePreferencesRequest(false, true));

        var putResult = mockMvc.perform(put("/api/v1/users/me/preferences")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertEquals(200, putResult.getResponse().getStatus());

        var getResult = mockMvc.perform(
                get("/api/v1/users/me/preferences").header("Authorization", token)).andReturn();
        JsonNode json = objectMapper.readTree(getResult.getResponse().getContentAsString());

        assertEquals(false, json.path("emailOptIn").asBoolean());
        assertEquals(true, json.path("smsOptIn").asBoolean());
    }
}
