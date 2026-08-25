package com.ticketmaster.venue.seat;

import com.ticketmaster.venue.shared.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Nested under its parent venue and section, since a seat's ownership
 * (ADR-030) is resolved through venue -> section -> organizer_id — every
 * method here re-checks the full chain via SeatService/SectionService/
 * VenueService before touching the seat.
 *
 * No update/delete endpoint — a bulk seat-import/edit flow is out of scope
 * for this slice (task brief, YAGNI); create-one-at-a-time plus list is
 * enough.
 *
 * No springdoc annotations — see VenueController's javadoc for why.
 */
@RestController
@RequestMapping("/api/v1/organizer/venues/{venueId}/sections/{sectionId}/seats")
public class SeatController {

    private final SeatService seatService;
    private final CurrentUserResolver currentUser;

    public SeatController(SeatService seatService, CurrentUserResolver currentUser) {
        this.seatService = seatService;
        this.currentUser = currentUser;
    }

    /** Create one seat under a section. 404s if any link in the venue -> section chain fails. */
    @PostMapping
    public ResponseEntity<SeatResponse> create(
            @PathVariable UUID venueId, @PathVariable UUID sectionId,
            @Valid @RequestBody CreateSeatRequest body, HttpServletRequest request) {
        Seat created = seatService.createSeat(
                venueId, sectionId, currentUser.resolve(request), isAdmin(request),
                body.rowLabel(), body.seatNumber(), body.xCoord(), body.yCoord());

        return ResponseEntity
                .created(URI.create(
                        "/api/v1/organizer/venues/" + venueId + "/sections/" + sectionId + "/seats/" + created.getId()))
                .body(SeatResponse.from(created));
    }

    /** List a section's seats. */
    @GetMapping
    public List<SeatResponse> list(
            @PathVariable UUID venueId, @PathVariable UUID sectionId, HttpServletRequest request) {
        return seatService.listSeats(venueId, sectionId, currentUser.resolve(request), isAdmin(request)).stream()
                .map(SeatResponse::from)
                .toList();
    }

    private boolean isAdmin(HttpServletRequest request) {
        return currentUser.resolveRoles(request).contains("ADMIN");
    }
}
