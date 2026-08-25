-- Transactional outbox (ADR-007) - identical shape to auth-service's V4,
-- see that file for the full mechanism explanation. Producers here:
-- event.created / event.updated / event.cancelled (aggregate_id = event
-- id) and session.flagged_high_demand (aggregate_id = session id, ADR-021).
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
