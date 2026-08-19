package com.ticketmaster.auth.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

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

    /**
     * Exchanges a refresh token for a new one (ADR-012 rotation).
     *
     * The old token is never reusable afterwards: refresh tokens are
     * single-use by construction, which is what makes replay detectable at
     * all. A token that stayed valid after use would make theft invisible,
     * because the thief's use and the owner's use would be indistinguishable.
     *
     * The new token stays in the SAME family and keeps the SAME session id -
     * the family is the chain reuse detection reasons about, and the session
     * is the device, so neither may change on a rotation.
     */
    // noRollbackFor is load-bearing, not a workaround. Reuse detection ends by
    // throwing, and a RuntimeException rolls the transaction back by default -
    // which would UNDO the family revocation that detection just performed.
    // The attacker would get a 401 and keep a working token, and the log line
    // would claim the family was revoked when nothing was.
    //
    // Caught by RefreshTest#reusingATokenRevokesTheWholeFamily, which is
    // exactly why that test presents the attacker's rotated token afterwards
    // instead of stopping at the 401.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RotatedSession rotate(String rawToken) {
        Instant now = Instant.now(clock);

        RefreshToken token = tokens.findByTokenHash(TokenHashing.sha256(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("unknown token"));

        if (token.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException("token belongs to a revoked family");
        }

        if (!token.getExpiresAt().isAfter(now)) {
            throw new InvalidRefreshTokenException("token expired");
        }

        // The claim is the security boundary. 0 rows means used_at was already
        // set, so this token has been presented before - either a replay by a
        // thief, or the legitimate client racing itself. Both are treated as
        // theft, because they are indistinguishable from here and the cost of
        // guessing wrong in the other direction is an attacker keeping a live
        // credential.
        if (tokens.claim(token.getId(), now) == 0) {
            int revoked = tokens.revokeFamily(token.getFamilyId(), now);
            log.warn("refresh token reuse detected for family {}; revoked {} token(s)",
                    token.getFamilyId(), revoked);
            throw new InvalidRefreshTokenException("token reuse detected");
        }

        IssuedRefreshToken issued = issue(token.getUserId(), token.getFamilyId(), token.getSessionId());
        return new RotatedSession(token.getUserId(), token.getSessionId(), issued);
    }

    /** ADR-012 self-service logout: revokes only the caller's own session. */
    @Transactional
    public int revokeSession(UUID sessionId) {
        return tokens.revokeBySessionId(sessionId, Instant.now(clock));
    }

    /** ADR-012 "log out everywhere" and the admin ban path: every session a user has. */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return tokens.revokeAllByUserId(userId, Instant.now(clock));
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
