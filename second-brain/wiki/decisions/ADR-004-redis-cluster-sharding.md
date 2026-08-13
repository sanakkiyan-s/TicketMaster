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
  A SINGLE capacity-planner job runs periodically (not one job per event —
  see coordination note below), reads ALL of event-service's high-demand-
  flagged events with on-sale dates in the next 48h, sums their combined
  extra-capacity need, and provisions/reshards ONCE, sequentially — well
  ahead of the earliest on-sale (buffer time, not last-minute).

REACTIVE (safety net, unpredicted virality with no advance signal):
  Prometheus + Redis exporter watches ops/sec and memory per shard.
  Trigger: ops/sec > 75% of benchmarked node capacity, OR memory > 75% of
  allocated capacity, sustained for 60 seconds (filters noise, not a
  single-spike trigger). On breach:
    1. Add a node (Kubernetes StatefulSet scale-out)
    2. CLUSTER MEET (new node joins the cluster)
    3. redis-cli --cluster reshard — targeted move of a specific slot
       count from a specific source to the new node. NOT `--cluster
       rebalance`, which would redistribute the WHOLE cluster evenly —
       that would disturb unrelated low-demand events' data for no
       reason. Reshard only touches the slots that actually need to move.
```

**75% is a starting default, not a final number** — the correct thresholds
can only come from real load-testing (finding where a benchmarked node
actually starts degrading), same as any production system. Start here,
tune from measured data once built.

**"High-demand" flag criteria**: `presale signups > 3x venue capacity`
(proxy for demand far exceeding supply) OR manual admin override (formula
won't catch every case — a surprise celebrity announcement, day-of virality
with no presale signal).

**Capacity-planner reliability** (it's a real single point of failure for
the proactive path if it silently fails):
- Runs with a full buffer (~24h before the earliest flagged on-sale, not
  minutes before) so a failure has room to retry.
- Retries with backoff on failure.
- Heartbeat check: if a flagged event isn't confirmed pre-scaled by
  T-1h, alert a human — never fail silently.
- If it still fails entirely, the REACTIVE path is still running as a
  fallback — degraded (cold-start lag returns) but not zero protection.

**Coordination across concurrently-flagged events**: exactly because it's
one job per planning cycle (not one job per event), two events flagged
around the same time get provisioned in the same run, sequentially —
avoids two independent processes issuing conflicting `CLUSTER SETSLOT`
operations against the same cluster at once.

**Mechanism, for the record** (why data must be physically moved, not just
relabeled): each Redis node's data lives only in that node's own RAM —
nothing is shared between nodes automatically. Migrating a slot means
`CLUSTER SETSLOT ... MIGRATING/IMPORTING`, then `MIGRATE`-ing each key's
actual bytes from source RAM to destination RAM (one atomic op per key —
never lost, never duplicated), and only once every key in that slot has
physically moved does ownership finalize (`CLUSTER SETSLOT ... NODE`).
Mid-migration reads/writes stay correct via Redis's own `ASK` redirect
protocol — a client asking the old node for an already-moved key gets
pointed to the new node automatically; this is handled by the Redis
protocol and any cluster-aware client library (Lettuce/Jedis), not
custom application code.

**Honest limitation — slot-level collision, not perfect per-event
isolation**: a slot can contain keys from multiple *different* events by
hash coincidence (16384 slots, potentially hundreds of concurrently active
events). Moving a flagged event's slot to a new node also moves any
unrelated event's keys that happen to share that slot — `MIGRATE` operates
per-key-in-slot, not per-event. Generally harmless (the new node was
provisioned with headroom for the flagged event, so a coincidental
passenger event's modest traffic doesn't hurt), but it means hash-tag
sharding gives "this event's data stays together," not "this event has a
node entirely to itself."

**Scale-in** (manual, conservative — from earlier): once demand metrics
stay low for a long cooldown window (hours, not minutes), a human
confirms the node is genuinely idle, runs `redis-cli --cluster reshard`
to move any remaining slots off it, then `CLUSTER FORGET` to remove it
from the cluster, then decommissions it.

**Lives in `infra/`**: Kubernetes StatefulSet for Redis nodes, Prometheus
+ Redis-exporter for metrics (ties into [[cross-cutting-concerns]]'s
observability/tracing section), a small controller watching thresholds and
invoking `redis-cli --cluster reshard` (targeted, not `rebalance`) on
breach.

## Amendment: the queue sequencer is a hot key this ADR's mitigation cannot fix

**Gap found**: the hot-shard mitigation above splits high-demand keys by
`{sessionId}:{sectionId}`. **That does not help the virtual queue.** The
queue is section-agnostic and requires a single total order, so its
sequence counter is structurally one key:

```
queue:{sessionId}:seq        <- INCR, one key, irreducible
```

It lands on exactly the shard already carrying the flagged event. The
mitigation above addresses seat-lock keys only; applying it here is
impossible, not merely unhelpful.

**Resolution — batched sequence allocation**: each `queue-service`
instance reserves a block with one `INCRBY queue:{sessionId}:seq 1000`
and hands numbers out from local memory. Reduces sequencer ops on that
key by ~1000x.

Cost: ordering becomes non-strict *across instances* (instance A's block
1000-1999 may be consumed after instance B's block 2000-2999). Acceptable
because queue admission ordering is **randomized within a join window**,
not strict FIFO — see [[queue-service]]. Strict FIFO would make latency
the ordering function, which is precisely what a bot buys; randomization
already discards exact arrival order deliberately, so batched allocation
costs nothing the design was relying on.

*Block size 1000 is a starting default, needs load-test data.*

## Amendment: composite fail-open collapse (cross-cutting, no single ADR owns it)

Reading this ADR together with [[ADR-002-seat-locking-strategy]],
[[api-gateway]], [[queue-service]], and [[fraud-service]] surfaces a
failure mode none of them states individually:

```
A Redis Cluster outage simultaneously removes:
  1. api-gateway's business-aware rate limiting  (Redis-backed bucket)
  2. queue-service ENTIRELY                       (Redis-only state, per
                                                   system-overview's
                                                   data-ownership table)
  3. fraud-service's velocity counters
  4. inventory-service's fast-reject gate         (fails open by ADR-002)

