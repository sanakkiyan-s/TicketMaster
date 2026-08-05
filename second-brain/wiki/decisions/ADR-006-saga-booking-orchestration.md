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

- Exact saga-state table schema — not designed yet, deferred to
  implementation.
- Whether compensations themselves need their own idempotency/retry
  handling if a compensation itself fails (e.g. refund call fails) — not
  yet designed.
