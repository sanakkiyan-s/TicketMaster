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

## 2026-08-05 (cont. 6)

### Decisions
- ADR-004 fully resolved: 75% ops/sec+memory threshold (60s sustained
  window) as starting default for reactive autoscaler; "high-demand" flag
  = presale signups > 3x venue capacity OR manual admin override;
  capacity-planner reliability via buffer time + retry + heartbeat alert
  + reactive fallback; concurrently-flagged events handled by ONE
  coordinated planner run (not racing per-event jobs).
- **Correction**: replaced `redis-cli --cluster rebalance` with targeted
  `--cluster reshard` throughout ADR-004 — rebalance would redistribute
  the whole cluster evenly (disturbing unrelated low-demand events'
  data), reshard moves only the specific slots that need to move.
- Documented honest limitation: hash-tag sharding co-locates one event's
  data reliably, but a slot can coincidentally also hold an unrelated
  event's keys (16384 slots, many concurrent events) — migrating a
  flagged event's slot sweeps up any passenger event sharing it. Not a
  bug, a known property of slot-level (not per-event) migration.

### Changed
- `wiki/decisions/ADR-004-redis-cluster-sharding.md` — all three open
  questions resolved; added the physical data-migration mechanism
  (RAM-to-RAM copy via MIGRATE, ASK-redirect correctness during
  transition) as a documented "why," and manual scale-in procedure
  (reshard + CLUSTER FORGET).

### Resolved Questions
- Reactive autoscaler thresholds (75% default, pending real load-test tuning).
- High-demand flag criteria.
- Capacity-planner reliability/single-point-of-failure handling.
- rebalance vs reshard tool choice (corrected to reshard).

## 2026-08-05 (cont. 7)

### Decisions
- ADR-005 routing mechanism resolved: Citus (Postgres-native sharding
  extension), two-layer design — manual region selection (compliance,
  not hashable), then Citus's own fixed-shard-count hashing
  (coordinator + workers) within a region, same conceptual shape as
  Redis Cluster's slots.
- ADR-005 resharding resolved: `citus_rebalance_start()`, same
  physical-copy-then-update-metadata order as Redis's MIGRATE/SETSLOT.
  Deliberately NOT a consistent-hash ring — Citus's shard unit is a
  whole relational table (schema/indexes/constraints), too expensive to
  split at an arbitrary ring boundary, unlike DynamoDB/Cassandra's
  independent key-value items.
- Shard count sizing rule: pick generously for max future node count,
  not current node count — going bigger costs little, running out is
  expensive (`citus_shard_split` is heavy, not the lightweight path).

### Changed
- `wiki/decisions/ADR-005-postgres-sharding.md` — both original open
  questions resolved; new opens recorded (coordinator HA, exact shard
  count, cross-shard foreign-key/reference-table handling).

### Resolved Questions
- Shard routing mechanism (ADR-005).
- Number of shards / resharding process (ADR-005).

## 2026-08-06

### Decisions
- ADR-005's remaining open questions resolved: coordinator HA (Postgres
  streaming replication + Patroni, same mechanism reused for worker HA),
  shard count = 1024 per regional cluster (headroom over a realistic
  100-200 max future node count), cross-shard foreign keys handled via
  Citus reference tables (`events`/`venues` fully replicated to every
  worker, not sharded).

### Changed
- `wiki/decisions/ADR-005-postgres-sharding.md` — caught and fixed a
  silent edit failure from the previous session (an earlier Open
  Questions update reported success but didn't actually apply — verified
  by re-reading the file directly rather than trusting the tool result).
  Corrected the stale "Harder" consequence line referencing an unresolved
  routing layer.

### Resolved Questions
- Coordinator HA setup (ADR-005).
- Exact shard count (ADR-005).
- Cross-shard foreign key / reference table handling (ADR-005).

