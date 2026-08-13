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

- Spring Boot, consumes Kafka events (`booking.confirmed`,
  `payment.succeeded/failed`, `ticket.issued`, `event.updated/cancelled`,
  `session.on_sale_started`).
- Postgres for delivery log only — no domain source-of-truth data.
- Channel-per-transport (email/SMS/push) since each has different
  reliability/cost/provider characteristics.
- **Push provider: FCM** (used for the web client too, not just a
  hypothetical native app — chosen specifically for its topic-based
  fan-out, so a mass broadcast is one message, not N individual sends).
  Full reasoning: [[ADR-021-notify-me-and-broadcast-alerts]].
- **Mass broadcast path** (distinct from per-user transactional
  alerts): on `session.on_sale_started`, one FCM topic publish to
  `session_{sessionId}`, high priority, short TTL, plus individual email
  fallback for non-push signups. Does not attempt to handle the
  resulting traffic stampede — that's [[queue-service]]'s job.
- Must fail independently — an outage here must never block booking or
  payment confirmation. Async, at-least-once consumption, own retry logic.

## Gap

Everything.

## Open Questions

- SMS/email provider choices — not decided (push provider resolved
  above).
- ~~Consumer idempotency~~ — resolved, see
  [[ADR-031-idempotent-kafka-consumer-pattern]].
