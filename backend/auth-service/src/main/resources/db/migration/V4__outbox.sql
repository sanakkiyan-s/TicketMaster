-- Transactional outbox (ADR-007). Debezium (infra, not this service) tails
-- Postgres's WAL and publishes each committed row to Kafka via the outbox
-- event-router SMT; auth-service only ever writes to this table, never to
-- Kafka directly. The first (and so far only) producer is ADR-012's
-- revocation feature (event_type = 'auth.revocation'), but the table
-- itself is generic per ADR-007, not revocation-specific.
--
-- Deviates from ADR-007's literal DDL (`created_at TIMESTAMPTZ DEFAULT
-- now()`) to match this repo's established convention of application-
-- supplied timestamps, same as V1/V2/V3 - flagged explicitly per project
-- policy (second-brain/CLAUDE.md) rather than silently picking one.
CREATE TABLE outbox (
    event_id     UUID        PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    traceparent  TEXT        NOT NULL,
    tracestate   TEXT,
    created_at   TIMESTAMPTZ NOT NULL
);

-- A consumer (Debezium in production, a direct query in this service's own
-- tests) filters by event_type; nothing queries by aggregate_id yet, so no
-- index on it until there is a real access pattern to justify one.
CREATE INDEX idx_outbox_event_type ON outbox (event_type);
