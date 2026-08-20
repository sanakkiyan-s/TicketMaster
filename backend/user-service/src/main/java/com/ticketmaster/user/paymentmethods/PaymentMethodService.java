package com.ticketmaster.user.paymentmethods;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class PaymentMethodService {

    private final SavedPaymentMethodRepository methods;
    private final Clock clock;

    PaymentMethodService(SavedPaymentMethodRepository methods, Clock clock) {
        this.methods = methods;
        this.clock = clock;
    }

    List<SavedPaymentMethod> list(UUID userId) {
        return methods.findByUserId(userId);
    }

    @Transactional
    SavedPaymentMethod add(UUID userId, String provider, String providerToken, String brand, String last4) {
        var method = new SavedPaymentMethod(userId, provider, providerToken, brand, last4, Instant.now(clock));
        return methods.saveAndFlush(method);
    }

    @Transactional
    void delete(UUID userId, UUID id) {
        SavedPaymentMethod method = methods.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new PaymentMethodNotFoundException(
                        "payment method not found or not owned by caller"));
        methods.delete(method);
    }
}
