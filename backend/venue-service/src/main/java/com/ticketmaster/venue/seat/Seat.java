package com.ticketmaster.venue.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A single seat under a parent section. section_id is a plain UUID column,
 * not a JPA @ManyToOne — same explicit-application-control reasoning as
 * Section.venueId. A seat has no organizer_id of its own; ownership for
 * ADR-030's check is resolved through the chain seat -> section -> venue
 * (see SectionService.findOwned, called from SeatService before every
 * mutation).
 *
 * rowLabel/seatNumber are TEXT, not INTEGER — real venues use labels like
 * "12A" (V1__baseline.sql's header note). x/y coordinates are nullable:
 * the interactive seat-map data format is still undecided
 * (wiki/projects/venue-service.md's Open Questions), so this is deliberately
 * not a blocker.
 *
 * ADR-002's seat_id (referenced by inventory-service later) is this
 * entity's plain UUID `id` — no special ID format required.
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "seats")
@Getter
public class Seat {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "section_id", nullable = false, updatable = false)
    private UUID sectionId;

    @Column(name = "row_label", nullable = false, updatable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private String seatNumber;

    @Column(name = "x_coord")
    private Double xCoord;

    @Column(name = "y_coord")
    private Double yCoord;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Seat() {
        // JPA
    }

    public Seat(UUID id, UUID sectionId, String rowLabel, String seatNumber, Double xCoord, Double yCoord, Instant now) {
        this.id = id;
        this.sectionId = sectionId;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.xCoord = xCoord;
        this.yCoord = yCoord;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
