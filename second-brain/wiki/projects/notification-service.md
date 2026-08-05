---
title: notification-service
type: project
sources: []
related: [[system-overview]], [[booking-service]], [[event-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Sends sale reminders, booking/payment confirmations, and event updates via
email/SMS/push. Pure consumer of domain events — never a dependency of the
booking write path.

## Current Implementation

Not started. `backend/notification-service` is an empty directory.

## Target Design

- Spring Boot, consumes Kafka events (`BookingConfirmed`,
  `PaymentSucceeded/Failed`, `TicketIssued`, `EventUpdated/Cancelled`).
- Postgres for delivery log only — no domain source-of-truth data.
- Channel-per-transport (email/SMS/push) since each has different
  reliability/cost/provider characteristics.
- Must fail independently — an outage here must never block booking or
  payment confirmation. Async, at-least-once consumption, own retry logic.

## Gap

Everything.

## Open Questions

- Email/SMS/push provider choices — not decided.
- Consumer idempotency (avoid duplicate notification on redelivered
  Kafka message) — not decided.
