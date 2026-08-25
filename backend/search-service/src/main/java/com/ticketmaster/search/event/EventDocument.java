package com.ticketmaster.search.event;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * The denormalized, non-PII search projection of an event — fields match
 * exactly what event-service's outbox actually publishes today
 * (EventService.toPayload: eventId, organizerId, venueId, title, status,
 * region). description/category live in event-service's Postgres row but
 * are NOT in the outbox payload, so they cannot be indexed here without
 * event-service publishing them first — see search-service.md's Gap
 * section, not guessed at by adding fields nothing ever populates.
 *
 * Lombok @Getter only, all fields final — same immutable-entity
 * convention as event-service's Event.java. No setters: a status change
 * (event.cancelled) goes through {@link #withStatus(String)}, which
 * returns a new copy rather than mutating in place.
 */
@Document(indexName = "events")
@Getter
public class EventDocument {

    @Id
    private final String eventId;

    @Field(type = FieldType.Keyword)
    private final String organizerId;

    @Field(type = FieldType.Keyword)
    private final String venueId;

    // Text-analyzed so GET /api/v1/events?q= matches on relevance, not
    // exact string equality — the one field this service actually exists
    // to search.
    @Field(type = FieldType.Text)
    private final String title;

    @Field(type = FieldType.Keyword)
    private final String status;

    @Field(type = FieldType.Keyword)
    private final String region;

    public EventDocument(String eventId, String organizerId, String venueId, String title,
                          String status, String region) {
        this.eventId = eventId;
        this.organizerId = organizerId;
        this.venueId = venueId;
        this.title = title;
        this.status = status;
        this.region = region;
    }

    /**
     * Returns a copy with only status replaced. event.cancelled's outbox
     * payload happens to carry every field today (EventService.toPayload
     * publishes the same shape on every call), but this stays a targeted
     * merge — not a blind re-upsert — so a future trim of the cancel
     * payload down to just eventId+status can't silently blank out the
     * other indexed fields of a document already in the index.
     */
    public EventDocument withStatus(String newStatus) {
        return new EventDocument(eventId, organizerId, venueId, title, newStatus, region);
    }
}
