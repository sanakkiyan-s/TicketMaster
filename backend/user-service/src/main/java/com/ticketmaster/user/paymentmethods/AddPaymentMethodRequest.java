package com.ticketmaster.user.paymentmethods;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * providerToken is trusted as given — there is no payment-service yet to
 * call to validate that the token is real (wiki/projects/user-service.md's
 * Target Design: this service only stores the reference, it does not
 * originate or verify it). Once payment-service exists, validating the
 * token against it before persisting is the natural next step; deferred
 * here on purpose rather than half-built against a service that doesn't
 * exist.
 */
public record AddPaymentMethodRequest(

        @NotBlank
        @Size(max = 64)
        String provider,

        @NotBlank
        @Size(max = 512)
        String providerToken,

        @Size(max = 32)
        String brand,

        @Size(max = 4)
        String last4
) {
}
