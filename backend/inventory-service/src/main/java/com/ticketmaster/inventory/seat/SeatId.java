package com.ticketmaster.inventory.seat;

import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key (event_id, session_id, seat_id) — event_id included per
 * ADR-002's Citus co-location amendment, so the partial unique index
 * stays global once event_id-based distribution is turned on.
 */
@Embeddable
public class SeatId implements Serializable {

    private UUID eventId;
    private UUID sessionId;
    private UUID seatId;

    protected SeatId() {
        // JPA
    }

    public SeatId(UUID eventId, UUID sessionId, UUID seatId) {
        this.eventId = eventId;
        this.sessionId = sessionId;
        this.seatId = seatId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getSeatId() {
        return seatId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SeatId other)) return false;
        return Objects.equals(eventId, other.eventId)
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(seatId, other.seatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, sessionId, seatId);
    }
}
