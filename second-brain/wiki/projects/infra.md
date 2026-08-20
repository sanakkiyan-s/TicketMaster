---
title: infra
type: project
sources: []
related: [[system-overview]], [[ADR-015-observability-stack]]
created: 2026-08-05
last-updated: 2026-08-20
---

## Purpose

Local orchestration (Docker Compose for all 14 services + Postgres, Redis,
Kafka, Elasticsearch) and eventual CI/CD config. Owns the Nginx edge
config. Not an application service — no business logic lives here.

## Current Implementation

This page was stale — it said "Not started, empty directory" as of
2026-08-05, but `infra/docker-compose.yml` and everything under it has
existed and grown since. What's actually running today (all via
`scripts/dev.sh`, profile-gated):

- **Base infra** (no profile, always up): `postgres-coordinator`,
  `postgres-worker-1` (Citus, see [[ADR-005-postgres-sharding]]),
  `pgbouncer`, `redis`, `kafka`, `kafka-connect` (Debezium, with a JMX
  Prometheus exporter javaagent baked into its own image —
  `infra/jmx-exporter/`), `schema-registry`, `vault` (dev mode), `minio`.
- **`backend` profile**: `auth-service`, `api-gateway`, `user-service` —
  the 3 services that exist. Each service's OTel Java agent + resource
  attributes are wired here (`OTEL_SERVICE_NAME` etc.), not in the
  services' own `application.yml`. auth-service's host port is `8180`,
  not the in-network `8081` — `8081`/`8083` were both already taken by
  unrelated local projects, discovered live 2026-08-20; container-internal
  port and every in-network reference (`AUTH_SERVICE_URI`,
  `JWKS_URI`) are unaffected.
- **`observability` profile** ([[ADR-015-observability-stack]], built and
  verified live 2026-08-20): `otel-collector`, `tempo`, `loki`, `mimir`,
  `prometheus` (agent mode, `remote_write`s to Mimir), `redis-exporter`,
  `grafana` (`localhost:3000`, anonymous admin in dev). All 4 storage
  backends (Tempo/Loki/Mimir/blocks — Mimir also needs ruler/alertmanager
  storage) use **filesystem storage**, not MinIO/S3, even though MinIO is
  already running for other purposes — each tool's own documented
  simplest local/single-binary mode, avoids S3 path-style/bucket-policy
  config risk for no real benefit at this scale. See
  [[ADR-015-observability-stack]]'s Verification Status section for what
  was actually confirmed working (full cross-service trace, Loki/Tempo
  trace_id correlation, real Mimir metric series) versus explicitly
  deferred.
- Elasticsearch: not present — no search-service yet to need it, and
  ADR-015 explicitly rejected sharing it with Loki even once it exists.

Config lives under `infra/{otel-collector,tempo,loki,mimir,prometheus,
jmx-exporter,grafana,kafka-connect}/`.

## Target Design

- Docker Compose for local dev: one compose file bringing up all 14
  backend services plus Postgres (per-service schema or per-service DB —
  TBD), Redis, Kafka, Elasticsearch, **Kafka Connect + Debezium
  connectors** (one per service DB, tails WAL for the outbox pattern) and
  **Confluent Schema Registry** — full reasoning in
  [[ADR-007-kafka-event-schema]].
- CI/CD: build+test per service, likely GitHub Actions.
- **Nginx sits in front of `api-gateway`**, two distinct layers, not
  redundant — Nginx doesn't know about users or business rules, only
  connections/IPs:
  - Nginx (edge): TLS termination, static asset serving (proxied to
    CDN/object storage), connection-level load balancing across
    api-gateway instances, coarse IP-based rate limiting
    (`limit_req_zone`), health-check routing — before any request reaches
    the JVM.
  - `api-gateway` (Spring Cloud Gateway, app-level, see [[api-gateway]]
    for full detail): local JWT validation via cached JWKS, business-aware
    Redis-backed per-user/per-endpoint rate limiting (Lua-script atomic
    token bucket), route-to-service logic, correlation ID injection,
    circuit breakers.
  - Flow: `Client → Nginx → api-gateway → backend services`.
- Eventually AWS deployment target — Nginx config here is meant to mirror
  what a real load balancer/ingress would do in production.

## Gap

- CI/CD: not started (see [[ADR-038-ci-platform]] for the platform
  decision; not wired into `infra/` yet).
- Nginx edge layer: not started — Nginx doesn't exist in
  `docker-compose.yml`, requests go straight to `api-gateway`.
- The other 11 backend services and their infra (their own DBs/schemas,
  any service-specific compose entries) — not started, blocked on the
  services themselves not existing yet (see [[ADR-036-build-order-and-phasing]]).
- Elasticsearch — not started, no search-service to need it yet.
- Grafana alert live-fire test for `OutboxStalled` — rule is provisioned
  and correct, but has not actually been triggered end-to-end (see
  [[ADR-015-observability-stack]]).
- The other 4 P1 alerts ADR-015 designs (`PaidUserUnresolved`,
  `DoubleSellDetected`, `PaymentWebhooksSilent`,
  `OnSaleCriticalPathDown`) and all domain-specific SLIs — blocked on the
  domain concepts (bookings, sagas, payments, holds) not existing yet.

## Open Questions

- Per-service database vs. shared Postgres instance with per-service
  schemas for local dev (production would be per-service instances
  regardless) — not decided.
- CI/CD provider and deployment target — not decided.
