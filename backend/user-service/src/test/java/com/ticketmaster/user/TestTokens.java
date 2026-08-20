package com.ticketmaster.user;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints a minimal, UNSIGNED test JWT — three base64url segments with a
 * real JSON payload but junk header/signature bytes.
 *
 * This is deliberately consistent with the production design: shared/
 * CurrentUserResolver never verifies a signature (that is api-gateway's
 * job per ADR-009), it only base64url-decodes the payload segment and
 * reads `sub`. So a real signature is not needed to exercise it, and
 * building one here would mean inventing a parallel auth mechanism for
 * tests only — exactly what the task brief says not to do.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String bearerTokenFor(UUID userId) {
        String header = encode("{\"alg\":\"none\"}");
        String payload = encode("{\"sub\":\"user:" + userId + "\"}");
        String signature = encode("test-signature-not-verified");
        return "Bearer " + header + "." + payload + "." + signature;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
