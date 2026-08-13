---
title: ADR-027 Schema Migration Strategy
type: decision
sources: []
related: [[ADR-005-postgres-sharding]], [[ADR-007-kafka-event-schema]], [[ADR-013-gdpr-crypto-shredding]], [[ADR-012-jwt-lifecycle]], [[ADR-008-testing-strategy]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

Flyway appears exactly twice in the vault — once in passing in
`event-service.md`'s prose, once inside an [[ADR-008-testing-strategy]]
test assertion — never as an actual decided migration policy. Nothing
addresses how DDL runs against [[ADR-005-postgres-sharding]]'s
*distributed* tables, how a schema change coordinates with app-version
rollout, or — the sharpest risk — that a schema change touching a PII
column simultaneously changes the WAL shape Debezium captures, the
outbox payload, and the Avro schema Confluent Schema Registry enforces.
[[ADR-007-kafka-event-schema]] already declares PII fields must be Avro
`bytes` "from schema v1... blocking, cannot be changed after a topic
goes live" — but nothing in the migration pipeline actually *enforces*
that a future migration can't violate it.

# Requirements / Constraints

- Every schema change must remain safe while old and new app versions
  run simultaneously during a rolling deploy — no hard schema/app
  coupling moment.
- DDL against a Citus-distributed table must be handled deliberately,
  not assumed to "just work" identically to a single-node Postgres.
- Any migration touching a column already governed by
  [[ADR-007-kafka-event-schema]]'s PII-as-`bytes` rule must be blocked
  in CI if it would violate that constraint — enforcement, not developer
  memory.
- Must define who runs migrations and in what order relative to app
  deploys.

# Options Considered

## A — Flyway, forward-only versioned migrations, run manually before deploy

Pros: simplest, standard. Cons: doesn't itself solve the
backward-compatibility-during-rollout problem, says nothing about Citus
DDL specifics, and has no connection at all to the PII/Avro lockstep
requirement — the exact gap that matters most here.

## B — Flyway + mandatory expand/contract discipline + a CI gate cross-checking PII columns against ADR-007

Pros: reuses the same zero-downtime shape this project already
committed to elsewhere ([[ADR-012-jwt-lifecycle]]'s 4-phase key
rotation is the same pattern applied to a different problem);
enforcement lives in CI, not in hoping every developer remembers a rule
from a different ADR.

## C — A Citus-aware migration tool with native distributed-table support

Pros: might smooth over some DDL propagation edge cases automatically.
Cons: adds a second migration tool alongside Flyway (already the
implicitly-assumed default) for marginal benefit — Citus already
propagates most standard DDL from coordinator to workers transparently;
the harder problems (expand/contract discipline, PII/Avro enforcement)
aren't solved by tooling choice, they need explicit process regardless.

# Decision

**Option B.** Flyway (formalizing the tool already assumed, not
introducing a second one), one migration set per service repo, under an
explicit expand/contract policy plus a CI-enforced PII/Avro gate.

## Citus DDL handling

```
Standard operations (ADD COLUMN, DROP COLUMN, most index changes):
  Citus propagates DDL from coordinator to every worker automatically —
  rely on this default behavior, no special handling needed.

Heavy/blocking operations (changing a column's type on a large
  distributed table, adding a NOT NULL constraint without a default):
  must be scheduled as an explicit maintenance-window operation, run
  deliberately, never silently executed mid-traffic by a normal deploy
  pipeline step.
```

## Expand/contract — mandatory for any breaking-shaped change

Any rename, type change, drop, or tightened constraint goes through five
phases, not one migration:

```
1. Expand: add the new column/table, nullable, no app change yet.
2. Deploy an app version that writes BOTH old and new shape.
3. Backfill existing rows into the new shape.
4. Deploy an app version that reads/writes ONLY the new shape.
5. Contract: drop the old column/constraint in a LATER migration, only
   once phase 4 has been live long enough that rolling back to the old
   app version is no longer a real option.
```

## PII/Avro CI gate — the enforcement this ADR exists to add

```
Any migration touching a column already listed in ADR-013's "What is
  encrypted" table (name, email, phone, DOB, IP, free-text notes,
  ticket-holder name) fails CI unless it is strictly additive and
  type-compatible with the existing Avro `bytes` contract.

Concretely: a small manifest of PII columns per service (derived
  directly from ADR-013's table), checked against every migration's DDL
  in CI before merge — the same "producer's own pipeline catches the
  violation before merge" shape as ADR-023's `buf breaking` gate and
  ADR-008's Spring Cloud Contract, applied to schema/PII instead of API
  contracts.
```

# Why

Prevents the exact live-topic PII `string`→`bytes` trap
[[ADR-007-kafka-event-schema]] already flagged as catastrophic, by tying
enforcement into the migration pipeline itself rather than trusting
developer memory across ADRs written months apart. Expand/contract
matches the zero-downtime discipline this project already committed to
for JWT key rotation — reused, not invented fresh.

# Consequences

**Easier:** schema changes become safe-by-construction against the
PII/Avro trap instead of relying on someone remembering a rule from a
different document; deploys roll forward and back without a hard
schema/app coupling moment.

**Harder:** every breaking-shaped schema change takes five real phases/
deploys instead of one — genuine velocity cost, stated plainly rather
than hidden; the PII-column CI check needs an actual implementation
(manifest file + lint step), not yet built.

# Revisit When

- If expand/contract's five-phase overhead becomes a real bottleneck for
  solo-developer iteration speed pre-launch — could relax for
  tables/services not yet carrying real traffic, tighten again once
  live.

## Open Questions

- Exact CI tool/implementation for the PII-column cross-check — not
  decided, needs real implementation-time design.
- Whether Citus's default DDL-propagation timing/locking behavior needs
  explicit measurement before trusting it at scale — flagged, not
  measured.
