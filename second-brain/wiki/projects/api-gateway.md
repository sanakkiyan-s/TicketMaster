---
title: api-gateway
type: project
sources: []
related: [[system-overview]], [[auth-service]], [[ADR-039-dual-tier-login-rate-limiting]]
created: 2026-08-05
last-updated: 2026-08-19
---

## Purpose

Single entry point for all client traffic. Routes requests to backend
services, validates auth tokens at the edge, applies rate limiting.

## Current Implementation

**Wrong as of 2026-08-19: this said "Not started" while
`JwtAuthenticationFilter`, routing, rate limiting, and a Kafka revocation
consumer all already existed.** Verified against `backend/api-gateway/src/`:

- `jwt/JwtAuthenticationFilter` — local JWKS-cached signature validation,
  then (post-signature) the revocation check below. Rejects with one
  consistent 401 shape for every JWT failure — bad signature, expired,
  unknown kid, and revoked all look identical to the caller.
- `jwt/JwksCache` — background-refreshed public-key cache; the unknown-kid
  emergency-refetch backstop from [[ADR-012-jwt-lifecycle]].
- `jwt/revocation/` — `RevocationConsumer` (raw `KafkaConsumer` on its own
  thread, `assign()`+`seekToBeginning`+`endOffsets` comparison to
  precisely detect startup catch-up rather than approximate it),
  `RevocationStore` (the in-memory map), `RevocationCleanupScheduler`
  (TTL tombstoning), `RevocationHealthIndicator` (readiness fails closed
  until caught up — [[ADR-012-jwt-lifecycle]]'s named exception to this
  project's usual fail-open convention; a later mid-flight Kafka
  disconnect does NOT flip readiness back down, it logs and keeps serving
  the last-known map). Single-region only — ADR-012's cross-region
  MirrorMaker amendment is not built. **Verified live, 2026-08-19**: a
  real `logout-everywhere` call flowed through the outbox → Debezium →
  `auth.revocation` → this consumer → a stale token rejected at
  `JwtAuthenticationFilter` with 401, a post-revocation token unaffected
  — see [[auth-service]] for the three infra bugs that surfaced and were
  fixed to get there.
- `ratelimit/RateLimitConfig` — the `ipKeyResolver` bean backing
  route-level `RequestRateLimiter` filters on login/register (see below).
- `application.yml` — routes for auth-service (including the
  login/register-specific rate-limited routes), Kafka bootstrap config,
  readiness probe group including both `jwks` and `revocation`.

**Verification status**: `./gradlew :backend:api-gateway:test` green as
of 2026-08-19 (24 tests spanning filter, rate-limit-store, and
Kafka-Testcontainers revocation suites).

## Target Design

**Decided: Spring Cloud Gateway.** Sits behind Nginx, not instead of it —
see [[infra]] for the two-layer split (Nginx edge vs api-gateway
app-level). Fits Java-ecosystem learning goal; Nginx alone can't do
JWT/business-rule-aware work below.

### Route configuration format

**Decided 2026-08-14: YAML for route definitions, Java for filter
behaviour.** Not a preference between the two — a split by what is being
expressed.

- **YAML** (`application.yml`, mounted from the per-service k8s ConfigMap
  per [[ADR-033-non-secret-config-management]]) owns the static routing
  table: `spring.cloud.gateway.routes[]` — route id, `Path=` predicate,
  `uri: lb://<service>`, which filters apply, and their numeric knobs
  (rate-limit replenish/burst, timeouts, retry counts). These are exactly
  the values ADR-033 exists to make changeable per environment without a
  rebuild, and they differ between Compose-local and each region. A Java
  DSL would compile the routing table into the artifact and force a
  rebuild+redeploy to retune a burst capacity — the opposite of what
  ADR-033 decided.
- **Java** owns behaviour: custom `GatewayFilter`/`GlobalFilter` beans and
  the `KeyResolver`. Anything with a conditional, a lookup, or a claim
  read — JWKS-cached JWT validation ([[ADR-012-jwt-lifecycle]], including
  the fail-closed Kafka revocation-map check), the role-tiered rate-limit
  key `role:userId:endpoint` ([[ADR-030-organizer-admin-authorization]]),
  correlation-ID injection ([[cross-cutting-concerns]]), and the
  coarse ORGANIZER/ADMIN route gate. Expressing that logic as YAML
  configuration would be encoding a program in a data format.

Rule of thumb: **YAML declares which route exists and with what numbers;
Java decides what happens to a request.** Route ordering stays explicit in
YAML — no reliance on bean-registration order.

Consequence: the ADR-034 CI diff of the generated OpenAPI spec and the
git-tracked ConfigMap change together are the reviewable surface for any
routing change; no route exists that is invisible in YAML.

Responsibilities:

- Path-based routing to all 14 backend services.
- JWT validation done **locally** — signature check against a cached JWKS
  public key, not a network call to auth-service per request. auth-service
  still owns issuance/refresh; the gateway only verifies.
- **Business-aware rate limiting for authenticated routes** — Spring
  Cloud Gateway's `RequestRateLimiter`, Redis-backed, keyed by
  `userId:endpoint` (not IP). `RequestRateLimiter` ships its own atomic
  Redis Lua script for the token-bucket read-decrement-write — not
  hand-rolled — same reason [[ADR-002-seat-locking-strategy]]'s seat-lock
  uses an atomic Redis op instead of separate GET-then-SET: without
  atomicity, two concurrent requests can both read the same token count
  before either writes, letting more through than the limit allows.
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

  **`/api/v1/auth/login` and `/api/v1/auth/register` are the one gap this
  scheme has no answer for** — there is no `userId` yet at the point
  credentials are being exchanged. [[ADR-039-dual-tier-login-rate-limiting]]
  resolves it with a second, independent layer: the gateway runs a loose,
  IP-keyed `RequestRateLimiter` on these two routes specifically
  (`RateLimitConfig.ipKeyResolver`, `auth-service-login`/
  `auth-service-register` routes in `application.yml` — 60/min and 20/min),
  purely as a volumetric shield against floods, since the gateway cannot
  safely buffer the request body to key by username without breaking its
  streaming WebFlux/Netty design. The tight, per-account defense (5 failed
  attempts/min by username, then a DB-backed 15-minute lockout after 10)
  lives entirely in auth-service's `LoginAttemptLimiter`, where the body
  has already been parsed — see [[auth-service]].
- Correlation ID injection (per [[cross-cutting-concerns]]).
- Circuit breakers (Resilience4j) around downstream service calls.
- CORS handling.
- Does not own business data.

## Gap

Also wrong until 2026-08-19 (see above). Real remaining gaps: routing
only covers auth-service so far, not the other 13 backend services (none
of them have code yet, so nothing to route to). Circuit breakers
(Resilience4j) mentioned in Responsibilities above are not implemented.
Cross-region revocation mirroring (ADR-012's amendment) is single-region
only for now. CORS handling not yet configured. Correlation-ID injection
not yet implemented.
