package com.ticketmaster.venue.seat;

import com.ticketmaster.venue.section.SectionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * No outbox events published here — venue.* through VenueService covers
 * the parent, same as SectionService not publishing anything of its own.
 * Bulk seat import is deliberately not built here (task brief, YAGNI) — a
 * single-seat create endpoint is enough for this slice.
 */
@Service
public class SeatService {

    private final SeatRepository seats;
    private final SectionService sectionService;
    private final Clock clock;

    SeatService(SeatRepository seats, SectionService sectionService, Clock clock) {
        this.seats = seats;
        this.sectionService = sectionService;
        this.clock = clock;
    }

    @Transactional
    Seat createSeat(UUID venueId, UUID sectionId, UUID callerId, boolean isAdmin,
                     String rowLabel, String seatNumber, Double xCoord, Double yCoord) {
        // Throws VenueNotFoundException/SectionNotFoundException (404, not
        // 403) if any link in the venue -> section chain doesn't exist or
        // isn't the caller's — see SectionService.findOwned.
        sectionService.findOwned(venueId, sectionId, callerId, isAdmin);
        Instant now = Instant.now(clock);
        return seats.saveAndFlush(new Seat(UUID.randomUUID(), sectionId, rowLabel, seatNumber, xCoord, yCoord, now));
    }

    List<Seat> listSeats(UUID venueId, UUID sectionId, UUID callerId, boolean isAdmin) {
        sectionService.findOwned(venueId, sectionId, callerId, isAdmin);
        return seats.findBySectionId(sectionId);
    }
}
