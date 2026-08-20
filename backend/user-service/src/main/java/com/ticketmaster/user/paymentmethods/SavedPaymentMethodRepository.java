package com.ticketmaster.user.paymentmethods;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SavedPaymentMethodRepository extends JpaRepository<SavedPaymentMethod, UUID> {

    List<SavedPaymentMethod> findByUserId(UUID userId);

    /**
     * Scoped by userId as well as id, so a lookup for someone else's id
     * comes back empty rather than finding-then-authorizing. That is what
     * lets the service answer a mismatched id with the same 404 it uses
     * for a genuinely unknown id — see PaymentMethodNotFoundException.
     */
    Optional<SavedPaymentMethod> findByIdAndUserId(UUID id, UUID userId);
}
