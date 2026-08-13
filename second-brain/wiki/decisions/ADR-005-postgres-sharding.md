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
- **Tooling: Citus** (Postgres-native distributed-database extension),
  not a hand-rolled routing layer. Reuses a proven mechanism instead of
  reinventing it — same philosophy as using Redis Cluster's own
  `reshard`/failover rather than custom code (see [[ADR-004-redis-cluster-sharding]]).

## Routing mechanism (resolved)

Two layers, only the second one is hash-based:

```
Layer 1 — region (manual, NOT hashed):
  Event's declared region decides which regional Citus cluster to
  connect to (EU event -> EU cluster). Deliberately not computed by a
  hash, since data-residency/compliance needs a forced, deliberate
  placement — a hash function has no notion of geography.

Layer 2 — event_id (hash-based, automatic, within a region's cluster):
  Citus architecture: 1 coordinator node (holds only metadata — which
  shard lives on which worker) + N worker nodes (hold the actual data,
  each a real separate Postgres instance).

  create_distributed_table('bookings', 'event_id') splits the table into
  a FIXED shard count (chosen up front, generously — see sizing note
  below), each shard a real physical Postgres table
  (e.g. bookings_102008), assigned to a worker.

  shard_of(event_id) = hash(event_id) mod SHARD_COUNT  <- permanent,
  never recalculated when node count changes (avoids the reshuffle
  problem naive hash-mod-node-count would cause — see Mechanism below)

  Query: app connects to coordinator only, never to workers directly.
  SELECT ... WHERE event_id = 'x' -> coordinator computes the shard,
  looks up which worker holds it, forwards the query there — a
  single-shard query, fast. Queries omitting event_id fan out to every
  worker (scatter-gather) — avoided on the hot path by design.
```

**Fixed shard count, not a consistent-hash ring** — deliberate, same
reasoning Redis Cluster's own creators used (see ADR-004): a ring's unit
of movement is one independent key-value item, trivial to split at an
arbitrary boundary; Citus's unit is a whole relational table (schema,
indexes, foreign keys) — splitting *that* at an arbitrary ring boundary
means rebuilding indexes and constraints on the fly, a much harder
problem than copying independent items (which is why DynamoDB/Cassandra
can use a pure ring and Citus/Redis both chose fixed-count instead).

**Shard count sizing**: pick the fixed count based on the *maximum future*
worker-node count you could realistically need, not today's node count —
each node needs at least 1 shard to be useful, so shard count is a hard
ceiling on useful node count. Going bigger than currently needed costs
almost nothing (extra shards just sit multiple-per-node for now); running
out later is expensive (Citus's `citus_shard_split` exists but is a heavy
operation, closer to rebuilding the table's layout than the lightweight
reassignment below).

## Resharding process (resolved)

```
1. Add Worker N — by itself, moves nothing. Shards only reassign when
   explicitly triggered (citus_rebalance_start()) — same deliberate,
   planner-triggered pattern as Redis's --cluster reshard, not automatic.

2. citus_rebalance_start() picks existing shards to move onto the new
   worker, physically copies each shard's REAL table data (via Postgres's
   own logical replication/COPY, live, no downtime) from the old worker's
   disk to the new worker's disk.

3. Only once the copy is confirmed complete does the coordinator's
   metadata (pg_dist_placement) update to point queries at the new
   worker. Same physical-copy-then-update-metadata order as Redis's
   MIGRATE-then-SETSLOT — a shard is never "assigned" to a node that
   doesn't actually have the data yet.
```

**Coordinator is a single point of failure** — everything routes through
it first; if it dies, the cluster is unreachable even though workers are
fine.

### Coordinator HA (resolved)

The coordinator only holds metadata (`pg_dist_shard`, `pg_dist_placement`)
— small, not the heavy data — so it's the easier half of the HA problem,
same shape as any single Postgres instance needing a standby:

```
Coordinator-Primary --(Postgres streaming replication)--> Coordinator-Standby
Clients connect via a stable address (virtual IP/DNS), not the primary's
literal host, so failover means repointing one address, not reconfiguring
every service.
Failover managed by Patroni — the same tool already planned for worker-
node HA, reused rather than adding a separate mechanism just for this.
```

**Worker node failure** is a separate concern from resharding — handled
by Postgres's own replication (e.g. Patroni for automatic standby
promotion), not by Citus's sharding logic itself. Same requirement as
Redis Cluster needing a replica per shard for failover to work at all.

### Shard count (resolved)

**1024 shards per regional cluster.** Reasoning: even a very large single
region realistically tops out around 100-200 worker nodes in a genuinely
massive future scenario; 1024 gives generous headroom over that — same
"far more buckets than you'll ever need nodes" philosophy as Redis's
16384 slots. Matches common real-world Citus guidance for large
deployments (a large power-of-2, not a number sized to current node
count).

### Cross-shard foreign keys / reference tables (resolved)

