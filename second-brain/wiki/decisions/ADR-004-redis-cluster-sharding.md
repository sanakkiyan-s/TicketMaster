---
title: ADR-004 Redis Cluster and Sharding (Global Scale)
type: decision
sources: []
related: [[ADR-002-seat-locking-strategy]], [[inventory-service]], [[queue-service]], [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

ADR-002 designed Redis as a single instance (+ Sentinel for HA) sufficient
for this project's realistic target load (one popular on-sale, ~100k
concurrent). This ADR extends the design to **global scale** — the real
Ticketmaster operating thousands of simultaneous events worldwide,
continuously — explicitly to learn the sharding problem, even though it
exceeds this project's actual expected load. See [[ADR-001-microservices-vs-modular-monolith]]
for the established precedent: this project optimizes for learning real
distributed-systems tradeoffs, not minimum-necessary footprint.

# Requirements / Constraints

- Aggregate concurrent Redis traffic across all simultaneous global
  on-sales exceeds one node's capacity, even though any single event's
  slice does not.
- Seat-lock and queue-admission keys must support atomic multi-key
  operations within one session (group booking — holding several seats
  together) despite being distributed across shards.
- Pub/Sub for seat-availability broadcast (see live-updates flow) must not
  degrade into cluster-wide broadcast overhead as node count grows.

# Options Considered

## Option A — Single instance + Sentinel (ADR-002's original design)

Pros: simple, sufficient for this project's actual target scale.

Cons: doesn't hold at true global scale; doesn't exercise the sharding
problem this ADR exists to learn.

## Option B — Redis Cluster, hash-tagged keys

Pros: horizontally scales Redis across many nodes; hash tags let related
keys (all seats in one session) stay co-located for atomic multi-key ops;
built-in per-shard master/replica failover.

Cons: operational complexity; requires deliberate key-naming discipline
(hash tags); introduces a hot-shard risk for viral single events (below).

# Decision

**Option B — Redis Cluster**, with hash-tag key design:

```
seat:{sessionId}:A15:lock       <- only "{sessionId}" is hashed
seat:{sessionId}:A16:lock       <- same session -> same shard
queue:{sessionId}:admission:*   <- same pattern, queue is per-session
```

All seats/queue entries for one session land on the same shard —
guarantees atomic multi-seat holds (group booking) via a single Lua
script/`MULTI`, since Redis Cluster only allows multi-key atomicity within
one slot.

Fraud-service velocity counters (`fraud:{accountId}:velocity`,
`fraud:{deviceId}:velocity`) are single-key operations — no hash tag
needed, free to distribute across shards independently.

**Pub/Sub**: use Redis 7+ sharded pub/sub (`SPUBLISH`/`SSUBSCRIBE`) with
the `{sessionId}` hash tag, not classic `PUBLISH` — classic pub/sub
broadcasts to every cluster node regardless of subscribers, which doesn't
scale with node count. Sharded pub/sub stays within the shard owning that
session.

**Supersedes ADR-002's Sentinel recommendation**: Redis Cluster's
per-shard master/replica failover replaces the need for a separate
Sentinel deployment. Don't run both — Cluster mode subsumes Sentinel's
job.

**Requires at least one replica per master shard** — failover is not
automatic just because Cluster mode is on. Mechanism: nodes gossip
health over the cluster bus; when a majority of masters agree a node is
unreachable, it's marked `FAIL`; that dead master's *own replica* then
requests votes from the other masters and, on majority, promotes itself
and takes over the dead node's hash slots. If a master shard has **no
replica**, there is nothing to promote — its slots simply go unavailable
until manually recovered. Every shard must be provisioned with at least
one replica for this design to actually deliver the HA it's meant to.

# Why

Matches the project's explicit learning goal, applied at the scale where
sharding actually becomes necessary (global, many-simultaneous-events),
not this project's own realistic target (which ADR-002 already handles
without Cluster).

# Consequences

**Easier:** handles true global aggregate load; clean atomicity boundary
for group booking; per-shard failover built in.

**Harder:** key-naming discipline required everywhere (forgetting a hash
tag silently breaks multi-key atomicity for that key); operational
complexity of running/monitoring a multi-node cluster.

