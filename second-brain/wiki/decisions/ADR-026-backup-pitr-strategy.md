---
title: ADR-026 Backup and Point-in-Time Recovery Strategy
type: decision
sources: []
related: [[ADR-005-postgres-sharding]], [[ADR-013-gdpr-crypto-shredding]], [[ADR-016-multi-region-cdn]], [[ADR-002-seat-locking-strategy]], [[ADR-024-pgbouncer-connection-pooling]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[ADR-013-gdpr-crypto-shredding]] *assumes* a backup regime already
exists — it states `subject_key` "lives in a separate database with
shorter backup retention" and that "any restored backup gets
erasure-replay applied as part of the documented restore runbook" — but
no ADR ever decided a backup schedule, retention window, or restore
procedure. [[ADR-016-multi-region-cdn]]'s RPO discussion covers regional
*failover* (a live standby), not backup/restore from a point in time —
different failure mode. Under [[ADR-005-postgres-sharding]]'s Citus
topology, this is sharper than a single-Postgres gap: coordinator and
every worker node must restore to a **mutually consistent** point, or a
partial restore can resurrect a seat as available on one shard while it
shows purchased on another — a real double-sell vector, not
hypothetical.

# Requirements / Constraints

- Coordinator and all worker nodes must restore to the same consistent
  point-in-time — never restored independently.
- Must integrate with [[ADR-013-gdpr-crypto-shredding]]'s stated
  requirements directly: `subject_key` needs its own, shorter retention;
  any restore must re-apply erasure events that happened after the
  backup's point-in-time but before "now."
