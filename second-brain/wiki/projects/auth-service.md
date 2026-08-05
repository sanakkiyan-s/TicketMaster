---
title: auth-service
type: project
sources: []
related: [[system-overview]], [[user-service]], [[api-gateway]]
created: 2026-08-05
last-updated: 2026-08-05
---

## Purpose

Identity and access: registration, login, JWT issuance/refresh, role
management. Called on nearly every authenticated request (directly or via
gateway-side token validation) — highest-QPS service in the system.

## Current Implementation

Not started. `backend/auth-service` is an empty directory.

## Target Design

- Spring Boot, Spring Security, JWT (access + refresh token pair).
- Owns: credentials (hashed), roles, refresh token store, sessions.
- PostgreSQL for durable storage of users/roles/refresh tokens.
- Deliberately separate from user-service (profile/preferences) — auth
  needs to stay fast and highly available; profile data is low-QPS CRUD
  with different scaling needs. See
  [[ADR-001-microservices-vs-modular-monolith]].

## Gap

Everything.

## Open Questions

- Refresh token storage: DB vs. Redis — not decided.
- Token revocation strategy (blocklist vs. short-lived access + rotation) — not decided.
