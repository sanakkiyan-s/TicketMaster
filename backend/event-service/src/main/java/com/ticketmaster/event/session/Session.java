package com.ticketmaster.event.session;

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
 * A single show/session under a parent event. event_id is a plain UUID
 * column, not a JPA @ManyToOne — this repo prefers explicit
 * application-level control over Hibernate-managed relationships (see
 * task brief's guidance and Event's venue_id for the same reasoning). A
 * session has no organizer_id of its own; ownership for ADR-030's check is
 * resolved via the parent event (see EventService.findOwned, called from
 * SessionService before every mutation).
 *
 * high_demand/high_demand_set_at exist in the schema for
 * ADR-021/ADR-004's periodic high-demand job — not written by anything in
 * this organizer-CRUD slice, only read/mapped through.
 *
 * Lombok @Getter only — ADR-037 rule 6.
 */
@Entity
@Table(name = "sessions")
@Getter
public class Session {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SessionStatus status;

    @Column(name = "on_sale_at")
    private Instant onSaleAt;

    @Column(name = "high_demand", nullable = false)
    private boolean highDemand;

    @Column(name = "high_demand_set_at")
    private Instant highDemandSetAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Session() {
        // JPA
    }

    public Session(UUID id, UUID eventId, Instant startsAt, Instant endsAt, Instant onSaleAt, Instant now) {
        this.id = id;
        this.eventId = eventId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = SessionStatus.SCHEDULED;
        this.onSaleAt = onSaleAt;
        this.highDemand = false;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(Instant startsAt, Instant endsAt, Instant onSaleAt, Instant now) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.onSaleAt = onSaleAt;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = SessionStatus.CANCELLED;
        this.updatedAt = now;
    }
}
