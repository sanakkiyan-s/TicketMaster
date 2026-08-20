package com.ticketmaster.venue.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.venue.TestTokens;
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
class VenueControllerTest {

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

    private MvcResult createVenue(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateVenueRequest("Arena", "123 Main St", "Metropolis", "US"));

        return mockMvc.perform(post("/api/v1/organizer/venues")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    void createThenGetReturnsIt() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        MvcResult created = createVenue(token);
        assertEquals(201, created.getResponse().getStatus());

        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String id = createdBody.path("id").asText();
        assertEquals("Arena", createdBody.path("name").asText());

        MvcResult getResult = mockMvc.perform(
                        get("/api/v1/organizer/venues/{id}", id).header("Authorization", token))
                .andReturn();
        assertEquals(200, getResult.getResponse().getStatus());
    }

    @Test
    void anotherOrganizersTokenGets404NeverForbidden() throws Exception {
        String ownerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));
        String attackerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        JsonNode owned = objectMapper.readTree(createVenue(ownerToken).getResponse().getContentAsString());
        String id = owned.path("id").asText();

        // 404, never 403 — see VenueNotFoundException's javadoc: a 403
        // would confirm the id exists to a caller who shouldn't be able to
        // tell.
        int getStatus = mockMvc.perform(
                        get("/api/v1/organizer/venues/{id}", id).header("Authorization", attackerToken))
                .andReturn().getResponse().getStatus();
        assertEquals(404, getStatus);

        int updateStatus = mockMvc.perform(put("/api/v1/organizer/venues/{id}", id)
                        .header("Authorization", attackerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateVenueRequest("New Name", "New Addr", "New City"))))
                .andReturn().getResponse().getStatus();
        assertEquals(404, updateStatus);
    }

    @Test
    void anUnknownIdIsAlso404() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        int status = mockMvc.perform(get("/api/v1/organizer/venues/{id}", UUID.randomUUID())
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus();
        assertEquals(404, status);
    }

    @Test
    void adminCanReadAnotherOrganizersVenue() throws Exception {
        String ownerToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));
        String adminToken = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ADMIN"));

        JsonNode owned = objectMapper.readTree(createVenue(ownerToken).getResponse().getContentAsString());
        String id = owned.path("id").asText();

        int status = mockMvc.perform(get("/api/v1/organizer/venues/{id}", id).header("Authorization", adminToken))
                .andReturn().getResponse().getStatus();
        assertEquals(200, status);
    }

    @Test
    void sectionAndSeatCrudHappyPath() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID(), List.of("ORGANIZER"));

        JsonNode venue = objectMapper.readTree(createVenue(token).getResponse().getContentAsString());
        String venueId = venue.path("id").asText();

        String sectionBody = objectMapper.writeValueAsString(new com.ticketmaster.venue.section.CreateSectionRequest("Floor", 100));
        MvcResult sectionResult = mockMvc.perform(post("/api/v1/organizer/venues/{venueId}/sections", venueId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sectionBody))
                .andReturn();
        assertEquals(201, sectionResult.getResponse().getStatus());

        JsonNode section = objectMapper.readTree(sectionResult.getResponse().getContentAsString());
        String sectionId = section.path("id").asText();

        String seatBody = objectMapper.writeValueAsString(
                new com.ticketmaster.venue.seat.CreateSeatRequest("A", "12", 1.5, 2.5));
        MvcResult seatResult = mockMvc.perform(post(
                        "/api/v1/organizer/venues/{venueId}/sections/{sectionId}/seats", venueId, sectionId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seatBody))
                .andReturn();
        assertEquals(201, seatResult.getResponse().getStatus());

        int listStatus = mockMvc.perform(get(
                        "/api/v1/organizer/venues/{venueId}/sections/{sectionId}/seats", venueId, sectionId)
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus();
        assertEquals(200, listStatus);
    }
}
