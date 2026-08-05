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
  TBD), Redis, Kafka, Elasticsearch.
- CI/CD: build+test per service, likely GitHub Actions.
- **Nginx sits in front of `api-gateway`**, two distinct layers, not
  redundant:
  - Nginx (edge): TLS termination, static asset serving, connection-level
    load balancing across api-gateway instances, coarse
    connection-flood/DDoS throttling — before any request reaches the JVM.
  - `api-gateway` (Spring Cloud Gateway, app-level): JWT validation,
    business-aware per-user/per-endpoint rate limiting, route-to-service
    logic, correlation ID injection.
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
