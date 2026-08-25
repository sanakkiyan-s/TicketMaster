-- ADR-021: "Notify Me" signup capture, per session. Schema is ADR-021's
-- own DDL verbatim, including its amendment (event_id as the future
-- Citus shard key, contact_email as ciphertext never plaintext - see
-- that ADR for the residency/GDPR reasoning).

CREATE TABLE session_notify_me (
    id                       UUID        NOT NULL,
    event_id                 UUID        NOT NULL REFERENCES events (id),
    session_id               UUID        NOT NULL REFERENCES sessions (id),
    user_id                  UUID,               -- nullable, anonymous OK
    contact_email_ciphertext BYTEA,              -- required if user_id NULL, NEVER plaintext
    fcm_topic_subscribed     BOOLEAN     NOT NULL DEFAULT false,
    created_at               TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (event_id, id)
);

-- A table-level UNIQUE constraint's column list only accepts plain column
-- names, never an expression like COALESCE(...) - Postgres rejects that
-- with a syntax error at parse time. A unique index expresses the same
-- constraint (dedupe by signup identity, whichever of user_id/
-- contact_email_ciphertext is populated) since Postgres unique indexes,
-- unlike unique constraints, do accept expressions.
CREATE UNIQUE INDEX idx_session_notify_me_dedupe
    ON session_notify_me (event_id, session_id, COALESCE(user_id::text, contact_email_ciphertext::text));

-- The high-demand job's access pattern (COUNT(*) WHERE session_id = X).
CREATE INDEX idx_session_notify_me_session_id ON session_notify_me (session_id);
