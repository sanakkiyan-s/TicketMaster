package com.ticketmaster.event.event;

/**
 * Thrown for both "no such event" and "that event belongs to a different
 * organizer". Both map to the same 404 in shared/ApiExceptionHandler —
 * never a 403 — so a caller probing another organizer's ids cannot use the
 * response to distinguish "doesn't exist" from "exists but isn't yours".
 * Same IDOR-oracle reasoning as user-service's
 * PaymentMethodNotFoundException, applied to ADR-030's ownership check.
 *
 * Public: shared/ApiExceptionHandler needs to reference it.
 */
public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(String message) {
        super(message);
    }
}
