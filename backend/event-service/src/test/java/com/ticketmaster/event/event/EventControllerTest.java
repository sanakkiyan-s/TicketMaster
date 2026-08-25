package com.ticketmaster.event.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.event.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class EventControllerTest {

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

    private MvcResult createEvent(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateEventRequest(UUID.randomUUID(), "Concert", "desc", "music", "US"));

        return mockMvc.perform(post("/api/v1/organizer/events")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    void createThenGetReturnsIt() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        MvcResult created = createEvent(token);
        assertEquals(201, created.getResponse().getStatus());

        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = createdBody.path("id").asText();
        assertEquals("DRAFT", createdBody.path("status").asText());

        MvcResult getResult = mockMvc.perform(
                        get("/api/v1/organizer/events/{id}", id).header("Authorization", token))
                .andReturn();
        assertEquals(200, getResult.getResponse().getStatus());
    }

    @Test
    void anotherOrganizersTokenGets404NeverForbidden() throws Exception {
        String ownerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));
        String attackerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        JsonNode owned = objectMapper.readTree(createEvent(ownerToken).getResponse().getContentAsString());
        String id = owned.path("id").asText();

        // 404, never 403 — see EventNotFoundException's javadoc: a 403
        // would confirm the id exists to a caller who shouldn't be able to
        // tell.
        int getStatus = mockMvc.perform(
                        get("/api/v1/organizer/events/{id}", id).header("Authorization", attackerToken))
                .andReturn().getResponse().getStatus();
        assertEquals(404, getStatus);

        int cancelStatus = mockMvc.perform(post("/api/v1/organizer/events/{id}/cancel", id)
                        .header("Authorization", attackerToken))
                .andReturn().getResponse().getStatus();
        assertEquals(404, cancelStatus);
    }

    @Test
    void anUnknownIdIsAlso404() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        int status = mockMvc.perform(get("/api/v1/organizer/events/{id}", UUID.randomUUID())
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus();
        assertEquals(404, status);
    }

    @Test
    void adminCanReadAnotherOrganizersEvent() throws Exception {
        String ownerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));
        String adminToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ADMIN"));

        JsonNode owned = objectMapper.readTree(createEvent(ownerToken).getResponse().getContentAsString());
        String id = owned.path("id").asText();

        int status = mockMvc.perform(get("/api/v1/organizer/events/{id}", id).header("Authorization", adminToken))
                .andReturn().getResponse().getStatus();
        assertEquals(200, status);
    }
}
