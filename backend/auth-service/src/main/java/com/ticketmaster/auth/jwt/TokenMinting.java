package com.ticketmaster.auth.jwt;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * The only way out of this package.
 *
 * AccessTokenIssuer stays package-private so nothing outside jwt/ can touch
 * the class that holds a private key, or reach SigningKeyProvider and pull the
 * key material itself. This facade exposes exactly two operations — mint a
 * token, and tell me how long it lasts — and nothing that returns a key.
 *
 * ADR-037 organises by feature; this is what the feature's public edge looks
 * like when the feature owns a secret.
 */
@Service
public class TokenMinting {

    private final AccessTokenIssuer issuer;
    private final JwtProperties properties;

    TokenMinting(AccessTokenIssuer issuer, JwtProperties properties) {
        this.issuer = issuer;
        this.properties = properties;
    }

    public String issueAccessToken(UUID userId, UUID sessionId, Set<String> roles) {
        return issuer.issue(userId, sessionId, roles);
    }

    /** So a client can refresh proactively instead of waiting for a 401. */
    public Duration accessTokenLifetime() {
        return properties.accessTokenTtl();
    }
}
