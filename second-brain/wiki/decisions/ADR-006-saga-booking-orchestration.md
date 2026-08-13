---
title: ADR-006 Saga Orchestration for Booking-Service Error Handling
type: decision
sources: []
related: [[booking-service]], [[ADR-002-seat-locking-strategy]], [[cross-cutting-concerns]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

`booking-service` coordinates a multi-step flow (hold seat → charge
payment → confirm seat → issue ticket) across three other services. Any
step can fail after a prior step already succeeded — e.g. payment
succeeds but the hold expired before `confirm()` lands (the exact race
identified in [[ADR-002-seat-locking-strategy]]). Previously this was
handled as an ad-hoc special case ("if payment succeeds but hold expired,
refund"). This ADR formalizes it as a Saga so every failure path is
explicit and consistent, not a growing set of special cases.

# Requirements / Constraints

- Every step must be idempotent (safe to retry) — ties directly into the
  idempotency-key requirement in [[cross-cutting-concerns]].
- A `booking-service` crash mid-flow must be resumable from its last
  completed step, not leave a booking in an undefined state.
- Compensations must never silently drop a paid booking — a failure after
  payment succeeded must always resolve to either a confirmed booking or a
  refund, never nothing.

# Options Considered

## Option A — Choreography (each service reacts to events, no central coordinator)

Pros: fully decoupled, no single point of orchestration.

Cons: the booking flow is inherently sequential and synchronous from the
user's perspective (they're waiting on a checkout screen) — choreography
scatters the flow's logic across multiple services' event handlers, harder
to trace end-to-end, harder to explain/debug (poor fit for the project's
stated interview/resume-value goal).

## Option B — Orchestrated Saga (booking-service as the single coordinator)

Pros: one place holds the full flow and its compensations — easy to trace,
reason about, and explain. booking-service already is the natural
coordinator (nothing new to introduce).

Cons: booking-service becomes a more critical single point in the flow
(mitigated — it was already the orchestrator before this ADR, this just
formalizes its failure handling).

# Decision

**Option B — Orchestrated Saga**, steps and compensations:

```
Step 1: hold seat(s)              compensate: release hold(s)
Step 2: charge payment            compensate: refund payment
Step 3: confirm seat (PURCHASED)  compensate: refund payment (if this step itself fails)
```

- Failure at step 2 (card declined): run step 1's compensation (release
  hold). Booking = FAILED. Nothing was ever charged, no refund needed.
- Failure at step 3 (hold expired before confirm — the ADR-002 race): run
  step 2's compensation (refund). Booking = FAILED, user notified. This
  replaces the earlier ad-hoc rule with a named saga compensation step.
- Saga state persisted in `booking-service`'s own table:
  `HOLD_PLACED → PAYMENT_CHARGED → CONFIRMED` (or `FAILED` with which
  compensations ran). On restart after a crash, `booking-service` reads
  its own last-known step and either resumes forward or drives the
  remaining compensations — never silently abandons an in-flight booking.
- Ticket issuance (step 4, async via `TicketIssued`/`BookingConfirmed`
  Kafka consumption by `ticket-service`) is deliberately **not** part of
  the saga's compensating chain — it's a downstream side effect with its
  own retry logic, not something that should unwind a completed,
  paid-for booking if it's slow or briefly fails.

## Saga-state schema (resolved)

```sql
CREATE TABLE bookings (
  booking_id        UUID NOT NULL,
  event_id          UUID NOT NULL,   -- Citus distribution column
  idempotency_key   TEXT NOT NULL,   -- client-supplied, prevents
                                      -- duplicate saga starts on retry
  user_id           UUID NOT NULL,
  session_id        UUID NOT NULL,

  PRIMARY KEY (event_id, booking_id),         -- must include the shard key
  UNIQUE (event_id, idempotency_key),         -- see amendment below
  seat_ids          TEXT[] NOT NULL,

  status            TEXT NOT NULL,  -- PENDING | HOLD_PLACED |
                                     -- PAYMENT_CHARGED | CONFIRMED |
                                     -- COMPENSATING | FAILED

  hold_reference    TEXT,           -- from inventory-service, needed to
                                     -- release/confirm the hold
  payment_intent_id TEXT,           -- from payment-service, needed to refund

  hold_released     BOOLEAN DEFAULT false,   -- compensation tracking
  payment_refunded  BOOLEAN DEFAULT false,   -- compensation tracking

  saga_traceparent  TEXT,           -- W3C trace context of the saga's own
                                     -- span; needed to re-attach the trace
                                     -- when the payment webhook resumes the
                                     -- saga on a different thread/instance
                                     -- (see amendment below)

  failure_reason    TEXT,
  created_at        TIMESTAMPTZ DEFAULT now(),
  updated_at        TIMESTAMPTZ DEFAULT now()
);
```

