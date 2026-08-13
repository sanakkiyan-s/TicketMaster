---
title: ADR-024 PgBouncer for Postgres Connection Pooling
type: decision
sources: []
related: [[ADR-002-seat-locking-strategy]], [[ADR-005-postgres-sharding]], [[ADR-004-redis-cluster-sharding]], [[inventory-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[ADR-002-seat-locking-strategy]] already names Postgres connection-pool
exhaustion as a real, tracked failure mode — it's the literal trigger for
the `infra_failure` hold outcome, and the Redis fast-gate's whole
justification (Option A → C) is protecting that pool from hot-seat
stampedes. But *what actually manages the pool* was never decided —
Spring Boot's default (HikariCP, in-process, per-service-instance) has
been an unstated assumption, not a chosen answer.

[[ADR-005-postgres-sharding]]'s Citus topology makes the underlying
problem structurally worse than a single-Postgres setup: every app-side
connection to the coordinator can fan out to multiple worker connections
for a cross-shard query, and 15 services × multiple instances × a default
per-instance Hikari pool can realistically produce hundreds of raw
connections at steady state — before [[ADR-004-redis-cluster-sharding]]'s
autoscale pattern (same pressure applies to compute, not just Redis)
spins up more instances during an on-sale, multiplying it further right
when it matters most.

# Requirements / Constraints

- Must not let real Postgres connection count scale linearly with
  autoscaled app instance count.
- Must work with Citus's coordinator + worker topology — pools in front
  of the coordinator, not something each worker manages independently.
- Must follow Patroni failover — must point at whichever node is
  currently primary, not a stale one after a promotion.
- Must not silently break session-level Postgres features some code
  might rely on (session `SET`, session-level advisory locks) — must be
  named explicitly, not discovered in production.
- Must not conflict with [[ADR-002-seat-locking-strategy]]'s
  `SELECT ... FOR UPDATE` row-lock pattern — a held lock spans a
  transaction; pooling must not reassign the connection mid-transaction.

# Options Considered

## A — App-side pooling only (HikariCP per instance, status quo/default)

Pros: zero extra infrastructure, works out of the box with Spring Boot.
Cons: doesn't solve the actual problem — real connection count still
scales linearly with (instance count × per-instance pool size), the
exact failure mode ADR-002 already names as real and never actually
solved, only detected.

## B — PgBouncer, transaction pooling mode, one instance per region

Pros: multiplexes many app-side connections onto a small real
Postgres/Citus connection ceiling; standard, battle-tested tool built
for exactly this; transaction mode fits this project's transaction
shape (ADR-002's hold path is one short transaction per request, not a
long-lived session). Cons: transaction-mode pooling breaks session-level
features (session `SET` persisting outside a transaction, session-level
advisory locks, some prepared-statement caching behavior) — must be
audited against actual usage; another component to run/monitor per
region; must be explicitly re-pointed at the new primary after a Patroni
failover, doesn't happen automatically without extra wiring.

## C — PgBouncer, session pooling mode

Pros: preserves session-level features fully. Cons: defeats the purpose
— session mode ties one real Postgres connection to one client
connection for its whole session, doesn't actually reduce connection
count under sustained load, the exact thing this ADR exists to fix.

# Decision

**Option B.** PgBouncer, transaction pooling mode, one PgBouncer
deployment per region — sits in front of that region's Citus
coordinator, reusing [[ADR-005-postgres-sharding]]'s existing regional
boundary rather than inventing a new one.

## Topology

```
service instances (many, autoscaled — same pattern ADR-004 established
  for Redis, applied here to compute)
  -> PgBouncer (transaction pooling, small real connection ceiling)
  -> Citus coordinator (Patroni-managed primary, ADR-005)
  -> Citus workers (per-query fan-out, unchanged)
```

One PgBouncer deployment per region, matching ADR-005/ADR-016's regional
boundary — not global, consistent with this project's existing
region-homed data pattern.

## Failover coordination with Patroni

PgBouncer must always point at whichever node Patroni currently holds as
primary. Standard mechanism: Patroni's `on_role_change` callback hook
triggers a PgBouncer config reload pointing at the new primary — this
does **not** happen automatically without that explicit wiring; flagged
as an open question below since the exact implementation needs
real-config-time verification, not assumed here.

## What breaks under transaction-mode pooling — audited against this project's actual patterns

```
- Session-level SET (not SET LOCAL) — lost between transactions, since
  the underlying real connection may be handed to a different caller
  for the next transaction. App code must never rely on session state
  surviving across a transaction boundary.
- Session-level advisory locks (pg_advisory_lock, not the _xact
  variant) — dangerous under transaction pooling, a lock could outlive
  what the app thinks is its "session." ADR-002's actual locking
  mechanism (SELECT ... FOR UPDATE) is fine — it's transaction-scoped,
  releases at commit, exactly what transaction-mode pooling supports
  correctly. This is why Option B does not conflict with ADR-002's
  stated constraint above.
- Prepared-statement caching behavior differs across PgBouncer
  versions — modern PgBouncer (1.21+) supports prepared statements in
  transaction mode via a translation layer, but the actual deployed
  version's support must be confirmed at implementation time.
```

## Amendment: idle-in-transaction protection and the Stripe-call risk

PgBouncer bounds the *count* of real connections but does not prevent a
single connection from being held open doing nothing — the other half of
the classic exhaustion failure mode (a transaction opened, then blocked
on a slow external call, holding its connection the whole time).

```
Postgres setting: idle_in_transaction_session_timeout, set on the
  Postgres/Citus side (not PgBouncer) — forcibly rolls back any
  transaction left open-and-idle past the threshold. Starting default,
  not yet numerically decided — needs to exceed the slowest legitimate
  in-transaction DB work, comfortably under the point where it would
  itself start masking real exhaustion.

Operational check: pg_stat_activity — the live per-connection state
  view — should be a named signal into ADR-015's observability stack,
  specifically watching for connections stuck in "idle in transaction."
  Not currently listed among ADR-015's domain-specific SLIs; flagged
  here as a gap that ADR should absorb.
```

**Explicit rule, load-bearing specifically for payment-service:** no
service may open a database transaction and then make a call to an
external provider (Stripe, FCM, HIBP, etc.) while that transaction is
still open. payment-service's PaymentIntent creation
([[ADR-011-pci-scope-containment]], [[ADR-020-payment-event-ledger]])
is the concrete risk case — if written naively, "create intent row,
call Stripe, wait, then commit" holds a DB connection for the full
Stripe round-trip. Correct shape: commit any local DB work first,
release the connection, make the external call, take a **fresh**
connection only to record the result. This was not previously stated
anywhere in ADR-006/011/020 and should govern payment-service's actual
implementation.

# Why

Directly closes a failure mode ADR-002 already named as real but never
actually solved with the right tool — the Redis fast-gate reduces *how
often* a connection gets requested under a hot-seat stampede, but never
addressed the *ceiling* on how many real Postgres connections can exist
at once. PgBouncer addresses that ceiling directly, the piece the fast-
gate structurally can't cover on its own.

# Consequences

**Easier:** real Postgres/Citus connection count stays bounded
regardless of how many service instances autoscaling spins up during an
on-sale; ADR-002's `infra_failure` outcome becomes meaningfully rarer
under load, not just correctly detected after the fact.

**Harder:** another component to deploy/monitor per region; every
service's actual query patterns need auditing against transaction-mode's
session-state limitations before relying on it blindly; Patroni-failover
coordination needs explicit wiring (callback hook + reload), not
automatic out of the box.

# Revisit When

- If a specific service genuinely needs session-level state (rare,
  would need real justification) — carve out a dedicated session-mode
  pool for just that service, not abandon transaction mode project-wide.
- If real connection-count data post-launch shows the default pool
  sizing badly miscalibrated — retune from load-test data, same
  convention as every other numeric default in this vault.

## Open Questions

- Exact Patroni-failover-to-PgBouncer-reload wiring (callback script vs.
  an external health-check poller) — not decided, needs
  implementation-time research.
- Pool size per PgBouncer instance (`default_pool_size`,
  `max_client_conn`) — starting-default category, needs real load data
  once services exist.
- Deployed PgBouncer version's prepared-statement support in
  transaction mode — verify at implementation time.
- `idle_in_transaction_session_timeout` numeric value — starting-default
  category, needs real data on legitimate in-transaction DB work
  duration once services exist.
