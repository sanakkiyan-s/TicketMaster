package com.ticketmaster.auth.token;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Mints and stores refresh tokens (ADR-012).
 *
 * Public because the login feature needs it and refresh/rotation will too;
 * everything else in this package stays package-private (ADR-037).
 */
@Service
public class RefreshTokenService {

    /**
     * 256 bits. The token IS the credential — there is no password behind it —
     * so it must be infeasible to guess, not merely long.
     */
    private static final int TOKEN_BYTES = 32;

    /** ADR-012: 30 days, acceptable only because rotation + reuse detection exist. */
    private static final Duration LIFETIME = Duration.ofDays(30);

    /**
     * One shared instance. SecureRandom is thread-safe, and constructing one
     * per call can block on entropy — on the login path under load that is a
     * self-inflicted stall.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final Clock clock;

    RefreshTokenService(RefreshTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /** A brand-new family: a fresh login, not a rotation. */
    @Transactional
    public IssuedRefreshToken issueForNewSession(UUID userId) {
        return issue(userId, UUID.randomUUID(), UUID.randomUUID());
    }

    @Transactional
    public IssuedRefreshToken issue(UUID userId, UUID familyId, UUID sessionId) {
        byte[] entropy = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(entropy);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(LIFETIME);

        // Only the hash is persisted. rawToken is returned to the caller and
        // written nowhere else.
        tokens.save(new RefreshToken(
                userId, TokenHashing.sha256(rawToken), familyId, sessionId, now, expiresAt));

        return new IssuedRefreshToken(rawToken, familyId, sessionId, expiresAt);
    }
}
