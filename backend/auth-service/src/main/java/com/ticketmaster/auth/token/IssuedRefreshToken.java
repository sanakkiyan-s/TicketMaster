package com.ticketmaster.auth.token;

import java.time.Instant;
import java.util.UUID;

/**
 * A freshly minted refresh token: the raw value — which exists exactly once,
 * in this object, on its way to a Set-Cookie header — plus the identifiers the
 * caller needs.
 *
 * A record rather than the entity, because the entity holds only the hash. If
 * callers had to read the raw token off the entity, the entity would have to
 * keep it, and then it would be persisted.
 */
public record IssuedRefreshToken(String rawToken, UUID familyId, UUID sessionId, Instant expiresAt) {
}
