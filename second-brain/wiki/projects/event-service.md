---
title: event-service
type: project
sources: []
related: [[system-overview]], [[venue-service]], [[search-service]], [[api-gateway]]
created: 2026-08-05
last-updated: 2026-08-20
---

## Purpose

Source of truth for events, sessions/shows, artist/performer data.
Organizer/admin CRUD for creating and managing events lives here.

## Current Implementation

In progress (`feat/event-service` branch). Spring Boot, Postgres, Flyway
(`V1__baseline.sql`, `V2__notify_me.sql`, `V3__outbox.sql`), transactional
outbox (`OutboxEvent`, `OutboxEventRepository`, `OutboxPublisher`) — Kafka
delivery confirmed live 2026-08-20: registered
`infra/kafka-connect/event-outbox-connector.json` (targets
`event_service.outbox`, same EventRouter SMT pattern as auth-service's
connector), created a real event through the running container
(`localhost:8084`, no gateway route yet — see below), and consumed the
actual `event.created` Kafka message: payload carried the real
`eventId`/`organizerId`/`venueId`/`title`/`status`/`region`, topic name
routed correctly by `event_type`. Also live-confirmed the ownership check
itself: the creating user got the event back (200), a second unrelated
user got 404 (not 403) on the same id.

Built so far — organizer-facing CRUD only, all under `/api/v1/organizer/`,
coarse ORGANIZER-role gate assumed at api-gateway (ADR-030 layer 1) plus
per-resource `organizer_id` ownership checks enforced in-service
(ADR-030 layer 2), ADMIN bypasses ownership:

- **Events** (`EventController`, `/api/v1/organizer/events`) — create,
  list-mine, get, update, soft-cancel (`status=CANCELLED`, never a hard
  delete). `region` is set at creation and immutable (data-residency
  anchor, ADR-016) — `UpdateEventRequest` excludes it.
- **Sessions** (`SessionController`, nested under
  `/api/v1/organizer/events/{eventId}/sessions`) — create, update
  (schedule only), cancel. Ownership is inherited from the parent event's
  `organizer_id`, re-checked via `EventService` on every call.
- **Artists** (`ArtistController`, `/api/v1/organizer/artists`) — create,
  get, case-insensitive substring search by name. Shared catalog, no
  per-resource ownership check — the coarse gateway role gate is the only
  authorization layer here (see the controller's own javadoc).
- `session_notify_me` table exists (`V2__notify_me.sql`, ADR-021's DDL
  verbatim — ciphertext contact email, dedupe unique index, high-demand
  count index) but **no controller or service reads/writes it yet** —
  schema landed ahead of the signup-capture code.

**Not built**: any public/buyer-facing read endpoint. Everything above is
organizer-only — a buyer has no way to list or view events yet. That is
also what search-service (still fully not-started) is meant to serve, but
neither exists on the read side yet.

**Hard blocker, not this service's gap**: api-gateway's
`application.yml` has no route for event-service at all (only
`auth-service` and `user-service` are routed, verified 2026-08-20) — none
of the endpoints above are reachable from outside the container network
yet, regardless of role/ownership logic being correct. See
[[api-gateway]]'s open items.

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

- No public/buyer-facing read endpoint (list/browse/get events as a
  non-organizer) — everything built is organizer-scoped.
- `event.updated`/`event.cancelled` delivery specifically not yet
  live-tested (only `event.created` was) — same code path, same
  connector, low risk, but not literally confirmed with a real message
  consumed the way `event.created` was.
- `session_notify_me` has a schema but no signup-capture code
  (ADR-021 not yet implemented past DDL).
- api-gateway route missing (blocks all frontend/external access —
  see [[api-gateway]]).

## Open Questions

- Exact EventCancelled downstream handling (mass refund flow) — depends on
  payment-service design, not yet worked out.
- Whether the public read endpoint lands in event-service itself (simple
  `GET`, cached) or is deferred entirely to search-service once that
  exists — not yet decided.
