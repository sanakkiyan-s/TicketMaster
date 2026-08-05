---
title: ticket-service
type: project
sources: []
related: [[system-overview]], [[booking-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Digital ticket issuance (mobile ticket/barcode), ticket transfer to
another user, resale listing and purchase of verified resale tickets.

## Current Implementation

Not started. `backend/ticket-service` is an empty directory.

## Target Design

- Spring Boot, PostgreSQL for issued tickets, transfer records, resale
  listings.
- Consumes `BookingConfirmed` (async, via Kafka) to issue tickets —
  decoupled from the synchronous checkout path so ticket generation
  latency never blocks payment confirmation.
- Resale modeled as a ticket ownership state transition, not separate
  inventory — kept in this service rather than a standalone resale
  service since it shares the same ticket-ownership data. See
  [[ADR-001-microservices-vs-modular-monolith]].
- Barcode/QR generation for venue entry scanning.

## Gap

Everything.

## Open Questions

- Barcode format/rotation strategy (static vs. rotating to prevent
  screenshot fraud) — not decided.
- Resale price cap/verification rules — not decided.
