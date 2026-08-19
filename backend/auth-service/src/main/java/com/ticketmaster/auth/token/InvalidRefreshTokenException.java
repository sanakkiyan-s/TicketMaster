package com.ticketmaster.auth.token;

/**
 * Thrown for every rejected refresh: unknown token, expired, revoked, or
 * replayed. Deliberately carries no reason.
 *
 * Telling a caller "this token was revoked because reuse was detected" tells
 * an attacker their stolen token was noticed, and telling them "expired"
 * rather than "unknown" confirms the token was real. Both are free
 * intelligence. The reason is logged server-side, where it belongs.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String reasonForLogsOnly) {
        super(reasonForLogsOnly);
    }
}
