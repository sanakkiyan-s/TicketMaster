package com.ticketmaster.inventory.seat;

import java.time.Instant;

/** Mirrors frontend types.ts's HoldResponse exactly. */
public record HoldResponse(String seatId, SeatStatus status, Instant heldUntil) {
}
