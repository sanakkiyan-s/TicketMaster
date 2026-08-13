---
title: ADR-022 SSE Connection-Level Admission Control
type: decision
sources: []
related: [[flows/seat-availability-live-updates]], [[api-gateway]], [[infra]], [[ADR-004-redis-cluster-sharding]], [[ADR-014-anti-bot-anti-scalper]], [[ADR-015-observability-stack]], [[inventory-service]], [[queue-service]]
created: 2026-08-08
last-updated: 2026-08-08
---

Status: Accepted

# Context

[[api-gateway]] and [[infra]] already document two request-level rate-limit
layers: Nginx `limit_req_zone` (coarse, per-IP, edge) and Spring Cloud
Gateway's Redis-backed token bucket (per-user, per-endpoint, app-level).
Neither was designed with a long-lived SSE connection in mind — they rate
the *rate of requests*, not *how many connections stay open at once*.
[[flows/seat-availability-live-updates]] documents inventory-service
instances holding open SSE connections for the live seat map, but its own
Observability section is explicitly incomplete and says nothing about a
cap on concurrent connections per instance, per user, or globally. This
gap surfaced from a direct comparison against a real-production 4-layer
pattern (per-user / per-IP-device / per-server / global admission) during
this session — the vault only covered two of the four for this flow.

# Requirements / Constraints

- Must not let one client open unbounded duplicate SSE connections (tab
  spam, retry-storm client bugs, or a scripted stampede).
- Must protect a single inventory-service instance's own resource limits
  (file descriptors, memory per open connection) — this is a capacity
  problem, not a fairness problem.
- Must not introduce a synchronous global gate on a read-only,
  non-scarce resource — that would misapply [[ADR-014-anti-bot-anti-scalper]]/
  queue-service's admission-control pattern, which exists specifically
  because *seats* are scarce. Watching the seat map is not.
- Must degrade predictably, not silently, per this project's convention
  of naming fail-open vs fail-closed behavior explicitly
  ([[ADR-004-redis-cluster-sharding]], [[ADR-012-jwt-lifecycle]]).
- Should reuse existing infra (Nginx, gateway Redis token bucket,
  Prometheus/Mimir autoscale signal from [[ADR-004-redis-cluster-sharding]])
  rather than stand up new stack for a bounded problem.

# Options Considered

## A — Do nothing beyond existing per-user/per-IP request-rate limits

Rejected. Request-rate limiting doesn't bound concurrent *open*
connections — a client under the request-rate limit can still hold
thousands of simultaneous SSE streams open. Doesn't address the actual
resource (file descriptors, per-connection memory on the instance).

## B — Route SSE connection-open through queue-service, same as booking admission

Rejected. Seat-map viewing is not a scarce resource — misapplies
[[ADR-014-anti-bot-anti-scalper]]'s fairness/admission mechanism (built
for a race over finite seats) to a read subscription, adding
queue-service latency and complexity to ordinary browsing for no
structural benefit. Would also make queue-service a dependency of a flow
that has nothing to do with the thing queue-service protects.

## C — Layered capacity protection: per-user connect-rate limit + per-instance concurrent-connection cap + autoscale-driven global capacity

Extends the two layers that already exist rather than replacing them,
adds the two that are actually missing (per-instance cap, per-IP/device
concurrent-connection limit), and treats "too many total connections" as
a capacity/autoscaling problem, not an admission-control problem — the
correct framing since nothing is being rationed.

# Decision

**Option C.** Four layers, each targeting a different failure mode:

```
1. Per-user            api-gateway's existing Redis Lua token bucket,
                        extended to the SSE connect endpoint itself:
                        GET /api/sessions/{id}/seat-updates capped at a
                        low connect-rate (not the stream duration).
                        Reuses [[api-gateway]]'s existing mechanism, no
                        new infra. *Bounds: connection-open rate.*

2. Per-IP/device        Nginx `limit_conn` (concurrent connections),
                        distinct from the existing `limit_req_zone`
                        (request rate) already documented in [[infra]].
                        Catches pre-auth abuse (connection floods before
                        a JWT is even checked) at the edge, before the
                        JVM. *Bounds: concurrent connections per IP.*

3. Per-instance         Each inventory-service instance tracks its own
                        open-SSE-connection count in local memory (no
                        Redis round-trip — must be fast and exact, not
                        eventually-consistent). Past a configured max,
                        reject new connections with 503 + `Retry-After`,
                        not a silent drop. *Bounds: the actual resource
                        (fds, per-connection memory) directly.*

4. Global               No synchronous global admission gate. Aggregate
                        connection count surfaces as a Prometheus metric
                        (tier-1 per [[ADR-015-observability-stack]]'s
                        cardinality tiering — one gauge per instance,
                        not per-connection) feeding the same reactive
                        autoscale path [[ADR-004-redis-cluster-sharding]]
                        already established for Redis pressure. More
                        instances come up; individual per-instance caps
                        (layer 3) are what actually protects each running
                        instance in the meantime. *Bounds: aggregate
                        capacity, via elasticity not rationing.*
```

**Explicit failure-mode split, matching this project's fail-open/closed
convention:** layer 3's per-instance rejection is a **deliberate
fail-closed on capacity** — an instance at its cap refuses new
connections rather than accept them and risk an OOM/fd-exhaustion crash
that would drop *existing* connections too. This is not the same class of
decision as [[ADR-012-jwt-lifecycle]]'s revocation fail-closed (security
state), but the same reasoning shape: degrading toward "some new
connections wait" is acceptable, degrading toward "the instance falls
over and drops everyone" is not.

# Why

The real scarce resource in this system is seats/holds, already protected
by [[ADR-002-seat-locking-strategy]] and [[ADR-014-anti-bot-anti-scalper]].
An open SSE connection is a read subscription — the risk it poses is
resource exhaustion on the serving instance, not an unfair race outcome.
Treating it as a capacity problem (per-instance caps + autoscaling) rather
than a fairness problem (global admission queue) matches the actual
failure mode and avoids overloading queue-service with a concern it
wasn't designed for.

# Consequences

**Easier:** reuses three pieces of infra that already exist (gateway
token bucket, Nginx, Prometheus/Mimir autoscale path) instead of standing
up new admission infrastructure; per-instance caps align directly with
the real constraint (fds/memory) instead of an arbitrary global number
that would need constant retuning as instance count changes.

**Harder:** per-instance cap needs real tuning against actual
per-connection memory cost, currently unmeasured; Nginx `limit_conn`
needs its own config distinct from the existing `limit_req_zone`, easy to
conflate during setup; autoscale reaction time is not instant, so a
sudden stampede can still hit per-instance 503s for a short window before
new instances absorb load — acceptable (clients retry with backoff) but
worth naming so it isn't mistaken for a bug later.

# Revisit When

- If SSE connection load measurably degrades booking-critical shared
  infra (Redis, Postgres connection pool) during a real on-sale — that
  would be evidence this flow needs to move from capacity protection
  (this ADR) to genuine admission control (Option B), because at that
  point it would be competing for a resource that *is* scarce.
- Once per-instance connection cost is actually measured — replaces the
  starting-default cap below with real data.

## Open Questions

- Per-instance concurrent-connection cap and per-user connect-rate limit
  are both starting defaults, need real data — same convention as every
  other tunable in this vault.
- Whether `limit_conn` at Nginx should key on IP alone (current gateway
  precedent argues per-user is more precise, but pre-auth requests have
  no user yet) — needs a decision once the SSE endpoint's auth timing is
  finalized.
