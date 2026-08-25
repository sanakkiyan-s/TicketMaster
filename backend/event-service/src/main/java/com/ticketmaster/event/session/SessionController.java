package com.ticketmaster.event.session;

import com.ticketmaster.event.shared.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Nested under its parent event, since a session's ownership (ADR-030) is
 * the parent event's organizer_id — every method here re-checks that via
 * SessionService/EventService before touching the session.
 *
 * No springdoc annotations — see EventController's javadoc for why.
 */
@RestController
@RequestMapping("/api/v1/organizer/events/{eventId}/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final CurrentUserResolver currentUser;

    public SessionController(SessionService sessionService, CurrentUserResolver currentUser) {
        this.sessionService = sessionService;
        this.currentUser = currentUser;
    }

    /** Create a session under an event. 404s if the event doesn't exist or isn't the caller's. */
    @PostMapping
    public ResponseEntity<SessionResponse> create(
            @PathVariable UUID eventId, @Valid @RequestBody CreateSessionRequest body, HttpServletRequest request) {
        Session created = sessionService.createSession(
                eventId, currentUser.resolve(request), isAdmin(request),
                body.startsAt(), body.endsAt(), body.onSaleAt());

        return ResponseEntity
                .created(URI.create("/api/v1/organizer/events/" + eventId + "/sessions/" + created.getId()))
                .body(SessionResponse.from(created));
    }

    /** Update a session's schedule. */
    @PutMapping("/{sessionId}")
    public SessionResponse update(
            @PathVariable UUID eventId, @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateSessionRequest body, HttpServletRequest request) {
        Session updated = sessionService.updateSession(
                eventId, sessionId, currentUser.resolve(request), isAdmin(request),
                body.startsAt(), body.endsAt(), body.onSaleAt());
        return SessionResponse.from(updated);
    }

    /** Cancel a session. */
    @PostMapping("/{sessionId}/cancel")
    public SessionResponse cancel(
            @PathVariable UUID eventId, @PathVariable UUID sessionId, HttpServletRequest request) {
        Session cancelled = sessionService.cancelSession(
                eventId, sessionId, currentUser.resolve(request), isAdmin(request));
        return SessionResponse.from(cancelled);
    }

    private boolean isAdmin(HttpServletRequest request) {
        return currentUser.resolveRoles(request).contains("ADMIN");
    }
}
