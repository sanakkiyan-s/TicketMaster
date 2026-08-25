package com.ticketmaster.venue.venue;

import com.ticketmaster.venue.shared.CurrentUserResolver;
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
 * No cancel/delete endpoint — venues aren't soft-cancelled the way events
 * are (task brief).
 *
 * No springdoc @Operation/@Tag annotations here — venue-service's
 * build.gradle.kts does not declare springdoc-openapi-starter-webmvc-ui,
 * same as event-service's EventController.
 */
@RestController
@RequestMapping("/api/v1/organizer/venues")
public class VenueController {

    private final VenueService venueService;
    private final CurrentUserResolver currentUser;

    public VenueController(VenueService venueService, CurrentUserResolver currentUser) {
        this.venueService = venueService;
        this.currentUser = currentUser;
    }

    /** Create a venue, owned by the caller. */
    @PostMapping
    public ResponseEntity<VenueResponse> create(
            @Valid @RequestBody CreateVenueRequest body, HttpServletRequest request) {
        Venue created = venueService.createVenue(
                currentUser.resolve(request), body.name(), body.address(), body.city(), body.region());

        return ResponseEntity
                .created(URI.create("/api/v1/organizer/venues/" + created.getId()))
                .body(VenueResponse.from(created));
    }

    /** List the caller's own venues. */
    @GetMapping
    public List<VenueResponse> listMine(HttpServletRequest request) {
        return venueService.listMyVenues(currentUser.resolve(request)).stream()
                .map(VenueResponse::from)
                .toList();
    }

    /**
     * Returns 404 for both an unknown id and one belonging to another
     * organizer — see VenueNotFoundException's javadoc. ADMIN bypasses
     * the ownership check (ADR-030).
     */
    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable UUID id, HttpServletRequest request) {
        return VenueResponse.from(venueService.getVenue(id, currentUser.resolve(request), isAdmin(request)));
    }

    /** Update a venue's mutable fields. Same ownership check as GET. */
    @PutMapping("/{id}")
    public VenueResponse update(
            @PathVariable UUID id, @Valid @RequestBody UpdateVenueRequest body, HttpServletRequest request) {
        Venue updated = venueService.updateVenue(
                id, currentUser.resolve(request), isAdmin(request), body.name(), body.address(), body.city());
        return VenueResponse.from(updated);
    }

    private boolean isAdmin(HttpServletRequest request) {
        return currentUser.resolveRoles(request).contains("ADMIN");
    }
}
