-- auth-service baseline schema (ADR-012 token lifecycle, ADR-027 Flyway).
--
-- Not yet Citus-distributed. ADR-018 shards auth-service by user_id in
-- production; distribution is applied in a later migration once a real
-- multi-node cluster exists, since create_distributed_table() has nothing
-- to distribute across on a single-node dev Postgres.

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- CITEXT, not TEXT: "User@x.com" and "user@x.com" are the same account.
-- Enforcing that in the column type makes a case-duplicate account
-- unrepresentable, rather than relying on every caller to lower-case.
--
-- Timestamps are supplied by the application, never DEFAULT now() —
-- ADR-002's amendment: SQL now() is transaction-start time, which skews
-- under long transactions and cannot be controlled from a test.
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          CITEXT      NOT NULL UNIQUE,
    password_hash  TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Roles are rows, not a column on users: ADR-030 needs one user to hold
-- several (USER + ORGANIZER), and the JWT `roles` claim is a list.
CREATE TABLE user_roles (
    user_id  UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role     TEXT NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- SHA-256 of the opaque 256-bit token, never the token itself. A
    -- database leak must not hand an attacker usable refresh tokens.
    token_hash  TEXT        NOT NULL UNIQUE,

    -- ADR-012 reuse detection: every rotation stays in one family. A
    -- token presented twice means theft, and the whole family is killed.
    family_id   UUID        NOT NULL,
    session_id  UUID        NOT NULL,

    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,

    -- Set when this token is exchanged. A second presentation of an
    -- already-used token is the theft signal, so used rows are retained
    -- rather than deleted.
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
