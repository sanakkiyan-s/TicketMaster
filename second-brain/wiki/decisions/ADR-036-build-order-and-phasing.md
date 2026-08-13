---
title: ADR-036 Build Order and Phasing
type: decision
sources: []
related: [[ADR-001-microservices-vs-modular-monolith]], [[ADR-002-seat-locking-strategy]], [[ADR-006-saga-booking-orchestration]], [[ADR-008-testing-strategy]], [[system-overview]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: Accepted

# Context

`index.md`'s Open Questions has named "build order across 15/16 services
not yet decided" since this vault's earliest session — every other
architectural gap raised across ADR-001 through ADR-035 is now closed,
making this the last standing item before implementation can start with
a real sequence instead of an ad hoc one.

# Requirements / Constraints

- Order must follow real dependency edges (what a service needs to
  exist/be callable to be buildable or testable), not org-chart or
  feature-priority ordering.
- The concurrency-critical core (seat locking, saga orchestration) must
  be built and stress-tested EARLY, while it's cheap to change, not
  discovered broken after five more services already depend on it.
- Scale/multi-region work explicitly built "ahead of actual load" for
  learning value ([[ADR-004-redis-cluster-sharding]],
  [[ADR-005-postgres-sharding]], [[ADR-016-multi-region-cdn]]) must not
  block a correct single-region system from shipping first.
- Must reuse [[ADR-008-testing-strategy]]'s existing CI tiers as the
  gate between phases, not invent a separate phase-gate mechanism.

# Options Considered

## A — Build services in the order they were designed (ADR sequence)

Cons: ADR sequence tracks when a gap was DISCOVERED during design
sessions, not dependency order — e.g. ADR-035 (payment reconciliation)
was written after ADR-017 (media-service) purely because the audit found
it later, not because it depends on media-service.

## B — Dependency-and-risk-ordered phases, concurrency core built and proven before secondary features

Pros: mirrors how the system will actually fail if built wrong — a
correctness bug in the seat-lock core discovered after five services
already integrate against it is far more expensive to fix than one
discovered in isolation. **Chosen.**

## C — Build every service in parallel, integrate at the end

Cons: this project's own concurrency-proof CI gate (ADR-008) requires
inventory-service and booking-service to exist and be stress-tested
together before anything meaningfully depends on them being correct —
parallel build with no ordering defeats that gate's purpose entirely.

# Decision

**Option B.** Six phases, each gated by [[ADR-008-testing-strategy]]'s
existing CI tiers — a phase is "done" when its services pass the
per-merge tier (full concurrency suite + chaos tests where applicable),
not merely when code exists.

```
Phase 0 — Platform bootstrap (no product code)
  Postgres/Citus + PgBouncer, Redis Cluster, Kafka + Debezium + Schema
  Registry, Vault, ConfigMaps, observability stack (ADR-015), CI
  pipeline skeleton (ADR-008). Everything downstream depends on this
  existing; nothing product-specific is buildable before it.

Phase 1 — Identity & edge
  auth-service, api-gateway, user-service.
  Gate: JWT issuance/validation working end-to-end through the gateway.
  Nothing else can be meaningfully built or tested without this.

Phase 2 — Catalog (read-side, deliberately low risk)
  event-service, venue-service, search-service (basic index).
  No money, no concurrency yet — stabilizes before the hard part.

Phase 3 — Transaction core (the actual hard part — built and
  stress-tested BEFORE secondary features, not after)
  inventory-service -> booking-service -> payment-service ->
  ticket-service, in that order (each is the prior's real dependency).
  Gate: ADR-002/006's concurrency-proof CI tier passes — zero double-
  sell, zero paid-and-unresolved bookings, under the full N=200x50
  chaos matrix — before Phase 4 begins. This is the one phase where
  "gate passed" is load-bearing, not a formality: everything after this
  phase assumes the core is correct.

Phase 4 — Support consumers
  notification-service, fraud-service, analytics-service.
  Pure Kafka consumers of events Phase 3 already emits — genuinely
  nothing to consume before Phase 3 exists, so building these earlier
  would mean building against a mocked event stream for no benefit.

Phase 5 — Secondary features, layered on a proven core
  queue-service (only matters at real demand), media-service, ticket
  transfer/resale (ADR-029), cancellation/mass-refund (ADR-028),
  dispute/reconciliation (ADR-035).

Phase 6 — Scale and multi-region hardening
  ADR-004/005's Cluster/Citus sharding at real scale, ADR-016
  multi-region, cross-region revocation mirror (ADR-012's amendment),
  CDN (ADR-019). Deliberately last: these ADRs are explicit "ahead of
  actual load, built for learning" choices, not required for a correct
  single-region system — sequencing them last means a working system
  exists well before this phase, matching this vault's honest framing
  of these as learning-value decisions rather than day-one necessities.
```

# Why

Mirrors real failure cost, not convenience: the concurrency core is the
one place a mistake compounds across every later phase, so it's proven
in isolation first, matching the same "verify the hard part before
building on top of it" instinct already applied throughout this vault
(ADR-002's amendment process itself, ADR-008's dedicated concurrency-proof
CI tier). Scale-hardening last keeps the explicit "ahead of actual load"
ADRs from gating a working system's existence.

# Consequences

**Easier:** a real, dependency-justified order to follow instead of an
implicit or ad hoc one; the concurrency core gets proven before five
other services quietly assume it's correct; scale work doesn't block a
working MVP.

**Harder:** Phase 3's gate is a real hard stop — if the concurrency-proof
CI tier doesn't pass, Phase 4 does not start, even under schedule
pressure; this is the phase order's entire point, stated so a future
reader doesn't quietly relax it.

# Revisit When

- If a phase's services turn out to have a dependency this ordering
  missed (discovered during actual implementation) — amend this ADR
  rather than silently building out of order.

## Open Questions

- None outstanding — this closes `index.md`'s standing build-order
  question.
