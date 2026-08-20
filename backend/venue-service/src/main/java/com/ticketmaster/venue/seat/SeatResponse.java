package com.ticketmaster.venue.seat;

import java.time.Instant;
import java.util.UUID;

public record SeatResponse(
        UUID id,
        UUID sectionId,
        String rowLabel,
        String seatNumber,
        Double xCoord,
        Double yCoord,
        Instant createdAt,
        Instant updatedAt
) {
    static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSectionId(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.getXCoord(),
                seat.getYCoord(),
                seat.getCreatedAt(),
                seat.getUpdatedAt());
    }
}
