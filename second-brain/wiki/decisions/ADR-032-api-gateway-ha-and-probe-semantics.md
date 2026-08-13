---
title: ADR-032 API Gateway High Availability, Scaling, and Probe Semantics
type: decision
sources: []
related: [[api-gateway]], [[infra]], [[ADR-004-redis-cluster-sharding]], [[ADR-012-jwt-lifecycle]], [[ADR-023-grpc-internal-service-calls]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: Accepted

# Context

Every other stateful piece of this system has an explicit HA/scaling
story — Redis Cluster autoscaling ([[ADR-004-redis-cluster-sharding]]),
Postgres/Citus + Patroni failover ([[ADR-005-postgres-sharding]]), gRPC
outlier detection for internal calls ([[ADR-023-grpc-internal-service-calls]]).
`api-gateway.md` has none — despite being the single entry point for
**all** client traffic, meaning its own availability is a hard ceiling on
the whole system's, and it is the one component every other autoscaling
decision assumes is already scaled out ("autoscaled instances" appears
across ADR-004/005/024 as a given, never itself decided for the gateway).
Separately, [[ADR-012-jwt-lifecycle]] already uses the term "readiness
probe" for one specific rule (refuse readiness if the revocation-map
Kafka consumer can't reach Kafka at startup) without ever defining what
readiness/liveness mean generically for this project — that specific
rule is a correct instance of a pattern that was never written down.

# Requirements / Constraints

- Gateway must scale horizontally without any instance holding
  correctness-critical state that isn't rebuildable — a killed instance
  must never be able to silently degrade correctness (e.g. serving JWT
  validation with a stale revocation map indefinitely).
- Must span failure domains (AZs at minimum) — a single-AZ gateway
  defeats every other region/AZ-level HA decision upstream of it.
- Liveness and readiness must mean different, specific things — conflating
  them causes either needless restarts (liveness too strict) or serving
  traffic a broken instance can't actually handle (readiness too loose).
- Must generalize past the gateway alone — every service in this vault
  needs the same liveness/readiness distinction; deciding it once here
  and applying it uniformly avoids each service inventing its own rule
  ad hoc, and formalizes what ADR-012 already assumed correctly for one
  case.

# Options Considered

## A — Single gateway instance, vertical scaling only

Cons: the hard ceiling problem above — every other HA decision in this
vault becomes moot if the one component in front of all of it is a
single point of failure.

## B — Stateless horizontal scaling behind the existing Nginx layer, HPA on CPU + request rate, min 3 replicas spread across AZs

Pros: gateway already holds no durable state per [[api-gateway.md]] (JWT
validation is signature-check against a cached JWKS, rate-limit counters
live in Redis not locally, revocation map is rebuilt from Kafka on
start) — it is naturally stateless-scalable, this just makes that
explicit and operational. Reuses the two-layer Nginx/gateway split
already decided in `infra.md`, no new component.

## C — Formal liveness/readiness rule set, generic across all services, gateway as the first concrete application

Pros: turns ADR-012's one-off correct instinct (refuse readiness without
Kafka) into a named, reusable rule instead of a rule a future reader
might not recognize as a pattern and might weaken "for consistency."

# Decision

**Options B + C, both adopted.**

## Gateway HA/scaling

```
Stateless by construction — nothing new to design, formalizing what
  already falls out of api-gateway.md's existing choices:
    JWT validation: local signature check against JWKS, cache rebuilds
      from auth-service on any fresh instance, no coordination needed.
    Rate-limit counters: live in Redis (ADR-004), not gateway memory —
      any instance sees the same bucket state.
    Revocation map: rebuilt from `auth.revocation` Kafka topic on start
      (ADR-012), including its cross-region mirror (ADR-012's amendment).

Deployment: k8s Deployment, min 3 replicas, spread across availability
  zones via podAntiAffinity — matches this ADR's failure-domain
  requirement, no single AZ loss can take out the entry point.

Scaling trigger: HorizontalPodAutoscaler on CPU utilization AND request
  rate (both, whichever triggers first) — same "reactive, Prometheus-fed"
  shape as ADR-004's Redis autoscaler, applied to compute instead of
  Redis nodes, consistent rather than inventing a second autoscaling
  philosophy.

Scale-down: new instance is excluded from the LB (via readiness, below)
  until its JWKS cache and revocation map are warm — never serves traffic
  half-initialized.
```

## Liveness vs readiness — generic rule, gateway as first application

```
Liveness (k8s restarts the pod if this fails):
  "Is the process itself alive and not deadlocked?" — an internal
  health check only (event loop responsive, no unbounded thread-pool
  starvation). Must NEVER depend on an external dependency (DB, Redis,
  Kafka) — a liveness probe that pings a downstream system causes a
  cascading restart storm exactly when that downstream is already
  struggling, the opposite of what you want during an incident.

Readiness (k8s removes the pod from the LB if this fails, does NOT
  restart it):
  "Is this instance currently able to correctly serve traffic?" —
  MAY depend on external dependencies, because incorrectly serving
  traffic is worse than not serving it. Two sub-cases, matching
  ADR-012's already-decided instance of this rule:

    Fail-open dependency (e.g. a downstream service circuit-broken per
      ADR-023): readiness stays true, requests degrade or route around
      it — matches this project's general fail-open convention.

    Fail-closed dependency, named explicitly per-case, not by default
      (e.g. ADR-012's revocation-map Kafka consumer): readiness fails
      until the dependency is confirmed healthy — reserved for cases
      where serving traffic without it causes a worse, silent failure
      than not serving at all. Any future fail-closed readiness rule
      must be justified the same way ADR-012 justified its own carve-out
      from the fail-open default, not added casually.
```

# Why

The gateway's own scaling story was the one piece every other
autoscaling ADR in this vault silently assumed already existed —
formalizing it closes that assumption rather than leaving it implicit.
Naming the liveness/readiness distinction generically, rather than
leaving ADR-012's correct usage as an unexplained one-off, prevents a
future reader from either misapplying it to a new service or "fixing"
ADR-012's fail-closed carve-out toward a false consistency, the exact
risk ADR-012 already flagged in its own Consequences section.

# Consequences

**Easier:** gateway availability no longer has a hidden single point of
failure; every future service's liveness/readiness split has a named
rule to follow instead of reinventing one; ADR-012's fail-closed
exception is now legible as a deliberate application of a stated rule,
not an inconsistency.

**Harder:** every service must now explicitly classify each of its own
dependencies as fail-open-readiness or fail-closed-readiness at
implementation time — real design surface per service, not automatic.

# Revisit When

- Once real traffic data exists, tune the HPA's CPU/request-rate
  thresholds — same "starting default" category as ADR-004's 75%
  trigger.

## Open Questions

- Exact HPA thresholds (CPU %, request-rate target) — starting defaults
  only, needs real load-test data, same category as every other numeric
  tunable across ADR-004/008.
- Whether the gateway needs a dedicated startup probe (distinct from
  readiness) for the JWKS/revocation-map warm-up window specifically, to
  avoid the readiness probe's own timeout racing a slow cold start — not
  decided, implementation-time detail.
