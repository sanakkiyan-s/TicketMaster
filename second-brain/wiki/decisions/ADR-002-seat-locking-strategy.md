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

## Hold TTL base duration (resolved)

**5 minutes**, flat across all events (no demand-tiering). Not the total
time a user gets to check out — that's covered separately by
[[ADR-006-saga-booking-orchestration]]'s extension mechanism (renew on
page interaction, extend on payment-submission start, hard ceiling 15 min
from original hold). Base TTL's only job is bridging "seat selected" to
"user does something" (form fill triggers renewal) — it does not need to
cover a full slow checkout, since renewal already does.

Reasoning:

```
Too short (1-2 min): punishes a user who paused a few seconds after
  selecting — they weren't abandoning, just slow to click into the form.
Too long (10 min): holds a likely-abandoned seat away from other buyers
  during exactly the scenario this system is optimizing for (hot on-sale
  contention) — wastes the resource being protected.
5 min: enough room for a real user to start engaging (which then
  triggers renewal), short enough to release a truly-abandoned seat
  reasonably fast.
```

Flat, not tiered by event demand: tiering adds real implementation
complexity (classify event, thread tier through hold creation) for a
number already flagged as a starting default needing real production
tuning — not worth building before there's data to justify it, same
category as ADR-004's 75% threshold and ADR-006's retry timings.

## Amendment: Hold Renewal Strategy — dropped "renew on interaction" (resolved)

**Problem**: an earlier version of this design (and [[ADR-006-saga-booking-orchestration]])
called for extending `held_until` on every user page interaction
(clicks, keystrokes). Under peak on-sale load this is an unbounded
O(N)-writes-per-session hammer on the same hot `SeatHold` rows in
Postgres — reintroduces the exact connection-pool/lock-contention
failure mode this ADR exists to prevent (see Requirements above), just
moved from "many users, one seat" to "one user, many writes."

**Decision**: drop renew-on-interaction entirely, replace with:

```
1. Fixed 5 min base hold on creation: held_until = NOW() + 5 minutes.
   No writes at all between creation and the next checkpoint below.

2. Client-side countdown is UI-only, computed from held_until - NOW(),
   no server round-trip needed to show a timer.

3. Single extension checkpoint — payment-submission start:
   if held_until - NOW() < 3 minutes AND hard ceiling not yet reached:
     held_until = held_until + 5 minutes  (atomic, one write)
   This is the ONLY possible additional write to a given hold row
   beyond its creation.

4. Hard ceiling: total hold duration, including the extension, never
   exceeds 15 minutes from the ORIGINAL held_until. Unchanged from
   ADR-006.
```

Result: DB writes per hold = 1 (create) + at most 1 (payment-submit
extension) + 1 (confirm/release) — flat O(1) per booking, independent of
how long or how actively a user browses before checking out.

**Accepted trade-off**: a user who never engages past selecting a seat
loses it after 5 minutes of total inactivity — desired, not a bug; frees
contested inventory back to active buyers, which is the entire point of
a tight base TTL under contention.

## Amendment: unique constraint must be co-located with the Citus shard key (CRITICAL)

**Defect found**: this ADR names the partial unique index on
`(session_id, seat_id)` as "the actual correctness guarantee." That
guarantee **silently degrades to per-shard** under
[[ADR-005-postgres-sharding]].

```
Citus enforces a unique constraint only WITHIN each shard, unless the
distribution column is part of the constraint. ADR-005 distributes by
event_id. The constraint above does not contain event_id.

Result: two rows for the same (session_id, seat_id) landing on two
different shards would BOTH be accepted. The double-sell backstop —
the thing this ADR calls the real guarantee — is not global.
```

Neither ADR mentioned the interaction. **Resolution**: `event_id` must be
part of the seat table's primary/unique key, so the constraint is
co-located with the distribution column:

```sql
-- partial unique index, amended
UNIQUE (event_id, session_id, seat_id) WHERE status IN ('HELD','PURCHASED')
```

Must be settled before `inventory-service` is built — retrofitting a
distribution key after the table exists is a table rebuild, not a
migration. Verified by a test asserting the constraint holds against a
**Citus-enabled** Postgres container, not vanilla Postgres (vanilla
passes either way and proves nothing).

## Amendment: Redis command timeout + circuit breaker (fail-open was unimplementable)

**Defect found**: the Decision above says "Redis unavailable -> fail
open, skip straight to step 2," but specifies **no command timeout and no
circuit breaker**. That covers a Redis that *refuses* connections. It does
not cover a Redis that is **blackholed** — accepts the TCP connection and
never responds — which is the more common real-world failure (network
partition, overloaded node, GC pause).

```
With Lettuce's default command timeout, a blackholed Redis makes every
hold request block for seconds before falling open. Under a stampede,
that exhausts the servlet thread pool.

Net effect: "degrades gracefully" becomes an outage — and a WORSE one
than the Postgres connection-pool exhaustion this ADR exists to prevent.
```

**Resolution**:

```
Redis command timeout:  ~50ms   (starting default, needs real data)
Circuit breaker:        Resilience4j, opens after a small consecutive
                        failure count; while open, hold requests skip
                        Redis entirely rather than each paying the
                        timeout. Half-open probe to recover.
```

Both numbers come from load-test experiment E1 and are verified by chaos
experiment C1, which must test **three** Redis modes — connection-refused,
blackhole/timeout, and added latency. Testing only the clean-refusal mode
passes while the real failure mode is broken.

## Amendment: application-supplied timestamps, not SQL `now()`

The Decision block evaluates `held_until = now() + TTL` and the sweep's
`held_until < now()` **in SQL**. Two consequences:

1. Hold expiry cannot be driven deterministically from a test — advancing
   a Java `Clock` does not move Postgres's clock, so verifying the
   ADR-006 payment race requires literally waiting minutes.
2. More importantly: with many `inventory-service` instances, app-vs-DB
   clock skew becomes a live correctness variable in expiry decisions.

**Resolution**: pass an `Instant` from an injected `java.time.Clock`
instead of calling SQL `now()`. Enforce with an ArchUnit rule banning
direct `Instant.now()`/`LocalDateTime.now()` in production code. This is
a testability and skew-safety change; the locking strategy itself is
unchanged.

## Amendment: hold outcomes are three-way, not binary

Instrumentation requirement, needed to answer this ADR's own "Revisit
When" conditions. A rejected hold is not one thing:

```
won            -> normal
lost_race      -> normal and EXPECTED during an on-sale; this ADR
                  explicitly accepts it ("acceptable for losers of a
                  hot-seat race to be rejected fast")
infra_failure  -> Postgres timeout, Redis fail-open then PG failure,
                  connection pool exhaustion -> THIS is the error
```

Only `infra_failure` counts against an SLO. A binary success/failure
metric makes a *successful* on-sale look like an outage, and hides the
one signal that actually indicates this ADR's design is failing.
Companion metric: Redis fail-open rate — a nonzero rate under normal
conditions means the fast gate is not protecting anything.

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

**Amendment: PgBouncer added in front of Postgres (ADR-024).** The Redis
fast-gate reduces *how often* a connection gets requested during a
hot-seat stampede, but never addressed the *ceiling* on how many real
Postgres connections can exist at once — that gap is what
[[ADR-024-pgbouncer-connection-pooling]] closes. The two are
complementary, not overlapping: Redis rejects losing attempts before they
touch a connection at all; PgBouncer bounds the real connection count for
whatever does get through.

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
