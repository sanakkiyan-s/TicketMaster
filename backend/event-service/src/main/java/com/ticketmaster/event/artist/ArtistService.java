package com.ticketmaster.event.artist;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class ArtistService {

    private final ArtistRepository artists;
    private final Clock clock;

    ArtistService(ArtistRepository artists, Clock clock) {
        this.artists = artists;
        this.clock = clock;
    }

    @Transactional
    Artist create(String name, String bio) {
        return artists.saveAndFlush(new Artist(UUID.randomUUID(), name, bio, Instant.now(clock)));
    }

    Artist get(UUID id) {
        return artists.findById(id)
                .orElseThrow(() -> new ArtistNotFoundException("artist not found"));
    }

    List<Artist> search(String name) {
        if (name == null || name.isBlank()) {
            return artists.findAll();
        }
        return artists.findByNameContainingIgnoreCase(name);
    }
}
