package com.ticketmaster.inventory.seat;

/** Mirrors frontend types.ts's PriceTier. */
public record PriceTierResponse(String id, String label, int priceCents) {
    public static PriceTierResponse from(PriceTier tier) {
        return new PriceTierResponse(tier.getId().toString(), tier.getLabel(), tier.getPriceCents());
    }
}
