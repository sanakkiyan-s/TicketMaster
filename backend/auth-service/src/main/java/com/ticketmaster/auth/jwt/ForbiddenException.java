package com.ticketmaster.auth.jwt;

/**
 * Thrown when a bearer token verifies but its `roles` claim lacks the role
 * an endpoint requires (ADR-030's admin gate, enforced here rather than at
 * a gateway that has no reason to hold this service's role vocabulary).
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException() {
        super("caller lacks the required role");
    }
}
