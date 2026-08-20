package com.ticketmaster.auth.jwt.rotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketmaster.auth.jwt.TokenMinting;
import com.ticketmaster.auth.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.vault.VaultContainer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR-012's four-phase rotation, driven by the real scheduler against a real
 * Vault (Testcontainers, same pattern as VaultSigningKeyProviderTest) and a
 * real Postgres for the rotation_state row.
 *
 * Durations are overridden to milliseconds via @DynamicPropertySource so the
 * test proves the state machine's SEQUENCING, not ADR-012's real-world
 * minutes - RotationProperties exists specifically so this does not have to
 * sleep for 45 minutes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RotationTest {

    private static final String VAULT_TOKEN = "test-root-token";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final VaultContainer<?> VAULT =
            new VaultContainer<>("hashicorp/vault:1.17").withVaultToken(VAULT_TOKEN);

    static {
        POSTGRES.start();
        VAULT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("auth.jwt.key-source", () -> "vault");
        registry.add("auth.jwt.vault.uri",
                () -> "http://" + VAULT.getHost() + ":" + VAULT.getFirstMappedPort());
        registry.add("auth.jwt.vault.token", () -> VAULT_TOKEN);
        registry.add("auth.jwt.vault.backend", () -> "secret");
        registry.add("auth.jwt.vault.path", () -> "auth-service/jwt-keys");
        registry.add("auth.jwt.vault.bootstrap-if-empty", () -> "true");
        // Well under the (also shortened) publish/drain durations, so the
        // provider's own cache does not mask a rotation that already
        // happened in Vault.
        registry.add("auth.jwt.vault.refresh-interval", () -> "PT0.1S");

        // Short enough to run in a unit-test timeframe, long enough that
        // assertions can observe each phase before it advances again.
        registry.add("auth.jwt.rotation.publish-duration", () -> "PT1S");
        registry.add("auth.jwt.rotation.drain-duration", () -> "PT1S");
        registry.add("auth.jwt.rotation.scheduler-interval", () -> "PT0.3S");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RotationOrchestrator orchestrator;
    @Autowired RotationStateRepository states;
    @Autowired TokenMinting tokens;

    @BeforeEach
    void clearState() {
        states.deleteAll();
    }

    private String adminToken() {
        return tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.ADMIN));
    }

    private List<JsonNode> publishedKids() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode keys = objectMapper.readTree(body).path("keys");
        List<JsonNode> result = new java.util.ArrayList<>();
        keys.forEach(result::add);
        return result;
    }

    private static PublicKey rebuild(JsonNode jwk) throws Exception {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        BigInteger modulus = new BigInteger(1, decoder.decode(jwk.path("n").asText()));
        BigInteger exponent = new BigInteger(1, decoder.decode(jwk.path("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    @Test
    void nonAdminCannotStartOrCompromiseARotation() throws Exception {
        String userToken = tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.USER));

        mockMvc.perform(post("/api/v1/auth/admin/keys/rotate")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/admin/keys/compromise")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void rotationAdvancesThroughAllFourPhasesAndOldKeyStillValidatesDuringDrain() throws Exception {
        List<JsonNode> before = publishedKids();
        assertEquals(1, before.size(), "expected exactly one bootstrapped key before rotation");
        String oldKid = before.get(0).path("kid").asText();
        PublicKey oldPublicKey = rebuild(before.get(0));

        // Mint a token signed by the CURRENT (pre-rotation) key, before
        // anything changes - this is the token that must still validate
        // once phase 2 has cut over to the new key.
        String tokenSignedByOldKey =
                tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.USER));

        // --- start: PUBLISH ---
        mockMvc.perform(post("/api/v1/auth/admin/keys/rotate")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());

        waitUntil(() -> states.findSingleton().map(s -> phaseOf(s)).orElse(null) == RotationPhase.PUBLISH);
        assertEquals(2, publishedKids().size(), "PUBLISH must publish both keys in JWKS");

        // --- PUBLISH -> CUTOVER -> DRAIN (scheduler-driven) ---
        waitUntil(() -> phaseIs(RotationPhase.DRAIN));

        // The old key is still PUBLISHED (not signing, but present) during
        // DRAIN - a token it signed earlier must still verify.
        List<JsonNode> duringDrain = publishedKids();
        assertEquals(2, duringDrain.size(), "DRAIN must still publish both keys");

        JsonNode oldJwkDuringDrain = duringDrain.stream()
                .filter(k -> k.path("kid").asText().equals(oldKid))
                .findFirst().orElseThrow();
        assertEquals(oldPublicKey, rebuild(oldJwkDuringDrain));

        Jws<Claims> stillValid = Jwts.parser()
                .verifyWith(rebuild(oldJwkDuringDrain))
                .build()
                .parseSignedClaims(tokenSignedByOldKey);
        assertEquals(oldKid, stillValid.getHeader().getKeyId());

        // --- DRAIN -> RETIRE (back to IDLE) ---
        waitUntil(() -> phaseIs(RotationPhase.IDLE));

        List<JsonNode> afterRetire = publishedKids();
        assertEquals(1, afterRetire.size(), "RETIRE must remove the old key entirely");
        assertTrue(afterRetire.stream().noneMatch(k -> k.path("kid").asText().equals(oldKid)),
                "old key kid should no longer be published after RETIRE");
    }

    @Test
    void compromiseDestroysTheCurrentKeyImmediatelyAndTheOldTokenStopsValidating() throws Exception {
        List<JsonNode> before = publishedKids();
        String oldKid = before.get(0).path("kid").asText();
        String tokenSignedByOldKey =
                tokens.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), Set.of(Role.USER));

        mockMvc.perform(post("/api/v1/auth/admin/keys/compromise")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());

        List<JsonNode> after = publishedKids();
        assertEquals(1, after.size(), "compromise must leave exactly one signing key published");
        assertTrue(after.stream().noneMatch(k -> k.path("kid").asText().equals(oldKid)),
                "the compromised key must no longer be published");

        // The old token cannot be verified against anything currently published.
        boolean stillVerifiable = after.stream().anyMatch(jwk -> {
            try {
                Jwts.parser().verifyWith(rebuild(jwk)).build().parseSignedClaims(tokenSignedByOldKey);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(false, stillVerifiable, "a token signed by the compromised key must no longer verify");
    }

    private RotationPhase phaseOf(RotationState state) {
        return state.getPhase();
    }

    private boolean phaseIs(RotationPhase expected) {
        return states.findSingleton().map(RotationState::getPhase).orElse(null) == expected;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met within timeout");
    }
}
