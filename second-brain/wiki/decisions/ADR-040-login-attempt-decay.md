---
title: ADR-040 Login Attempt Decay (Two-Window Redis Limiter)
type: decision
sources: []
related: [[auth-service]], [[api-gateway]], [[ADR-012-jwt-lifecycle]], [[ADR-039-dual-tier-login-rate-limiting]], [[ADR-027-schema-migration-strategy]], [[ADR-004-redis-cluster-sharding]]
created: 2026-08-19
last-updated: 2026-08-19
---

Status: Accepted

# Context

[[ADR-039-dual-tier-login-rate-limiting]] settled the gateway-vs-auth-service
split for login rate limiting. This ADR is scoped entirely inside the
auth-service side of that split — the username-keyed layer — and does not
revisit the gateway's IP-keyed shield.

That layer, as originally built, combined two mechanisms:

- `LoginAttemptLimiter`: a single Redis window, 5 failures/minute, atomic
  `INCR`+`EXPIRE`.
- `User.failedLoginAttempts`/`lockedUntil` (`V2__login_lockout.sql`): a
  DB-persisted counter and lock, the backstop for an attacker pacing
  guesses just under the Redis window's rate to dodge it entirely.

The DB counter had a real bug: it only reset on a successful login. A user
who mistypes their password once on Monday, twice on Wednesday, and twice
on Friday accumulates toward the same `lockThreshold` as a genuine
brute-force attempt, even though the failures are spread across a week
with no attack pattern. `LoginService.authenticate` also wrote
`user.recordFailedLogin(...)` on every single wrong-password attempt
against a known, unlocked user — not just the attempt that crossed the
threshold — meaning one DB write (via JPA dirty-checking on commit) per
failed login, unconditionally.

# Requirements / Constraints

- Preserve every property [[ADR-039-dual-tier-login-rate-limiting]]
  already established for this layer: identical Redis counting whether
  the email belongs to a real account or not (enumeration safety), fail
  open on a Redis outage, a locked account fails through the exact same
  `InvalidCredentialsException` path as a wrong password (same exception,
  same BCrypt cost, no distinguishable response), and the Redis pre-check
  runs before any DB lookup or BCrypt call.
- Fix the decay bug: occasional, spaced-out mistakes must not accumulate
  toward a lock indefinitely.
- Reduce DB writes to only the moment a lock actually trips (and the
  moment it clears), not one per failed attempt.
- Any schema change follows [[ADR-027-schema-migration-strategy]] unless
  that ADR's own pre-launch carve-out applies.

# Options Considered

## A — Keep the DB counter, add a periodic decay job

A scheduled job that decrements or resets `failed_login_attempts` after
some elapsed time. Cons: reintroduces exactly the kind of background
sweep/scheduling complexity Redis TTLs give for free, and still leaves
every failed attempt writing to Postgres.

## B — Two independent Redis windows (fast + slow), DB write only on trip

A second Redis key per username with a much longer TTL (24h) and higher
limit (15), incremented in the same atomic script as the existing fast
window. `locked_until` stays as the sole DB-persisted state; the
now-redundant `failed_login_attempts` column is dropped.

Pros: both windows self-decay via TTL — no scheduled job, no manual
decrement logic. `LoginAttemptLimiter.recordFailure` can report back
whether *this* failure crossed either threshold, so `LoginService` writes
to the DB only on that one attempt, not every attempt. Reuses the same
atomic-script pattern (`login_attempt_windows.lua`) already established
for the single-window version and for the gateway's own rate limiting.

Cons: one more Redis key per active login-failure streak (bounded memory
either way — each key expires on its own TTL).

## Central Redis for the gateway's revocation blocklist, by analogy?

Raised during design but explicitly out of scope for this ADR — the
gateway's JWT revocation check (`RevocationStore`) runs on every
authenticated request through the gateway, the highest-QPS path in the
system, and stays local-in-memory-per-instance (materialized from Kafka)
specifically to avoid a per-request network hop and a new availability
dependency on that hot path. Login attempts are comparatively rare and
already inherently rate-limited, so `LoginAttemptLimiter`'s existing use
of the shared central Redis (ADR-004) is the right call there — the two
layers face different traffic shapes and don't generalize to the same
answer.

# Decision

**Option B.** Two independent Redis windows in `LoginAttemptLimiter`,
keyed by the lower-cased raw input email, incremented atomically in one
Lua script (`login_attempt_windows.lua`):

```
fast window: 5 failures / 1 minute   (unchanged from ADR-039)
slow window: 15 failures / 24 hours  (new - replaces the DB counter)
```

`recordFailure(email)` returns `true` only on the attempt that pushes
either window to its limit. `LoginService` writes `user.lock(now,
lockDuration)` only when that's `true` — one DB write per lock cycle, not
one per failed attempt. `isBlocked(email)` (the pre-DB-lookup check) peeks
both windows via a single `MGET` round trip.

`User.failedLoginAttempts` and its `V2` column are removed
(`V5__drop_failed_login_counter.sql`), replaced by `User.lock`/`User.unlock`
operating on `lockedUntil` alone. `locked_until` remains the sole
DB-persisted state — the backstop that survives a Redis flush or restart,
since the lock check (`user.isLocked(now)`) reads it directly, not Redis.

Single-phase drop, not [[ADR-027-schema-migration-strategy]]'s full
five-phase expand/contract: that ADR's own "Revisit When" clause allows
relaxing the discipline for "tables/services not yet carrying real
traffic, tighten again once live" — `users.failed_login_attempts` has no
production data and no rolling-deploy window where an old app version
could still expect it to exist. `failed_login_attempts` is not a
[[ADR-013-gdpr-crypto-shredding|PII]] column, so ADR-027's separate
PII/Avro CI gate does not apply.

# Why

Redis TTLs already do exactly the "forget old failures" job a decay
mechanism needs — building a second one (scheduled job, manual decrement)
on top of a DB counter would duplicate expiry logic Redis provides for
free. Reporting the trip moment back from `recordFailure` turns the DB
write from "every failed attempt" into "the one attempt that mattered,"
which is both the actual fix for the accumulation bug and, as a side
effect, exactly the DB-write-reduction the two-window proposal aimed for.

# Consequences

**Easier:** an infrequent mistyper's failures decay naturally instead of
counting toward a lock forever; `LoginService`'s wrong-password branch
writes to Postgres only when a lock actually trips, not on every failure;
one fewer DB column to keep migrated.

**Harder:** two Redis keys per active failure streak instead of one
(bounded by TTL either way); `LoginAttemptLimiter`'s Lua script and its
Java wrapper are slightly more involved (two-window atomic increment,
multi-value script result) than the single-window version it replaces.

# Revisit When

- If real usage shows 15/24h miscalibrated — same category as every other
  numeric tunable in this vault, a starting default.
- If `users` ever carries live traffic before a future breaking-shaped
  schema change on this table — the single-phase-drop exception taken
  here would not apply; go back to full expand/contract.

## Open Questions

None currently.
