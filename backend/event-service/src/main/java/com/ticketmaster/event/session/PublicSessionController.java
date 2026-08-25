package com.ticketmaster.event.session;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Genuinely public, unauthenticated read endpoint — same carve-out as
 * search-service's EventSearchController (no CurrentUserResolver, no
 * ownership check). Lets the storefront list a published event's sessions
 * so a browse card has somewhere real to link to before the buyer reaches
 * the (already-protected) seat selection page.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/sessions")
public class PublicSessionController {

    private final SessionService sessionService;

    public PublicSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public List<SessionResponse> list(@PathVariable UUID eventId) {
        return sessionService.listSessionsForEvent(eventId).stream()
                .map(SessionResponse::from)
                .toList();
    }
}
