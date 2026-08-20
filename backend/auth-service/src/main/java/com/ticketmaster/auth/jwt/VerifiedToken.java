package com.ticketmaster.auth.jwt;

import java.util.Set;
import java.util.UUID;

/**
 * The result of a successful {@link TokenVerifier#verify(String)} call:
 * exactly the claims a caller outside this package is allowed to see.
 *
 * Deliberately narrower than the full JWT claim set - no `jti`, no `amr` -
 * because nothing outside {@code jwt/} has needed them yet, and adding a
 * field back is cheap while a caller depending on one prematurely is not.
 */
public record VerifiedToken(UUID userId, UUID sessionId, Set<String> roles) {
}