### Decisions
- Patroni failover mechanism resolved: etcd-based lease election (not
  Redis-style master voting), applies identically to coordinator and
  every worker HA pair. Failover timing (lease duration) needs real
  load-test/network data, same category as ADR-004's 75% threshold.
- **Correction**: earlier conversational answer "this project doesn't
  need Zookeeper" was incomplete — Patroni-managed Postgres HA needs a
  small distributed consensus store (etcd, Zookeeper's lighter modern
  cousin) for leader election. Scoped correction: applies to
  Postgres/Patroni only, Kafka's KRaft-mode conclusion (no Zookeeper for
  Kafka) still stands. The original answer was never written into the
  vault, so no other file needed correcting.

### Changed
- `wiki/decisions/ADR-005-postgres-sharding.md` — Patroni open question
  resolved with full mechanism.

### Added
- `wiki/concepts/etcd-raft-consensus.md` — full etcd/Raft internals:
  majority-write mechanism, leases/watch/compare-and-swap, contrast with
  Redis's async replication, lagging-node catch-up and leader-election
  eligibility rule, odd-node-count reasoning distinguishing etcd (pure
  voting, always odd) from Redis masters (data-sharding-driven, odd is a
  secondary preference not a hard rule). Linked from ADR-005 and indexed.

## 2026-08-06 (cont.)

### Decisions
- ADR-006's remaining open questions resolved: saga-state table schema
  (`bookings` table with hold_reference/payment_intent_id persisted
  per-step, hold_released/payment_refunded compensation flags), resume
  logic on crash, compensation reliability (idempotent compensations +
  background retry job + bounded retries with human-alert escalation).
- Added a design improvement beyond the original ADR-006: extend the
  seat hold specifically when payment submission starts (not just on
  page-interaction renewal), to shrink the payment-in-flight race window
  — documented as mitigation, not elimination; compensation remains the
  true backstop.
- Confirmed and documented: 2PC is used ONLY for Citus reference-table
  writes (a single coordinated system); the booking flow across
  independent microservices deliberately uses Saga instead, specifically
  to avoid 2PC's cross-service blocking/coupling anti-pattern. During a
  worker failover gap, a reference-table 2PC write fails/times out
  entirely (all-or-nothing) rather than partially succeeding — caller
  needs retry-with-backoff, acceptable since reference tables are
  written rarely.

### Changed
- `wiki/decisions/ADR-006-saga-booking-orchestration.md` — added saga
  schema, compensation reliability design, hold-extension mitigation,
  and a full worked example of the payment-race scenario. Open questions
  narrowed to just the specific tunable numbers (retry backoff/escalation
  timing, hold-extension margin).
- Filled in concrete starting numbers: compensation retry backoff
  1min/5min/30min then escalate (~35-40 min total, faster than the
  earlier flat 1-hour guess, deliberately — money/trust-sensitive);
  hold-extension threshold 5 min minimum on payment submission, 15 min
  hard ceiling total. Retry timing explicitly flagged as needing real
  production data to finalize; hold-extension numbers are reasoned
  defaults from realistic payment-timing analysis.

### Added
- `wiki/flows/seat-availability-live-updates.md` — first `wiki/flows/`
  page. Documents the SSE + Redis Pub/Sub design that had been discussed
  at length in conversation but never persisted — closes that gap.

