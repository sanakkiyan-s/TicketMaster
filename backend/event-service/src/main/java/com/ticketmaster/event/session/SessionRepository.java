package com.ticketmaster.event.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SessionRepository extends JpaRepository<Session, UUID> {

    List<Session> findByEventId(UUID eventId);

    /**
     * Scoped by eventId as well as id, so a session id that exists but
     * belongs to a different event comes back empty — the same
     * find-scoped-not-find-then-check pattern EventRepository uses, one
     * level down. Ownership itself (organizer_id) is still resolved via
     * the parent event, not here.
     */
    Optional<Session> findByIdAndEventId(UUID id, UUID eventId);
}
