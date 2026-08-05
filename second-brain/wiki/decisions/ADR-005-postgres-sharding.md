---
title: ADR-005 Postgres Sharding by event_id/region (Global Scale)
type: decision
sources: []
related: [[ADR-001-microservices-vs-modular-monolith]], [[inventory-service]], [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

ADR-001 already splits data by service (federation — auth DB, event DB,
inventory DB, etc.), a different technique from sharding: it splits
*different tables* by domain, not *one table's rows* across nodes by a
hash/range key. At this project's realistic target scale (one popular
event's on-sale), no single service's Postgres instance is anywhere near
its ceiling — write pressure is already throttled twice before reaching
it (queue-service admission limit, then the Redis fast-reject gate from
[[ADR-002-seat-locking-strategy]]).

At **true global scale** — thousands of simultaneous events running
continuously worldwide — this changes: aggregate volume and throughput
across all events exceeds one instance, and geography/data-residency
requirements (users should hit data physically near them; some
jurisdictions require data to stay in-region) become real constraints, not
just performance ones.

# Requirements / Constraints

- Any single event's data (sessions, seat inventory, related bookings)
  must be fully self-contained on one shard — no query should ever need to
  join across two different events' seat/booking data.
- Cross-event aggregate queries (organizer-wide reporting, platform-wide
  metrics) must not require live cross-shard joins.
- This is explicitly a "how it would be done at global scale" exercise —
  this project's actual expected load does not require it (see Revisit
  When).

# Options Considered

## Option A — No sharding, one instance per service (ADR-001's current design)

Pros: simple, sufficient for this project's realistic target.

Cons: doesn't hold at true global, continuous, multi-event scale; doesn't
address geo/data-residency.

## Option B — Shard by `event_id`, grouped under `region`

Pros: natural boundary — events are already fully independent of each
other, so no cross-shard joins are needed for the core booking flow.
Region grouping additionally addresses latency and data-residency.

Cons: needs a routing layer (which shard holds event X); cross-event
reporting needs an aggregation step instead of a live query.

# Decision

**Option B.** Shard key: `event_id`, with `region` as a coarser grouping
where events physically route to region-local shard clusters (e.g. an EU
event's data stays on EU-region shards).

- `inventory-service`, and any other service whose tables are keyed to a
  specific event (bookings, tickets for that event), shard the same way —
  co-locating an event's inventory, bookings, and tickets on the same
  shard keeps the booking flow's writes single-shard, avoiding
  distributed transactions across shards for the hot path.
- Cross-event/cross-region aggregation (organizer dashboards, platform
  metrics) is **not** done via live cross-shard queries — it's exactly
  what `analytics-service` already exists for ([[ADR-003-gap-list-triage]]):
  an async Kafka consumer building its own aggregate view, sidestepping
  the need for the OLTP tier to answer cross-shard analytical queries at
  all.
- Routing (which shard owns event X) needs a lookup — not yet designed,
  likely a small routing table or consistent-hash-based lookup service,
  itself simple enough not to need its own sharding.

# Why

`event_id` is a boundary that already exists naturally in the data model
and is already the unit nothing needs to query across — using it as the
shard key avoids inventing artificial partitioning logic. Reusing
`analytics-service` for cross-shard aggregation avoids building a second
mechanism for a problem already solved by an existing service.

# Consequences

**Easier:** clean scaling per event/region; no cross-shard joins on the
booking hot path; reuses existing analytics-service instead of adding new
infrastructure for reporting.

**Harder:** needs a shard-routing layer; any future cross-event feature
that *does* need to join across events (not currently identified) would be
expensive.

# Revisit When

- This project's actual load never approaches global scale — this ADR is
  explicitly documentation of "how it would be done," not a claim that the
  project needs it. [[ADR-001-microservices-vs-modular-monolith]]'s
  one-instance-per-service design remains what's actually built unless the
  project's scope changes.
- If a real cross-event query need emerges that analytics-service can't
  serve async (e.g. a synchronous cross-event uniqueness constraint) —
  would need to revisit the "no cross-shard queries" assumption.

## Open Questions

- Shard routing mechanism (lookup table vs. consistent hash vs. directory
  service) — not designed.
- Number of shards per region, resharding process as event volume grows —
  not decided, deferred until actually built.