Citus's built-in answer: **reference tables**. Small, rarely-changing
tables (`events`, `venues`) get fully replicated to *every* worker node
via `create_reference_table('events')` — not sharded at all. Since every
worker holds a complete copy, a sharded `bookings` row's foreign key to
`events` is checked locally on whichever worker holds that booking, no
cross-shard lookup needed. `events`/`venues` fit this well: small
relative to `bookings`, read far more often than written.

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

**Harder:** operational complexity of running Citus (coordinator + worker
topology, two HA mechanisms via Patroni to operate); any future
cross-event feature that *does* need to join across events (not currently
identified) would be expensive.

**Amendment: PgBouncer added in front of the coordinator (ADR-024).**
This topology's coordinator→worker fan-out means a single app-side
connection can multiply into several real connections at the worker
layer for a cross-shard query — on top of raw app-side connection count
already scaling with autoscaled instances. Never addressed here;
[[ADR-024-pgbouncer-connection-pooling]] adds transaction-mode pooling in
front of the coordinator, one PgBouncer deployment per region, matching
this ADR's regional boundary.

# Revisit When

- This project's actual load never approaches global scale — this ADR is
  explicitly documentation of "how it would be done," not a claim that the
  project needs it. [[ADR-001-microservices-vs-modular-monolith]]'s
  one-instance-per-service design remains what's actually built unless the
  project's scope changes.
- If a real cross-event query need emerges that analytics-service can't
  serve async (e.g. a synchronous cross-event uniqueness constraint) —
  would need to revisit the "no cross-shard queries" assumption.

### Patroni failover mechanism (resolved)

Full internal mechanics (Raft consensus, majority-write, leases/watch/
compare-and-swap, lagging-node catch-up, odd-count reasoning nuance
between etcd and Redis) documented separately in
[[etcd-raft-consensus]] — summary below.

Unlike Redis Cluster's mechanism (masters directly vote on failure via
gossip), Patroni uses a **separate, small external consensus store —
`etcd`** — for leader election. Applies identically to the coordinator
and to every worker's HA pair; each gets its own independent leader key
in the same shared etcd cluster:

```
etcd cluster (its own separate deployment, typically 3 nodes — needs an
odd number for its own internal majority consensus, same reason Redis
Cluster needs 3+ masters)

Each Postgres HA pair (coordinator's primary/standby, each worker's
primary/standby) gets its OWN leader key in etcd.

1. Whichever instance is currently primary continuously renews a
   short-lived lease on its key (a heartbeat).
2. If it dies or is network-partitioned away from etcd, it stops
   renewing -> the lease expires.
3. Any standby can now claim that key; whoever claims it first is
   promoted to primary.
4. Split-brain prevention: if the OLD primary is still alive but just
   cut off from etcd, IT notices it can't renew its own lease and
   self-fences (demotes itself, refuses writes) — it doesn't wait to be
   told. There is never a moment where two nodes both hold a valid lease
   for the same key, because etcd only ever grants one.
```

`etcd` itself never touches Postgres directly — it only holds the lock
state. **Patroni** is the agent running alongside every Postgres instance
that actually watches/renews the lease and executes the real
promote/demote action locally.

**Failover timing (lease duration) is a real number that needs
load-testing/real-network data**, same category as ADR-004's 75%
threshold — too short and normal network blips trigger false failovers;
too long and a genuine failure stays unrecovered longer. Not guessable in
the abstract; deferred to real-world tuning once deployed.

**Correction to an earlier answer**: this project was earlier told it
doesn't need Zookeeper (Kafka runs KRaft mode, no coordinator needed
there). That conclusion still holds for Kafka specifically — but it
missed that Patroni-managed Postgres HA genuinely needs a small
distributed consensus store of its own. `etcd` (a lighter-weight, more
modern tool in the same family as Zookeeper — Raft-based consensus, same
underlying idea) fills that role here. Scoped correction: needed for
Postgres/Patroni leader election only, not for Kafka or message routing.

## Open Questions

- Which tables beyond `events`/`venues` should be reference tables vs.
  sharded — needs a full schema review once services are actually being
  built, not decided in the abstract.
- Exact etcd lease-duration/failover-timing value — mechanism is
  designed, the real number needs load-testing against actual network
  conditions once deployed.
- Within-region Patroni failover currently relies on Patroni's default
  behavior for two things never explicitly decided here: (1) whether
  promotion checks replica lag first and how much data-loss risk is
  acceptable if the replica was behind when the primary died (Patroni
  supports a `maximum_lag_on_failover` guard — value not set); (2) the
  exact fencing mechanism that stops a revived old primary from
  accepting writes again (split-brain) — implicit in etcd's lease-based
  leadership (the old primary self-demotes once its lease lapses) but
  never stated as a deliberate design choice, unlike ADR-016's explicit,
  reasoned human-gating of *cross-region* promotion for the same
  split-brain risk. Should be stated with the same rigor here.
