package com.ticketmaster.user.paymentmethods;

import java.time.Instant;
import java.util.UUID;

/** providerToken is intentionally NOT exposed here — display metadata only. */
public record PaymentMethodResponse(
        UUID id,
        String provider,
        String brand,
        String last4,
        boolean isDefault,
        Instant createdAt
) {
    static PaymentMethodResponse from(SavedPaymentMethod method) {
        return new PaymentMethodResponse(
                method.getId(),
                method.getProvider(),
                method.getBrand(),
                method.getLast4(),
                method.isDefault(),
                method.getCreatedAt());
    }
}
