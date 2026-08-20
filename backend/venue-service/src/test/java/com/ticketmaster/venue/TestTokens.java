package com.ticketmaster.venue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Mints a minimal, UNSIGNED test JWT — three base64url segments with a
 * real JSON payload but junk header/signature bytes.
 *
 * This is deliberately consistent with the production design: shared/
 * CurrentUserResolver never verifies a signature (that is api-gateway's
 * job per ADR-009), it only base64url-decodes the payload segment and
 * reads `sub` and `roles`. So a real signature is not needed to exercise
 * it, and building one here would mean inventing a parallel auth
 * mechanism for tests only.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static String bearerTokenFor(UUID userId) {
        return bearerTokenFor(userId, List.of());
    }

    public static String bearerTokenFor(UUID userId, List<String> roles) {
        String header = encode("{\"alg\":\"none\"}");
        String rolesJson = roles.stream()
                .map(role -> "\"" + role + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String payload = encode("{\"sub\":\"user:" + userId + "\",\"roles\":[" + rolesJson + "]}");
        String signature = encode("test-signature-not-verified");
        return "Bearer " + header + "." + payload + "." + signature;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
