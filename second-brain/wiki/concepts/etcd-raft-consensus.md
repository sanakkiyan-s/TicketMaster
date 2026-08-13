---
title: etcd and Raft Consensus (Postgres/Patroni HA mechanism)
type: concept
sources: []
related: [[ADR-005-postgres-sharding]], [[ADR-004-redis-cluster-sharding]]
created: 2026-08-06
last-updated: 2026-08-06
---

Supporting mechanics behind [[ADR-005-postgres-sharding]]'s Patroni
failover design — how etcd itself achieves the strong consistency Patroni
relies on for leader election.

## What etcd is

A distributed, strongly-consistent key-value store. Simple operations
(`get`/`put`/`watch`) with a strong guarantee: once a write succeeds, it's
durable and agreed-upon, not something that can silently vanish or
conflict with another copy.

## How it achieves that — Raft consensus

```
etcd runs as its own small cluster (e.g. 3 nodes: E1, E2, E3)

1. The nodes elect ONE leader among themselves (majority vote — need 2
   of 3 to agree)
2. ALL writes route through that leader
3. Leader does NOT confirm success to the client until a MAJORITY of
   nodes (including itself) have actually stored the write
4. Only after majority confirms -> "success" returned
5. If the leader dies, remaining nodes elect a new one the same way —
   etcd handles its own failover using the identical mechanism it
   provides to Patroni
```

**This is synchronous, majority-confirmed replication — the opposite of
Redis's default.** Redis masters reply "OK" immediately and stream to
replicas in the background, without waiting (see [[ADR-004-redis-cluster-sharding]]
— this is exactly why a Redis master dying can lose a just-confirmed
write, and why Cluster/Sentinel failover isn't "zero data loss"). etcd
pays the extra latency because its entire job is being a trustworthy
record of "who's the leader" — it has no backstop the way Redis has
Postgres; a lost etcd write could directly cause split-brain. Redis
chose speed for its job (fast-path gate, Postgres is the real backstop);
etcd chose safety for its job (it *is* the backstop). Redis does have an
opt-in `WAIT` command for stronger per-write guarantees, not the default
and not as rigorous as full Raft.

## Three features Patroni actually uses

- **Leases (TTL)**: a key expires unless continuously renewed — the
  heartbeat mechanism behind "primary keeps renewing, stops if it dies."
- **Watch**: subscribe to a key, get notified the instant it changes — no
  polling delay for standbys noticing a lease expired.
- **Compare-and-swap**: "set this key only if it's currently
  empty/expired" — processed atomically through etcd's leader, so if two
  standbys race to claim leadership at once, only one compare-and-swap
  can succeed.

## What happens to a node that misses a write (lagging, not dead)

The leader tracks how far behind each follower is and continuously
streams whatever it's missing — normal ongoing behavior, not a special
recovery procedure.

**Critical safety rule**: a node can only win a leader election if its own
log is at least as up-to-date as a majority of the cluster. A lagging
node is structurally ineligible to become leader until it catches up —
this guarantees no confirmed write (majority-agreed) can ever be lost by
a leadership change.

If a node stays down permanently (not just lagging), the cluster keeps
working as long as the remaining nodes still form a majority of the
original count. The dead node is excluded until manually replaced, at
which point it starts fresh and fully syncs before being trusted again.

## Why odd node counts — etcd vs. Redis, a real distinction

Both use the same quorum math (majority = more than half; even counts can
tie). But the *reason* to pick odd differs:

- **etcd**: its nodes exist for ONE job — voting. No other requirement
  competes for that number, so always pick odd (3, 5) — pure upside, no
  tradeoff.
- **Redis masters**: their PRIMARY job is holding sharded data; serving as
  the failover-vote body is secondary. Master count is mainly driven by
  data/capacity needs — if that lands on an even number (e.g. 4), you
  don't need to add a 5th purely for voting neatness. The accepted
  tradeoff: a worst-case even split (2-2) means neither side has majority
  until the partition heals — real but rare, not a broken system.

```
1 node:  majority=1 -> tolerates 0 failures
2 nodes: majority=2 -> tolerates 0 failures (worse than 1 — needs BOTH up)
3 nodes: majority=2 -> tolerates 1 failure
4 nodes: majority=3 -> tolerates 1 failure (SAME as 3 — wasted node)
5 nodes: majority=3 -> tolerates 2 failures
6 nodes: majority=4 -> tolerates 2 failures (SAME as 5 — wasted node)
```

Every even count gives identical fault tolerance to the odd count just
below it, for one extra node's cost — the general reason odd is preferred
whenever the count is a free choice.
