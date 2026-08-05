# Vault Index

Master catalog. Read this first, every session, before touching
architecture, code, or planning. See `second-brain/CLAUDE.md` for the full
ruleset this vault operates under.

# System

- [[system-overview]] — components, data ownership, communication, failure boundaries (target design; nothing implemented yet)

# Architecture

- [[system-overview]] (`wiki/architecture/system-overview.md`)

# Projects

One page per repo. All currently **not started** — pages document target
design only, will be rewritten from actual code once implementation begins.

- [[api-gateway]] (`wiki/projects/api-gateway.md`)
- [[auth-service]] (`wiki/projects/auth-service.md`)
- [[user-service]] (`wiki/projects/user-service.md`)
- [[event-service]] (`wiki/projects/event-service.md`)
- [[venue-service]] (`wiki/projects/venue-service.md`)
- [[search-service]] (`wiki/projects/search-service.md`)
- [[inventory-service]] (`wiki/projects/inventory-service.md`)
- [[booking-service]] (`wiki/projects/booking-service.md`)
- [[queue-service]] (`wiki/projects/queue-service.md`)
- [[payment-service]] (`wiki/projects/payment-service.md`)
- [[ticket-service]] (`wiki/projects/ticket-service.md`)
- [[notification-service]] (`wiki/projects/notification-service.md`)
- [[fraud-service]] (`wiki/projects/fraud-service.md`)
- [[analytics-service]] (`wiki/projects/analytics-service.md`)
- [[frontend]] (`wiki/projects/frontend.md`)
- [[infra]] (`wiki/projects/infra.md`)

# Domains

None written yet — will be added once a domain has real design decisions
(inventory/booking domain expected first, being the concurrency core).

# Critical Flows

None written yet. Expected first: seat-reservation-flow, checkout-flow,
payment-flow.

# Decisions

- [[ADR-001-microservices-vs-modular-monolith]] — microservices chosen over
  modular monolith, 12-service breakdown, full rationale per service.
- [[ADR-003-gap-list-triage]] — full feature gap-list triage: 2 new
  services (fraud, analytics), rest folded into existing services or
  documented as cross-cutting concepts, some explicitly deferred.
- [[ADR-002-seat-locking-strategy]] — hybrid Redis fast-gate + Postgres
  `FOR UPDATE` + unique-constraint backstop for seat holds. Postgres
  remains sole source of truth; Redis never authoritative.
- [[ADR-004-redis-cluster-sharding]] — Redis Cluster + hash-tagged
  sharding for global scale; supersedes ADR-002's Sentinel note (Cluster
  failover replaces Sentinel). Explicitly ahead of this project's actual
  target load, built for learning.
- [[ADR-005-postgres-sharding]] — Postgres sharding by `event_id`/region
  for global scale; also explicitly ahead of actual target load.
- [[ADR-006-saga-booking-orchestration]] — orchestrated Saga formalizing
  booking-service's hold→payment→confirm compensation chain.

# Infrastructure

None written yet.

# Data

None written yet.

# APIs

None written yet.

# Security

None written yet.

# Testing

None written yet.

# Concepts

- [[cross-cutting-concerns]] — idempotency, tracing/observability, feature
  flags, GDPR, audit logging, CDN.

# People

Skipped — solo project.

# Open Questions

See `wiki/architecture/system-overview.md#open-questions` for the current
live list. Summary:

- Kafka topic/event schema not yet designed.
- Build order across 14 services not yet decided.
- api-gateway technology choice not yet decided.
- fraud-service fail-open vs fail-closed — not decided.
- Dynamic/surge pricing — deferred, see [[ADR-003-gap-list-triage]].
- Hold TTL duration — not decided ([[inventory-service]]).
- Payment-succeeded-but-hold-expired refund path frequency — may need
  grace-period mitigation, see [[ADR-002-seat-locking-strategy]].
