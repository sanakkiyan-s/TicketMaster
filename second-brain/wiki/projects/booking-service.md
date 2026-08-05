---
title: booking-service
type: project
sources: []
related: [[system-overview]], [[inventory-service]], [[payment-service]], [[ticket-service]], [[queue-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Orchestrates the checkout flow: seat hold → checkout review → payment →
booking confirmation → ticket issuance trigger. Coordinates
inventory-service, payment-service, and ticket-service without owning
their internal state.

## Current Implementation

Not started. `backend/booking-service` is an empty directory.

## Target Design

- Spring Boot, PostgreSQL for booking orchestration state
  (PENDING/CONFIRMED/FAILED/CANCELLED).
- Calls inventory-service synchronously to place a hold, payment-service
  synchronously (or via async webhook callback) to charge, then confirms
  the hold and emits `BookingConfirmed` for ticket-service and
  notification-service to consume.
- Must be idempotent: retried checkout requests (client retry, network
  blip) must not create duplicate bookings or double-charge — idempotency
  key strategy needed at the API boundary.
- Kept separate from inventory-service specifically so the
  highest-contention code (seat locking) stays small and independently
  testable/scalable — see
  [[ADR-001-microservices-vs-modular-monolith]].

## Gap

Everything.

- **Payment race resolved**: booking-service retries `confirm` against
  inventory-service with an idempotency key until it gets a definitive
  success or "hold expired." If payment succeeds but the hold already
  expired before `confirm` lands, that's a real failure case — trigger
  automatic refund via payment-service and notify the user. See
  [[ADR-002-seat-locking-strategy]].

## Open Questions

- Idempotency key scheme for checkout requests — not decided.
- Saga vs. simple sequential orchestration with compensating actions on
  failure (e.g. release hold if payment fails) — not decided.
- How often the payment-succeeded-but-hold-expired refund path actually
  triggers in practice — may need a grace-period extension of the hold
  once payment starts, per [[ADR-002-seat-locking-strategy]] Revisit When.
