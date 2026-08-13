---
title: ADR-031 Idempotent Kafka Consumer Pattern
type: decision
sources: []
related: [[ADR-007-kafka-event-schema]], [[ADR-025-idempotency-key-policy]], [[cross-cutting-concerns]], [[notification-service]], [[ticket-service]], [[search-service]], [[analytics-service]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: Accepted

# Context

[[ADR-007-kafka-event-schema]] states the requirement plainly — `eventId`
exists in the envelope "for idempotent dedup... must be a no-op the second
time" — but never designs the actual mechanism. Every consumer service
(`notification-service`, `ticket-service`, `search-service`,
`analytics-service`) inherits this as an unstated assumption.
`notification-service.md` names the gap explicitly in its own Open
Questions: "Consumer idempotency (avoid duplicate notification on
redelivered Kafka message) — not decided." At-least-once delivery is
already decided (ADR-007); this ADR designs what a consumer must do with
that guarantee to avoid duplicate side effects (a second welcome email,
a second push notification).

# Requirements / Constraints

- Must not process the same `eventId` twice in a way that repeats an
  external side effect (email send, push notification, search index
  write) — a real user-visible bug, not cosmetic.
- Must not silently and permanently drop a message that never actually
  completed — worse than a duplicate, since nothing downstream ever
  detects it.
- Must use manual offset commit, not Kafka client default auto-commit —
  auto-commit acknowledges a message before work is confirmed done,
  which loses the message outright on a mid-processing crash (no
  redelivery at all).
- Must not require a new piece of infrastructure — every consuming
  service already has its own Postgres, per [[ADR-005-postgres-sharding]]
  /[[ADR-018-user-identity-sharding-residency]]'s per-service-database
  discipline.
- Must be honest about what it can and cannot guarantee — Kafka's
  exactly-once semantics only cover Kafka-to-Kafka pipelines
  (consume-transform-produce); it has no visibility into an external
  side effect like an SMTP/SES call, so no purely Kafka-side or
  app-side-only mechanism can close that gap completely.

# Options Considered

## A — Two-phase: commit an `IN_PROGRESS` row before the external call, commit `DONE` after

Pros: leaves a durable trace of an attempt even across a crash between the
external call and marking done. Cons: does not actually close the race it
targets — a crash between "external call returns success" and "commit
DONE" leaves the row at `IN_PROGRESS`, and a naive retry-on-`IN_PROGRESS`
policy re-sends anyway. Two extra states and two commits to reason about,
for a race it does not fully close. Rejected on inspection during design
review — added complexity without the safety guarantee it promised.

## B — Single transaction: `INSERT event_id` (unique PK) + do the external work, one commit

Pros: simplest correct baseline. A crash before commit rolls back
everything Postgres controls — the dedup row never exists, safe to
retry. Duplicate redelivery after a successful commit hits a PK
collision on `INSERT`, skips cleanly. Cons: does not close the narrow
race where the external call itself succeeds but the process crashes
before the transaction commits — the row rolls back, the side effect
already happened, and a retry repeats it. This gap is real in Option A
too; Option B does not pretend otherwise.

## C — Provider-side idempotency key only, no local table

Pros: if the downstream provider (email/push/SMS API) supports an
idempotency key, this closes the exact gap Option A/B's app-side table
cannot. Cons: not universal — depends on provider support, and does
nothing for providers that lack it; not a substitute for the dedup
table, since the table is also what makes retries cheap (skip without
even attempting the external call) rather than relying on the provider
to eat every duplicate attempt.

# Decision

**Option B, layered with Option C wherever the provider supports it.**
Reject Option A's two-phase status design — it adds real complexity for
a race window it does not actually close.

## Schema (per consuming service)

```sql
CREATE TABLE processed_events (
  event_id     UUID PRIMARY KEY,
  processed_at TIMESTAMPTZ DEFAULT now()
);
```

## Consumer flow

```
1. Read message from Kafka. Do NOT auto-commit offset on read.
2. BEGIN transaction
3.   INSERT INTO processed_events (event_id) VALUES (eventId)
     -- PK collision if this eventId was already committed -> atomic,
     -- no separate SELECT-then-INSERT race (same trick as ADR-002's
     -- row-lock: let the constraint do the concurrency control).
     -- On collision: ROLLBACK, skip the work, commit the Kafka offset,
     -- done — this is the real duplicate-redelivery case, closed.
4.   Do the external work (send email/push, call provider's send API
     WITH an idempotency key if the provider supports one).
5. COMMIT (only if step 4 succeeded)
6. Commit the Kafka offset only after step 5's commit succeeds.

Work fails (step 4 throws) -> ROLLBACK (row never persisted) -> retry
  with backoff, same shape as ADR-007's DLQ retry loop -> after N
  attempts, publish to <topic>.dlq, commit the offset anyway (per
  ADR-007, unblocks the partition) -> no dangling processed_events row,
  since nothing in this failed attempt ever committed. A later manual
  DLQ replay is therefore always treated as a fresh attempt, not a
  duplicate — same correctness property Option A was reaching for,
  achieved here as a side effect of the transaction boundary instead of
  a status field.
```

## The gap this does not close, stated plainly

If the process crashes in the exact window after the external call
returns success but before step 5's commit, the transaction rolls back,
the row never persists, and a retry repeats the external call — a real
duplicate. Neither this design nor Option A's two-phase version closes
this window from the app side alone; only a provider-side idempotency
key can, and only where the provider supports one. This is stated as an
accepted residual risk, not solved silently.

# Why

Reuses the same atomic-constraint-as-concurrency-control trick already
trusted in [[ADR-002-seat-locking-strategy]] instead of inventing a new
mechanism. Rejecting Option A explicitly (rather than defaulting to "more
state = safer") matches this project's practice of naming a design that
looked more robust but wasn't, once traced through — the same kind of
correction ADR-023 made explicit about its own load-balancing model.
Layering Option C where available follows the same "protocol-level retry
tracking and business-side-effect tracking are two different problems"
reasoning already applied in [[ADR-025-idempotency-key-policy]].

# Consequences

**Easier:** one small, reused table per consumer service instead of a
stateful status machine; duplicate redelivery after a real crash is
handled correctly and cheaply (skip before even attempting the external
call); DLQ replay composes correctly with dedup, with no separate case to
handle.

**Harder:** the crash-during-external-call race is explicitly not fully
closed by this design alone — services sending to a provider without
idempotency-key support carry this residual risk knowingly, not by
oversight.

# Revisit When

- If a specific provider integration turns out to lack any idempotency
  mechanism AND duplicate sends there prove costly enough (e.g. paid SMS)
  to justify a provider-side reconciliation job — not designed here,
  would be provider-specific.

## Open Questions

- Retention/cleanup policy for `processed_events` rows — bounded by
  Kafka's own topic retention (no need to keep a dedup row longer than
  Kafka could possibly redeliver), exact cron/TTL not decided.
- Whether to route the dedup `INSERT` and the external work through the
  same DB transaction when the external work itself needs to write other
  rows (e.g. `ticket-service` marking a ticket issued) — likely combines
  naturally in that case; not verified against every consumer yet.
