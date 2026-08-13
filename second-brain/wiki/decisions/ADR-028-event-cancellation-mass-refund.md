---
title: ADR-028 Event Cancellation and Mass-Refund Saga
type: decision
sources: []
related: [[ADR-006-saga-booking-orchestration]], [[ADR-003-gap-list-triage]], [[ADR-014-anti-bot-anti-scalper]], [[ADR-025-idempotency-key-policy]], [[ADR-007-kafka-event-schema]], [[event-service]], [[ticket-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[ADR-003-gap-list-triage]] routed event cancellation to "payment-service,
triggered by `EventCancelled`, orchestrated through booking-service" but
never designed the mechanics — `event-service.md` still lists it as an
open question, and it fell out of `final-architecture-reference.md`'s
tracked TBD list entirely, silently reading as resolved when it isn't.
Separately, [[ADR-014-anti-bot-anti-scalper]]'s layer 7 (post-hoc
cancellation of fraudulent bulk purchases) states it "requires
ticket-service's admin-cancel + inventory-re-release path,
payment-service's programmatic refund" — the **same** unbuilt mechanism,
assumed by a second ADR without either one actually designing it. Both
gaps close with one Saga, triggered two different ways.

# Requirements / Constraints

- Must handle potentially tens of thousands of bookings for one
  cancelled event without a single blocking transaction or a synchronous
  loop that times out the organizer's cancel request.
- Must reuse [[ADR-006-saga-booking-orchestration]]'s existing
  compensation state machine — booking-service already knows how to
  unwind one booking (release hold, refund payment); mass cancellation
  is that same logic run at scale, not a new pattern.
- Must be resumable and safe to retry without double-refunding or
  silently skipping bookings — depends directly on
  [[ADR-025-idempotency-key-policy]].
- Must distinguish the two triggers' different consequences: an
  event-cancellation cancel does **not** re-release seats (the event is
  dead, nothing to re-sell); an ADR-014 layer-7 fraud bulk-cancel
  **does** re-release seats (the event is still on sale).
- Must produce a bulk-aware version of ADR-006's stuck-refund alerting,
  not go undetected just because it's operating at scale.

# Options Considered

## A — Synchronous loop over an ops-triggered API call

Pros: simplest to imagine. Cons: doesn't scale to thousands of bookings,
times out the organizer's cancel action, no resumability if it crashes
partway through — the exact failure shape that turns a cancellation into
a support incident.

## B — Async fan-out via Kafka, each booking reusing ADR-006's existing compensation path

Pros: reuses booking-service's compensation logic verbatim instead of
writing a second one; scales naturally with Kafka consumer parallelism;
resumable via normal consumer offset/retry semantics; slots directly
into ADR-025's idempotency guarantee for safe retry.

## C — A dedicated new "cancellation-service"

Pros: none specific to this problem. Cons: this is squarely
booking-service's existing compensation logic run in bulk — doesn't
independently justify a 16th service against
[[ADR-001-microservices-vs-modular-monolith]]'s existence bar.

# Decision

**Option B.**

## Flow

```
1. Organizer cancels event (or fraud review confirms a bulk-cancel
   decision, ADR-014 layer 7) -> event-service marks the event
   CANCELLED, emits event.cancelled via the existing outbox pattern
   (ADR-007) — this is the trigger ADR-003 already named, now given
   real mechanics.

2. booking-service consumes event.cancelled. Queries all bookings with
   a non-terminal status for that event_id — a SINGLE-SHARD query,
   since bookings are event_id-sharded (ADR-005); this event's
   bookings all live on one shard, no cross-shard scan needed.

3. booking-service fans out ONE cancellation job per affected booking
   (a Kafka message per booking_id, or a work-queue table drained by a
   background worker at N-per-tick) — this fan-out, not a synchronous
   loop, is what makes the whole operation resumable and scalable.

4. Each per-booking job reuses ADR-006's EXISTING compensation path
   exactly: status -> COMPENSATING, call payment-service to refund
   (idempotent via ADR-025's Idempotency-Key — safe to retry the whole
   job without double-refunding), mark the ticket VOIDED via
   ticket-service, emit AdminActionPerformed per
   [[cross-cutting-concerns]].

   Trigger-specific branch, the one real difference between the two
   callers of this same mechanism:
     event.cancelled  -> do NOT re-release the seat (event is dead).
     ADR-014 layer-7 admin bulk-cancel -> DO re-release the seat via
       inventory-service (event is still on sale, seat goes back to
       AVAILABLE for a real buyer).

5. Bulk-aware alerting: reuses ADR-006/ADR-015's PaidUserUnresolved
   THRESHOLD (40 minutes), not a new number — new metric
   mass_cancellation_stuck_count{event_id}, pages if ANY individual
   booking's cancellation job sits in COMPENSATING past that same
   window, scaled to the batch rather than one booking.
```

# Why

Both ADR-003's event-cancellation gap and ADR-014's admin-bulk-cancel
gap turn out to need the identical fan-out-plus-reused-compensation
mechanism — designing it once, parameterized by trigger, avoids building
two near-duplicate saga variants and keeps booking-service's
compensation logic as the single place "how do we unwind a booking"
lives, matching this project's consistent preference for reusing an
existing mechanism over inventing a parallel one (the same instinct
behind ADR-014's admission-token nonce reusing ADR-002's Lua pattern,
and ADR-023's resilience layers reusing Resilience4j).

# Consequences

**Easier:** a previously-silent gap (dropped off the TBD list, read as
resolved) now has a real, resumable mechanism; ADR-014's layer 7 has an
actual implementation path instead of an assumed one; no second saga
implementation to maintain.

**Harder:** booking-service gains a new consumer + fan-out responsibility
on top of its existing saga-orchestrator role; the trigger-specific
branch (re-release vs. not) must be tested for both callers explicitly,
not just one.

# Revisit When

- If mass-cancellation volume for a single event ever becomes large
  enough that single-shard fan-out throughput itself becomes the
  bottleneck — would need batched/rate-limited job dispatch, not
  redesigned before real volume data exists.

## Open Questions

- Fan-out mechanism specifics (Kafka message-per-booking vs. a
  work-queue table with a polling worker) — both viable, not chosen
  between here; implementation-time decision.
- Refund timing/communication to affected users (immediate vs. batched
  notification) — product-level choice, not yet decided.
