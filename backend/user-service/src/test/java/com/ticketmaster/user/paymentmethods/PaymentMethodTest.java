package com.ticketmaster.user.paymentmethods;

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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentMethodTest {

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

    private MvcResult addPaymentMethod(String token) throws Exception {
        String body = objectMapper.writeValueAsString(
                new AddPaymentMethodRequest("stripe", "tok_abc123", "visa", "4242"));

        return mockMvc.perform(post("/api/v1/users/me/payment-methods")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    @Test
    void addThenListReturnsIt() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID());

        MvcResult addResult = addPaymentMethod(token);
        assertEquals(201, addResult.getResponse().getStatus());

        JsonNode added = objectMapper.readTree(addResult.getResponse().getContentAsString());
        assertEquals("stripe", added.path("provider").asText());
        assertEquals("visa", added.path("brand").asText());
        assertEquals("4242", added.path("last4").asText());
        // providerToken must never be echoed back.
        assertTrue(added.path("providerToken").isMissingNode());

        MvcResult listResult = mockMvc.perform(
                get("/api/v1/users/me/payment-methods").header("Authorization", token)).andReturn();
        assertEquals(200, listResult.getResponse().getStatus());

        JsonNode list = objectMapper.readTree(listResult.getResponse().getContentAsString());
        assertEquals(1, list.size());
        assertEquals(added.path("id").asText(), list.get(0).path("id").asText());
    }

    @Test
    void deleteRemovesItFromTheList() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID());

        JsonNode added = objectMapper.readTree(addPaymentMethod(token).getResponse().getContentAsString());
        String id = added.path("id").asText();

        assertEquals(204, mockMvc.perform(delete("/api/v1/users/me/payment-methods/{id}", id)
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus());

        JsonNode list = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/users/me/payment-methods").header("Authorization", token))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, list.size());
    }

    @Test
    void oneUsersTokenCannotReadOrDeleteAnotherUsersPaymentMethod() throws Exception {
        String ownerToken = TestTokens.bearerTokenFor(UUID.randomUUID());
        String attackerToken = TestTokens.bearerTokenFor(UUID.randomUUID());

        JsonNode owned = objectMapper.readTree(
                addPaymentMethod(ownerToken).getResponse().getContentAsString());
        String id = owned.path("id").asText();

        // The attacker's own list must not contain the victim's method.
        JsonNode attackerList = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/users/me/payment-methods").header("Authorization", attackerToken))
                .andReturn().getResponse().getContentAsString());
        assertEquals(0, attackerList.size());

        // Deleting someone else's id must answer 404, never 403 — a 403
        // would confirm the id exists to a caller who shouldn't be able to
        // tell (see PaymentMethodNotFoundException's javadoc).
        int deleteStatus = mockMvc.perform(delete("/api/v1/users/me/payment-methods/{id}", id)
                        .header("Authorization", attackerToken))
                .andReturn().getResponse().getStatus();
        assertEquals(404, deleteStatus);

        // The owner can still see it — the attacker's failed delete must not
        // have removed it.
        JsonNode ownerList = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/users/me/payment-methods").header("Authorization", ownerToken))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, ownerList.size());
    }

    @Test
    void deletingAnUnknownIdIs404() throws Exception {
        String token = TestTokens.bearerTokenFor(UUID.randomUUID());

        int status = mockMvc.perform(delete("/api/v1/users/me/payment-methods/{id}", UUID.randomUUID())
                        .header("Authorization", token))
                .andReturn().getResponse().getStatus();
        assertEquals(404, status);
    }
}
