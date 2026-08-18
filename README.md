# TicketMaster

Ticketmaster-inspired event-ticketing platform, built as a Java/Spring
microservices portfolio project. Full architecture and every design
decision behind this repo lives in [`second-brain/`](second-brain/wiki/index.md)
— read that first, always, before touching code.

Fast entry points:
- [`second-brain/wiki/index.md`](second-brain/wiki/index.md) — master catalog
- [`second-brain/wiki/architecture/blueprint.html`](second-brain/wiki/architecture/blueprint.html) — interactive service map
- [`second-brain/wiki/architecture/implementation-roadmap.md`](second-brain/wiki/architecture/implementation-roadmap.md) — full build plan
- [`second-brain/wiki/decisions/ADR-036-build-order-and-phasing.md`](second-brain/wiki/decisions/ADR-036-build-order-and-phasing.md) — the phase order this repo follows

## This is Phase 0

This base setup is **platform bootstrap only** (ADR-036) — no product
code yet. It gives you:

- A Gradle multi-module skeleton, one module per backend service, wired
  into `settings.gradle.kts`, common config in the root `build.gradle.kts`
  (Java 21, Spring Boot 3.3, gRPC for internal calls per ADR-023).
- A `docker-compose.yml` (`infra/`) bringing up everything every service
  depends on: Postgres/Citus (+ PgBouncer), Redis, Kafka + Debezium +
  Schema Registry, Vault, MinIO.
- A `frontend/` scaffold: Vite + React + TypeScript, React Router,
  TanStack Query (server state), Zustand (client state), Tailwind +
  shadcn/ui. Builds and type-checks; no routes or pages yet.
- `scripts/dev.sh` to bring the above up together.

## Quick start

```bash
./scripts/dev.sh
```

Seeds `infra/.env` and `frontend/.env.local` from their `.env.example`
files (never overwrites an existing one — edit them for non-default
credentials), starts the infra containers, waits for the four that
declare a healthcheck to report healthy, then runs the Vite dev server.

```bash
./scripts/dev.sh infra      # containers only
./scripts/dev.sh frontend   # Vite only, assumes infra is already up
./scripts/dev.sh status     # what is running
./scripts/dev.sh logs redis # tail one service
./scripts/dev.sh down       # stop, keep data
./scripts/dev.sh reset      # stop AND delete volumes (prompts first)
```

| | |
|---|---|
| Frontend | http://localhost:5173 |
| Postgres (coordinator) | localhost:**5433** — remapped, not the default 5432 |
| PgBouncer | localhost:6432 |
| Redis | localhost:**6380** — remapped, not the default 6379 |
| Kafka | localhost:29092 |
| Schema Registry / Connect | localhost:8082 / localhost:8083 |
| Vault | localhost:8200, token `dev-root-token` |
| MinIO API / console | localhost:9000 / localhost:9001 |

**`dev.sh` starts no backend service.** One now exists — auth-service, on
:8081 — but it is started separately:

```bash
./gradlew :backend:auth-service:bootRun
```

Every other `backend/*` module is still build config only. api-gateway is
not on :8080, so `/api/*` calls from the frontend fail at the Vite proxy
even with auth-service up.

Gradle modules still compile on their own:

```bash
./gradlew build
```

## Modules

| Module | Phase | Purpose |
|---|---|---|
| `backend/auth-service` | 1 | Identity, JWT |
| `backend/api-gateway` | 1 | Edge routing, JWT validation |
| `backend/user-service` | 1 | Profile |
| `backend/event-service` | 2 | Events, sessions |
| `backend/venue-service` | 2 | Venues, seat maps |
| `backend/search-service` | 2 | Discovery (Kafka-fed ES projection) |
| `backend/inventory-service` | 3 | Seat state — the concurrency core |
| `backend/booking-service` | 3 | Saga orchestration |
| `backend/payment-service` | 3 | Payment ledger, Stripe |
| `backend/ticket-service` | 3 | Issuance, transfer/resale |
| `backend/notification-service` | 4 | Email/SMS/push |
| `backend/fraud-service` | 4 | Risk scoring |
| `backend/analytics-service` | 4 | Organizer dashboards |
| `backend/queue-service` | 5 | Virtual waiting room |
| `backend/media-service` | 5 | Video trailers |

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
