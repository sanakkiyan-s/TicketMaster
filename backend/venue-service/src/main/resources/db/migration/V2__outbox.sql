-- Transactional outbox (ADR-007) - identical shape to event-service's
-- V3__outbox.sql, see that file for the full mechanism explanation.
-- Producers here: venue.created / venue.updated (aggregate_id = venue id).
-- No venue.cancelled - venues aren't cancelled the way events are.
CREATE TABLE outbox (
    event_id     UUID        PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    traceparent  TEXT        NOT NULL,
    tracestate   TEXT,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_outbox_event_type ON outbox (event_type);
