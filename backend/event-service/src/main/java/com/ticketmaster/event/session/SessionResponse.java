package com.ticketmaster.event.session;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        UUID eventId,
        Instant startsAt,
        Instant endsAt,
        SessionStatus status,
        Instant onSaleAt,
        boolean highDemand,
        Instant createdAt,
        Instant updatedAt
) {
    static SessionResponse from(Session session) {
        return new SessionResponse(
                session.getId(),
                session.getEventId(),
                session.getStartsAt(),
                session.getEndsAt(),
                session.getStatus(),
                session.getOnSaleAt(),
                session.isHighDemand(),
                session.getCreatedAt(),
                session.getUpdatedAt());
    }
}
