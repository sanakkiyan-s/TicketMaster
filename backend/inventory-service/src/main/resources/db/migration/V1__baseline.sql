-- ADR-002: seats table is the correctness authority. One row per seat,
-- mutated in place via SELECT ... FOR UPDATE + conditional UPDATE - never
-- one row per hold attempt - so the row lock itself already serializes
-- concurrent holds on the same seat; the partial unique index below is
-- the stated backstop against a locking-code defect, not the primary
-- mechanism.
--
-- event_id is part of every key (ADR-002's Citus co-location amendment):
-- must be present before this table exists, since retrofitting it after
-- the fact is a table rebuild, not a migration.
CREATE TABLE price_tiers (
    id           UUID PRIMARY KEY,
    session_id   UUID NOT NULL,
    label        TEXT NOT NULL,
    price_cents  INTEGER NOT NULL CHECK (price_cents >= 0)
);

CREATE INDEX idx_price_tiers_session ON price_tiers (session_id);

CREATE TABLE seats (
    event_id       UUID NOT NULL,
    session_id     UUID NOT NULL,
    seat_id        UUID NOT NULL,
    section_id     UUID NOT NULL,
    section_name   TEXT NOT NULL,
    row_number     INTEGER NOT NULL,
    col_number     INTEGER NOT NULL,
    price_tier_id  UUID NOT NULL REFERENCES price_tiers (id),
    status         TEXT NOT NULL DEFAULT 'AVAILABLE'
                   CHECK (status IN ('AVAILABLE', 'HELD', 'PURCHASED')),
    held_by        UUID,
    held_until     TIMESTAMPTZ,
    PRIMARY KEY (event_id, session_id, seat_id)
);

-- ADR-002's stated correctness backstop, amended to include event_id so
-- the constraint stays global once Citus distribution (by event_id,
-- ADR-005) is actually turned on - a constraint without the distribution
-- column only enforces per-shard, silently.
CREATE UNIQUE INDEX uq_seats_locked ON seats (event_id, session_id, seat_id)
    WHERE status IN ('HELD', 'PURCHASED');

CREATE INDEX idx_seats_session ON seats (session_id);

-- Expiry sweep's own access path: WHERE status = 'HELD' AND held_until < now.
CREATE INDEX idx_seats_held_until ON seats (held_until) WHERE status = 'HELD';
