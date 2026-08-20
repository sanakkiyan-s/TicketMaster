package com.ticketmaster.event.event;

import com.ticketmaster.event.shared.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * ADR-034 versions the REST edge at /api/v1. /organizer/* is the coarse
 * role gate api-gateway enforces (ADR-030 layer 1, not this service's
 * job); every method here additionally enforces layer 2, the fine-grained
 * organizer_id ownership check, since the gateway holds no per-resource
 * ownership data.
 *
 * No springdoc @Operation/@Tag annotations here — event-service's
 * build.gradle.kts does not declare springdoc-openapi-starter-webmvc-ui
 * (out of scope for this task to add; application.yml's springdoc block
 * is currently inert without it).
 */
@RestController
@RequestMapping("/api/v1/organizer/events")
public class EventController {

    private final EventService eventService;
    private final CurrentUserResolver currentUser;

    public EventController(EventService eventService, CurrentUserResolver currentUser) {
        this.eventService = eventService;
        this.currentUser = currentUser;
    }

    /** Create an event, owned by the caller. */
    @PostMapping
    public ResponseEntity<EventResponse> create(
            @Valid @RequestBody CreateEventRequest body, HttpServletRequest request) {
        Event created = eventService.createEvent(
                currentUser.resolve(request), body.venueId(), body.title(), body.description(),
                body.category(), body.region());

        return ResponseEntity
                .created(URI.create("/api/v1/organizer/events/" + created.getId()))
                .body(EventResponse.from(created));
    }

    /** List the caller's own events. */
    @GetMapping
    public List<EventResponse> listMine(HttpServletRequest request) {
        return eventService.listMyEvents(currentUser.resolve(request)).stream()
                .map(EventResponse::from)
                .toList();
    }

    /**
     * Returns 404 for both an unknown id and one belonging to another
     * organizer — see EventNotFoundException's javadoc. ADMIN bypasses
     * the ownership check (ADR-030).
     */
    @GetMapping("/{id}")
    public EventResponse get(@PathVariable UUID id, HttpServletRequest request) {
        return EventResponse.from(eventService.getEvent(id, currentUser.resolve(request), isAdmin(request)));
    }

    /** Update an event's mutable fields. Same ownership check as GET. */
    @PutMapping("/{id}")
    public EventResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateEventRequest body, HttpServletRequest request) {
        Event updated = eventService.updateEvent(
                id, currentUser.resolve(request), isAdmin(request),
                body.venueId(), body.title(), body.description(), body.category());
        return EventResponse.from(updated);
    }

    /** Soft-cancel — sets status=CANCELLED, never a hard delete. Same ownership check as GET. */
    @PostMapping("/{id}/cancel")
    public EventResponse cancel(@PathVariable UUID id, HttpServletRequest request) {
        return EventResponse.from(eventService.cancelEvent(id, currentUser.resolve(request), isAdmin(request)));
    }

    private boolean isAdmin(HttpServletRequest request) {
        return currentUser.resolveRoles(request).contains("ADMIN");
    }
}
