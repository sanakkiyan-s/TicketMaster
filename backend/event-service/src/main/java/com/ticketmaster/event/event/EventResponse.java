package com.ticketmaster.event.event;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID id,
        UUID venueId,
        UUID organizerId,
        String title,
        String description,
        String category,
        EventStatus status,
        String region,
        Instant createdAt,
        Instant updatedAt
) {
    static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getVenueId(),
                event.getOrganizerId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getStatus(),
                event.getRegion(),
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}
