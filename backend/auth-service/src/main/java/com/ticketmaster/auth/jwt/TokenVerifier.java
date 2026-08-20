package com.ticketmaster.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.ProtectedHeader;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies a bearer access token against this service's own published
 * signing keys.
 *
 * auth-service is the token issuer and already holds {@link
 * SigningKeyProvider}, so it verifies its own tokens properly - unlike a
 * downstream service (or the gateway) that only ever decodes without
 * checking a signature. That distinction is load-bearing here: the
 * admin/self-service endpoints this class backs (key rotation, logout,
 * ban) must not trust an unverified `sub` claim.
 *
 * One class, several callers (ADR-037): the rotation admin endpoints and
 * the three revocation endpoints all call {@link #verify(String)} instead
 * of each re-implementing signature checking.
 */
@Component
public class TokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SUBJECT_PREFIX = "user:";

    private final SigningKeyProvider keys;
    private final JwtProperties properties;
    private final Clock clock;

    TokenVerifier(SigningKeyProvider keys, JwtProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param authorizationHeader the raw `Authorization` header value,
     *                            e.g. {@code "Bearer eyJ..."}.
     */
    public VerifiedToken verify(String authorizationHeader) {
        String raw = stripBearerPrefix(authorizationHeader);

        Locator<Key> keyLocator = (Header header) -> {
            if (!(header instanceof ProtectedHeader protectedHeader)) {
                throw new JwtException("token header carries no key id");
            }
            String kid = protectedHeader.getKeyId();
            return keys.published().stream()
                    .filter(key -> key.kid().equals(kid))
                    .findFirst()
                    .map(SigningKey::publicKey)
                    .orElseThrow(() -> new JwtException("unknown kid: " + kid));
        };

        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .keyLocator(keyLocator)
                    .requireIssuer(properties.issuer())
                    .requireAudience(properties.audience())
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(raw);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidBearerTokenException("token failed verification: " + e.getMessage());
        }

        Claims claims = jws.getPayload();
        return toVerifiedToken(claims);
    }

    private String stripBearerPrefix(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new InvalidBearerTokenException("missing or malformed Authorization header");
        }
        String raw = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            throw new InvalidBearerTokenException("empty bearer token");
        }
        return raw;
    }

    private VerifiedToken toVerifiedToken(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
            throw new InvalidBearerTokenException("subject is not a user token");
        }

        UUID userId;
        try {
            userId = UUID.fromString(subject.substring(SUBJECT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("subject is not a valid user id");
        }

        String sid = claims.get("sid", String.class);
        if (sid == null) {
            throw new InvalidBearerTokenException("token has no sid claim");
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sid);
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("sid claim is not a valid uuid");
        }

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        Set<String> roleSet = roles == null ? Set.of() : Set.copyOf(roles);

        return new VerifiedToken(userId, sessionId, roleSet);
    }
}