### Changed
- `wiki/index.md` — added the new flow page; corrected a stale open
  question (payment-succeeded-but-hold-expired was already resolved by
  ADR-006, index hadn't caught up); flagged SSE observability details as
  a genuinely new, still-open item.

### Resolved Questions
- Payment-succeeded-but-hold-expired refund path (was stale in index,
  actually resolved by ADR-006 — corrected).

## 2026-08-06 (cont. — multi-agent architecture review)

Ran four parallel read-only architect agents against the decided
architecture (multi-region/CDN, observability, security, testing). They
found defects in already-accepted ADRs, not just new gaps. Amendments
below were written; the larger new designs are pending write-up.

### Decisions
- api-gateway technology resolved: Spring Cloud Gateway behind Nginx.
- fraud-service resolved: fail-open on outage, every skipped check
  logged for audit.
- Hold TTL resolved: 5 min base, flat across events.
- Hold renewal amended: "renew on every page interaction" DROPPED
  (unbounded per-interaction DB writes under load). Single extension
  checkpoint at payment submission (<3 min remaining -> +5 min), 15 min
  hard ceiling. Writes per booking now flat O(1).

### Added
- `wiki/decisions/ADR-007-kafka-event-schema.md` — topic-per-event-type,
  partition key = aggregate ID, event envelope, Transactional Outbox +
  Debezium/CDC, DLQ, Avro + Schema Registry compatibility rules.

### Changed — ADR amendments from the review
- **ADR-002 (CRITICAL)** — the partial unique index named as "the actual
  correctness guarantee" would be enforced PER-SHARD under ADR-005's
  Citus distribution, because it omitted the distribution column.
  Amended to `UNIQUE (event_id, session_id, seat_id) WHERE status IN
  (...)`. Must be settled before inventory-service is built.
- **ADR-002** — "fail open" was unimplementable as written: no Redis
  command timeout or circuit breaker, so a *blackholed* Redis (accepts
  connection, never responds) would block every hold until thread-pool
  exhaustion — worse than the failure the ADR exists to prevent. Added
  ~50ms timeout + Resilience4j breaker.
- **ADR-002** — SQL `now()` replaced by application-supplied timestamps
  from an injected Clock (deterministic expiry testing; removes app-vs-DB
  clock skew as a live correctness variable). Hold outcomes instrumented
  three-way (won / lost_race / infra_failure) — only the third is an SLO
  error.
- **ADR-004** — the `{sessionId}:{sectionId}` hot-shard mitigation cannot
  apply to the queue sequencer, which is structurally a single key.
  Added batched sequence allocation (INCRBY blocks of 1000).
- **ADR-004** — documented the composite fail-open collapse: one Redis
  outage simultaneously removes gateway rate limiting, queue-service
  entirely, fraud velocity counters, and the inventory fast-gate. Each
  fail-open is individually defensible; the aggregate was never decided.
  Gateway degrades to a local limiter; queue-service fails CLOSED on the
  on-sale path (deliberate carve-out from the fail-open convention).
- **ADR-006** — the promised "escalate to a human" is now a named P1
  alert (`PaidUserUnresolved`, 40 min, tied to the retry ladder). Added
  `saga_traceparent` column and span-link tracing across the
  payment-webhook suspend/resume boundary.
- **ADR-007** — the outbox pattern breaks OTel Kafka trace propagation
  (the producing service never calls Kafka), so trace context dies at the
  transaction boundary. Outbox now persists `traceparent`/`tracestate`,
  mapped to Kafka headers via the Debezium event-router SMT.
  `correlationId` redefined as the W3C trace-id (one join key, not two).
- **ADR-007 (BLOCKING)** — PII payload fields must be Avro `bytes` from
  schema v1, because crypto-shredding needs ciphertext and this ADR's own
  rules reject a later `string` -> `bytes` change. Free now, very
  expensive once a topic is live.

### Opened Questions
- Write-up pending for the review's larger designs: security (6 areas),
  observability stack, testing + load/chaos strategy, multi-region/CDN,
  media/video service. Several resolve existing open questions on
  auth-service, ticket-service, queue-service, payment-service.
- Object storage (S3/MinIO) identified as a real missing store — needed
  for ticket PDFs/QR, event images, and video trailers. Not yet designed.

### Resolved Questions
- api-gateway technology choice.
- Kafka topic/event schema.
- fraud-service fail-open vs fail-closed.
- Hold TTL base duration.

## 2026-08-06 (cont. — design-review write-up complete)

Wrote all remaining designs from the multi-agent architecture review into
the vault as nine new ADRs. Session cost flagged repeatedly during this
work (parallel agent research was the expensive part; this write-up phase
was direct writing from already-reviewed designs).

### Added
- `wiki/decisions/ADR-009-service-to-service-auth.md` — signed internal
  service JWTs (OAuth2 client-credentials), two-token model, network
  policy floor, mTLS deferred to k8s.
- `wiki/decisions/ADR-010-secrets-management.md` — HashiCorp Vault:
  AppRole + response-wrapped bootstrap, dynamic Postgres credentials,
  KV v2, Transit engine.
- `wiki/decisions/ADR-011-pci-scope-containment.md` — provider-hosted
  iframe card collection (SAQ A, not SAQ D); payment-service sole holder
  of provider tokens.
- `wiki/decisions/ADR-012-jwt-lifecycle.md` — token lifetimes, 4-phase
  zero-downtime key rotation, Kafka-pushed revocation with a fail-closed
  carve-out from the project's fail-open convention.
- `wiki/decisions/ADR-013-gdpr-crypto-shredding.md` — per-subject DEK
  envelope encryption so right-to-erasure reaches immutable Kafka
  history; erasure saga with a completion ledger.
- `wiki/decisions/ADR-014-anti-bot-anti-scalper.md` — nine defense
  layers, structural-first, each annotated for survival under
  fraud-service's fail-open outage.
- `wiki/decisions/ADR-015-observability-stack.md` — OpenTelemetry +
  Tempo/Mimir/Loki (deliberately not the search Elasticsearch cluster);
  domain-specific SLIs; PaidUserUnresolved alert implemented concretely.
- `wiki/decisions/ADR-016-multi-region-cdn.md` — event-homed anycast
  routing; honest statement that cross-jurisdiction failover is
  architecturally impossible, not just undesigned; CDN geometry/
  occupancy split.
- `wiki/decisions/ADR-017-media-service-video.md` — object storage
  (real prior gap), video trailer feature, new 15th service
  `media-service`.
- `wiki/projects/media-service.md` — new project page.

### Changed
- `wiki/index.md` — indexed all nine new ADRs, added Security and
  Infrastructure section pointers (previously "none written yet"),
  added media-service to Projects, corrected the "pending write-up"
  open question to resolved.
- `CLAUDE.md` (root) — added `backend/media-service` to the repo table.

### Resolved Questions
- `auth-service` refresh-token storage and revocation mechanism.
- `ticket-service` barcode format and resale/transfer model.
- `queue-service` fairness model (randomized, not FIFO) and admission-
  token design.
- `payment-service` provider choice (Stripe, on DX grounds).

### Opened Questions
- `auth-service`/`user-service` have no sharding/residency ADR —
  ADR-005 covers `event_id`-keyed tables only; multi-region (ADR-016)
  surfaced this as a real gap in `user_id`-keyed data.
- CDN vendor choice (Cloudflare vs. AWS-native) flagged as deserving its
  own ADR once `infra.md`'s AWS target firms up — not decided here,
  only recommended.
- media-service rendition ladder, max upload size, allowed formats —
  product-level choices, not yet decided.

## 2026-08-06 (cont. — remaining gaps closed)

### Added
- `wiki/decisions/ADR-018-user-identity-sharding-residency.md` — closes
  the auth-service/user-service sharding gap ADR-016 surfaced. Reuses
  ADR-005's regional Citus clusters with `user_id` as the distribution
  column instead of `event_id`; same identifier-prefix routing trick as
  events. Cross-region profile lookups for foreign-event ticket buyers
  designed as a low-volume async call, not a caching layer.
- `wiki/decisions/ADR-019-cdn-vendor-choice.md` — Cloudflare for the
  edge/CDN layer (cache-tag purge is load-bearing for ADR-016's
  invalidation design, CloudFront lacks it), AWS for compute. Resolves
  the tension flagged in ADR-016 by clarifying it was never a real
  conflict — `infra.md`'s AWS target is about the compute tier only.

### Changed
- `wiki/decisions/ADR-017-media-service-video.md` — amended with
  concrete rendition ladder (240p/480p/720p/1080p), 6s HLS segments,
  upload limits (2GB, 3min, MP4/MOV), flagged as starting defaults.
- `wiki/index.md` — indexed ADR-018/019, resolved the corresponding
  open questions.

### Resolved Questions
- auth-service/user-service sharding/residency gap.
- CDN vendor choice (Cloudflare vs AWS-native).
- media-service rendition ladder, HLS segment duration, upload limits.

All items from the 2026-08-06 design review are now closed except:
build order across 15 services, and the still-empty wiki/domains/,
wiki/data/, wiki/api/ sections.

## 2026-08-06 (cont. — payment-service event ledger)

Comparing the vault against an external reference design (a payment-
gateway architecture walkthrough) surfaced a real gap: payment-service's
internal state storage was never specified in detail, and the implicit
assumption (single mutable status column, matching booking-service's
saga-state shape) would be vulnerable to out-of-order webhook delivery —
a real risk with any real payment provider, not a hypothetical.

### Added
- `wiki/decisions/ADR-020-payment-event-ledger.md` — payment-service
  gets its own append-only `payment_events` ledger (never UPDATE, only
  INSERT), `provider_event_id UNIQUE` as the webhook idempotency
  mechanism, current status derived via a same-transaction state-machine
  recompute over the full transition graph (not overwritten by
  arrival order), integrated with the existing Transactional Outbox
  ([[ADR-007-kafka-event-schema]]) for publishing
  payment.succeeded/failed.

### Changed
- `wiki/projects/payment-service.md` — target design updated to
  reference the ledger design and Stripe provider choice
  ([[ADR-011-pci-scope-containment]]); open questions narrowed to
  reconciliation-job design and provider-specific transition-graph
  edge cases.
- `wiki/index.md` — indexed ADR-020.

### Resolved Questions
- Payment provider choice (Stripe, already resolved via ADR-011,
  cross-referenced here).
- Payment state storage shape (ledger, not mutable column).

### Opened Questions
- Reconciliation job design (catching a webhook that never arrives at
  all, as opposed to arriving out of order) — genuinely still open.

## 2026-08-06 (cont. — Notify Me / broadcast alerts)

User asked whether a "notify me" feature existed to explain ADR-004's
"presale signups" input — search confirmed it did not: the signal was
referenced as if it already existed, but no feature ever captured it.
Same gap doubled as the missing subscriber list for the mass-broadcast
notification design discussed earlier (KodeKloud FCM/pub-sub comparison).

### Added
- `wiki/decisions/ADR-021-notify-me-and-broadcast-alerts.md` — per-
  session "Notify Me" signup capture in event-service
  (`session_notify_me` table), feeding ADR-004's high-demand flag via a
  periodic signup-count-vs-venue-capacity job, and feeding
  notification-service's mass on-sale broadcast via
  `session.on_sale_started`. Push provider resolved as FCM (used even
  for the web frontend, specifically for its topic-based fan-out —
  one message reaches every subscriber instead of N individual sends).
  Explicitly scoped OUT: the post-notification stampede, already owned
  by queue-service/ADR-016.

### Changed
- `wiki/projects/event-service.md` — added Notify Me ownership.
- `wiki/projects/notification-service.md` — added FCM push-provider
  decision and the mass-broadcast path, narrowed open questions to
  SMS/email provider choice only.
- `wiki/index.md` — indexed ADR-021.

### Resolved Questions
- What produces the "presale signups" ADR-004 assumes.
- Which push technology notification-service actually uses.

### Opened Questions
- FCM message TTL, 3x-capacity threshold interaction with real signup
  volume — starting defaults.
- Whether push-declined users also get SMS fallback — product choice,
  not yet decided.
