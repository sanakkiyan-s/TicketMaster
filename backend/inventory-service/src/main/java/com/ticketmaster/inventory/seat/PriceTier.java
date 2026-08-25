package com.ticketmaster.inventory.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(name = "price_tiers")
@Getter
public class PriceTier {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    protected PriceTier() {
        // JPA
    }

    public PriceTier(UUID id, UUID sessionId, String label, int priceCents) {
        this.id = id;
        this.sessionId = sessionId;
        this.label = label;
        this.priceCents = priceCents;
    }
}
