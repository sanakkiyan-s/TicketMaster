---
title: ADR-020 Payment Event Ledger and Webhook Processing
type: decision
sources: []
related: [[payment-service]], [[booking-service]], [[ADR-006-saga-booking-orchestration]], [[ADR-007-kafka-event-schema]], [[ADR-011-pci-scope-containment]], [[ADR-015-observability-stack]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`payment-service.md` names "payment intents, webhook handling,
idempotency, refunds" as responsibilities but never specified an internal
schema. [[ADR-006-saga-booking-orchestration]]'s `bookings` table uses a
single mutable `status` column, overwritten as the saga progresses — a
reasonable design for the *saga's own* step tracking, but never intended
as, and insufficient for, the payment provider's own event history.
[[ADR-015-observability-stack]] already treats "the webhook is the source
of truth" as an established fact without ever specifying the mechanics.

The concrete risk this ADR closes: payment provider webhooks
(Stripe-style) do not arrive in guaranteed order — network jitter,
provider-side retries, and independent delivery paths mean an event like
`charge.captured` can genuinely arrive before `payment_intent.succeeded`.
A single mutable status column, overwritten by whichever event arrives
last, can land in the wrong state depending purely on arrival order, not
what actually happened.

# Requirements / Constraints

- Must never process the same provider webhook twice (Stripe explicitly
  documents at-least-once delivery).
- Must derive the correct current status regardless of webhook arrival
  order.
- Must produce a full audit trail — "what happened to this payment, in
  what order, per event" is a real requirement once a regulator, a
  chargeback, or a finance reconciliation asks.
- Must integrate with the existing Transactional Outbox
  ([[ADR-007-kafka-event-schema]]) rather than inventing a second event-
  publishing mechanism.

# Options Considered

## A — Single mutable status row (current implicit design)

Pros: simple, matches the shape already used for booking-service's own
saga state. Cons: exactly the out-of-order-webhook risk above; destroys
the audit trail (`UPDATE ... SET status = X` overwrites the fact that the
payment was ever in a prior state, and when).

## B — Append-only event ledger, status derived from the full timeline

Every webhook/provider interaction becomes a new row, never an update.
Current status is computed from the ledger, not stored as the only
record of truth.

# Decision

**Option B.**

## Schema

```sql
CREATE TABLE payment_events (
  id                 UUID NOT NULL,
  event_id           UUID NOT NULL,        -- Citus distribution column —
                                            -- see amendment below
  payment_intent_id  TEXT NOT NULL,        -- Stripe's pi_xxx, groups
                                            -- all events for one payment
  event_type         TEXT NOT NULL,        -- INTENT_CREATED | AUTHORIZED
                                            -- | CAPTURED | FAILED |
                                            -- REFUNDED | DISPUTED
  provider_event_id  TEXT NOT NULL,        -- Stripe's evt_xxx — the
                                            -- idempotency key for
                                            -- webhook dedup
  occurred_at        TIMESTAMPTZ NOT NULL, -- when the provider says
                                            -- this happened
  received_at        TIMESTAMPTZ DEFAULT now(),
  raw_payload        JSONB NOT NULL,       -- full webhook body, for
                                            -- audit/debugging

  PRIMARY KEY (event_id, id),
  UNIQUE (event_id, provider_event_id)
);

CREATE TABLE payment_intents (
  payment_intent_id  TEXT NOT NULL,        -- Stripe's pi_xxx
  event_id           UUID NOT NULL,        -- Citus distribution column,
                                            -- same family as bookings
  booking_id         UUID NOT NULL,        -- ADR-006's saga row this
                                            -- payment belongs to
  current_status     TEXT,                 -- derived cache, see below —
                                            -- was previously only ever
                                            -- introduced via ALTER TABLE,
                                            -- full definition was missing
  created_at         TIMESTAMPTZ DEFAULT now(),

  PRIMARY KEY (event_id, payment_intent_id)
);
```

**Amendment: `event_id` added as the Citus distribution column for both
tables — was previously undecided.** Neither table had a stated shard
key; `payment_intent_id` isn't part of either of this project's two
distribution-key families ([[ADR-005-postgres-sharding]]'s `event_id`,
[[ADR-018-user-identity-sharding-residency]]'s `user_id`), so both
tables risked landing as un-sharded or arbitrarily keyed. Resolved here:
**`event_id`**, matching [[ADR-006-saga-booking-orchestration]]'s
`bookings` table — every payment originates from exactly one booking, so
colocating on the same shard key keeps the saga's cross-references
(`bookings.payment_intent_id` ↔ `payment_intents.booking_id`)
single-shard, avoiding a cross-shard join on the hot path. Without this,
`provider_event_id UNIQUE` had the same per-shard-only weakness ADR-002
was amended to fix — a duplicate Stripe webhook landing on a different
shard than the original could have bypassed the dedup constraint
entirely.

**Never `UPDATE` this table. Only `INSERT`.** `provider_event_id UNIQUE`
is the idempotency mechanism — a duplicate webhook delivery attempts a
duplicate insert, the unique constraint rejects it, the handler treats
that as an already-processed no-op (same "DB constraint is the real
guarantee" pattern established in [[ADR-002-seat-locking-strategy]]).

## Webhook handler — the concrete mechanics the earlier docs assumed

```
1. Verify the webhook SIGNATURE using the provider's signing secret
   (from Vault, [[ADR-010-secrets-management]]) — reject anything that
   doesn't verify. This is what stops an attacker from POSTing a fake
   "payment succeeded" directly to the endpoint.
2. Extract provider_event_id. Attempt INSERT into payment_events.
   Unique violation -> already processed, return 200 OK, do nothing
   else (Stripe expects 200 to stop retrying; returning an error on
   an already-processed event would cause pointless re-delivery).
3. Insert succeeds -> this is a genuinely new event. Proceed to
   recompute derived status (below) and, if it's a terminal state
   change, write to the ADR-007 outbox in the SAME transaction as the
   payment_events insert.
```

## Deriving "current status" without scanning the whole ledger every read

A pure append-only ledger with no cached status would need to scan every
row for a payment on every read — wasteful for a hot path
(booking-service checks payment status often during the saga). Add a
**derived, recomputed cache column**, explicitly documented as a cache,
not the source of truth:

`payment_intents.current_status` (defined in the schema above, alongside
`event_id`/`booking_id`/`payment_intent_id` — no longer a bolt-on
`ALTER TABLE`, per this ADR's shard-key amendment). `payment_intents` is
the "one row per payment" summary table; `payment_events` is the ledger
— two different tables, two different jobs: summary for fast reads,
ledger for the real history.

`current_status` is recomputed, in the same transaction as every new
`payment_events` insert, by a state-machine function that considers the
**semantic transition graph**, not arrival order:

```
INTENT_CREATED -> AUTHORIZED -> CAPTURED -> (REFUNDED | DISPUTED)
                -> FAILED (from INTENT_CREATED or AUTHORIZED)
```

Concretely: if `CAPTURED` arrives before `AUTHORIZED` (the out-of-order
case this ADR exists to handle), the derive function checks the full set
of events for that `payment_intent_id`, sees `CAPTURED` is a valid later
state regardless of which row was inserted more recently, and sets
`current_status = CAPTURED` — correct outcome, independent of the
INSERT order. If `AUTHORIZED` arrives even later (a genuinely late,
already-superseded event), the derive function recognizes `CAPTURED` is
already a strictly later state in the graph and does **not** regress
`current_status` backward — this is exactly the "look at the full
timeline and decide the correct state" principle, implemented as a
same-transaction recomputation rather than a description in prose.

## Integration with the existing outbox and saga

payment-service publishing `payment.succeeded`/`payment.failed`
([[ADR-007-kafka-event-schema]]'s topics) happens via the **same
Transactional Outbox pattern** already decided — an outbox row is
inserted in the same transaction as the `payment_events` insert and
`current_status` recomputation, whenever that recomputation produces a
new terminal status. No second event-publishing mechanism invented.

booking-service's own saga-state table ([[ADR-006-saga-booking-orchestration]])
remains a **separate, legitimate thing** — it tracks the saga's own step
progression (`HOLD_PLACED` -> `PAYMENT_CHARGED` -> `CONFIRMED`), driven
BY consuming payment-service's `payment.succeeded`/`failed` events, not
a duplicate of payment-service's internal ledger. Two different
questions ("what step is this booking's saga on" vs. "what actually
happened to this payment, per the provider") answered by two different
tables in two different services — not a redundancy.

# Why

An append-only ledger with a same-transaction derived-status recompute
gives correctness under out-of-order delivery (the real risk) while
keeping fast reads (the cache column) — without needing every reader to
understand the full state machine themselves. Reusing the existing
outbox mechanism avoids a second, parallel way of publishing events that
would need its own reliability guarantees built from scratch.

# Consequences

**Easier:** full audit trail exists for any payment, satisfying the
"what happened, when, in what order" requirement without extra work;
out-of-order webhook delivery is handled by design, not by hoping
Stripe delivers in order; idempotent webhook processing is a database
constraint, not application-level bookkeeping.

**Harder:** the state-machine derive function must correctly encode the
full transition graph and its "don't regress on a stale late event"
rule — a real piece of logic to get right and test (this is exactly the
kind of case [[ADR-008-testing-strategy]]'s concurrency/correctness
testing philosophy should cover: inject out-of-order events in a test
and assert `current_status` never regresses).

# Revisit When

- If a payment provider is ever added whose event model doesn't map
  cleanly to this transition graph (e.g. a fundamentally different
  lifecycle) — the graph, not the ledger pattern itself, would need
  revisiting.

## Open Questions

- Full transition-graph edge cases (partial refunds, multiple disputes
  on one payment) — not yet fully enumerated, needs real provider
  documentation review at implementation time.
