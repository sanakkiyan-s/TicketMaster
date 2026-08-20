package com.ticketmaster.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Generates the OpenAPI spec and writes it to openapi/user-service.json —
 * same ADR-034 drift-gate pattern as auth-service's OpenApiSpecTest. See
 * that class's javadoc for why writing a source-tree file from a test is
 * deliberate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("openapi", "user-service.json");

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
    void generatesAndWritesTheSpec() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode spec = objectMapper.readTree(json);

        assertTrue(spec.at("/paths/~1api~1v1~1users~1me/get").isObject(),
                "profile GET endpoint missing from generated spec");
        assertTrue(spec.at("/paths/~1api~1v1~1users~1me~1preferences/get").isObject(),
                "preferences GET endpoint missing from generated spec");
        assertTrue(spec.at("/paths/~1api~1v1~1users~1me~1payment-methods/post").isObject(),
                "payment-methods POST endpoint missing from generated spec");

        Files.createDirectories(SPEC.getParent());
        Files.writeString(
                SPEC,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void swaggerUiIsReachableWithoutCredentials() throws Exception {
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertTrue(status == 200 || status == 302, "unexpected status " + status);
    }
}