**Amendment: schema corrected for Citus shard-key colocation.** The
original PK (`booking_id` alone) and `idempotency_key UNIQUE` constraint
omitted `event_id`, this table's Citus distribution column
([[ADR-005-postgres-sharding]]) — the same class of bug ADR-002 was
already amended to fix for the seat-hold unique index. Citus enforces
uniqueness only *within* a shard, so neither constraint was actually a
global guarantee: two different `event_id` shards could each accept the
same `idempotency_key`, silently allowing a retried request to start a
second saga instead of being caught. Fixed above by making `event_id`
part of both the primary key and the uniqueness constraint.

`hold_reference` and `payment_intent_id` are persisted the moment each
step succeeds, *before* moving to the next step — this is what makes
compensation possible: if step 3 fails, `payment_intent_id` is already on
the row, so booking-service knows exactly what to refund.

Resume logic on restart (booking-service reads its own last-known state):

```
status=HOLD_PLACED, no payment_intent_id       -> retry step 2 (charge)
status=PAYMENT_CHARGED, not CONFIRMED          -> retry step 3 (confirm)
status=COMPENSATING                            -> retry whichever of
                                                   hold_released/
                                                   payment_refunded is
                                                   still false
```

## Compensation reliability (resolved)

Compensation calls (release-hold, refund) must themselves be idempotent —
safe to retry without double-refunding. A background job (same shape as
the inventory expiry sweep) scans for bookings stuck in `COMPENSATING`
and retries whichever step hasn't completed:

```
Attempt 1: immediately (within seconds of entering COMPENSATING)
Attempt 2: +5 minutes
Attempt 3: +30 minutes
Still failing after attempt 3 (~35-40 min total) -> escalate to a
  human/ops alert — same heartbeat pattern as ADR-004's
  capacity-planner reliability.
```

**Amendment — the escalation is now a named, concrete alert.** "Escalate
to a human/ops alert" above was a promise with no implementation. It is:

```
Alert:     PaidUserUnresolved                      -- P1, pages 24/7
Condition: count(bookings WHERE status='COMPENSATING'
                          AND payment_refunded=false
                          AND updated_at < now() - 40min) > 0
           for: 0m   (fire instantly, no dwell)
Runbook:   manual refund via the payment provider console, then
           reconcile the booking row.
```

40 min fires immediately after this ADR's own attempt-3 window (~35-40
min) exhausts. It is tied to the retry ladder above — **retune both
together or the alert drifts out of alignment with the thing it watches.**
*Starting default, same category as the ladder itself.*

Companion SLI, the standing invariant: **zero bookings in a state of
"payment captured, neither CONFIRMED nor refunded."** Asserted
continuously in load and chaos test runs, not only alerted on in
production.

Reasoning: most payment-provider transient issues (network blip, brief
rate-limit, momentary outage) resolve within minutes, so a fast first
retry catches those. Faster escalation than a flat 1-hour window,
deliberately — this is money-sensitive and user-trust-sensitive, better
to loop in a human sooner than leave someone in refund limbo. **Still a
starting default, not a final number** — the real tuning requires
observing actual payment-provider failure patterns in production, same
category of gap as ADR-004's 75% threshold. Never leave a stuck
compensation retrying silently forever with no visibility — a booking
stuck `COMPENSATING` means a user paid and got neither a seat nor a
refund, the worst possible outcome.

## Hold extension margin (resolved, amended — see [[ADR-002-seat-locking-strategy]])

```
When payment submission starts:
  if remaining hold time < 3 minutes -> extend held_until by a flat
  5 minutes (covers realistic 3D Secure/bank-verification delays,
  typically seconds to a few minutes)

Hard ceiling: total hold duration, including the extension, never
  exceeds 15 minutes from the ORIGINAL hold time — guarantees the seat
  eventually frees up even if the checkout is abandoned/hung mid-payment,
  regardless of "payment in flight" status.
```

