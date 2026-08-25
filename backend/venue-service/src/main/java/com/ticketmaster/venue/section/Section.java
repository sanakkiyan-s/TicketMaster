package com.ticketmaster.venue.section;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A seating section under a parent venue. venue_id is a plain UUID column,
 * not a JPA @ManyToOne — this repo prefers explicit application-level
 * control over Hibernate-managed relationships, same reasoning as
 * event-service's Session.eventId. A section has no organizer_id of its
 * own; ownership for ADR-030's check is resolved via the parent venue (see
 * VenueService.findOwned, called from SectionService before every
 * mutation).
 *
 * capacity is the declared section capacity, used for aggregate reporting
 * even before individual seats are enumerated — it is not derived from
 * counting `seats` rows.
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "sections")
@Getter
public class Section {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Section() {
        // JPA
    }

    public Section(UUID id, UUID venueId, String name, int capacity, Instant now) {
        this.id = id;
        this.venueId = venueId;
        this.name = name;
        this.capacity = capacity;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, int capacity, Instant now) {
        this.name = name;
        this.capacity = capacity;
        this.updatedAt = now;
    }
}
