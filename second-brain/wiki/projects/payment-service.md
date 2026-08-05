---
title: payment-service
type: project
sources: []
related: [[system-overview]], [[booking-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Payment intent/order creation, third-party payment provider integration,
webhook handling, idempotent charge processing, refunds. Isolates
PCI-relevant concerns and third-party integration behind one boundary.

## Current Implementation

Not started. `backend/payment-service` is an empty directory.

## Target Design

- Spring Boot, PostgreSQL for payment/transaction records.
- Integrates with a payment provider (e.g. Stripe) — never stores raw card
  data, only tokens/references.
- Webhook endpoint must verify provider signature and handle duplicate
  delivery idempotently (provider webhooks are at-least-once).
- Publishes `PaymentSucceeded`/`PaymentFailed` for booking-service to
  consume — must never assume "frontend says success" implies a
  successful charge; the webhook/callback is the source of truth.

## Gap

Everything. Payment state machine (pending/succeeded/failed/refunded) not
yet documented — needed before implementation.

## Open Questions

- Payment provider choice — not decided.
- Idempotency key propagation from booking-service through to the provider
  call — not decided.
- Reconciliation job design (catch missed webhooks) — not decided.
