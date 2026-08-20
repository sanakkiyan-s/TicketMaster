-- event-service baseline schema (ADR-036 Phase 2). Owns events, sessions,
-- artists, and organizer CRUD. See second-brain/wiki/projects/event-service.md.
--
-- Not yet Citus-distributed by event_id - same deferral as auth-service's
-- V1 (see that file's header): create_distributed_table() has nothing to
-- distribute across on a single-node dev Postgres. event_id is carried on
-- every child table now so distribution is a later migration, not a
-- later schema change.
--
-- Timestamps are application-supplied, never DEFAULT now() - same
-- convention as every other service's migrations (ADR-002's amendment:
-- SQL now() is transaction-start time, skews under long transactions,
-- uncontrollable from a test).

CREATE TABLE events (
    id             UUID        PRIMARY KEY,
    venue_id       UUID        NOT NULL,  -- venue-service, reference only, no FK across services
    organizer_id   UUID        NOT NULL,  -- ADR-030's ownership anchor
    title          TEXT        NOT NULL,
    description    TEXT,
    category       TEXT,
    status         TEXT        NOT NULL,  -- DRAFT | PUBLISHED | CANCELLED
    region         TEXT        NOT NULL,  -- ADR-016 data residency / future ADR-005 region routing
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Organizer's own dashboard/CRUD lists "my events" - the one query this
-- service will run constantly.
CREATE INDEX idx_events_organizer_id ON events (organizer_id);

CREATE TABLE sessions (
    id                   UUID        PRIMARY KEY,
    event_id             UUID        NOT NULL REFERENCES events (id),
    starts_at            TIMESTAMPTZ NOT NULL,
    ends_at              TIMESTAMPTZ,
    status               TEXT        NOT NULL,  -- SCHEDULED | ON_SALE | CANCELLED | COMPLETED
    on_sale_at           TIMESTAMPTZ,
    -- ADR-021/ADR-004: set by a periodic job comparing Notify Me signups
    -- to venue-service's capacity, consumed by Redis's reactive
    -- autoscale trigger and notification-service's mass broadcast.
    high_demand          BOOLEAN     NOT NULL DEFAULT false,
    high_demand_set_at   TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_sessions_event_id ON sessions (event_id);

CREATE TABLE artists (
    id          UUID        PRIMARY KEY,
    name        TEXT        NOT NULL,
    bio         TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- Many-to-many: a session can have multiple performers (headliner +
-- support), an artist plays many sessions across their touring history.
CREATE TABLE session_artists (
    session_id  UUID NOT NULL REFERENCES sessions (id),
    artist_id   UUID NOT NULL REFERENCES artists (id),
    role        TEXT NOT NULL,  -- HEADLINER | SUPPORT

    PRIMARY KEY (session_id, artist_id)
);
