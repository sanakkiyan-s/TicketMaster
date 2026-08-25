package com.ticketmaster.venue.section;

import java.time.Instant;
import java.util.UUID;

public record SectionResponse(
        UUID id,
        UUID venueId,
        String name,
        int capacity,
        Instant createdAt,
        Instant updatedAt
) {
    static SectionResponse from(Section section) {
        return new SectionResponse(
                section.getId(),
                section.getVenueId(),
                section.getName(),
                section.getCapacity(),
                section.getCreatedAt(),
                section.getUpdatedAt());
    }
}
