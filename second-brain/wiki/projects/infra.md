---
title: infra
type: project
sources: []
related: [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Local orchestration (Docker Compose for all 14 services + Postgres, Redis,
Kafka, Elasticsearch) and eventual CI/CD config. Owns the Nginx edge
config. Not an application service — no business logic lives here.

## Current Implementation

Not started. `infra` is an empty directory.

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

Everything.

## Open Questions

- Per-service database vs. shared Postgres instance with per-service
  schemas for local dev (production would be per-service instances
  regardless) — not decided.
- CI/CD provider and deployment target — not decided.
