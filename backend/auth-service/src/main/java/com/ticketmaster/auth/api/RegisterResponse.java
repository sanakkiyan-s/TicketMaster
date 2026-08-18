package com.ticketmaster.auth.api;

import java.util.Set;
import java.util.UUID;

/**
 * Deliberately does NOT contain a token. Registration and authentication
 * are separate steps: auto-issuing a session on register would mean a
 * bot that completes registration is already authenticated, and ADR-014's
 * Verified Fan flow needs registration to be cheap to reject after the
 * fact.
 */
public record RegisterResponse(UUID id, String email, Set<String> roles) {
}
