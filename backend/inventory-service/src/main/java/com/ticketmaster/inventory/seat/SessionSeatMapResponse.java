package com.ticketmaster.inventory.seat;

import java.util.List;

/** Mirrors frontend types.ts's SessionSeatMap exactly. */
public record SessionSeatMapResponse(
        String sessionId,
        String eventId,
        List<PriceTierResponse> priceTiers,
        List<SectionResponse> sections
) {
}
