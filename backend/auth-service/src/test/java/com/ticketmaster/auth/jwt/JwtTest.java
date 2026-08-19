package com.ticketmaster.auth.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the token/JWKS pair the way the gateway will: take the published
 * public key, rebuild it from `n` and `e`, and check a real signature with it.
 *
 * Asserting the JSON shape alone would pass with a plain-base64 (rather than
 * base64url) modulus, or with `n` still carrying BigInteger's sign byte — both
 * produce a JWKS that parses cleanly and then fails every verification in
 * production.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtTest {

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
    @Autowired AccessTokenIssuer issuer;
    @Autowired JwtProperties properties;

    private JsonNode firstPublishedJwk() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("keys").path(0);
    }

    /** Rebuilds the key from published material only, as a gateway must. */
    private static PublicKey rebuild(JsonNode jwk) throws Exception {
        Base64.Decoder decoder = Base64.getUrlDecoder();
        BigInteger modulus = new BigInteger(1, decoder.decode(jwk.path("n").asText()));
        BigInteger exponent = new BigInteger(1, decoder.decode(jwk.path("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    @Test
    void jwksIsPublicAndCarriesNoPrivateMaterial() throws Exception {
        JsonNode key = firstPublishedJwk();

        assertEquals("RSA", key.path("kty").asText());
        assertEquals("sig", key.path("use").asText());
        assertEquals("RS256", key.path("alg").asText());
        assertFalse(key.path("kid").asText().isBlank());

        // The entire risk of this endpoint, in one assertion.
        for (String secret : new String[]{"d", "p", "q", "dp", "dq", "qi"}) {
            assertTrue(key.path(secret).isMissingNode(),
                    "JWKS leaked private RSA parameter '" + secret + "'");
        }
    }

    @Test
    void jwksIsCacheableForTheRotationOverlap() throws Exception {
        // ADR-012 derives rotation phase 1's duration from this TTL. If the
        // header said no-cache, gateways would refetch constantly and the
        // "publish before you sign" overlap would stop meaning anything.
        String cacheControl = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getHeader("Cache-Control");

        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("max-age=300"),
                "unexpected Cache-Control: " + cacheControl);
    }

    @Test
    void tokenVerifiesAgainstThePublishedKey() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = issuer.issue(userId, sessionId, Set.of("USER", "ORGANIZER"));

        JsonNode key = firstPublishedJwk();

        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(rebuild(key))
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .build()
                .parseSignedClaims(token);

        // kid must match, or rotation cannot route a token to the right key.
        assertEquals(key.path("kid").asText(), parsed.getHeader().getKeyId());
        assertEquals("RS256", parsed.getHeader().getAlgorithm());

        Claims claims = parsed.getPayload();
        assertEquals("user:" + userId, claims.getSubject());
        assertEquals(sessionId.toString(), claims.get("sid"));
        assertNotNull(claims.getId(), "jti missing — revocation needs it");
        assertEquals(List.of("ORGANIZER", "USER"), claims.get("roles"));
        assertEquals(List.of("pwd"), claims.get("amr"));
    }

    @Test
    void accessTokenExpiresOnTheAdr012Schedule() throws Exception {
        String token = issuer.issue(UUID.randomUUID(), UUID.randomUUID(), Set.of("USER"));

        Claims claims = Jwts.parser()
                .verifyWith(rebuild(firstPublishedJwk()))
                .build()
                .parseSignedClaims(token)
                .getPayload();

        long lifetimeSeconds =
                (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(properties.accessTokenTtl().toSeconds(), lifetimeSeconds);
    }

    @Test
    void jwksNeedsNoCredentials() throws Exception {
        // A token cannot be the credential for fetching the keys that verify
        // tokens. If this ever 401s, every gateway fails closed at once.
        mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
    }
}
