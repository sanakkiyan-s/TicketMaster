---
title: api-gateway
type: project
sources: []
related: [[system-overview]], [[auth-service]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Single entry point for all client traffic. Routes requests to backend
services, validates auth tokens at the edge, applies rate limiting.

## Current Implementation

Not started. `backend/api-gateway` is an empty directory.

## Target Design

- Likely Spring Cloud Gateway (fits the Java/Spring ecosystem direction).
- Responsibilities: routing, JWT validation (delegates issuance to
  auth-service, but can validate signature locally to avoid a network hop
  per request), rate limiting, request logging/correlation ID injection.
- Does not own business data.

## Gap

Everything.

## Open Questions

- Spring Cloud Gateway vs. a simpler reverse proxy (e.g. Nginx) — not
  decided. Spring Cloud Gateway gives more Java-ecosystem learning value;
  Nginx is simpler. Leaning Spring Cloud Gateway but not committed — needs
  an ADR once other services exist to route to.
