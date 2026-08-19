package com.ticketmaster.user.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Resolves "me" into a UUID from the bearer token on the incoming request.
 *
 * This deliberately does NOT verify the JWT signature. Per ADR-009,
 * api-gateway performs local signature verification at the edge before
 * proxying the request downstream — building a second full verification
 * stack here (JWKS fetch, key rotation handling, etc.) would duplicate a
 * responsibility ADR-009/ADR-032 already assign to the gateway. This class
 * only base64url-decodes the JWT payload segment and reads the `sub`
 * claim, exactly the way AccessTokenIssuer (auth-service) wrote it:
 * {@code "user:" + userId}.
 *
 * Public (not package-private) because every feature package — profile,
 * preferences, paymentmethods — needs to resolve the caller's identity;
 * that is what shared/ is for per ADR-037.
 */
@Component
public class CurrentUserResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SUBJECT_PREFIX = "user:";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @throws MissingOrInvalidTokenException if there is no usable bearer
     *         token — see that exception's javadoc for why this should be
     *         unreachable in production behind api-gateway.
     */
    public UUID resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MissingOrInvalidTokenException("missing bearer token");
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new MissingOrInvalidTokenException("malformed JWT");
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
            String subject = payload.path("sub").asText(null);

            if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
                throw new MissingOrInvalidTokenException("unrecognized subject claim");
            }

            return UUID.fromString(subject.substring(SUBJECT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            // Covers both Base64 decode failures and UUID.fromString failures.
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        } catch (java.io.IOException e) {
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        }
    }
}
