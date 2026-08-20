package com.ticketmaster.event.artist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ArtistRepository extends JpaRepository<Artist, UUID> {

    /** Simple name search — no search-service integration needed for this slice. */
    List<Artist> findByNameContainingIgnoreCase(String name);
}
