-- venue-service baseline schema (ADR-036 Phase 2). Owns venues and their
-- seat maps (sections/seats). See second-brain/wiki/projects/venue-service.md.
--
-- Not yet Citus-distributed - same deferral as event-service's V1 (see that
-- file's header): create_distributed_table() has nothing to distribute
-- across on a single-node dev Postgres. This is a later migration, not a
-- later schema change, when it comes.
--
-- Timestamps are application-supplied, never DEFAULT now() - same
-- convention as every other service's migrations (ADR-002's amendment:
-- SQL now() is transaction-start time, skews under long transactions,
-- uncontrollable from a test).

CREATE TABLE venues (
    id             UUID        PRIMARY KEY,
    organizer_id   UUID        NOT NULL,  -- ADR-030's ownership anchor
    name           TEXT        NOT NULL,
    address        TEXT,
    city           TEXT,
    region         TEXT        NOT NULL,  -- ADR-016 data residency, same reasoning as event-service's events.region
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Organizer's own dashboard/CRUD lists "my venues" - the one query this
-- service will run constantly, same access pattern as event-service's
-- idx_events_organizer_id.
CREATE INDEX idx_venues_organizer_id ON venues (organizer_id);

CREATE TABLE sections (
    id          UUID        PRIMARY KEY,
    venue_id    UUID        NOT NULL REFERENCES venues (id),
    name        TEXT        NOT NULL,
    capacity    INTEGER     NOT NULL,  -- declared section capacity, used for aggregate reporting even before seats are enumerated
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_sections_venue_id ON sections (venue_id);

CREATE TABLE seats (
    id           UUID             PRIMARY KEY,
    section_id   UUID             NOT NULL REFERENCES sections (id),
    row_label    TEXT             NOT NULL,  -- e.g. "A", "B" - kept flat on seats rather than a separate rows table (YAGNI: a row has no metadata beyond its label)
    seat_number  TEXT             NOT NULL,  -- text not int, real venues do "12A" etc.
    x_coord      DOUBLE PRECISION,           -- map rendering; format still undecided (frontend.md's Open Questions), nullable so this isn't a blocker
    y_coord      DOUBLE PRECISION,
    created_at   TIMESTAMPTZ      NOT NULL,
    updated_at   TIMESTAMPTZ      NOT NULL,

    -- No duplicate seat identity within a section.
    UNIQUE (section_id, row_label, seat_number)
);

CREATE INDEX idx_seats_section_id ON seats (section_id);
