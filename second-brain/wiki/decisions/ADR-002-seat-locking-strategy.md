---
title: ADR-002 Seat Locking Strategy
type: decision
sources: []
related: [[inventory-service]], [[booking-service]], [[queue-service]], [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

`inventory-service` is the concurrency core of the system: must guarantee
no two users ever hold/purchase the same seat, under on-sale traffic where
thousands of users may compete for the same specific seat within seconds.
Must decide source of truth, locking mechanism, Redis's exact role, hold
TTL handling, and how this behaves under partial failure (crash, Redis
outage, payment/hold-expiry race).

# Requirements / Constraints

- Correctness over throughput: it is unacceptable to sell one seat twice.
  It is acceptable for losers of a hot-seat race to be rejected fast.
- Must survive inventory-service crash mid-transaction without corrupting
  seat state (no "phantom HELD forever" or "double PURCHASED").
- Must not let Postgres connection pool exhaustion on one hot seat take
  down unrelated bookings (different seats/sessions).
- queue-service already throttles total admission into inventory-service,
  but does not prevent many already-admitted users from converging on one
  specific hot seat within the same second — a second line of defense is
  needed at the seat level.

# Options Considered

## Option A — Pessimistic Postgres lock only (`SELECT ... FOR UPDATE`)

Pros: simple, correct, one clean winner per seat, no extra infrastructure
dependency for correctness.

Cons: each contending request holds a DB connection for the duration of
the row-lock wait. Under a stampede on one hot seat, this can exhaust the
connection pool and start failing unrelated requests (different seats,
different sessions) — a real failure mode, not hypothetical.

## Option B — Optimistic locking (version column + retry)

Pros: no lock held, scales well under low contention.

Cons: on genuine hot-seat contention (the exact scenario this system
targets), almost every "conflict" is real contention, not a benign
concurrent unrelated write — retries just requeue the same crowd and
amplify load instead of reducing it.

## Option C — Redis distributed lock (Redlock, multi-node consensus) in
front of Postgres

Pros: keeps losing requests off Postgres entirely.

Cons: Redlock's multi-node consensus protocol adds real operational
complexity and its own failure modes for a single-seat, single-region use
case that doesn't need that level of distributed consensus.

## Option D — Hybrid: single-instance Redis atomic lock (fast gate) +
Postgres `FOR UPDATE` + partial unique constraint (durable authority)

Pros: Redis lock (`SETNX`/Lua, single instance, not Redlock) rejects
losing attempts in microseconds *before* they touch a Postgres connection
— protects the connection pool from hot-seat stampedes, which Option A
cannot do. Postgres remains the correctness authority regardless of Redis
state, so a Redis outage degrades (falls back to Option A's behavior) but
never corrupts data.

Cons: one more moving part than Option A alone; needs an explicit
fail-open/fail-degraded policy for Redis outages.

# Decision

**Option D.** Source of truth is Postgres. Redis is a fast admission gate
in front of it, not a replacement for it.

```
hold(seatId):
  1. Redis: SETNX seat:{sessionId}:{seatId}:lock <holderId> TTL=few sec
     (single-instance atomic op, not Redlock)
     - fail -> reject immediately, no DB connection touched
     - success -> continue
     - Redis unavailable -> fail open, skip straight to step 2
  2. Postgres: BEGIN; SELECT seat FOR UPDATE;
     if status = AVAILABLE -> UPDATE status = HELD, held_until = now() + TTL;
     COMMIT
  3. Redis: release the seat-lock key; separately set/refresh a
     hold-expiry TTL key used by the expiry sweep (scheduling hint only)

confirm(seatId, callerId):
  BEGIN; SELECT seat FOR UPDATE;
  if status = HELD AND NOT expired AND held_by = callerId
    -> UPDATE status = PURCHASED
  COMMIT

expiry sweep (periodic job):
  SELECT seats WHERE status = HELD AND held_until < now()
  FOR UPDATE SKIP LOCKED
  -> UPDATE status = AVAILABLE
```

Database backstop: partial unique index on `(session_id, seat_id) WHERE
status IN ('HELD', 'PURCHASED')` — guarantees no double-sell even if the
locking code above has a defect. This is the actual correctness guarantee;
everything else is throughput/availability optimization around it.

**Payment race**: booking-service must retry `confirm` with an idempotency
key until it gets a definitive success or "hold expired" result — never
silently drop a paid booking. If the hold expired before `confirm` lands
(payment succeeded but the seat was released back to AVAILABLE), that is a
real failure case: booking-service must trigger an automatic refund and
notify the user. Not currently modeled as a retryable success case,
because the seat may have already been sold to someone else by then.

# Why

Matches the project's stated priority (correctness over throughput,
learning real distributed-systems tradeoffs at scale) while directly
addressing the connection-pool exhaustion failure mode that a
Postgres-only design has under a genuine hot-seat stampede. Avoids
Redlock's multi-node complexity since single-instance Redis is sufficient
for a fast reject gate — the actual correctness guarantee never depends on
Redis being correct or even available.

# Consequences

**Easier:** hot-seat stampedes get rejected cheaply, without spending a DB
connection per loser; Postgres connection pool stays available for
unrelated bookings even during a stampede on one seat; system degrades
gracefully (slower, not incorrect) if Redis is down.

**Harder:** one more component (Redis lock layer) to build, monitor, and
reason about failure modes for; must implement and test the fail-open path
(Redis down → straight to Postgres) explicitly, not assume it "just
works."

# Revisit When

- If Redis lock layer itself becomes a bottleneck or single point of
  contention (unlikely at this scale, but worth a load test before
  trusting it).
- If crash-recovery testing (inventory-service restarts mid-transaction)
  reveals a state inconsistency not covered by the unique constraint
  backstop — revisit transaction boundaries.
- If the payment-race refund path (hold expired after payment succeeded)
  turns out to happen often enough in testing to need a different
  mitigation (e.g. grace-period extension of the hold once payment starts).
