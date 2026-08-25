package com.ticketmaster.event.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * An organizer-managed event. venue_id is a plain UUID reference, never a
 * JPA relationship into venue-service — each service owns its own
 * database, no cross-service foreign keys (ADR-001). organizer_id is
 * ADR-030's ownership anchor: every mutating/get-single endpoint checks
 * this against the caller's identity.
 *
 * Lombok @Getter only — see auth-service's User for why @Data/@Setter/
 * @ToString/@EqualsAndHashCode are not permitted on JPA entities
 * (ADR-037 rule 6). Mutation happens through named intent-revealing
 * methods (update/cancel), not generated setters.
 */
@Entity
@Table(name = "events")
@Getter
public class Event {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private UUID organizerId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "category")
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EventStatus status;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Event() {
        // JPA
    }

    public Event(UUID id, UUID venueId, UUID organizerId, String title, String description,
                 String category, String region, Instant now) {
        this.id = id;
        this.venueId = venueId;
        this.organizerId = organizerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.status = EventStatus.DRAFT;
        this.region = region;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(UUID venueId, String title, String description, String category, Instant now) {
        this.venueId = venueId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.updatedAt = now;
    }

    /** Soft-cancel, never a hard delete — see EventService.cancelEvent. */
    public void cancel(Instant now) {
        this.status = EventStatus.CANCELLED;
        this.updatedAt = now;
    }

    /** DRAFT -> PUBLISHED. Guarded in EventService.publishEvent, not here — see its javadoc. */
    public void publish(Instant now) {
        this.status = EventStatus.PUBLISHED;
        this.updatedAt = now;
    }
}
