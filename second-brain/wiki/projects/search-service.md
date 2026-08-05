---
title: search-service
type: project
sources: []
related: [[system-overview]], [[event-service]], [[venue-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Discovery/search: browse and filter events by artist, venue, city,
category, date. Read-optimized projection, not a source of truth.

## Current Implementation

Not started. `backend/search-service` is an empty directory.

## Target Design

- Spring Boot, Elasticsearch (or OpenSearch) as the index store.
- Consumes EventCreated/Updated/Cancelled and venue-changed domain events
  from Kafka, updates its denormalized index asynchronously.
- Eventually consistent by design — a brand-new event may take seconds to
  appear in search. Documented tradeoff, not a bug.
- No writes flow through search-service; it's read-only from the client's
  perspective.

## Gap

Everything.

## Open Questions

- Elasticsearch vs. OpenSearch — not decided, functionally similar for
  this use case.
- Filter/facet schema (date, location, genre, ticket type, accessibility) — not yet designed.
