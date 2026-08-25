package com.ticketmaster.inventory.shared;

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
 * Resolves "me" from the bearer token on the incoming request. Does not
 * verify the JWT signature — api-gateway (ADR-009) already does that at
 * the edge; this only reads claims the way AccessTokenIssuer wrote them.
 * Duplicated per-service rather than shared as a library, matching every
 * other service in this repo (ADR-037).
 */
@Component
public class CurrentUserResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SUBJECT_PREFIX = "user:";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        } catch (java.io.IOException e) {
            throw new MissingOrInvalidTokenException("unparsable JWT claims", e);
        }
    }
}
