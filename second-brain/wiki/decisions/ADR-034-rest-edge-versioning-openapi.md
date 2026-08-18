---
title: ADR-034 REST Edge API Versioning and OpenAPI Generation
type: decision
sources: []
related: [[api-gateway]], [[ADR-008-testing-strategy]], [[ADR-023-grpc-internal-service-calls]], [[frontend]]
created: 2026-08-13
last-updated: 2026-08-18
---

Status: Accepted

# Context

[[ADR-023-grpc-internal-service-calls]] narrowed [[ADR-008-testing-strategy]]'s
Spring Cloud Contract to the client-facing REST edge specifically —
`api-gateway`'s public API to the frontend and any future third-party
integrator — but nothing decides how that edge surface is versioned, or
how its contract is published for consumers (the frontend team, future
API consumers) to work against. This is the one remaining REST contract
surface in the whole system after ADR-023 moved everything internal to
gRPC, so it carries a versioning/discoverability need the internal
services no longer have.

# Requirements / Constraints

- Must let a breaking edge-API change ship without forcing every client
  (frontend, any future third-party integration) to update in lockstep —
  same zero-forced-coordination principle already applied internally via
  [[ADR-027-schema-migration-strategy]]'s expand/contract discipline.
- Must give frontend (and any future consumer) a real, generated contract
  to develop against — not hand-maintained docs that drift from the code.
- Must not conflict with ADR-023's internal gRPC contracts — this is
  specifically the outward-facing REST edge, a different surface.

# Options Considered

## A — No explicit versioning, breaking changes ship as-is, frontend updates same-day

Cons: only survives because frontend and backend are the same team today
— breaks the instant a third-party integrator or a mobile app with its
own release cadence exists; not a durable decision, just deferred cost.

## B — URI path versioning (`/api/v1/...`), OpenAPI 3 generated from Spring annotations via springdoc-openapi, published at a stable `/v3/api-docs` endpoint

Pros: URI versioning is the simplest scheme to reason about for both
routing (api-gateway path-matches on it directly, no header-parsing
logic) and caching/CDN (a versioned path is trivially cacheable, unlike
a header-versioned one). springdoc-openapi generates the spec from the
same annotated controllers that already exist, so the contract can never
drift silently out of sync with the actual code — same "generated
artifact, not hand-maintained" principle as ADR-007's Avro schemas.
**Chosen.**

## C — Header-based versioning (`Accept: application/vnd.ticketmaster.v1+json`)

Pros: keeps URIs "clean." Cons: harder to test/cache/debug in practice
(every client request needs the header set correctly, curl/browser
testing needs extra care), and buys nothing this project's simpler URI
scheme doesn't already cover at this scale.

# Decision

**Option B.**

```
Versioning: /api/v1/... at api-gateway. A breaking change (removed/
  renamed field, changed status-code semantics) ships as /api/v2/...
  alongside v1, not a replacement — v1 stays live until every known
  consumer has migrated, then is deprecated on a stated timeline (not
  silently removed).

Non-breaking changes (additive fields, new endpoints) ship in place on
  the current version — no version bump needed, matches
  [[ADR-007-kafka-event-schema]]'s own backward-compatible-vs-breaking
  distinction for the same reasoning: additive is free, breaking needs
  a real coordination mechanism.

OpenAPI generation: springdoc-openapi on api-gateway (and/or per
  downstream service if a service ever needs to expose its own doc
  surface — not needed today since api-gateway is the sole edge). Spec
  auto-generates from existing `@RestController`/`@Operation` annotations,
  published at `/v3/api-docs` and rendered via Swagger UI for manual
  browsing. CI gate: same "producer's own pipeline catches drift before
  merge" shape as ADR-023's `buf breaking` and ADR-027's PII/Avro
  check — a CI step diffs the generated spec against the previous
  committed version and flags (not blocks, since REST evolution here is
  more fluid pre-launch) any removed/retyped field outside a version
  bump.
```

# Why

URI versioning plus generated-not-handwritten OpenAPI reuses this
project's established "the contract is generated from the source of
truth, never drifts, never hand-maintained" pattern (Avro/Schema
Registry for Kafka, `.proto`/`buf breaking` for gRPC) applied to the one
remaining REST surface, rather than inventing a fourth contract
philosophy for it.

# Amendment (2026-08-18) — generation is per-service, not gateway-only

The original text said springdoc runs on `api-gateway`, with per-service
doc surfaces as an "and/or ... not needed today" fallback. Implementing
the first real endpoint inverted that: **the fallback is the answer.**

Spring Cloud Gateway proxies routes; it does not see the annotated types.
`RegisterRequest`'s `@Size(min = 12)` lives in auth-service, so a spec
generated at the gateway would be either empty or hand-written — and
hand-written is exactly what this ADR exists to forbid. Each service
therefore runs `springdoc-openapi-starter-webmvc-ui` and publishes its own
`/v3/api-docs`; `api-gateway` (reactive, so `-webflux-ui`) aggregates them
into one Swagger UI. The "generated from the source of truth" principle is
unchanged; only the location of generation moves.

Version line: `2.8.x`, not the `2.6.0` originally pinned on api-gateway.
2.6.0 targets Spring Boot 3.3; this repo is on 3.5.6. Both modules now use
`2.8.9`.

Exposure: the spec and Swagger UI are permitted **unauthenticated at the
service**, which is only safe because they are internal. `api-gateway`
must not route `/v3/api-docs` or `/swagger-ui` publicly, and
`SWAGGER_UI_ENABLED=false` in production. A publicly reachable spec hands
an attacker the complete endpoint and field inventory.

Drift gate, concretely: `OpenApiSpecTest` regenerates
`backend/<service>/openapi/<service>.json` on every `test` run. CI then
runs `git diff --exit-code openapi/` and flags a changed contract. The
`servers` block is pinned to `/` rather than inferred from the request, or
the committed spec would carry a random localhost port and the diff would
fire every run for no contract reason.

# Consequences

**Easier:** frontend (and any future consumer) always has an accurate,
generated contract to develop against; breaking changes have a real
migration path instead of an implicit "update same-day" assumption.

**Harder:** running two API versions simultaneously during a migration
window is real maintenance surface — a v2 rollout means api-gateway
routes and validates against two contracts until v1's stated deprecation
date passes.

# Revisit When

- If a genuine third-party/public API program starts (API keys, rate
  tiers for external partners, a developer portal) — this ADR's scope is
  internal-frontend-facing only; a public API product would need its own
  ADR for auth/quota/SLA concerns this one doesn't cover.

## Open Questions

- Deprecation timeline policy (how long must an old version stay live
  after a new one ships) — not decided, needs a real usage-tracking
  mechanism first to know who's still on the old version.
