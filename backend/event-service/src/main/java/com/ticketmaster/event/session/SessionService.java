package com.ticketmaster.event.session;

import com.ticketmaster.event.event.EventService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * No outbox events published here — session.* events (e.g.
 * session.on_sale_started) are a later slice per the task brief; this
 * organizer-CRUD slice only publishes event.* through EventService.
 */
@Service
public class SessionService {

    private final SessionRepository sessions;
    private final EventService eventService;
    private final Clock clock;

    SessionService(SessionRepository sessions, EventService eventService, Clock clock) {
        this.sessions = sessions;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Transactional
    Session createSession(UUID eventId, UUID callerId, boolean isAdmin,
                           Instant startsAt, Instant endsAt, Instant onSaleAt) {
        // Throws EventNotFoundException (404, not 403) if the event doesn't
        // exist or isn't the caller's — see EventService.findOwned.
        eventService.findOwned(eventId, callerId, isAdmin);
        Instant now = Instant.now(clock);
        return sessions.saveAndFlush(new Session(UUID.randomUUID(), eventId, startsAt, endsAt, onSaleAt, now));
    }

    @Transactional
    Session updateSession(UUID eventId, UUID sessionId, UUID callerId, boolean isAdmin,
                           Instant startsAt, Instant endsAt, Instant onSaleAt) {
        eventService.findOwned(eventId, callerId, isAdmin);
        Session session = findInEvent(eventId, sessionId);
        session.update(startsAt, endsAt, onSaleAt, Instant.now(clock));
        return session;
    }

    @Transactional
    Session cancelSession(UUID eventId, UUID sessionId, UUID callerId, boolean isAdmin) {
        eventService.findOwned(eventId, callerId, isAdmin);
        Session session = findInEvent(eventId, sessionId);
        session.cancel(Instant.now(clock));
        return session;
    }

    /**
     * Public read, no ownership check — same "no CurrentUserResolver"
     * carve-out as search-service's EventSearchController. Returns
     * sessions regardless of the parent event's status (including DRAFT),
     * matching the existing documented gap on that public surface rather
     * than introducing a new inconsistency here.
     */
    List<Session> listSessionsForEvent(UUID eventId) {
        return sessions.findByEventId(eventId);
    }

    private Session findInEvent(UUID eventId, UUID sessionId) {
        return sessions.findByIdAndEventId(sessionId, eventId)
                .orElseThrow(() -> new SessionNotFoundException("session not found"));
    }
}
