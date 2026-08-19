package com.ticketmaster.auth.token;

import java.util.UUID;

/**
 * The outcome of a successful rotation: who the session belongs to, which
 * device it is, and the replacement token.
 *
 * sessionId is carried out explicitly because the new ACCESS token must claim
 * the same `sid`. Minting a fresh session id on every refresh would make
 * "log out this device" impossible to express - the identifier a revocation
 * would target changes every ten minutes.
 */
public record RotatedSession(UUID userId, UUID sessionId, IssuedRefreshToken refreshToken) {
}
