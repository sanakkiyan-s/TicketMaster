package com.ticketmaster.venue.venue;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface VenueRepository extends JpaRepository<Venue, UUID> {

    List<Venue> findByOrganizerId(UUID organizerId);

    /**
     * Scoped by organizerId as well as id, so a lookup for someone else's
     * venue comes back empty rather than finding-then-authorizing. That is
     * what lets VenueService answer a mismatched id with the same 404 it
     * uses for a genuinely unknown id — see VenueNotFoundException.
     */
    Optional<Venue> findByIdAndOrganizerId(UUID id, UUID organizerId);
}
