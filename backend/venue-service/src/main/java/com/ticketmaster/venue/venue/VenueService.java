package com.ticketmaster.venue.venue;

import com.ticketmaster.venue.shared.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VenueService {

    private final VenueRepository venues;
    private final OutboxPublisher outbox;
    private final Clock clock;

    VenueService(VenueRepository venues, OutboxPublisher outbox, Clock clock) {
        this.venues = venues;
        this.outbox = outbox;
        this.clock = clock;
    }

    List<Venue> listMyVenues(UUID organizerId) {
        return venues.findByOrganizerId(organizerId);
    }

    /**
     * ADR-030: an ADMIN caller bypasses the ownership check entirely
     * (admin acts platform-wide by design); every other caller only ever
     * sees their own venues, and a mismatched id answers exactly like an
     * unknown one — see VenueNotFoundException's javadoc.
     */
    Venue getVenue(UUID id, UUID callerId, boolean isAdmin) {
        return findOwned(id, callerId, isAdmin);
    }

    @Transactional
    Venue createVenue(UUID organizerId, String name, String address, String city, String region) {
        Instant now = Instant.now(clock);
        Venue venue = venues.saveAndFlush(new Venue(UUID.randomUUID(), organizerId, name, address, city, region, now));
        outbox.publish("venue.created", venue.getId().toString(), toPayload(venue));
        return venue;
    }

    @Transactional
    Venue updateVenue(UUID id, UUID callerId, boolean isAdmin, String name, String address, String city) {
        Venue venue = findOwned(id, callerId, isAdmin);
        venue.update(name, address, city, Instant.now(clock));
        outbox.publish("venue.updated", venue.getId().toString(), toPayload(venue));
        return venue;
    }

    /**
     * Public: SectionService (a different package) needs this exact
     * ownership check to authorize section mutations, since a section has
     * no organizer_id of its own — its ownership is its parent venue's.
     * Same pattern as event-service's EventService.findOwned, consumed by
     * SessionService.
     */
    public Venue findOwned(UUID id, UUID callerId, boolean isAdmin) {
        if (isAdmin) {
            return venues.findById(id)
                    .orElseThrow(() -> new VenueNotFoundException("venue not found"));
        }
        return venues.findByIdAndOrganizerId(id, callerId)
                .orElseThrow(() -> new VenueNotFoundException("venue not found or not owned by caller"));
    }

    private static Map<String, Object> toPayload(Venue venue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", venue.getId().toString());
        payload.put("organizerId", venue.getOrganizerId().toString());
        payload.put("name", venue.getName());
        payload.put("region", venue.getRegion());
        return payload;
    }
}
