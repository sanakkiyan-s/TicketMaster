# TicketMaster

A Ticketmaster-inspired event-ticketing platform, built to demonstrate
real Java backend / system-design engineering judgment — not a CRUD demo.
Microservices was a deliberate choice, defended in
[ADR-001](second-brain/wiki/decisions/ADR-001-microservices-vs-modular-monolith.md),
not a default. Every non-trivial decision — seat-locking under
concurrency, event-driven outbox delivery, build-order phasing — has a
written ADR explaining the alternatives considered and why they lost.
That decision trail lives in [`second-brain/`](second-brain/wiki/index.md)
and is the fastest way to see how this project was actually reasoned
through, not just what it does.

Worth reading first if you're evaluating the engineering, not just running it:
- [ADR-002 — seat-locking strategy](second-brain/wiki/decisions/ADR-002-seat-locking-strategy.md) — Postgres row locks as the correctness authority, Redis as a fast-fail gate, not the other way around
- [ADR-036 — build order and phasing](second-brain/wiki/decisions/ADR-036-build-order-and-phasing.md) — why the concurrency-critical core gets built and stress-tested before anything downstream depends on it
- [`second-brain/wiki/architecture/blueprint.html`](second-brain/wiki/architecture/blueprint.html) — interactive service map
- [`second-brain/wiki/index.md`](second-brain/wiki/index.md) — master catalog of every ADR, domain doc, and open question

## Status: Phase 3 underway (catalog done, transaction core in progress)

Phase 0 (platform) and Phase 1 (identity/edge) are done. **Phase 2
(catalog) is done**: event-service, venue-service, and search-service are
real, running, and wired together end-to-end — an organizer can create an
event, publish it, and it becomes publicly browsable and bookable within
seconds via a Kafka/Debezium outbox pipeline into Elasticsearch. **Phase 3
(the transaction core) is underway**: inventory-service is built and
live-verified (seat hold/confirm state machine, Redis fast-gate + Postgres
row-lock correctness, Avro-serialized Kafka events, SSE live seat updates)
per [ADR-002](second-brain/wiki/decisions/ADR-002-seat-locking-strategy.md).
booking-service, payment-service, and ticket-service are next, in that
order — each is the prior's real dependency, and Phase 3's own gate (zero
double-sell, zero paid-and-unresolved bookings, under a full concurrency
chaos matrix) has to pass before Phase 4 starts, per
[ADR-036](second-brain/wiki/decisions/ADR-036-build-order-and-phasing.md).

This gives you:

- A Gradle multi-module skeleton, one module per backend service, wired
  into `settings.gradle.kts`, common config in the root `build.gradle.kts`
  (Java 21, Spring Boot 3.5, gRPC for internal calls per ADR-023).
- A `docker-compose.yml` (`infra/`) bringing up everything every service
  depends on: Postgres/Citus (+ PgBouncer), Redis, Kafka + Debezium +
  Schema Registry, Vault, MinIO — plus the observability stack (OTel
  Collector, Tempo, Loki, Mimir, Prometheus, Grafana), Kafka Connect
  outbox connectors registering themselves automatically on startup.
- Seven working backend services: auth-service, api-gateway,
  user-service, event-service, venue-service, search-service,
  inventory-service — each carrying an OpenTelemetry Java agent by
  default, each with real tests, not stubs.
- A `frontend/` app: Vite + React + TypeScript, React Router, TanStack
  Query, Zustand, Tailwind + shadcn/ui — a public storefront (region-
  grouped browse, event detail, live seat selection with SSE-pushed
  updates) plus an organizer dashboard, both talking to the real backend
  through the gateway.
- `scripts/dev.sh` to bring the above up together.

## Quick start

```bash
./scripts/dev.sh
```

Seeds `infra/.env` and `frontend/.env.local` from their `.env.example`
files (never overwrites an existing one — edit them for non-default
credentials), then brings up infra, backend, observability, and the Vite
dev server together, waiting for each container that declares a
healthcheck to report healthy.

