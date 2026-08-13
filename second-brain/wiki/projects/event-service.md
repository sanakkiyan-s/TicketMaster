---
title: event-service
type: project
sources: []
related: [[system-overview]], [[venue-service]], [[search-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Source of truth for events, sessions/shows, artist/performer data.
Organizer/admin CRUD for creating and managing events lives here.

## Current Implementation

Not started. `backend/event-service` is an empty directory.

## Target Design

- Spring Boot, Spring Data JPA, PostgreSQL, Flyway migrations.
- Owns: events, sessions, artists/performers, organizer-facing management.
- Publishes domain events (EventCreated, EventUpdated, EventCancelled) to
  Kafka on write — consumed by search-service (index update) and
  notification-service (event update alerts) and booking-service
  (cancellation triggers refund flow).
- Does not own venue layout (venue-service) or seat inventory
  (inventory-service) — an event references a venue and session, inventory
  is provisioned separately per session.
- **Owns "Notify Me" signup capture**, per session, feeding two
  consumers: [[ADR-004-redis-cluster-sharding]]'s high-demand flag
  (a periodic job comparing signup count to venue capacity) and
  [[notification-service]]'s mass broadcast at on-sale time (via
  `session.on_sale_started`). Full design:
  [[ADR-021-notify-me-and-broadcast-alerts]].

## Gap

Everything.

## Open Questions

- Exact EventCancelled downstream handling (mass refund flow) — depends on
  payment-service design, not yet worked out.
