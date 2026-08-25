package com.ticketmaster.venue.section;

import com.ticketmaster.venue.venue.VenueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * No outbox events published here — sections aren't independently
 * broadcast (task brief); venue.* through VenueService covers the parent,
 * same as event-service's SessionService not publishing session.* events
 * in the organizer-CRUD slice.
 */
@Service
public class SectionService {

    private final SectionRepository sections;
    private final VenueService venueService;
    private final Clock clock;

    SectionService(SectionRepository sections, VenueService venueService, Clock clock) {
        this.sections = sections;
        this.venueService = venueService;
        this.clock = clock;
    }

    @Transactional
    Section createSection(UUID venueId, UUID callerId, boolean isAdmin, String name, int capacity) {
        // Throws VenueNotFoundException (404, not 403) if the venue doesn't
        // exist or isn't the caller's — see VenueService.findOwned.
        venueService.findOwned(venueId, callerId, isAdmin);
        Instant now = Instant.now(clock);
        return sections.saveAndFlush(new Section(UUID.randomUUID(), venueId, name, capacity, now));
    }

    @Transactional
    Section updateSection(UUID venueId, UUID sectionId, UUID callerId, boolean isAdmin, String name, int capacity) {
        venueService.findOwned(venueId, callerId, isAdmin);
        Section section = findInVenue(venueId, sectionId);
        section.update(name, capacity, Instant.now(clock));
        return section;
    }

    List<Section> listSections(UUID venueId, UUID callerId, boolean isAdmin) {
        venueService.findOwned(venueId, callerId, isAdmin);
        return sections.findByVenueId(venueId);
    }

    /**
     * Public: SeatService (a different package) needs this exact
     * ownership chain check — venue ownership plus "this section actually
     * belongs to this venue" — to authorize seat mutations one level
     * further down.
     */
    public Section findOwned(UUID venueId, UUID sectionId, UUID callerId, boolean isAdmin) {
        venueService.findOwned(venueId, callerId, isAdmin);
        return findInVenue(venueId, sectionId);
    }

    private Section findInVenue(UUID venueId, UUID sectionId) {
        return sections.findByIdAndVenueId(sectionId, venueId)
                .orElseThrow(() -> new SectionNotFoundException("section not found"));
    }
}
