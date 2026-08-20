package com.ticketmaster.event.event;

import com.ticketmaster.event.shared.outbox.OutboxPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;
    private final OutboxPublisher outbox;
    private final Clock clock;

    EventService(EventRepository events, OutboxPublisher outbox, Clock clock) {
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    List<Event> listMyEvents(UUID organizerId) {
        return events.findByOrganizerId(organizerId);
    }

    /**
     * ADR-030: an ADMIN caller bypasses the ownership check entirely
     * (admin acts platform-wide by design); every other caller only ever
     * sees their own events, and a mismatched id answers exactly like an
     * unknown one — see EventNotFoundException's javadoc.
     */
    Event getEvent(UUID id, UUID callerId, boolean isAdmin) {
        return findOwned(id, callerId, isAdmin);
    }

    @Transactional
    Event createEvent(UUID organizerId, UUID venueId, String title, String description,
                       String category, String region) {
        Instant now = Instant.now(clock);
        Event event = events.saveAndFlush(
                new Event(UUID.randomUUID(), venueId, organizerId, title, description, category, region, now));
        outbox.publish("event.created", event.getId().toString(), toPayload(event));
        return event;
    }

    @Transactional
    Event updateEvent(UUID id, UUID callerId, boolean isAdmin, UUID venueId, String title,
                       String description, String category) {
        Event event = findOwned(id, callerId, isAdmin);
        event.update(venueId, title, description, category, Instant.now(clock));
        outbox.publish("event.updated", event.getId().toString(), toPayload(event));
        return event;
    }

    @Transactional
    Event cancelEvent(UUID id, UUID callerId, boolean isAdmin) {
        Event event = findOwned(id, callerId, isAdmin);
        event.cancel(Instant.now(clock));
        outbox.publish("event.cancelled", event.getId().toString(), toPayload(event));
        return event;
    }

    /**
     * Public: SessionService (a different package) needs this exact
     * ownership check to authorize session mutations, since a session has
     * no organizer_id of its own — its ownership is its parent event's.
     */
    public Event findOwned(UUID id, UUID callerId, boolean isAdmin) {
        if (isAdmin) {
            return events.findById(id)
                    .orElseThrow(() -> new EventNotFoundException("event not found"));
        }
        return events.findByIdAndOrganizerId(id, callerId)
                .orElseThrow(() -> new EventNotFoundException("event not found or not owned by caller"));
    }

    private static Map<String, Object> toPayload(Event event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId().toString());
        payload.put("organizerId", event.getOrganizerId().toString());
        payload.put("venueId", event.getVenueId().toString());
        payload.put("title", event.getTitle());
        payload.put("status", event.getStatus().name());
        payload.put("region", event.getRegion());
        return payload;
    }
}
