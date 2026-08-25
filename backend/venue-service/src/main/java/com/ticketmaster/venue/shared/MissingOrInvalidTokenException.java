package com.ticketmaster.venue.shared;

/**
 * Thrown when the incoming request has no usable identity — no bearer
 * token, a token that doesn't look like a JWT, or a `sub` claim that
 * doesn't parse as this service's `user:&lt;uuid&gt;` shape.
 *
 * Maps to 401 in ApiExceptionHandler. This should not normally happen in
 * production: api-gateway (ADR-009) is supposed to reject the request
 * before it ever reaches this service if the token is missing or invalid.
 * Its presence here is a defense-in-depth backstop, not the primary
 * enforcement point.
 */
class MissingOrInvalidTokenException extends RuntimeException {
    MissingOrInvalidTokenException(String message) {
        super(message);
    }

    MissingOrInvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
