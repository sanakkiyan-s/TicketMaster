package com.ticketmaster.venue.section;

import com.ticketmaster.venue.shared.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Nested under its parent venue, since a section's ownership (ADR-030) is
 * the parent venue's organizer_id — every method here re-checks that via
 * SectionService/VenueService before touching the section.
 *
 * No springdoc annotations — see VenueController's javadoc for why.
 */
@RestController
@RequestMapping("/api/v1/organizer/venues/{venueId}/sections")
public class SectionController {

    private final SectionService sectionService;
    private final CurrentUserResolver currentUser;

    public SectionController(SectionService sectionService, CurrentUserResolver currentUser) {
        this.sectionService = sectionService;
        this.currentUser = currentUser;
    }

    /** Create a section under a venue. 404s if the venue doesn't exist or isn't the caller's. */
    @PostMapping
    public ResponseEntity<SectionResponse> create(
            @PathVariable UUID venueId, @Valid @RequestBody CreateSectionRequest body, HttpServletRequest request) {
        Section created = sectionService.createSection(
                venueId, currentUser.resolve(request), isAdmin(request), body.name(), body.capacity());

        return ResponseEntity
                .created(URI.create("/api/v1/organizer/venues/" + venueId + "/sections/" + created.getId()))
                .body(SectionResponse.from(created));
    }

    /** List a venue's sections. */
    @GetMapping
    public List<SectionResponse> list(@PathVariable UUID venueId, HttpServletRequest request) {
        return sectionService.listSections(venueId, currentUser.resolve(request), isAdmin(request)).stream()
                .map(SectionResponse::from)
                .toList();
    }

    /** Update a section's name/capacity. */
    @PutMapping("/{sectionId}")
    public SectionResponse update(
            @PathVariable UUID venueId, @PathVariable UUID sectionId,
            @Valid @RequestBody UpdateSectionRequest body, HttpServletRequest request) {
        Section updated = sectionService.updateSection(
                venueId, sectionId, currentUser.resolve(request), isAdmin(request), body.name(), body.capacity());
        return SectionResponse.from(updated);
    }

    private boolean isAdmin(HttpServletRequest request) {
        return currentUser.resolveRoles(request).contains("ADMIN");
    }
}
