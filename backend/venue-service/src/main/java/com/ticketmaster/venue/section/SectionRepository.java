package com.ticketmaster.venue.section;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByVenueId(UUID venueId);

    /**
     * Scoped by venueId as well as id, so a section id that exists but
     * belongs to a different venue comes back empty — the same
     * find-scoped-not-find-then-check pattern VenueRepository uses, one
     * level down. Ownership itself (organizer_id) is still resolved via
     * the parent venue, not here.
     */
    Optional<Section> findByIdAndVenueId(UUID id, UUID venueId);
}
