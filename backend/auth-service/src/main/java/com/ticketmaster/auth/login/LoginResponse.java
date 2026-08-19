package com.ticketmaster.auth.login;

import java.util.Set;
import java.util.UUID;

/**
 * The refresh token is deliberately absent from this body — it travels as an
 * httpOnly cookie instead, so JavaScript cannot read it and an XSS cannot
 * exfiltrate a 30-day credential. The access token is in the body precisely
 * because it is short-lived (10 min) and must be attachable as an
 * Authorization header by the client.
 *
 * @param expiresIn seconds, so the client can refresh proactively rather than
 *                  waiting for a 401 and retrying every in-flight request.
 */
record LoginResponse(String accessToken, String tokenType, long expiresIn, UserSummary user) {

    record UserSummary(UUID id, String email, Set<String> roles) {
    }
}
