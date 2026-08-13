---
title: payment-service
type: project
sources: []
related: [[system-overview]], [[booking-service]], [[ADR-011-pci-scope-containment]], [[ADR-020-payment-event-ledger]]
created: 2026-08-05
last-updated: 2026-08-06
---

## Purpose

Payment intent/order creation, third-party payment provider integration,
webhook handling, idempotent charge processing, refunds. Isolates
PCI-relevant concerns and third-party integration behind one boundary.

## Current Implementation

Not started. `backend/payment-service` is an empty directory.

## Target Design

- Spring Boot, PostgreSQL for payment/transaction records.
- Integrates with **Stripe** ([[ADR-011-pci-scope-containment]]) via
  provider-hosted iframe collection — never stores raw card data, only
  `pm_xxx`/`cus_xxx` tokens and the card fingerprint.
- **State storage: append-only `payment_events` ledger**, not a single
  mutable status column — current status is derived from the full event
  timeline so out-of-order webhook delivery can never produce a wrong
  state. Webhook signature verification + `provider_event_id`-based
  dedup + outbox integration all specified in
  [[ADR-020-payment-event-ledger]].
- Publishes `payment.succeeded`/`payment.failed`
  ([[ADR-007-kafka-event-schema]]) for booking-service to consume, via
  the same Transactional Outbox pattern — never assumes "frontend says
  success" implies a successful charge; the webhook is the source of
  truth.

## Gap

Everything.

## Open Questions

- Reconciliation job design (catch missed webhooks, e.g. a webhook
  delivery that never arrives at all) — not yet decided.
- Full payment-provider transition-graph edge cases (partial refunds,
  multiple disputes) — flagged in [[ADR-020-payment-event-ledger]].