**Amendment**: "renew on page interaction" (mentioned below and
originally in this ADR) is dropped entirely — it caused unbounded
per-interaction DB writes under load. Payment-submission extension above
is now the ONLY renewal checkpoint. Full reasoning in
[[ADR-002-seat-locking-strategy]]'s Hold Renewal Strategy amendment.

If a real payment completes after the hold has already expired past this
ceiling, that's exactly the worked-example race above — handled by
compensation, not a new problem.

## Hold extension on payment submission (added, amended)

Extend the hold specifically the moment payment submission starts (a
deliberate "payment in flight" extension by a safety margin) — shrinks,
but does not eliminate, the race window where a real-world payment
gateway (card network round-trip, 3D Secure) takes long enough for the
hold to expire mid-payment. The compensation path above remains the true
backstop for whatever window remains — this is a mitigation, not a fix.
(No longer "beyond renewing on page interaction" — that renewal path was
dropped, see amendment above; this is now the only pre-confirm
extension point.)

## Worked example — the race this ADR exists to handle

```
User A holds A15, clicks Pay. Payment starts processing.
Hold TTL expires before payment confirms (nobody renewed during the wait).
User B sees A15 available, holds it, pays fast — confirm() succeeds
  (seat status=HELD, held_by=userB, matches) -> A15 now PURCHASED by userB.
User A's slower payment ALSO succeeds. booking-service calls confirm()
  for User A -> REJECTED (seat is PURCHASED, not held by userA anymore).
booking-service (User A's booking) -> COMPENSATING -> refund succeeds
  -> FAILED, user notified: "seat was taken while your payment was
  processing, fully refunded."
```

End state: User B legitimately owns the seat, User A is refunded and
informed — never a double-sale, never a silent loss of money. This is
exactly why `confirm()` checks the seat's actual current state rather
than trusting "payment succeeded" alone — the database's hold state
decides ownership, not the payment outcome.

## Amendment: tracing the saga across the payment-webhook boundary

The saga **suspends** after the charge step and resumes when the provider
webhook arrives — different thread, different HTTP request, possibly a
different `booking-service` instance, minutes later. Naive tracing breaks
here in both available directions:

```
Option A: webhook gets its own unrelated trace
  -> booking is no longer traceable end to end, defeating the whole
     reason this ADR chose orchestration over choreography.

Option B: webhook continues the original trace as a child of the saga
  span -> produces one span lasting minutes-to-hours whose parent was
  already exported with a now-wrong duration.
```

**Decision — Option C**: the webhook handler starts its **own root
trace**, and attaches an OpenTelemetry **span link** back to the saga's
stored span context (`saga_traceparent`, added to the schema above). Both
traces carry `booking.id` and `booking.correlation_id` as span
attributes, so a single query returns both halves even if one was
sampled out.

Same pattern for compensation: each retry attempt (0m/+5m/+30m) is its
own root trace with a span link back to the saga, carrying
`saga.compensation.attempt=1|2|3`. This makes "show every third-attempt
compensation last week" a one-line query — which is precisely the data
this ADR's Open Question needs in order to close.

`saga_traceparent` is persisted for the same reason as `hold_reference`
and `payment_intent_id`: durable context required to resume correctly
after a crash.

# Why

Formalizes error handling booking-service already needed piecemeal
(idempotent retries, the payment-race refund) into one consistent,
inspectable pattern. Orchestration fits this flow specifically because
it's synchronous from the user's point of view — a real-time checkout,
not a background process — so a single traceable coordinator is a better
fit than choreography's implicit, scattered event reactions.

# Consequences

**Easier:** every failure path is named and explicit; crash recovery has a
clear resume point; matches the "explain this in an interview" goal — the
whole flow reads top-to-bottom in one place.

**Harder:** booking-service's saga-state table and step-transition logic
must be built carefully — a bug here can leave a booking stuck between
steps if not handled defensively (the resume/compensate-on-restart logic
must itself be idempotent).

# Revisit When

- If ticket issuance ever needs to become a blocking part of the saga
  (e.g. a business requirement that a booking isn't "confirmed" until a
  ticket physically exists) — revisit keeping it outside the compensating
  chain.
- If step count grows enough that a saga orchestration library/framework
  becomes worth adopting instead of hand-rolled state tracking.

## Open Questions

- Exact retry/escalation timing values (1min/5min/30min) are starting
  defaults, not final — need real production data on actual
  payment-provider failure patterns to tune, same category as ADR-004's
  75% threshold.