- Must state plainly what does *not* need backing up (Redis, per
  [[ADR-002-seat-locking-strategy]]'s design) rather than leaving it
  ambiguous.
- Must be practical at this project's actual scale/budget — not an
  enterprise-grade solution bought for its own sake.

# Options Considered

## A — Manual `pg_dump` snapshots, cron-scheduled

Pros: simplest, no extra tooling. Cons: not point-in-time — only as
fresh as the last dump, no recovery of writes between dumps; coordinating
a consistent dump across coordinator + N worker nodes by hand is
error-prone exactly where correctness matters most.

## B — WAL archiving + base backups (pgBackRest), coordinated across the Citus cluster

Pros: true point-in-time recovery, minimizes the data-loss window
between backups, standard tooling built for exactly this. Cons: real
operational surface — coordinating a consistent restore target across
coordinator and every worker takes an explicit procedure, not a single
command.

## C — Rely entirely on a managed cloud provider's automated backups (e.g. AWS RDS)

Pros: offloads the hard cross-node consistency problem to the provider.
Cons: only available once actually migrated to managed Postgres —
`infra.md` states "eventually AWS," not decided yet for the compute
layer specifically ([[ADR-019-cdn-vendor-choice]] only locked the
CDN/edge vendor); Compose/local development still needs its own answer
in the meantime.

# Decision

**Amended 2026-08-13 — Option C (cloud volume-snapshot, e.g. AWS EBS
snapshot + WAL) chosen as the primary mechanism**, not deferred to "once
migrated." Reasoning matches this project's established pattern of
picking the real-infrastructure option for its learning value over the
simpler local one (same call as ADR-004's Redis Cluster over single
instance, ADR-005's Citus over no sharding) — block-snapshot + WAL-replay
is the mechanism a production system on AWS actually uses, so it's worth
learning directly rather than learning pgBackRest first and swapping
later. Original Option B (pgBackRest) reasoning below is kept as the
**Compose-local dev fallback**, not superseded — the two now serve
different environments on purpose, same "local diverges from prod,
documented explicitly" split already used in [[ADR-024-pgbouncer-connection-pooling]]
and [[ADR-010-secrets-management]].

```
Local dev (Docker Compose): pgBackRest, exactly as designed below —
  EBS snapshots don't exist outside AWS, Compose needs its own real
  mechanism, not a mock. This is what actually runs day to day.

Production (AWS target, per infra.md): EBS snapshot of each Postgres
  node's volume + continuous WAL archiving (same WAL mechanism either
  way — Postgres doesn't care which layer copied its files). Snapshot
  is storage-layer, taken while Postgres is live and possibly mid-write
  (the copy can be "torn"); WAL replay from the snapshot's start LSN on
  restore is what brings it to a real consistent point — same fix-up
  Postgres's own crash recovery already does after any unclean
  shutdown, just reused here for backup/restore instead of a real crash.
  RDS's own automated-backup feature does exactly this under the hood;
  self-managing it directly (rather than paying for RDS) is the more
  hands-on, more-to-learn version of the same mechanism, consistent with
  this project's "build it like the real thing" priority (`infra.md`'s
  AWS target is EC2 + self-managed Postgres/Citus, not RDS, for the same
  reason ADR-019 split CDN vendor from compute vendor).
```

Everything below (retention numbers, multi-node consistent-restore
procedure, GDPR erasure-replay requirement) applies identically to both
— the snapshot vs file-copy choice only changes HOW the base copy is
taken, not the WAL-replay/restore-consistency logic built on top of it.

## Mechanism

```
pgBackRest (or equivalent), per region's Citus cluster:
  - Full base backup: daily, on coordinator AND every worker node.
  - Continuous WAL archiving between base backups.
  - Retention: 30 days rolling for the main data cluster — starting
    default, needs real retention-cost tradeoff data.

subject_key table (ADR-013) — separate, SHORTER retention:
  - Already lives in a separate database per ADR-013's own design.
  - Backup retention: 7 days — starting default, deliberately shorter
    than the main cluster's, matching ADR-013's stated intent.
```

## Consistent multi-node restore procedure (the actual gap this closes)

```
1. Choose a single target timestamp/LSN.
2. Restore coordinator AND every worker node to that SAME target point
   using pgBackRest's PITR restore, per node.
3. Validate Citus shard metadata consistency across all restored nodes
   BEFORE bringing the cluster back online for writes — an explicit
   pre-flight check, not assumed to just work.
4. Replay EVERY outbox topic from [[ADR-007-kafka-event-schema]] —
   not just erasure — for anything timestamped AFTER the restored point
   but before now: booking.confirmed, payment.succeeded, ticket.issued,
   event.updated, and [[ADR-013-gdpr-crypto-shredding]]'s
   user.erasure.finalized. Widened 2026-08-13 from erasure-only: the
   restore gap loses real bookings/payments/tickets the exact same way
   it loses erasure events — Stripe already charged a card, a user
   already has a ticket in-app, and the restored DB has no record of
   either unless this replay runs. [[ADR-016-multi-region-cdn]] already
   states this same "post-promotion recovery is outbox-replay +
   provider reconciliation" principle for regional failover; this is
   that principle carried into the backup-restore case, not a new one.
   REQUIRES Kafka's topic retention to exceed the backup's maximum age —
   not cross-checked against ADR-007's retention settings, flagged in
   Open Questions below.
5. Only then resume normal traffic.
```

## Honest limit on outbox-replay: it cannot recover what Debezium never read

**Stated plainly, not implied as a complete fix**: outbox-replay only
recovers an event if Debezium had already read that WAL record and
published it to Kafka *before* the crash/corruption hit. Postgres is
written first in this project's outbox design
([[ADR-007-kafka-event-schema]]) — Kafka is downstream of Postgres, not
the other way around. If Debezium's replication lag (normally
near-real-time, low seconds) meant a handful of the most recent WAL
records hadn't reached Kafka yet at the exact moment of failure, that
data is genuinely unrecoverable — absent from Postgres AND from Kafka,
by construction, not by a flaw in this procedure. This is the tradeoff of
using the outbox pattern (Postgres-first, Kafka-second) instead of a
broker-first architecture where every write's durability boundary is the
broker itself — accepted here because restructuring every service to
write Kafka before Postgres is a materially larger architectural change
than this project takes on, not an oversight.

## Surgical single-object recovery (accidental DROP/bad migration, DB stays up)

**A different failure shape than the full-cluster-down case above** — the
cluster never actually goes offline; a bad migration or an accidental
`DROP TABLE`/`DELETE` destroys one object while everything else keeps
serving traffic normally.

```
1. Restore a SEPARATE scratch instance from the most recent snapshot,
   replaying WAL only up to just BEFORE the destructive statement — not
   into production, a throwaway clone.
2. Pull the lost rows/table out of that scratch clone.
3. Merge them back into the still-running production database.
4. Discard the scratch clone.
```

Zero downtime, zero data loss for anything outside the destroyed object
— production never stopped accepting writes to everything else, so
there is no "gap window" to replay from Kafka at all here; this is a
strictly easier and more precise recovery than the full-cluster restore
procedure above, and should be used instead of it whenever the failure
is actually scoped to one object, not the whole cluster.

## What is explicitly NOT backed up

```
Redis (seat holds, queue state): no backup. Fully consistent with
  ADR-002's own design — Redis is never authoritative, a lost/wiped
  Redis degrades to "fail open, slower," never "wrong." Backing it up
  would imply it holds truth, which it deliberately never does.

Kafka: not a backup problem in the traditional sense — already covered
  by topic retention + the outbox-replay design (ADR-007). Its
  retention window matters here only because of step 4 above.
```

# Why

Turns the Citus double-sell restore risk into an explicit, ordered
procedure instead of an unstated hope, and gives
[[ADR-013-gdpr-crypto-shredding]]'s two backup-dependent assumptions (
shorter `subject_key` retention, erasure-replay on restore) a real
mechanism to actually rely on instead of a forward reference to an ADR
that didn't exist yet.

# Consequences

**Easier:** GDPR erasure survives disaster recovery correctly — a
restored backup can't silently un-shred someone; the Citus cluster has
an actual multi-node consistency procedure for restore instead of ad hoc
per-node dumps.

**Harder:** real new operational surface (pgBackRest or equivalent, per
region, per cluster); this only provides genuine protection if restore
drills are actually run periodically — a documented-but-never-tested
runbook is not a safety net.

# Revisit When

- Once the eventual AWS migration ([[ADR-019-cdn-vendor-choice]],
  `infra.md`) happens — swap to the managed provider's automated
  backup/PITR feature (Option C), same procedure, less self-managed
  burden.

## Open Questions

- Retention numbers (30 days main / 7 days `subject_key`) — starting
  defaults, need real cost/risk tradeoff data.
- Kafka topic retention vs. this ADR's backup retention window —
  never cross-checked; step 4's erasure-replay requires Kafka retention
  to exceed backup age, not currently verified against ADR-007's
  settings.
- Restore-drill cadence — not decided; a backup strategy nobody has
  tested restoring from is unverified, not proven.
