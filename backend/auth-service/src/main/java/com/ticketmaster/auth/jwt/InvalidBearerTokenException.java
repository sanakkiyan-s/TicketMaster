package com.ticketmaster.auth.jwt;

/**
 * Thrown for every rejected bearer token presented to {@link TokenVerifier}:
 * missing header, malformed header, unknown `kid`, bad signature, wrong
 * issuer/audience, or expired. Deliberately carries no distinguishing detail
 * in the response - only in the exception message, for logs - matching
 * {@code InvalidRefreshTokenException}'s existing rationale: telling a caller
 * exactly why a token failed hands an attacker free intelligence about which
 * part of their forgery attempt was wrong.
 */
public class InvalidBearerTokenException extends RuntimeException {

    public InvalidBearerTokenException(String reasonForLogsOnly) {
        super(reasonForLogsOnly);
    }
}
