package com.ticketmaster.auth;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Generates the OpenAPI spec and writes it to openapi/auth-service.json
 * (ADR-034).
 *
 * Writing a source-tree file from a test is deliberate. The ADR's drift
 * gate needs a committed previous version to diff against, and there is no
 * such thing unless something regenerates it every build. `./gradlew test`
 * regenerates; CI then runs `git diff --exit-code openapi/` and FLAGS a
 * changed contract — flags, not blocks, because REST evolution here is
 * still fluid pre-launch (unlike ADR-023's `buf breaking`, which blocks).
 *
 * Consequence worth knowing: change a request record or a status code and
 * this test leaves a dirty working tree. That is the signal, not a bug.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTest {

    private static final Path SPEC = Path.of("openapi", "auth-service.json");

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

        // Sanity: an empty or error document would still write a file and
        // silently pass a diff gate forever.
        assertTrue(spec.at("/paths/~1api~1v1~1auth~1register/post").isObject(),
                "register endpoint missing from generated spec");

        // The constraints on RegisterRequest must survive into the spec —
        // that is the whole reason the spec is generated rather than
        // written by hand.
        JsonNode password = spec.at("/components/schemas/RegisterRequest/properties/password");
        assertEquals(12, password.path("minLength").asInt());
        assertEquals(128, password.path("maxLength").asInt());

        Files.createDirectories(SPEC.getParent());
        Files.writeString(
                SPEC,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void swaggerUiIsReachableWithoutCredentials() throws Exception {
        // 302 to swagger-ui/index.html is springdoc's normal behaviour;
        // what matters is that it is not a 401. ADR-032's rule for
        // probes applies here too: an authenticated doc endpoint is a doc
        // endpoint nobody can aggregate.
        int status = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertTrue(status == 200 || status == 302, "unexpected status " + status);
    }
}
