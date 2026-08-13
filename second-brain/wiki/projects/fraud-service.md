---
title: fraud-service
type: project
sources: []
related: [[system-overview]], [[queue-service]], [[booking-service]], [[ADR-003-gap-list-triage]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Real-time risk scoring: device fingerprinting, purchase velocity checks,
bot detection, bulk-purchase-limit enforcement signal. Called
synchronously by `queue-service` (admission) and `booking-service`
(checkout). See [[ADR-003-gap-list-triage]] for why this is its own
service.

## Current Implementation

Not started. `backend/fraud-service` is an empty directory.

## Target Design

- Spring Boot, Redis for velocity counters (short-lived, high write rate —
  good fit), Postgres for longer-lived risk signal/decision history.
- Exposes a synchronous scoring endpoint: given account/device/IP, return
  risk score + decision (allow/challenge/block).
- Feeds CAPTCHA challenge decisions to the client via queue-service.
- **Fail-open on outage** (resolved): if fraud-service is unreachable,
  callers (queue-service, booking-service) allow the request through
  unscored rather than blocking. Reasoning: fraud-service is one of
  several independent defense layers (queue-service admission
  throttling, api-gateway's business-aware rate limiting, inventory-
  service's Redis+Postgres seat-lock) — its own outage doesn't leave the
  system defenseless. Fail-closed would make fraud-service a single point
  of failure for the entire revenue-critical checkout/admission path;
  blocking all legitimate buyers over a scoring-service hiccup is worse
  than a temporary gap in one signal among several. Every skipped-check
  event must be logged (not silently dropped) so ops can audit exactly
  how much unscored traffic passed during any outage window.

## Gap

Everything.

## Open Questions

- Device fingerprinting library/approach — not decided.
