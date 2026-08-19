-- Four-phase key rotation state machine (ADR-012). Single-row table: this
-- service runs one rotation at a time, so the row's presence/phase IS the
-- state machine rather than a queue of rotation "jobs". The CHECK pins the
-- id so a second row (a second concurrent rotation) is unrepresentable
-- rather than merely discouraged.
--
-- Phase 0 (steady state) is represented as phase = 'IDLE', not a distinct
-- row absence - the scheduler always has exactly one row to read.
-- Phase 4 RETIRE is folded into the IDLE transition (old_kid dropped,
-- new_kid promoted) rather than persisted as its own phase, because
-- nothing is ever "waiting" in RETIRE: the key removal and the return to
-- steady state happen in the same step.
--
-- Timestamps are supplied by the application, never DEFAULT now() - the
-- same convention V1/V2 already use (ADR-002's amendment: SQL now() is
-- transaction-start time, which skews under long transactions and cannot
-- be controlled from a test).
CREATE TABLE rotation_state (
    id               SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    phase            TEXT        NOT NULL,
    old_kid          TEXT,
    new_kid          TEXT,
    phase_entered_at TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);
