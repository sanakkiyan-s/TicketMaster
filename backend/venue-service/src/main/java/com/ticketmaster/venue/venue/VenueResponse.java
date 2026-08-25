package com.ticketmaster.venue.venue;

import java.time.Instant;
import java.util.UUID;

public record VenueResponse(
        UUID id,
        UUID organizerId,
        String name,
        String address,
        String city,
        String region,
        Instant createdAt,
        Instant updatedAt
) {
    static VenueResponse from(Venue venue) {
        return new VenueResponse(
                venue.getId(),
                venue.getOrganizerId(),
                venue.getName(),
                venue.getAddress(),
                venue.getCity(),
                venue.getRegion(),
                venue.getCreatedAt(),
                venue.getUpdatedAt());
    }
}
