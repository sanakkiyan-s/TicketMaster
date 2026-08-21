package com.ticketmaster.inventory.seat;

/** ADR-002: only these three stored states — no EXPIRED value, ever. */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    PURCHASED
}
