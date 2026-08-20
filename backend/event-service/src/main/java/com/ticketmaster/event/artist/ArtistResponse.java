package com.ticketmaster.event.artist;

import java.time.Instant;
import java.util.UUID;

public record ArtistResponse(
        UUID id,
        String name,
        String bio,
        Instant createdAt,
        Instant updatedAt
) {
    static ArtistResponse from(Artist artist) {
        return new ArtistResponse(
                artist.getId(),
                artist.getName(),
                artist.getBio(),
                artist.getCreatedAt(),
                artist.getUpdatedAt());
    }
}
