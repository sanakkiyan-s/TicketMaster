---
title: inventory-service
type: project
sources: []
related: [[system-overview]], [[booking-service]], [[venue-service]], [[queue-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

**The concurrency core of the whole system.** Owns per-session seat
inventory state and enforces that no two users can hold/purchase the same
seat. Everything else in the system exists to feed traffic into this
service correctly.

## Current Implementation

Not started. `backend/inventory-service` is an empty directory.

## Target Design

- Spring Boot, PostgreSQL as source of truth for seat state.
- Seat lifecycle: `AVAILABLE → HELD → PURCHASED`, and `HELD → EXPIRED →
  AVAILABLE` on timeout.
- Booking-service calls inventory-service to place/release/confirm holds;
  inventory-service never initiates payment or booking logic itself —
  it's the state machine, not the orchestrator.
- **Locking strategy**: hybrid — single-instance Redis atomic lock
  (`SETNX`/Lua) as a fast admission gate rejecting hot-seat contenders
  before they touch a DB connection, then Postgres `SELECT ... FOR UPDATE`
  as the actual correctness authority, backed by a partial unique
  constraint on `(session_id, seat_id) WHERE status IN ('HELD',
  'PURCHASED')`. Redis unavailable → fail open to Postgres-only path. Full
  reasoning: [[ADR-002-seat-locking-strategy]].
- Redis's role is strictly a fast-reject gate + hold-expiry scheduling
  hint — never the source of truth for PURCHASED state.

## Gap

Everything. Locking strategy is now designed ([[ADR-002-seat-locking-strategy]])
but not implemented.

## Open Questions

- Hold TTL duration — not decided (Ticketmaster typically 5-10 min).
- Crash recovery: if inventory-service crashes mid-hold, how is state
  reconciled on restart — not yet designed in detail (constraint backstop
  covers correctness, but operational recovery flow not written).
