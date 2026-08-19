package com.ticketmaster.user.paymentmethods;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A reference to a payment method held by an external provider — NEVER
 * raw card data. providerToken is the opaque provider-side token; brand
 * and last4 are display metadata only, safe to store because neither
 * reconstructs a usable card number.
 *
 * userId has no FK into auth-service's users table — same ADR-001
 * reasoning as profile/UserProfile.
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "saved_payment_methods")
@Getter
public class SavedPaymentMethod {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_token", nullable = false)
    private String providerToken;

    @Column(name = "brand")
    private String brand;

    @Column(name = "last4")
    private String last4;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedPaymentMethod() {
        // JPA
    }

    public SavedPaymentMethod(
            UUID userId, String provider, String providerToken, String brand, String last4, Instant now) {
        this.userId = userId;
        this.provider = provider;
        this.providerToken = providerToken;
        this.brand = brand;
        this.last4 = last4;
        this.isDefault = false;
        this.createdAt = now;
    }
}
