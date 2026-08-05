---
title: System Overview
type: architecture
sources: []
related: [[ADR-001-microservices-vs-modular-monolith]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Current Implementation

None. All 12 backend services, `frontend`, and `infra` are empty
directories — no code written yet. This page describes the **target
design** only.

## Target Design

### Components

```mermaid
flowchart LR
  Client[React Client] --> Nginx[Nginx: TLS, LB, static, DDoS throttling]
  Nginx --> Gateway[api-gateway: JWT validation, routing, rate limiting]

  Gateway --> Auth[auth-service]
  Gateway --> UserSvc[user-service]
  Gateway --> EventSvc[event-service]
  Gateway --> VenueSvc[venue-service]
  Gateway --> SearchSvc[search-service]
  Gateway --> QueueSvc[queue-service]
  Gateway --> BookingSvc[booking-service]
  Gateway --> TicketSvc[ticket-service]

  BookingSvc --> InventorySvc[inventory-service]
  BookingSvc --> PaymentSvc[payment-service]
  BookingSvc --> TicketSvc

  EventSvc -- domain events --> SearchSvc
  VenueSvc -- domain events --> SearchSvc
  BookingSvc -- BookingConfirmed --> NotifySvc[notification-service]
  PaymentSvc -- PaymentSucceeded/Failed --> BookingSvc
  BookingSvc -- TicketIssued --> NotifySvc

  Auth --> AuthDB[(Postgres: auth)]
  UserSvc --> UserDB[(Postgres: user)]
  EventSvc --> EventDB[(Postgres: event)]
  VenueSvc --> VenueDB[(Postgres: venue)]
  InventorySvc --> InvDB[(Postgres: inventory)]
  InventorySvc --> Redis[(Redis: holds/locks)]
  QueueSvc --> Redis
  SearchSvc --> ES[(Elasticsearch)]
  PaymentSvc --> PayDB[(Postgres: payment)]
  TicketSvc --> TicketDB[(Postgres: ticket)]

  EventSvc -.Kafka.-> Kafka[(Kafka)]
  VenueSvc -.Kafka.-> Kafka
  BookingSvc -.Kafka.-> Kafka
  PaymentSvc -.Kafka.-> Kafka
  TicketSvc -.Kafka.-> Kafka
  Kafka -.-> SearchSvc
  Kafka -.-> NotifySvc
```

Diagram is illustrative of target shape — exact Kafka topic list lives in
domain/flow pages, not duplicated here.

### Data ownership (target)

| Service | Owns | Primary store |
|---|---|---|
| auth-service | credentials, roles, sessions | Postgres |
| user-service | profile, saved payment methods, preferences | Postgres |
| event-service | events, sessions/shows, artists | Postgres |
| venue-service | venues, seating layout | Postgres |
| search-service | denormalized discovery index (read-only copy) | Elasticsearch |
| inventory-service | seat state (AVAILABLE/HELD/PURCHASED) | Postgres + Redis (holds) |
| booking-service | booking orchestration state | Postgres |
| queue-service | queue admission tokens, position | Redis |
| payment-service | payment intents, transactions | Postgres |
| ticket-service | issued tickets, transfers, resale listings | Postgres |
| notification-service | delivery log (no source-of-truth domain data) | Postgres (log only) |

No service reads another service's database directly — cross-service data
access is via API call or consumed domain event, never a shared schema.

### Communication (target)

- **Synchronous REST**: client → gateway → service; booking-service →
  inventory-service (seat hold) and → payment-service (charge) where an
  immediate result is required for the user-facing flow.
- **Asynchronous (Kafka)**: side effects that don't need to block the
  triggering request — search index updates, notifications, analytics,
  audit.
- **WebSocket/SSE**: queue-service → client, for live queue position
  updates without polling.

Specific per-flow reasoning lives in `wiki/flows/`.

### Failure boundaries (target)

- notification-service outage must never block booking confirmation
  (async, at-least-once, consumer retries independently).
- search-service outage must never block event/venue writes (search is a
  read projection, not a dependency of the write path).
- payment-service and inventory-service are the two components where
  correctness under partial failure matters most — tracked as an open
  question below until a dedicated seat-locking ADR exists.

## Gap

Everything — no service implemented yet. Build order and first concrete
ADRs to write (seat locking strategy, Redis role, payment idempotency)
are tracked as open questions below.

## Open Questions

- Exact Kafka topic list and schema per event (BookingConfirmed,
  PaymentSucceeded, etc.) — not yet designed.
- Build order across the 12 services — likely inventory-service first
  (hardest problem) but not yet decided/documented.
- Whether api-gateway is Spring Cloud Gateway or a simpler reverse proxy —
  not yet decided.
- Seat locking strategy (optimistic vs pessimistic vs Redis-assisted) —
  not yet decided; needs its own ADR before inventory-service is built.
