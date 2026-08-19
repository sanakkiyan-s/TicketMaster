package com.ticketmaster.auth.jwt;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

/**
 * Mints RS256 access tokens (ADR-012).
 *
 * Deliberately knows nothing about credentials. Verifying a password is the
 * login feature's job; this only turns an already-established identity into a
 * token, which keeps the one class holding a private key free of
 * authentication branches.
 */
@Service
class AccessTokenIssuer {

    private final SigningKeyProvider keys;
    private final JwtProperties properties;
    private final Clock clock;

    AccessTokenIssuer(SigningKeyProvider keys, JwtProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param sessionId `sid`, so one device can be logged out without killing
     *                  every session the user has.
     * @param amr       authentication methods actually used, which ADR-012
     *                  needs for step-up gating — a token from a password
     *                  alone must not satisfy a check that demands MFA.
     */
    String issue(UUID userId, UUID sessionId, Set<String> roles, Set<String> amr) {
        Instant now = Instant.now(clock);
        SigningKey key = keys.signing();

        return Jwts.builder()
                // The kid is what makes rotation survivable: the gateway looks
                // up which published key to verify with, instead of assuming
                // there is only ever one.
                .header().keyId(key.kid()).and()

                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()

                // `user:` prefix because ADR-009 also issues service tokens,
                // whose subjects are not users. A bare uuid would make the two
                // indistinguishable to an authorization check.
                .subject("user:" + userId)

                .id(UUID.randomUUID().toString())   // jti
                .claim("sid", sessionId.toString())

                // Sorted so a given role set always produces the same claim,
                // which keeps tokens comparable in tests and logs.
                .claim("roles", roles.stream().sorted().toList())
                .claim("amr", amr.stream().sorted().toList())

                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))

                .signWith(key.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    /** The common password-only login. */
    String issue(UUID userId, UUID sessionId, Set<String> roles) {
        return issue(userId, sessionId, roles, Set.of("pwd"));
    }
}