**Risk — hot shard**: hash-tagging by `{sessionId}` means *all* traffic
for one viral event's on-sale (every seat, every queue entry) hashes to
the **same single shard** — concentrating load exactly where a popular
event needs the most capacity. Consistent hashing distributes *different*
keys evenly; it does not fix one key (or one hash tag) being
disproportionately hot. Mitigated below — not eliminated, since it's a
genuine tradeoff, not a bug.

## Hot-shard mitigation (resolved)

Hybrid tagging, applied selectively, not globally:

```
Default (most events): hash tag = {sessionId}
  -> whole session one shard, atomic multi-seat holds, simple

Flagged "high-demand" events (presale signup volume, artist popularity
signal, or manual admin flag, set BEFORE the on-sale): hash tag =
{sessionId}:{sectionId}
  -> spreads across multiple shards, no single node absorbs it all
```

`event-service` (or an admin) sets the high-demand flag ahead of time;
`inventory-service` reads it when generating keys for that session and
picks tag granularity accordingly. Cross-section group bookings on
flagged events lose one-shot Lua-script atomicity — handled via two-phase
lock with compensation (acquire each section's lock in sequence; if any
fails, release the ones already acquired), the same failure-handling shape
already established in [[ADR-006-saga-booking-orchestration]], applied one
layer lower. Most events (long tail, low demand) never pay this
complexity cost.

## Cluster node count / resharding (resolved — autoscaling, Option 2)

Chosen deliberately for learning value over what a portfolio project
strictly needs (managed Redis offerings — ElastiCache, Redis Enterprise —
exist specifically so teams don't have to build this themselves; building
it here is the point).

**Reactive-only is insufficient by itself**: a viral on-sale spike can peak
and largely resolve within 5-15 minutes. Provisioning a new node, joining
the cluster, and migrating slots takes real time (minutes) — by the time a
metric threshold trips and a new node is ready, the worst of the burst may
already be over. Needs a proactive half too:

```
PROACTIVE (scheduled, known events):
  A capacity-planner job reads event-service's high-demand-flagged events
  and their scheduled on-sale start times, pre-provisions extra nodes and
  pre-splits that event's keyspace (section-level tagging, per the
  mitigation above) BEFORE the on-sale starts — while there's no live
  traffic, so migration is cheap and safe.

REACTIVE (safety net, unpredicted virality with no advance signal):
  Prometheus + Redis exporter watches ops/sec and memory per shard.
  Sustained threshold breach over a time window (not a single spike, to
  avoid overreacting to noise) triggers:
    1. Add a node (Kubernetes StatefulSet scale-out)
    2. CLUSTER MEET (new node joins the cluster)
    3. redis-cli --cluster rebalance — Redis's own built-in slot
       redistribution tool, not hand-rolled migration logic
```

**Scale-in stays manual/conservative** — auto-shrinking a stateful store
carries real data-movement risk for uncertain benefit (traffic could spike
again); auto scale-out only.

**Lives in `infra/`**: Kubernetes StatefulSet for Redis nodes, Prometheus
+ Redis-exporter for metrics (ties into [[cross-cutting-concerns]]'s
observability/tracing section), a small controller watching thresholds and
invoking `redis-cli --cluster rebalance` on breach.

# Revisit When

- Load testing shows the hot-shard mitigation's section-split threshold
  (how "high-demand" gets defined) is miscalibrated — too many events
  flagged (unnecessary complexity) or too few (hot-shard risk returns).
- If real load never approaches this scale (likely, given this is a
  portfolio project) — this ADR remains a documented "how it would be done
  at global scale," not a claim that the project's actual traffic requires
  it. Keep [[ADR-002-seat-locking-strategy]]'s single-instance design as
  what's actually needed for this project's realistic target.

## Open Questions

- Exact threshold/time-window values for the reactive autoscaler (ops/sec,
  memory %, sustained-breach duration) — not decided, needs real load-test
  data.
- "High-demand" flag criteria (presale signup count threshold, etc.) — not
  decided.
- Capacity-planner job's own reliability (what if it fails to pre-scale
  before a scheduled on-sale?) — not designed, itself a single point of
  failure for the proactive path.