```bash
./scripts/dev.sh infra          # containers only
./scripts/dev.sh backend        # auth-service/api-gateway/user-service (assumes infra up)
./scripts/dev.sh observability  # OTel Collector/Tempo/Loki/Mimir/Prometheus/Grafana (assumes infra up)
./scripts/dev.sh frontend       # Vite only, assumes infra is already up
./scripts/dev.sh status         # what is running
./scripts/dev.sh logs redis     # tail one service
./scripts/dev.sh down           # stop, keep data
./scripts/dev.sh reset          # stop AND delete volumes (prompts first)
```

With no argument, `dev.sh` now brings up infra + backend + observability +
frontend together.

| | |
|---|---|
| Frontend | http://localhost:5173 |
| api-gateway | http://localhost:8080 |
| auth-service | http://localhost:8180 — container's own port is 8081; host 8081 was already taken by an unrelated local process, and 8083 by kafka-connect's own REST port |
| user-service | http://localhost:8090 — container's own port is 8082; host 8082 is schema-registry's mapping |
| event-service | http://localhost:8084 |
| venue-service | http://localhost:8085 |
| search-service | http://localhost:8086 — the one genuinely public, unauthenticated read API |
| inventory-service | http://localhost:8088 |
| Postgres (coordinator) | localhost:**5433** — remapped, not the default 5432 |
| PgBouncer | localhost:6432 |
| Redis | localhost:**6380** — remapped, not the default 6379 |
| Kafka | localhost:29092 |
| Schema Registry / Connect | localhost:8082 / localhost:8083 |
| Vault | localhost:8200, token `dev-root-token` |
| MinIO API / console | localhost:9000 / localhost:9001 |
| Grafana (ADR-015) | http://localhost:3000, anonymous Admin — dev only |
| Tempo / Loki / Mimir | localhost:3200 / localhost:3100 / localhost:9009 |

Every other `backend/*` module beyond the seven above is still build
config only.

Gradle modules still compile on their own:

```bash
./gradlew build
```

## Modules

| Module | Phase | Status | Purpose |
|---|---|---|---|
| `backend/auth-service` | 1 | ✅ Live | Identity, JWT |
| `backend/api-gateway` | 1 | ✅ Live | Edge routing, JWT validation |
| `backend/user-service` | 1 | ✅ Live | Profile |
| `backend/event-service` | 2 | ✅ Live | Events, sessions, publish flow |
| `backend/venue-service` | 2 | ✅ Live | Venues, seat maps |
| `backend/search-service` | 2 | ✅ Live | Discovery (Kafka-fed ES projection) |
| `backend/inventory-service` | 3 | ✅ Live | Seat state — the concurrency core |
| `backend/booking-service` | 3 | Not started | Saga orchestration |
| `backend/payment-service` | 3 | Not started | Payment ledger, Stripe |
| `backend/ticket-service` | 3 | Not started | Issuance, transfer/resale |
| `backend/notification-service` | 4 | Not started | Email/SMS/push |
| `backend/fraud-service` | 4 | Not started | Risk scoring |
| `backend/analytics-service` | 4 | Not started | Organizer dashboards |
| `backend/queue-service` | 5 | Not started | Virtual waiting room |
| `backend/media-service` | 5 | Not started | Video trailers |

Build order is not optional — see ADR-036. Phase 3 (inventory → booking
→ payment → ticket) has a hard CI gate: the full concurrency-proof suite
must pass before Phase 4 starts.

## What's not decided yet

See `implementation-roadmap.md`'s **Open Decisions**: CI runner platform,
seed data strategy, staging environment shape.

Frontend tooling is no longer open — resolved 2026-08-14 to Vite, React
Router, Zustand, TanStack Query, Tailwind + shadcn/ui. Rationale in
[`second-brain/wiki/projects/frontend.md`](second-brain/wiki/projects/frontend.md);
the CSS/component-library choice turns on the CSP that `frontend/index.html`
carries for ADR-011's PCI SAQ A scope.
