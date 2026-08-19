package com.ticketmaster.auth.jwt;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes the public half of the signing set (RFC 7517).
 *
 * Not under /api/v1: `/.well-known/jwks.json` is a registered well-known URI
 * and is infrastructure rather than product API. ADR-034's versioning governs
 * the client-facing surface; versioning a JWKS document would break every
 * standard client, which expects the fixed path.
 */
@RestController
@Tag(name = "jwks", description = "Public signing keys for token verification")
class JwksController {

    /**
     * Matches ADR-012's assumed gateway JWKS cache TTL. Load-bearing, not a
     * performance tweak: phase 1 of rotation must publish a new key for at
     * least one TTL before signing with it, so if this header and the
     * gateway's cache disagree, the real overlap is shorter than the rotation
     * schedule believes it is.
     */
    private static final long CACHE_SECONDS = 300;

    private final SigningKeyProvider keys;

    JwksController(SigningKeyProvider keys) {
        this.keys = keys;
    }

    @Operation(
            summary = "JSON Web Key Set",
            description = """
                    Every key gateways should currently accept. Public material
                    only. Cached for 5 minutes; gateways must refresh
                    proactively in the background and must NOT fetch on an
                    unknown `kid` — a lazy fetch turns a flood of forged `kid`
                    values into an amplified attack on auth-service (ADR-012).""")
    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<Map<String, Object>> jwks() {
        List<Map<String, String>> jwks = keys.published().stream()
                .map(JwksController::toJwk)
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .body(Map.of("keys", jwks));
    }

    /**
     * Only `n` and `e` — modulus and public exponent. {@link SigningKey} is
     * mapped field by field rather than serialized wholesale, so no branch
     * here can reach the private fields (`d`, `p`, `q`). Serializing the key
     * object directly is how private material ends up on a public endpoint.
     */
    private static Map<String, String> toJwk(SigningKey key) {
        Map<String, String> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        jwk.put("kid", key.kid());
        jwk.put("n", Base64Url.of(key.publicKey().getModulus()));
        jwk.put("e", Base64Url.of(key.publicKey().getPublicExponent()));
        return jwk;
    }
}
