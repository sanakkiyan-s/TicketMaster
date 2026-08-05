---
title: queue-service
type: project
sources: []
related: [[system-overview]], [[booking-service]], [[inventory-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Virtual queue / waiting room for high-demand on-sale events. Controls how
many users are admitted into booking-service/inventory-service at once, so
those services see bounded, manageable concurrency instead of the full
traffic spike.

## Current Implementation

Not started. `backend/queue-service` is an empty directory.

## Target Design

- Spring Boot, Redis for queue position/token state (high write volume,
  short-lived data — good Redis fit, unlike inventory's need for a durable
  source of truth).
- WebSocket or SSE connection to client for live position updates instead
  of polling.
- Issues short-lived admission tokens; booking-service/inventory-service
  reject requests without a valid token during an active on-sale.
- Distinct scaling profile from inventory-service: huge number of mostly
  idle connections (holding a queue position) vs. inventory's short,
  intense transactional load. See
  [[ADR-001-microservices-vs-modular-monolith]].

## Gap

Everything.

## Open Questions

- Admission rate strategy (fixed rate vs. dynamic based on
  inventory-service load) — not decided.
- Fairness guarantee (strict FIFO vs. randomized within window) — not decided.
- Bot/rate-limit signal integration — not decided.
