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

## Gap

Everything.

## Open Questions

- Fail-open vs fail-closed on fraud-service outage — not decided (see
  [[ADR-003-gap-list-triage]]).
- Device fingerprinting library/approach — not decided.
