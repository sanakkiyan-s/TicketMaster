---
title: frontend
type: project
sources: []
related: [[system-overview]], [[api-gateway]], [[queue-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Client application: event browsing/search, seat map selection, waiting
room/queue UI, checkout, digital ticket display, account/ticket history.

## Current Implementation

Not started. `frontend` is an empty directory.

## Target Design

- React, TypeScript, Vite.
- All API calls go through `api-gateway`, never directly to individual
  backend services.
- Queue position updates via WebSocket/SSE connection to queue-service
  (through the gateway).
- Interactive seat map rendering driven by venue-service's layout data.

## Gap

Everything.

## Open Questions

- State/data-fetching library choice — not decided, to be picked once API
  contracts exist.
