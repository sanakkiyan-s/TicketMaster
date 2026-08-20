package com.ticketmaster.user.paymentmethods;

/**
 * Thrown for both "no such payment method" and "that payment method
 * belongs to someone else". Both map to the same 404 in
 * shared/ApiExceptionHandler — never a 403 — so that a caller probing
 * another user's ids cannot use the response to distinguish "doesn't
 * exist" from "exists but isn't yours" (the same IDOR-oracle instinct
 * auth-service's ApiExceptionHandler applies to login and refresh).
 *
 * Public: shared/ApiExceptionHandler needs to reference it, which is
 * exactly the "something outside the feature genuinely needs it"
 * exception ADR-037 rule 1 carves out.
 */
public class PaymentMethodNotFoundException extends RuntimeException {
    public PaymentMethodNotFoundException(String message) {
        super(message);
    }
}
