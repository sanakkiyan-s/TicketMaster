package com.ticketmaster.inventory.seat;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-002: one row per seat, mutated in place — never one row per hold
 * attempt. Concurrent holds on the same seat serialize on this row's lock
 * (SeatRepository's {@code SELECT ... FOR UPDATE}), which is the actual
 * correctness mechanism; the migration's partial unique index is the
 * stated backstop, not the primary guarantee.
 */
@Entity
@Table(name = "seats")
@Getter
public class Seat {

    @EmbeddedId
    private SeatId id;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "section_name", nullable = false)
    private String sectionName;

    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    @Column(name = "col_number", nullable = false)
    private int colNumber;

    @Column(name = "price_tier_id", nullable = false)
    private UUID priceTierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SeatStatus status;

    @Column(name = "held_by")
    private UUID heldBy;

    @Column(name = "held_until")
    private Instant heldUntil;

    protected Seat() {
        // JPA
    }

    public Seat(SeatId id, UUID sectionId, String sectionName, int rowNumber, int colNumber, UUID priceTierId) {
        this.id = id;
        this.sectionId = sectionId;
        this.sectionName = sectionName;
        this.rowNumber = rowNumber;
        this.colNumber = colNumber;
        this.priceTierId = priceTierId;
        this.status = SeatStatus.AVAILABLE;
    }

    /**
     * ADR-002's hold step. Caller must have already taken the row lock
     * (repository's {@code SELECT ... FOR UPDATE}) and checked
     * {@link #isAvailable()} — this method does not re-check, it only
     * applies the transition.
     */
    public void hold(UUID holderId, Instant heldUntil) {
        this.status = SeatStatus.HELD;
        this.heldBy = holderId;
        this.heldUntil = heldUntil;
    }

    /** ADR-002's payment-submission extension checkpoint — one possible extra write beyond creation. */
    public void extendHold(Instant newHeldUntil) {
        this.heldUntil = newHeldUntil;
    }

    /** ADR-002's confirm step — HELD (by callerId, not expired) -> PURCHASED. */
    public void confirm() {
        this.status = SeatStatus.PURCHASED;
        this.heldUntil = null;
    }

    /** Expiry sweep / lazy-on-read release — HELD -> AVAILABLE, no EXPIRED value ever stored. */
    public void releaseExpiredHold() {
        this.status = SeatStatus.AVAILABLE;
        this.heldBy = null;
        this.heldUntil = null;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public boolean isHeldBy(UUID callerId) {
        return status == SeatStatus.HELD && callerId != null && callerId.equals(heldBy);
    }

    public boolean isExpired(Instant now) {
        return status == SeatStatus.HELD && heldUntil != null && heldUntil.isBefore(now);
    }
}
