-- ADR-040: failed_login_attempts is superseded by LoginAttemptLimiter's
-- Redis slow window (24h TTL, self-decaying) - the DB column never
-- decayed, so an infrequent mistyper could accumulate toward a lock
-- across unrelated attempts weeks apart. locked_until stays: it is
-- still the persistent backstop LoginAttemptLimiter's Redis windows
-- write into once tripped.
--
-- Single-phase drop, not full five-phase expand/contract (ADR-027):
-- this table carries no live traffic yet (pre-launch), which is exactly
-- ADR-027's own "Revisit When" carve-out for relaxing that discipline -
-- there is no rolling-deploy window where an already-running old app
-- version could still expect this column to exist.
ALTER TABLE users
    DROP COLUMN failed_login_attempts;
