package com.ticketmaster.venue.venue;

/**
 * Thrown for both "no such venue" and "that venue belongs to a different
 * organizer". Both map to the same 404 in shared/ApiExceptionHandler —
 * never a 403 — so a caller probing another organizer's ids cannot use the
 * response to distinguish "doesn't exist" from "exists but isn't yours".
 * Same IDOR-oracle reasoning as event-service's EventNotFoundException,
 * applied to ADR-030's ownership check.
 *
 * Public: shared/ApiExceptionHandler needs to reference it.
 */
public class VenueNotFoundException extends RuntimeException {
    public VenueNotFoundException(String message) {
        super(message);
    }
}
