---
title: venue-service
type: project
sources: []
related: [[system-overview]], [[event-service]], [[inventory-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Venues and their seating layouts (sections, rows, seats) — the reusable
template that event-service references per session and that
inventory-service instantiates per-session inventory rows from.

## Current Implementation

Not started. `backend/venue-service` is an empty directory.

## Target Design

- Spring Boot, Spring Data JPA, PostgreSQL.
- Owns: venue metadata, seat map/layout (sections/rows/seat coordinates).
- A venue's seat map is reused across many events at that venue — separate
  from event-service so layout changes don't couple to event lifecycle.
- inventory-service reads the seat map (via API, not shared DB) when
  provisioning per-session seat inventory.

## Gap

Everything.

## Open Questions

- Interactive seat map data format (coordinates for frontend rendering) —
  not yet designed.