Composite result during an on-sale: traffic arrives unthrottled,
unqueued, unscored, and unlimited, straight into a Postgres-only
inventory path.
```

Every individual fail-open decision is defensible on its own terms. The
**aggregate is a coordinated collapse nobody decided on.** Two
mitigations, both cheap:

1. **Gateway rate limiting degrades, does not disappear.** On Redis
   unreachable, fall back to a conservative in-JVM per-instance limit
   (local token bucket, global limit ÷ instance count, biased low).
   Approximate throttling beats none.
2. **queue-service fails CLOSED on the on-sale path.** No queue state
   means no valid admission token, so `inventory-service` rejects
   on-sale bookings by default. Deliberate carve-out from this project's
   ambient fail-open convention, on the grounds that "on-sale
   temporarily paused" is a recoverable business event while "inventory
   oversold to bots in 90 seconds" is not.

Item 2 contradicts the fail-open expectation a reader would carry over
from ADR-002 — named here explicitly rather than left as a silent
inconsistency.

# Revisit When

- Load testing shows the 75% thresholds or the "3x capacity" flag
  criteria are miscalibrated — too aggressive (unnecessary scaling churn)
  or too lax (hot-shard risk returns despite the mitigation).
- If real load never approaches this scale (likely, given this is a
  portfolio project) — this ADR remains a documented "how it would be done
  at global scale," not a claim that the project's actual traffic requires
  it. Keep [[ADR-002-seat-locking-strategy]]'s single-instance design as
  what's actually needed for this project's realistic target.

## Open Questions

- Exact node-capacity benchmark numbers behind the 75% threshold (ops/sec,
  memory in absolute terms) — the percentage is decided, the underlying
  per-node ceiling it's a percentage OF still needs real load-test data.
