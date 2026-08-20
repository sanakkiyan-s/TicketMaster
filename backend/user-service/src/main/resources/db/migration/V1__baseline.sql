-- user-service baseline schema (ADR-001, ADR-037).
--
-- No foreign keys into auth-service's users table: ADR-001 gives each
-- service its own database, so user_id here is just a UUID value copied
-- from the JWT `sub` claim, never a JPA relationship auth-service's schema
-- could satisfy or break.
--
-- Timestamps are supplied by the application, never DEFAULT now() — see
-- auth-service's V1__baseline.sql for why (ADR-002 amendment: SQL now()
-- is transaction-start time, skews under long transactions, uncontrollable
-- from a test).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- One row per user, created lazily on first profile access (see
-- profile/ProfileService) rather than on registration — user-service has
-- no registration event to react to yet, and a profile logically exists
-- the moment an account does even if every field is still blank.
CREATE TABLE user_profiles (
    user_id       UUID PRIMARY KEY,
    display_name  TEXT,
    phone_number  TEXT,
    avatar_url    TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

-- Same lazily-created, userId-as-PK pattern as user_profiles.
CREATE TABLE user_preferences (
    user_id         UUID PRIMARY KEY,
    email_opt_in    BOOLEAN     NOT NULL DEFAULT TRUE,
    sms_opt_in      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- provider_token is an opaque reference into the payment provider (or,
-- once it exists, payment-service) — NEVER raw card data. brand/last4 are
-- display metadata only, safe to store because they don't reconstruct a
-- usable card number on their own.
CREATE TABLE saved_payment_methods (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    provider        TEXT        NOT NULL,
    provider_token  TEXT        NOT NULL,
    brand           TEXT,
    last4           TEXT,
    is_default      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_saved_payment_methods_user_id ON saved_payment_methods (user_id);
