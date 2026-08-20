package com.ticketmaster.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;

/**
 * RFC 7638 JWK thumbprint: SHA-256 over the canonical JSON of the key's
 * required members, lexicographically ordered, no whitespace.
 *
 * Shared by both providers on purpose. If the ephemeral and Vault providers
 * derived kids differently, a key seeded by one and read by the other would
 * change identity, and every already-issued token naming the old kid would
 * fail verification against a key set that still contains the same key.
 *
 * Public so {@code jwt.rotation} can derive the same kid for a freshly
 * generated key, rather than inventing a second thumbprint algorithm.
 */
public final class Thumbprint {

    private Thumbprint() {
    }

    public static String of(RSAPublicKey publicKey) {
        String canonical = "{\"e\":\"" + Base64Url.of(publicKey.getPublicExponent())
                + "\",\"kty\":\"RSA\",\"n\":\"" + Base64Url.of(publicKey.getModulus()) + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64Url.of(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
