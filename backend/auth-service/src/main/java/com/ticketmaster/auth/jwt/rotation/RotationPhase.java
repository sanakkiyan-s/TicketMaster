package com.ticketmaster.auth.jwt.rotation;

/**
 * ADR-012's four-phase overlap, modelled as a state machine.
 *
 * Phase 0 (steady state, no rotation running) is IDLE rather than a fifth
 * named phase - there is nothing to advance when no rotation is in
 * progress. Phase 4 RETIRE is not a state a row ever sits in: leaving
 * DRAIN removes the old key from Vault and returns the row straight to
 * IDLE in one step, because nothing is ever "waiting" mid-retirement.
 */
enum RotationPhase {

    /** No rotation in progress. Steady state. */
    IDLE,

    /** K2 generated and published; K1 still signs. Must last >= 15 min. */
    PUBLISH,

    /** K2 now signs. Instant - advances to DRAIN on the very next tick. */
    CUTOVER,

    /** Waiting for outstanding K1-signed tokens to expire. Must last >= 30 min. */
    DRAIN
}
