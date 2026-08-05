# Activity Log

Append-only. Never edit or delete a prior entry.

## 2026-08-05

### Added
- Initial second-brain vault scaffold: `raw/`, `wiki/` (architecture, concepts, decisions, domains, flows, projects, infrastructure, api, data, security, testing), `journal/`, `content/`, `.claude/commands/` (`/ingest`, `/query`, `/lint`, `/log`), `.claude/ingest-state.json`.
- Root `CLAUDE.md` and `second-brain/CLAUDE.md` vault rulebooks.
- `wiki/architecture/system-overview.md` — target component diagram, data ownership table, communication model.
- `wiki/decisions/ADR-001-microservices-vs-modular-monolith.md`.
- `wiki/projects/*.md` — one page per repo (api-gateway, auth-service, user-service, event-service, venue-service, search-service, inventory-service, booking-service, queue-service, payment-service, ticket-service, notification-service, frontend, infra), all status "not started."
- Repo skeleton directories created: `backend/{api-gateway,auth-service,user-service,event-service,venue-service,search-service,inventory-service,booking-service,queue-service,payment-service,ticket-service,notification-service}`, `frontend/`, `infra/`.

### Decisions
- Microservices architecture chosen over modular monolith, 12-service breakdown matching the original domain diagram (not the initially-proposed 6-service merge) — see [[ADR-001-microservices-vs-modular-monolith]]. Driven by explicit project goal: learn distributed system design by building something close to the real thing, for a system intended to reason about ~100,000+ concurrent users on a popular on-sale, not to minimize service count for portfolio neatness.

### Opened Questions
- Seat locking strategy (optimistic/pessimistic/Redis-assisted) — needs its own ADR before inventory-service is built.
- Kafka topic/event schema — not yet designed.
- Build order across the 14 services — not yet decided.
- api-gateway technology (Spring Cloud Gateway vs. simpler reverse proxy) — not yet decided.

## 2026-08-05 (cont.)

### Added
- `wiki/decisions/ADR-003-gap-list-triage.md` — full triage of extended
  feature gap list (multi-currency/tax, refunds, group limits, loyalty,
  bundles, add-ons, surge pricing, CAPTCHA, analytics, audit log, CDN,
  idempotency, tracing, feature flags, GDPR, fraud detection).
- `wiki/projects/fraud-service.md`, `wiki/projects/analytics-service.md`.
- `wiki/concepts/cross-cutting-concerns.md` — idempotency, tracing,
  feature flags, GDPR, audit logging, CDN.
- Repo dirs: `backend/fraud-service`, `backend/analytics-service`.

### Decisions
- 2 new services added (fraud-service, analytics-service), each with an
  independent scaling/ownership justification — see
  [[ADR-003-gap-list-triage]].
- Most gap-list items folded into existing services' data model (refunds
  into payment-service, bundles into ticket/event-service, etc.) rather
  than becoming new services.
- Audit logging explicitly decided NOT to be a standalone service.
- Dynamic/surge pricing explicitly deferred — no ADR written for it yet,
  tracked as an open question instead of a fabricated decision.

### Architecture
- Backend service count: 12 → 14.

### Opened Questions
- fraud-service fail-open vs fail-closed on outage — not decided.
- Seat-locking ADR (ADR-002) still not written — pessimistic-lock
  approach discussed in conversation (Postgres `SELECT ... FOR UPDATE` +
  partial unique constraint backstop, Redis limited to TTL
  scheduling/queue admission, not the lock itself) but not yet committed
  to the vault as a formal ADR.

## 2026-08-05 (cont. 2)

### Changed
- `wiki/projects/infra.md`, `wiki/architecture/system-overview.md` —
  added Nginx as edge layer in front of `api-gateway` (TLS, LB, static,
  DDoS throttling at Nginx; JWT/routing/business rate limiting stays in
  api-gateway). Documented as target design, no ADR — standard topology,
  not a contested tradeoff.

## 2026-08-05 (cont. 3)

### Added
- `wiki/decisions/ADR-002-seat-locking-strategy.md` — hybrid design:
  single-instance Redis atomic lock as fast admission gate (protects
  Postgres connection pool from hot-seat stampedes) + Postgres `SELECT
  ... FOR UPDATE` as correctness authority + partial unique constraint as
  backstop. Redis unavailable → fail open to Postgres-only path.

### Decisions
- Seat locking: rejected pure Postgres-only pessimistic locking (Option A)
  after user correctly identified its connection-pool-exhaustion failure
  mode under hot-seat stampedes — revised from the earlier "Redis adds
  marginal gain" framing to a hybrid design where Redis's real job is
  protecting the DB connection pool, not replacing Postgres as authority.
- Payment race resolved: hold-expired-after-payment-succeeded triggers
  automatic refund, not a silent drop.

### Changed
- `wiki/projects/inventory-service.md` — open questions on locking
  strategy resolved, now points to ADR-002.
- `wiki/projects/booking-service.md` — payment-race handling documented.

### Resolved Questions
- Locking strategy for inventory-service (was open, now ADR-002).
- Payment-succeeded-but-hold-expired handling (now: automatic refund).

## 2026-08-05 (cont. 4)

### Added
- `wiki/decisions/ADR-004-redis-cluster-sharding.md` — Redis Cluster,
  hash-tag key design (`{sessionId}`) for group-booking atomicity,
  sharded pub/sub for seat-availability broadcast. Supersedes ADR-002's
  Sentinel note.
- `wiki/decisions/ADR-005-postgres-sharding.md` — Postgres sharding by
  `event_id`/region for global scale.
- `wiki/decisions/ADR-006-saga-booking-orchestration.md` — orchestrated
  Saga for booking-service (hold/payment/confirm steps + compensations),
  formalizing the earlier ad-hoc payment-race refund rule.

### Decisions
- Explicitly decided to design Redis Cluster + Postgres sharding + Saga
  orchestration even though they exceed this project's realistic target
  load (one popular on-sale) — driven by the project's stated learning
  goal, same precedent as [[ADR-001-microservices-vs-modular-monolith]].
  Each ADR states plainly what's "needed at target scale" vs "needed at
  true global scale" so the vault stays honest about the difference.
- Identified and documented a new risk: hash-tag-per-session sharding
  creates a hot-shard concentration risk for a single viral event — not
  yet mitigated, tracked as an open question on ADR-004.

### Opened Questions
- Hot-shard mitigation (ADR-004).
- Shard routing mechanism for Postgres sharding (ADR-005).
- Saga-state table schema, compensation-of-compensation handling (ADR-006).

## 2026-08-05 (cont. 5)

### Decisions
- ADR-004 hot-shard mitigation resolved: hybrid tagging — default
  `{sessionId}` for most events, `{sessionId}:{sectionId}` for
  admin/system-flagged high-demand events, with two-phase compensating
  lock for cross-section group bookings on flagged events (reuses
  ADR-006's failure-handling shape).
- ADR-004 node-count/resharding resolved: chose autoscaling (Option 2)
  explicitly for learning value — proactive pre-scaling for known
  high-demand events (via a capacity-planner job) plus reactive
  Prometheus-triggered autoscaling using Redis's own `--cluster
  rebalance` tool as the safety net. Scale-in stays manual.

### Changed
- `wiki/decisions/ADR-004-redis-cluster-sharding.md` — both open
  questions resolved into the Decision section; new finer-grained open
  questions recorded (threshold tuning, flag criteria, capacity-planner
  reliability).

### Resolved Questions
- Hot-shard mitigation for viral events (ADR-004).
- Cluster node count / resharding process (ADR-004).
