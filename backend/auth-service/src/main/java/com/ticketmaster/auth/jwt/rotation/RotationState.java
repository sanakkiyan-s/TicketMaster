package com.ticketmaster.auth.jwt.rotation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/**
 * The rotation state machine's single row (ADR-012). No {@code @Setter}
 * (ADR-037 rule 6): every mutation goes through {@link #transitionTo}, which
 * keeps `phaseEnteredAt` and `updatedAt` from ever drifting apart from the
 * phase they describe.
 */
@Entity
@Table(name = "rotation_state")
@Getter
class RotationState {

    /** This service runs one rotation at a time - a single fixed row. */
    static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private short id = SINGLETON_ID;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private RotationPhase phase;

    /** The key being phased out. Null only while IDLE with no history yet. */
    @Column(name = "old_kid")
    private String oldKid;

    /** The key being phased in. Null outside PUBLISH/CUTOVER/DRAIN. */
    @Column(name = "new_kid")
    private String newKid;

    @Column(name = "phase_entered_at", nullable = false)
    private Instant phaseEnteredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RotationState() {
        // JPA
    }

    private RotationState(RotationPhase phase, String oldKid, String newKid, Instant now) {
        this.id = SINGLETON_ID;
        this.phase = phase;
        this.oldKid = oldKid;
        this.newKid = newKid;
        this.phaseEnteredAt = now;
        this.updatedAt = now;
    }

    static RotationState idle(Instant now) {
        return new RotationState(RotationPhase.IDLE, null, null, now);
    }

    void transitionTo(RotationPhase phase, String oldKid, String newKid, Instant now) {
        this.phase = phase;
        this.oldKid = oldKid;
        this.newKid = newKid;
        this.phaseEnteredAt = now;
        this.updatedAt = now;
    }
}
