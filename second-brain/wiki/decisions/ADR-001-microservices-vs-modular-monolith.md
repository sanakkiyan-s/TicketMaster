---
title: ADR-001 Microservices vs Modular Monolith
type: decision
sources: []
related: [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

TicketMaster is a portfolio project to learn system design by building a
Ticketmaster-style event ticketing platform capable of handling
high-contention, high-traffic scenarios (target: on-sale events with
~100,000+ concurrent users competing for limited seat inventory). Goal is
learning, not shipping to real customers or minimizing infra cost.

# Requirements / Constraints

- Primary goal: deep, hands-on learning of distributed system design —
  service boundaries, data ownership, consistency, async messaging,
  concurrency control, failure handling — not minimizing build time.
- Solo developer, no team coordination constraints.
- Must remain explainable in an interview: every service must have a
  concrete reason to exist, not just "because microservices."
- Must not become undebuggable — still needs to be a system one person can
  reason about and operate locally (Docker Compose).

# Options Considered

## Option A — Modular monolith

Pros: simplest to build and debug; no network calls between domains; single
deployable; easiest to keep transactionally consistent.

Cons: doesn't exercise the distributed-systems problems (network failure,
partial failure, eventual consistency, service-to-service auth, distributed
tracing, independent scaling) that are the actual learning target here.

## Option B — Microservices, one per noun (12+ services)

Pros: maximum exposure to real distributed-system problems; each service
independently scalable; matches how a real ticketing platform at this
traffic tier would likely be decomposed.

Cons: highest operational complexity; requires justifying each boundary
individually or it becomes "overengineering" the project explicitly warns
against.

## Option C — Hybrid: fewer, merged services (6)

Pros: less operational overhead than Option B.

Cons: merges services with genuinely different scaling profiles and data
ownership (e.g. queue admission vs. seat locking; user profile CRUD vs.
auth token validation), which is exactly the kind of coupling a
learning-focused rebuild of Ticketmaster should demonstrate separating.

# Decision

**Microservices**, close to the original domain breakdown (Option B), with
an explicit API gateway as its own service:

- `api-gateway` — edge routing, auth token validation, rate limiting
- `auth-service` — identity, JWT issuance/refresh, roles
- `user-service` — profile, payment methods on file, preferences
- `event-service` — events, sessions/shows, artist/performer data
- `venue-service` — venues, seating layout/seat maps
- `search-service` — denormalized discovery index, fed by event/venue events
- `inventory-service` — seat inventory state machine (concurrency core)
- `booking-service` — orchestrates hold → checkout → confirm
- `queue-service` — virtual queue / waiting-room admission for on-sales
- `payment-service` — payment intents, webhooks, idempotency, refunds
- `ticket-service` — digital ticket issuance, transfer, resale
- `notification-service` — email/SMS/push on booking/payment/event events

# Why

The explicit project goal, restated by the user, is to learn system design
by building something close to the real thing, not to minimize services for
portfolio neatness. Each service above has a distinct justification:

- **auth vs. user**: auth-service is called on nearly every request (token
  validation) and must be fast/highly available; user-service is low-QPS
  CRUD. Different scaling and caching strategy.
- **event/venue vs. search**: event/venue are the write-path source of
  truth (normalized, transactional); search is a read-optimized denormalized
  projection (e.g. Elasticsearch), updated asynchronously. Different storage
  technology, different consistency model (eventual for search).
- **inventory vs. booking**: inventory owns the seat state machine and its
  concurrency control; booking is an orchestrator coordinating inventory +
  payment + ticket issuance. Separating them keeps the highest-contention
  code (inventory) small, focused, and independently scalable/testable.
- **queue vs. inventory/booking**: queue admission is a traffic-shaping
  problem (who's allowed to even try) with a very different load pattern
  (huge concurrent connection count, mostly idle) than inventory locking
  (short, intense, transactional). Different scaling axis entirely.
- **payment**: isolates third-party integration, webhook handling, and PCI
  concerns behind one boundary.
- **ticket vs. booking**: ticket issuance/transfer/resale is a separate
  lifecycle that continues long after a booking is confirmed; keeping it
  separate avoids booking-service becoming a dumping ground for
  post-purchase concerns.
- **notification**: pure consumer of domain events, needs to fail
  independently (an outage here shouldn't block booking).

This decomposition intentionally matches the earlier hand-drawn
architecture diagram rather than collapsing it, because the collapsed
6-service version would have hidden exactly the coupling/scaling
distinctions this project exists to learn.

# Consequences

**Easier:** each service can use the storage/technology best suited to it
(Postgres for transactional data, Elasticsearch for search, Redis for
holds/queue state); independent scaling; forces explicit API/event
contracts instead of in-process shortcuts; realistic practice ground for
distributed tracing, circuit breakers, idempotent consumers, sagas.

**Harder:** local dev requires Docker Compose orchestrating 12+ services;
cross-service transactions need sagas/outbox instead of a DB transaction;
debugging requires correlation IDs and tracing from day one; more
boilerplate (DTOs, clients) per service.

# Revisit When

- If local development/debugging overhead becomes a blocker to actually
  learning (i.e. more time fighting Docker Compose than doing system
  design), consider merging low-traffic, tightly-coupled services (e.g.
  venue-service into event-service) — document as a new ADR, don't silently
  fold them back.
- If a specific service turns out to have no real independent scaling or
  ownership justification once built, document that and consider merging.
