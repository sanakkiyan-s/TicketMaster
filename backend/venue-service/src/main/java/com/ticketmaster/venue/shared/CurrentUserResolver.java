package com.ticketmaster.venue.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Resolves "me" into a UUID from the bearer token on the incoming request,
 * and separately exposes the `roles` claim for ADR-030's admin-bypass
 * check.
 *
 * This deliberately does NOT verify the JWT signature. Per ADR-009,
 * api-gateway performs local signature verification at the edge before
 * proxying the request downstream — building a second full verification
 * stack here (JWKS fetch, key rotation handling, etc.) would duplicate a
 * responsibility ADR-009/ADR-032 already assign to the gateway. This class
 * only base64url-decodes the JWT payload segment and reads claims, exactly
 * the way AccessTokenIssuer (auth-service) wrote them: `sub` as
 * {@code "user:" + userId}, `roles` as a JSON array of strings.
 *
 * Public (not package-private) because every feature package — venue,
 * section, seat — needs to resolve the caller's identity; that is what
 * shared/ is for per ADR-037. Duplicated per-service rather than shared as
 * a library — this repo's services never depend on each other's code.
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
        JsonNode payload = decodePayload(request);
        String subject = payload.path("sub").asText(null);

        if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
            throw new MissingOrInvalidTokenException("unrecognized subject claim");
        }

        try {
            return UUID.fromString(subject.substring(SUBJECT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        }
    }

    /**
     * ADR-030's admin-bypass check needs the caller's roles, not just
     * their identity — read from the same `roles` claim AccessTokenIssuer
     * (auth-service) writes. Absent or non-array claim resolves to an
     * empty list rather than throwing, since a missing roles claim is a
     * "no privileged role" fact, not a malformed-token fact.
     */
    public List<String> resolveRoles(HttpServletRequest request) {
        JsonNode rolesNode = decodePayload(request).path("roles");
        if (!rolesNode.isArray()) {
            return List.of();
        }

        List<String> roles = new ArrayList<>();
        rolesNode.forEach(node -> roles.add(node.asText()));
        return List.copyOf(roles);
    }

    private JsonNode decodePayload(HttpServletRequest request) {
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
            return objectMapper.readTree(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            // Base64 decode failure.
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        } catch (java.io.IOException e) {
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        }
    }
}
