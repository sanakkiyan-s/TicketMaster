package com.ticketmaster.event.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findByOrganizerId(UUID organizerId);

    /**
     * Scoped by organizerId as well as id, so a lookup for someone else's
     * event comes back empty rather than finding-then-authorizing. That is
     * what lets EventService answer a mismatched id with the same 404 it
     * uses for a genuinely unknown id — see EventNotFoundException.
     */
    Optional<Event> findByIdAndOrganizerId(UUID id, UUID organizerId);
}
