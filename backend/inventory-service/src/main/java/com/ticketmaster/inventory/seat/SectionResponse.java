package com.ticketmaster.inventory.seat;

import java.util.List;

/** Mirrors frontend types.ts's SeatSection. */
public record SectionResponse(String id, String name, List<SeatResponse> seats) {
}
