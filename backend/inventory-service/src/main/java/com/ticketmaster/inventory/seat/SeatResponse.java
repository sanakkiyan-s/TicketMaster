package com.ticketmaster.inventory.seat;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors frontend/src/features/booking/types.ts's Seat exactly.
 * heldByMe/heldUntil are only ever non-null-meaningful for the caller's
 * own hold — computed per-response against the resolved caller id, never
 * stored that way (privacy: another buyer must not see who holds a seat
 * or when it expires, only that it's HELD).
 */
public record SeatResponse(
        String id,
        int row,
        int col,
        String priceTierId,
        SeatStatus status,
        boolean heldByMe,
        Instant heldUntil
) {
    public static SeatResponse from(Seat seat, UUID callerId) {
        boolean mine = seat.isHeldBy(callerId);
        return new SeatResponse(
                seat.getId().getSeatId().toString(),
                seat.getRowNumber(),
                seat.getColNumber(),
                seat.getPriceTierId().toString(),
                seat.getStatus(),
                mine,
                mine ? seat.getHeldUntil() : null);
    }
}
