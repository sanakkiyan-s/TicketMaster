package com.ticketmaster.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA-256 over the raw refresh token.
 *
 * Deliberately NOT bcrypt, and this is the opposite of the rule for passwords
 * (ADR-012). Bcrypt's work factor exists to slow down guessing of low-entropy
 * human input. A refresh token is 256 bits of CSPRNG output — there is nothing
 * to guess, so the work factor would buy zero security while adding ~250ms of
 * CPU to every refresh, on the one endpoint every active session hits every 10
 * minutes.
 *
 * Also unsalted on purpose: salting defends against precomputation across
 * equal inputs, and two 256-bit random tokens are never equal. A deterministic
 * digest is required anyway, since lookup is BY the hash.
 */
final class TokenHashing {

    private TokenHashing() {
    }

    static String sha256(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
