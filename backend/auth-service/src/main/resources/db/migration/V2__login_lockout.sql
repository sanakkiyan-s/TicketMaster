-- Account-level lockout, the slow-attacker backstop behind the Redis
-- per-username limiter in LoginAttemptLimiter. Redis catches a fast burst
-- (5 failures/min); this catches an attacker who paces guesses slower than
-- that window to avoid it entirely - a persistent counter that survives
-- across many separate windows, which Redis's per-window key deliberately
-- does not.
ALTER TABLE users
    ADD COLUMN failed_login_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN locked_until           TIMESTAMPTZ;
