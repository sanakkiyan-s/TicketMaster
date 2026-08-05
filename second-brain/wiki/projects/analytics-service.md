---
title: analytics-service
type: project
sources: []
related: [[system-overview]], [[booking-service]], [[payment-service]], [[ticket-service]], [[ADR-003-gap-list-triage]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Organizer-facing sales dashboards, real-time sell-through rate,
aggregated reporting. Pure async consumer — never on the booking write
path. See [[ADR-003-gap-list-triage]] for why this is its own service.

## Current Implementation

Not started. `backend/analytics-service` is an empty directory.

## Target Design

- Spring Boot, consumes Kafka events from booking/payment/ticket/event
  services, builds read-optimized aggregates.
- Starts with Postgres materialized views; column store (e.g.
  ClickHouse) only if aggregation load justifies it later.
- Also aggregates `AdminActionPerformed` events for audit reporting (see
  [[cross-cutting-concerns]]).

## Gap

Everything.

## Open Questions

- Materialized views vs. dedicated OLAP store — deferred until real query
  load exists.
