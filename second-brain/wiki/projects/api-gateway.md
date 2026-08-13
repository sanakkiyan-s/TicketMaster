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

**Decided: Spring Cloud Gateway.** Sits behind Nginx, not instead of it —
see [[infra]] for the two-layer split (Nginx edge vs api-gateway
app-level). Fits Java-ecosystem learning goal; Nginx alone can't do
JWT/business-rule-aware work below.

Responsibilities:

- Path-based routing to all 14 backend services.
- JWT validation done **locally** — signature check against a cached JWKS
  public key, not a network call to auth-service per request. auth-service
  still owns issuance/refresh; the gateway only verifies.
- **Business-aware rate limiting** — Spring Cloud Gateway's
  `RequestRateLimiter`, Redis-backed, keyed by `userId:endpoint` (not IP).
  Runs a Lua script in Redis (token-bucket: read remaining tokens,
  decrement, write, all as one atomic op) — same reason
  [[ADR-002-seat-locking-strategy]]'s seat-lock uses an atomic Redis op
  instead of separate GET-then-SET: without atomicity, two concurrent
  requests can both read the same token count before either writes,
  letting more through than the limit allows. `RequestRateLimiter` ships
  this Lua script built in — not hand-rolled.
  Example limits: `user-42:POST /api/bookings` -> 5/min (expensive,
  abuse-prone), `user-42:GET /api/events` -> 100/min (cheap, browsing).
  Contrast with Nginx's `limit_req_zone`, which only keys off IP — can't
  distinguish real users behind shared IPs (NAT/corporate networks) from
  each other, and has no concept of "this endpoint is business-sensitive."

  **Tiered by role, not flat**: the bucket key includes the caller's role
  from [[ADR-030-organizer-admin-authorization]]'s JWT `roles` claim —
  `ORGANIZER`/`ADMIN` routes (`/organizer/*`, `/admin/*`) get separate,
  higher limits than plain `USER` traffic on the same endpoint shape,
  since a legitimate organizer dashboard or an admin bulk-cancel
  ([[ADR-028-event-cancellation-mass-refund]]) issues a real burst of
  calls a normal buyer never would. Concretely: rate-limit key becomes
  `role:userId:endpoint` (e.g. `ADMIN:user-7:POST /admin/events/cancel`),
  same Lua token-bucket mechanism, just a different bucket per role
  instead of one bucket assumed to fit every caller. Anonymous/`USER`
  buckets stay the tightest tier — unauthenticated and regular-buyer
  traffic is exactly the abuse surface this limiter exists for.
- Correlation ID injection (per [[cross-cutting-concerns]]).
- Circuit breakers (Resilience4j) around downstream service calls.
- CORS handling.
- Does not own business data.

## Gap

Everything.
