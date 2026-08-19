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

## 2026-08-14 (cont. — frontend stack + gateway route-config format)

### Changed
- `wiki/projects/frontend.md` — stack locked: Vite (build), React Router
  (plain SPA router, no SSR framework), **Zustand** for client state,
  TanStack Query for server state, with a strict no-mirroring rule
  between the two. Store layout specified per concern (auth / seat
  selection / queue / checkout) with selector-scoped subscriptions,
  chosen for the SSE seat-status push load. `Current Implementation`
  rewritten against actual code (`frontend/package.json`, `frontend/src/`)
  — scaffold exists, only `main.tsx` + `lib/gateway.ts`.
- `wiki/projects/api-gateway.md` — new "Route configuration format"
  section: YAML owns the routing table and its numbers (ConfigMap-mounted
  per ADR-033, retunable without a rebuild); Java filter beans own
  behaviour (JWT + revocation, role-tiered rate-limit KeyResolver,
  correlation ID, ORGANIZER/ADMIN gate). Java DSL rejected for route
  definitions specifically because it would compile per-environment
  numbers into the artifact, contradicting ADR-033.
- `wiki/architecture/implementation-roadmap.md` — both Open Decisions
  entries struck; stack table gained a frontend detail row and a gateway
  route-config row; the earlier "global client-state library:
  deliberately none" position is explicitly superseded by Zustand.
- `wiki/architecture/blueprint.html` — `client` and `gateway` nodes
  updated with both decisions.
- `wiki/index.md` — both open questions struck.

### Resolved Questions
- Frontend state management / routing library / build tool.
- api-gateway route-config format (YAML vs Java DSL).

### Notes
- Neither decision got an ADR, matching the bar the roadmap page already
  set: an ADR is for choices that constrain a backend guarantee. Both are
  replaceable without touching one.
- Mismatch found and corrected: the roadmap's "Open Decisions (stack)"
  list still called the frontend tooling undecided while the same page's
  frontend section had already recorded Vite/React Router/TanStack Query.

### Added
- `frontend/package.json` — `zustand` ^5.0.15 installed.
- Styling layer wired into the frontend scaffold: `tailwindcss` 4 via
  `@tailwindcss/vite`, `src/index.css` (Tailwind import + shadcn
  `:root`/`.dark` token layers + `@theme inline` mapping),
  `components.json` (shadcn CLI config), `src/lib/utils.ts` (`cn()`),
  `@` → `src` alias added to `vite.config.ts` to match the one already
  in `tsconfig.json`, and the first two generated components
  (`src/components/ui/button.tsx`, `dialog.tsx`).
  `npm run typecheck` + `npm run build` pass; build emits
  `dist/assets/index-*.css` at 21.3 kB (4.6 kB gzipped) — a static
  stylesheet, i.e. the CSP argument holding in practice.

### Decisions
- **Frontend CSS / component library: Tailwind CSS + shadcn/ui.**
  shadcn components are copied source in `src/components/ui/`, not a
  dependency; real deps are Tailwind + `@radix-ui/*`. Decided on two
  system constraints, not preference: (1) runtime CSS-in-JS
  (styled-components / Emotion / MUI) permanently requires `style-src
  'unsafe-inline'` against the CSP `frontend/index.html` already carries
  for ADR-011's SAQ A scope — Tailwind compiles to a static stylesheet
  and leaves that directive droppable; (2) ADR-016's multi-MB layout SVG
  has already spent the seat page's byte budget, so a ~90KB
  batteries-included library buying overridable visual defaults is a bad
  trade. Radix carried specifically for dialog focus-trap/`aria-modal`
  correctness on checkout and queue modals.
- **Seat map explicitly excluded** from the component/utility-class
  system: hand-rolled `<svg>` over ADR-016's versioned geometry asset,
  styled by a plain `seatmap.css` keyed on `[data-status]`. SSE seat
  updates flip one attribute instead of churning utility class strings
  across thousands of nodes.

### Resolved Questions
- Frontend CSS approach / design system / component library.

### Opened Questions
- Whether `style-src 'unsafe-inline'` can actually be dropped once
  Tailwind lands — Stripe's iframe embed may still require it. Verify
  against Stripe's documented CSP requirements before tightening.

## 2026-08-14 (cont. — deployment provisioning gap opened)

### Opened Questions
- **IaC tooling not decided** (Terraform vs Pulumi vs CloudFormation vs
  none). Twelve wiki pages assume Kubernetes — ADR-032 (min-3 replicas
  across AZs, HPA on CPU+request-rate, liveness/readiness split), ADR-033
  (per-service ConfigMap mounted as `application.yml`), ADR-009
  (NetworkPolicy floor, mTLS "deferred to k8s"), ADR-010 (Vault AppRole),
  ADR-008's amendment (`kubectl rollout undo`) — and ADR-016/018 add
  per-region clusters. No decision anywhere covers how a cluster or its
  managed services get provisioned. Searched the vault: zero mentions of
  terraform, pulumi, cloudformation, argocd, flux, gitops, or helm.
- **k8s manifest delivery not decided** (ArgoCD / Flux / kubectl-from-CI /
  Helm). Same root gap. ADR-008's rolling update and SLI-triggered
  automatic rollback both presuppose something applies manifests; that
  something is unnamed.

### Notes
- Deliberately NOT resolved with an ADR now. Deciding tooling for
  infrastructure that does not exist, six phases before it is needed, is
  how ADRs go stale before first use. Both are flagged for Phase 5/6.
- Only infrastructure that exists today: `infra/docker-compose.yml`
  (local dev) plus `scripts/dev.sh`. No k8s manifests, no cluster, no IaC.
- Risk recorded on the roadmap: several ADRs describe k8s-dependent
  behaviour (fail-open readiness, cross-AZ spread, HPA on request rate)
  that would never be exercised if the project only ever runs on Compose —
  documented but unproven.

### Changed
- `wiki/architecture/implementation-roadmap.md` — section 9 gained `IaC:`
  and `Manifests:` rows both marked NOT DECIDED; both Open Decisions lists
  extended.
- `wiki/index.md` — gap added to Open Questions.

## 2026-08-14 (cont. — auth-service skeleton, first code in the repo)

### Added
- `backend/auth-service/src/main/java/com/ticketmaster/auth/AuthApplication.java`
  — `@SpringBootApplication`. First Java file in this repository
  (ADR-036 Phase 1).
- `backend/auth-service/src/main/resources/application.yml` — port 8081,
  datasource via `DB_*` env vars with local-dev defaults, Flyway enabled,
  `spring.jpa.hibernate.ddl-auto: validate`, actuator health with
  ADR-032's liveness/readiness probes exposed. Points at Postgres
  directly rather than PgBouncer (:6432) for now — ADR-024's pooling is
  production shape, and going direct keeps a local startup failure one
  hop instead of two while there is nothing to pool.
- `backend/auth-service/src/main/resources/db/migration/V1__baseline.sql`
  — `users` (CITEXT email so a case-duplicate account is
  unrepresentable), `user_roles` (rows not a column, since ADR-030 needs
  one user to hold USER + ORGANIZER), `refresh_tokens` (SHA-256
  `token_hash` never the token itself, `family_id` for ADR-012's reuse
  detection, `used_at` retained rather than deleted because a second
  presentation IS the theft signal). Timestamps app-supplied, never
  `DEFAULT now()`, per ADR-002's amendment.
- `backend/auth-service/src/test/java/.../AuthApplicationTest.java` —
  Testcontainers Postgres, ADR-008 integration tier. 3 tests, green.

### Changed
- `backend/auth-service/build.gradle.kts` — re-enables `bootJar` and
  disables plain `jar` (both write to build/libs and collide on archive
  name). First module to take the root build's documented opt-back-in.
- Root `build.gradle.kts` — Testcontainers 1.20.1 -> 1.21.4, and
  `systemProperty("api.version", "1.44")` added to `tasks.withType<Test>`.
- `wiki/projects/auth-service.md` — rewritten: Current Implementation now
  describes real code; both Open Questions removed.

### Resolved Questions
- `auth-service.md`'s two open questions (refresh-token storage DB vs
  Redis; revocation strategy) deleted. **They were stale, not newly
  resolved** — ADR-012 answered both on 2026-08-06 and `index.md` had
  recorded them as resolved the whole time; only the project page was
  never updated.

### Notes
- **Docker Engine 29.x breaks Testcontainers' default API negotiation.**
  Engine answers `/info` with HTTP 400 and an all-empty stub body, and
  Testcontainers reports it as "Could not find a valid Docker
  environment" — which sends you hunting for a socket/pipe problem that
  does not exist. The Docker CLI worked fine throughout. Diagnosed by
  reading the raw 400 body out of the JUnit XML. Fix is the `api.version`
  pin above; revisit when Testcontainers ships a client that negotiates
  correctly against Engine 29+.
- Testcontainers 2.0.5 was tried and rejected: at 2.x the DB modules and
  JUnit 5 integration are not published under the existing coordinates
  (`org.testcontainers:postgresql` and `:junit-jupiter` both stop at
  1.21.4), and the consolidated `org.testcontainers:testcontainers:2.0.5`
  jar contains no Postgres or JUnit classes.
- Infra verified booting via `scripts/dev.sh infra` before this work —
  the script's container path is no longer untested.

### Opened Questions
- ADR-018 shards auth-service by `user_id` under Citus. `V1__baseline.sql`
  creates plain tables with no `create_distributed_table()` call, since a
  single-node dev Postgres has nothing to distribute across. The
  distribution migration is deferred and must not be forgotten before a
  real multi-node cluster exists.

## 2026-08-14 (cont. - service internal architecture)

### Added
- `wiki/decisions/ADR-037-service-internal-architecture.md` - package by
  feature, cross-cutting concerns by type. Closes a gap 36 ADRs had left
  open: nothing anywhere said how a single service is organised
  internally, so auth-service had been written with the Spring default
  and no recorded reason.

### Decisions
- **Package by feature.** Decided on compiler enforcement rather than
  preference: with a feature's classes in one package the service class
  can be package-private, so another feature calling it fails to
  compile. Package-by-layer forces every class public and can only
  document the boundary. Same reasoning shape as ADR-002's
  unique-constraint backstop and ADR-025's colocated body hash - make
  the mistake unrepresentable rather than discouraged.
- **No layer sub-folders inside a feature** (`registration/controller/`
  would be a separate package again, forcing the service public and
  discarding the benefit). A feature that outgrows one folder splits by
  sub-feature: `token/keys/`, `token/jwks/`.
- **One error shape per service**: `@RestControllerAdvice` in `shared/`
  returning RFC 9457 `ProblemDetail`. Per-controller handlers produced
  two different shapes from one API, and ADR-034 publishes that shape as
  the OpenAPI contract.
- **Lombok policy**: `@Getter` permitted; `@Data`, `@Setter`,
  `@ToString`, `@EqualsAndHashCode` forbidden on JPA entities -
  `@ToString` would print a password hash into any log line touching a
  User, `@EqualsAndHashCode` breaks under JPA field population and lazy
  loading, `@Setter` defeats the constructor's invariants.
- **Hexagonal rejected for now, on cost not principle.** For services
  whose domain rules are "validate, hash, insert" the mapper layer is
  ceremony. Named revisit trigger: inventory-service and
  booking-service, where the real invariants live.

### Changed
- auth-service restructured to the new layout (commit 6535dc7):
  `config/`, `shared/`, `user/`, `registration/`. `RegistrationService`
  is now package-private.
- `user/User.java` - Lombok `@Getter` replaces five hand-written
  accessors; `getRoles()` stays hand-written because it returns a
  defensive copy, so a caller cannot grant itself a role by mutating the
  set it was handed.
- `wiki/index.md` - ADR-037 indexed.

### Notes
- 12 tests green across auth-service; `./gradlew build` passes on all 15
  modules after the restructure.

## 2026-08-18 (OpenAPI wired into auth-service)

### Decided
- **springdoc runs per service, not on api-gateway alone** - amends
  [[ADR-034-rest-edge-versioning-openapi]]. The gateway proxies routes and
  cannot see `RegisterRequest`'s constraints, so a gateway-generated spec
  would have to be hand-written, which is the one thing ADR-034 forbids.
  Gateway aggregates; services generate.
- **springdoc 2.8.9** replaces the 2.6.0 pinned on api-gateway. 2.6.0
  targets Spring Boot 3.3; this repo runs 3.5.6.
- **Spec is committed and regenerated by a test.** ADR-034's drift gate
  needs a previous version in git to diff against, and that only exists if
  something rewrites it every build. `OpenApiSpecTest` writes
  `backend/auth-service/openapi/auth-service.json`; CI diffs it. A dirty
  working tree after `test` IS the drift signal.

### Changed
- `backend/auth-service/build.gradle.kts`,
  `backend/api-gateway/build.gradle.kts` - springdoc dependency / version.
- `config/OpenApiConfig.java` (new) - document metadata only; `servers`
  pinned to `/` so the committed spec is deterministic.
- `registration/RegistrationController.java` - `@Tag`, `@Operation`,
  and explicit 400/409 `ProblemDetail` responses. The error shape is part
  of the contract (ADR-037), so it belongs in the published spec rather
  than being discovered by hitting it.
- `config/SecurityConfig.java` - `/v3/api-docs/**`, `/swagger-ui/**`
  permitted. Safe only while internal: the gateway must not route them and
  `SWAGGER_UI_ENABLED=false` in production.

### Fixed
- **Postgres coordinator moved to host port 5433.** A native Postgres
  install already owned 5432 on this machine, so `bootRun` authenticated
  against the wrong server and failed with `28P01 password authentication
  failed for user "ticketmaster"`. That message is misleading by design:
  Postgres reports the same `28P01` for an **unknown role** as for a wrong
  password, to prevent user enumeration - so "wrong credentials" and "wrong
  server entirely" are indistinguishable from the error alone. Compose now
  maps `5433:5432`; `DB_PORT` defaults to 5433.
- **Both Postgres healthchecks now authenticate.** `pg_isready` only asks
  whether the server accepts connections - it never logs in - so a
  coordinator nobody can authenticate to reported `healthy` and the failure
  surfaced later inside a Spring stack trace. Replaced with
  `PGPASSWORD=... psql -h 127.0.0.1 -c 'SELECT 1'`. `-h 127.0.0.1` is
  load-bearing: a Unix-socket connection matches the image's
  `local all all trust` rule and would pass with any password at all.
- **`dev.sh` now proves the published port reaches the compose container.**
  A container-side healthcheck cannot detect a port squatter. The new check
  compares `system_identifier` (unique per cluster, set once by `initdb`)
  read inside the container against the same value read through
  `localhost:5433`; different values mean a different server owns the port.
  Also worth remembering: `POSTGRES_PASSWORD` is consumed **only by
  `initdb` on an empty data directory** and is inert forever after, so a
  stale volume keeps its original password no matter what `.env` says -
  that was the other candidate cause here, and `dev.sh reset` clears it.
- **`src/test/resources/application.yml` was silently replacing the whole
  service config.** Spring resolves `classpath:/application.yml` to the
  FIRST match on the classpath - it does not merge - so every test had been
  running against framework defaults, with none of `application.yml`'s
  datasource, JPA `validate`, Flyway or actuator settings applied. It
  looked fine because `@DynamicPropertySource` supplied the datasource and
  the defaults happened to work. Surfaced only because `OpenApiConfig`
  read `${spring.application.name}` and the context failed to start.
  The file is deleted; `grpc.server.port=-1` and the Testcontainers log
  levels are now system properties in the root `tasks.withType<Test>`,
  where they cannot shadow anything and apply to all 15 modules.

### Notes
- 14 tests green in auth-service; `./gradlew build` passes on all 15
  modules.
- CI cannot actually run the drift diff yet - the CI runner platform is
  still an open decision.

## 2026-08-18 (cont. — access tokens + JWKS)

### Decided
- **Key storage was already decided; only the location of the seam was
  open.** [[ADR-010-secrets-management]] puts the JWT signing private key in
  Vault KV v2, loaded into memory at startup, never written to disk, and
  explicitly NOT Vault Transit - auth-service signs on every login and
  refresh, so a network round trip per signature is too expensive. No new
  ADR needed.
- **`SigningKeyProvider` seam, with an ephemeral dev implementation.** The
  Vault provider needs a client, a seeded secret and an auth path, which is
  its own slice; blocking token issuance on it would be sequencing for its
  own sake. The interface splits `signing()` from `published()` because
  ADR-012 phase 1 requires a key to be published for at least one gateway
  cache TTL BEFORE anything signs with it - one combined method makes that
  phase unrepresentable.
- **`kid` is the RFC 7638 JWK thumbprint**, not a counter or random string.
  The same key then yields the same kid whoever computes it, and two keys
  cannot collide during an overlap.
- **JWKS lives at `/.well-known/jwks.json`, outside `/api/v1`.** It is a
  registered well-known URI and infrastructure rather than product API;
  versioning it would break every standard client. ADR-034's versioning
  governs the client-facing surface only.

### Added
- `jwt/SigningKey`, `jwt/SigningKeyProvider`,
  `jwt/EphemeralSigningKeyProvider`, `jwt/Base64Url`, `jwt/JwtProperties`,
  `jwt/AccessTokenIssuer`, `jwt/JwksController`.
- `auth.jwt.*` config: `issuer`, `audience`, `access-token-ttl` (PT10M per
  ADR-012), `key-source`. Validated at startup - a blank issuer is a refusal
  to start, not a run of tokens the gateway will reject.
- `JwtTest` - 5 tests. The load-bearing one rebuilds the public key from the
  published `n`/`e` and verifies a real signature with it, because asserting
  the JSON shape alone would pass with plain-base64 instead of base64url, or
  with BigInteger's sign byte still on the modulus. Both produce a JWKS that
  parses cleanly and fails every verification in production. Another asserts
  no private RSA parameter (`d`, `p`, `q`, `dp`, `dq`, `qi`) appears.
- `openapi/auth-service.json` regenerated by the ADR-034 gate with no manual
  step - it picked up `/.well-known/jwks.json` on its own, which is the
  first evidence that gate actually works.

### Notes
- 19 tests green; `./gradlew build` passes on all 15 modules.
- **The ephemeral provider is correct at one instance and wrong at two.**
  Each replica would generate its own key, publish only that, and reject the
  other's tokens. It is behind `auth.jwt.key-source=ephemeral` and logs a
  WARN at startup so it cannot be mistaken for production-ready.
- Not implemented: login itself (this only mints a token from an
  already-established identity), refresh with reuse detection, the
  four-phase rotation, the Vault provider, the auth.revocation producer.

## 2026-08-18 (cont. — login, and CI decided)

### Decided
- **CI platform: GitHub Actions** - [[ADR-038-ci-platform]], closing an Open
  Decision the roadmap had carried since it was written. It stopped being
  theoretical once three ADRs were depending on a runner that did not exist:
  ADR-034's spec-drift diff, ADR-023's `buf breaking`, and ADR-008's
  Testcontainers tier (which needs a real Docker daemon). `ubuntu-latest`
  provides that daemon with no setup step, and the repo is already on GitHub.
  GitLab CI would have cost a migration to buy a better DSL; Jenkins is a
  server somebody has to operate, which for a solo project is the worst trade
  on the list.
- **Refresh token ships as an httpOnly cookie, access token in the body.** Not
  previously specified by ADR-012, which defines the token design but not its
  delivery. A refresh token readable by JavaScript is stealable by any XSS,
  and it is a 30-day credential - far more valuable to a thief than a 10-minute
  access token. Cookie is `HttpOnly; Secure; SameSite=Strict;
  Path=/api/v1/auth`; SameSite=Strict is also what makes CSRF on the refresh
  endpoint a non-issue while the service stays CSRF-disabled.

### Added
- `login/` - `LoginRequest`, `LoginResponse`, `LoginService`,
  `LoginController` (`POST /api/v1/auth/login`), `RefreshCookie`,
  `InvalidCredentialsException`.
- `token/` - `RefreshToken` entity, `RefreshTokenRepository`,
  `RefreshTokenService`, `IssuedRefreshToken`, `TokenHashing`. Fills in the
  `refresh_tokens` table that V1 created and nothing used.
- `jwt/TokenMinting` - the jwt package's only public edge. `AccessTokenIssuer`
  stays package-private so nothing outside `jwt/` can reach the class holding a
  private key, or reach `SigningKeyProvider` and pull the key material itself.
- `.github/workflows/ci.yml` - backend (JDK 21, Gradle cache, `./gradlew
  build`, spec-drift check, test reports on `if: always()`) and frontend
  (Node 20, `npm ci`, typecheck, build).
- `LoginTest` - 6 tests.

### Notes
- **Three deliberate anti-enumeration measures, which are the substance of this
  slice:**
  1. Unknown email and wrong password throw the same exception and produce
     **byte-identical** 401 bodies. A test asserts the two response strings are
     equal, not merely that both are 401.
  2. `LoginService` verifies the submitted password against a **dummy BCrypt
     hash** when the email does not exist. Without it, a missing user returns
     immediately while a real one pays ~250ms of BCrypt - a timing difference
     measurable over the network that leaks exactly what the identical message
     was hiding. The dummy's strength must stay at 12 to match the real
     encoder, or the leak reopens.
  3. `LoginRequest` deliberately omits `@Email` and the 12-char minimum.
     Restating registration's policy on the login form would tell an attacker
     the rule, and a 400 for "malformed" versus 401 for "wrong" is itself an
     oracle. The `@Size` caps remain, as DoS guards - without them a 10 MB
     "password" reaches deliberately-slow BCrypt.
- Refresh tokens are hashed with **SHA-256, unsalted, not bcrypt** (ADR-012).
  The token is 256 bits of CSPRNG output, so there is nothing to brute-force;
  bcrypt's work factor would add ~250ms of CPU to the endpoint every active
  session hits every 10 minutes, for zero security. Unsalted because lookup is
  BY the hash and two 256-bit random values are never equal.
- 25 tests green across auth-service.
- Still not implemented: the `/refresh` endpoint itself (reuse detection,
  family revocation), which is what the `family_id`/`used_at` columns exist
  for.

## 2026-08-18 (cont. - Vault-backed signing keys)

### Decided
- **`spring-vault-core` (VaultTemplate), NOT `spring-cloud-vault-config`** -
  a correction to ADR-010's own startup sketch, which describes loading Vault
  secrets as a Spring `PropertySource`. That is right for static secrets and
  wrong for signing keys: a PropertySource is resolved at bootstrap and frozen
  for the life of the context, while ADR-012's rotation changes the key set
  WHILE the service runs. Phase 1 publishes K2 before anything signs with it,
  which a frozen PropertySource cannot represent. VaultTemplate re-reads on an
  interval (60s, well under the ~15 min phase-1 window).
- **Scope held to KV v2 only.** ADR-010 specifies four engines; JWT keys need
  one. Transit is explicitly excluded by that ADR (a network round trip per
  signature on the highest-QPS service is the wrong trade), and the dynamic
  Postgres credentials piece - which the ADR itself flags as "most likely to
  bite" - is left for its own slice rather than dragged in here.
- **Token auth locally, AppRole deferred.** ADR-010 requires AppRole with a
  response-wrapped secret_id, whose value is that an attacker who unwraps it
  first makes the unwrap FAIL, so compromise is detectable. A static token
  gives up that signal, so it stays a dev-only path.

### Added
- `jwt/VaultSigningKeyProvider`, `jwt/VaultConfig`, `jwt/VaultKeyProperties`,
  `jwt/KeyCodec`, `jwt/KeyStatus`, `jwt/Thumbprint`.
- `auth.jwt.vault.*` config, and `spring-vault-core:3.1.2` (version pinned -
  Boot manages spring-vault only through the Spring Cloud Vault BOM, which
  this project does not import).
- `VaultSigningKeyProviderTest` - 6 tests against a real Vault container.

### Notes
- **`KeyStatus` is what makes rotation representable.** A PUBLISHED key is in
  JWKS and accepted by gateways but signs nothing; exactly one key may be
  SIGNING, and the provider throws if two are. Without that distinction
  "publish then cut over" collapses into "cut over", and every warm-cached
  gateway rejects every token for up to one cache TTL.
- Tested against a real Vault rather than a mocked VaultTemplate. A mock would
  only prove the class calls the methods it calls; the real failure modes here
  are KV v2 nesting the payload under `data` and base64 DER surviving a round
  trip, neither of which a mock can catch. One test recomputes the RFC 7638
  thumbprint from the decoded key, so a mangled modulus changes the kid and
  fails.
- The provider fails fast at construction if there is no SIGNING key: a service
  that starts without one answers every login with a 500, which reads as a bug
  in login rather than a missing secret.
- Vault being briefly unreachable does NOT stop token issuance - the cached key
  set stays in memory and is served with a WARN. Only a cold start with no
  cache fails.
- `bootstrap-if-empty` (default false) lets a fresh local Vault work with no
  seeding step. It means the service mints its own key, so it must never be
  set in production.
- **The ephemeral provider is kept**, not deleted: it lets the service and its
  tests run with no Vault at all. `auth.jwt.key-source` still defaults to
  `ephemeral`; switching the default to `vault` is a deployment decision, not
  a code one.
- 31 tests green; `./gradlew build` passes on all 15 modules.
- Still open: ADR-012's four-phase rotation is now REPRESENTABLE but not
  implemented - no KeyRotationService advances the phases yet.

## 2026-08-18 (cont. - refresh endpoint, and a bootstrap race fixed)

### Fixed
- **Bootstrap race in the Vault provider, from code committed the same day.**
  `kv.put` was unconditional, so with several replicas starting against an
  empty path each generated a key and the last write won. The losers kept
  signing with a kid that was no longer in JWKS until their 60s cache expired,
  and the gateway rejected every token they issued - the ephemeral provider's
  split-key defect, reintroduced through the bootstrap door. Now a CAS-0
  create ("only if absent"); exactly one instance wins, and the losers
  DISCARD the key they generated and adopt the winner's. Surfaced by a
  question about multi-instance behaviour, not by a test.
- **Reuse detection was being rolled back.** `rotate()` is `@Transactional`
  and ends by throwing on reuse; a RuntimeException rolls back by default, so
  the family revocation that detection had just performed was undone. The
  attacker got a 401 and kept a working token, while the log claimed the
  family was revoked. Now `noRollbackFor = InvalidRefreshTokenException`.
  Caught by the test presenting the ATTACKER's rotated token after the 401
  rather than stopping at the 401.

### Added
- `POST /api/v1/auth/refresh` - `refresh/RefreshController`, `RefreshResponse`.
- `token/` - `RotatedSession`, `InvalidRefreshTokenException`, `rotate()` on
  `RefreshTokenService`, plus `claim` and `revokeFamily` repository queries.
- `RefreshCookie` moved `login/` -> `token/`: two features need it now, and
  ADR-037 puts genuinely cross-feature pieces where both can reach them.
- `RefreshTest` - 6 tests.

### Notes
- **The claim is one conditional UPDATE**, `WHERE used_at IS NULL`. Reading
  then writing in two statements loses the race: two parallel refreshes both
  read null, both rotate, and one token yields two live chains - which is
  indistinguishable from theft. The database arbitrates; the loser gets 0 rows.
- A losing race is treated as theft, which does log out a legitimate client
  that double-submitted. Accepted deliberately: the two are indistinguishable
  from the server, and guessing the other way lets an attacker keep a live
  30-day credential.
- **Roles are re-read from the database on every refresh**, never copied from
  the old token. That bounds privilege staleness to one access-token lifetime
  - a user demoted five minutes ago stops being an admin at their next
  refresh, with no revocation machinery involved.
- Rotation keeps the same `family_id` AND the same `session_id`. A fresh
  session id per refresh would make "log out this device" unimplementable,
  since the identifier a revocation targets would change every ten minutes.
- All four failure modes (unknown / expired / revoked / replayed) return the
  same 401 with no detail. The reason exists only in the log. "Reuse detected"
  would tell an attacker their stolen token was noticed; "expired" rather than
  "unknown" would confirm the token was real.
- 38 tests green; `./gradlew build` passes on all 15 modules.
- Still not implemented: the `auth.revocation` Kafka producer (ADR-012
  amendment), so a revoked family is not yet pushed to gateway memory - a
  revoked refresh token is dead, but access tokens already issued stay valid
  for up to their 10 minutes.

### Answered (from a question, worth keeping)
- Rotation has NO trigger anywhere. `KeyStatus` is a model nothing writes to.
  A per-replica timer would be the wrong shape: ten replicas advancing a phase
  concurrently produce two SIGNING keys, which the provider refuses to load,
  so a botched rotation would take down every instance at its next refresh.
  The workable shapes are a Kubernetes CronJob (one pod by construction),
  leader election, or an admin-triggered endpoint - with CAS underneath all
  three. The CronJob option is blocked on the manifest-delivery Open Decision.

## 2026-08-19 (api-gateway: routing + JWKS validation at the edge)

### Added
- `api-gateway` gets its first source: `GatewayApplication`, `jwt/JwksCache`,
  `jwt/JwtAuthenticationFilter`, `jwt/JwtValidationProperties`,
  `jwt/JwksHealthIndicator`, plus `application.yml` carrying the routing table.
- `JwtAuthenticationFilterTest` - 10 tests.

### Notes
- **The JWKS cache has no on-demand fetch method at all.** Not an oversight:
  the obvious design - fetch whenever an unknown `kid` arrives - is an
  amplification vector, because an attacker sending garbage `kid` values turns
  unauthenticated traffic into outbound load on auth-service from every
  gateway instance at once, exactly when auth-service can least absorb it
  (ADR-012). Two tests pin this, including one that fires 200 forged kids and
  asserts the fetch count is still zero. Verified live as well: a forged kid
  through the running gateway returned 401 with the fetch count unchanged.
- ADR-012 also describes an emergency backstop (one out-of-band refetch per
  60s, only when the unknown kid is seen from multiple distinct sources).
  NOT implemented - the stricter subset (never fetch lazily) is what shipped.
  It only degrades availability during an already-botched rotation, never
  security.
- **503, not 401, while JWKS has never loaded.** A 401 tells a client with a
  perfectly good token to discard it and re-login, turning one gateway's cold
  start into a login stampede against auth-service.
- The Authorization header is **forwarded unchanged**, not stripped in favour
  of a trusted `X-User-Id`. That pattern is only safe when downstream services
  are unreachable except through the gateway; on a flat network any workload
  could set the header and become any user. ADR-009's mesh mTLS is the
  prerequisite and is not in place.
- Global filters only run AFTER a route matches, so an unrouted path 404s
  without reaching the filter. Not a hole - nothing is proxied - but it means
  "no token -> 401" is only observable on a routed path.

### Fixed (all five found by actually running it, none by tests)
- **Spring Cloud Gateway 4.1.5 is incompatible with Boot 3.5.6.** Startup
  fails with an explicit compatibility check naming the release train. Bumped
  to 4.3.0 (the 2025.0 train).
- **gRPC on the gateway broke startup**: `NoClassDefFoundError
  io/grpc/netty/NettyChannelBuilder`. Spring Cloud Gateway sees `io.grpc` on
  the classpath, activates its JsonToGrpc filter, then dies because the netty
  transport is absent. This is the long-standing "gRPC deps on all 15 modules"
  debt finally causing a concrete failure. Excluded from api-gateway, which
  proxies HTTP and holds no stubs.
- **JWKS cold start left the gateway dead for a full refresh interval.** The
  initial fetch got "connection refused" when the gateway won the race against
  auth-service, and the steady-state timer does not return for 5 minutes.
  Added a 5-second retry that runs only until the first successful load.
  Verified by deliberately starting the gateway FIRST: 6 failed attempts, then
  ready.
- **auth-service defaulted to Postgres 5432** while compose now publishes
  5433 (moved to dodge a native install). Every `bootRun` failed. Same fix
  applied to `scripts/dev.sh`, which was checking the wrong port.
- **Unused Redis starter on auth-service** pointed at 6379 and made
  `/actuator/health` report DOWN. Removed - ADR-012 puts refresh tokens in
  Postgres, so auth-service has no Redis use. The gateway keeps Redis (ADR-014
  rate limiting) and now configures port 6380.
- Readiness includes the `jwks` indicator; liveness deliberately does not.
  A gateway that cannot reach auth-service is not broken, and restarting it
  would turn someone else's outage into a crash loop of our own (ADR-032).

### Verified end to end (live, not just tests)
Through the gateway on :8080 - register 201, login 200, refresh via the
httpOnly cookie 200, wrong password 401, no token 401, forged kid 401, valid
token proxied through to auth-service.

### Still open
- Revocation consumer: NOT built. Blocked on the `auth.revocation` producer,
  which ADR-012 routes through ADR-007's transactional outbox - and no outbox
  table or Debezium connector exists yet. Until then a revoked refresh family
  does not invalidate already-issued access tokens for up to 10 minutes.
- Rate limiting (ADR-014) not wired, though the Redis dependency is present.

## 2026-08-19 (gateway rate limiting)

### Decided
- **Login/register rate limits, concrete numbers for the first time.**
  ADR-014 named rate limiting as part of the anti-bot posture but specified no
  values. Login: 10 requests/minute per IP. Register: 5/minute per IP -
  starting defaults, not measured against real traffic, same honesty as
  ADR-012's token lifetimes. Login is looser than a typical brute-force guard
  because a shared corporate/NAT IP or a typo-prone user must not get locked
  out; it is defense in depth on top of BCrypt(12)'s ~250ms cost and the
  byte-identical 401 already closing account enumeration, not the only
  control.
- **Hand-rolled fixed-window Redis filter, not Spring Cloud Gateway's
  RequestRateLimiter.** The built-in RedisRateLimiter's config is integer
  requests-PER-SECOND; "10 per minute" is not representable without either
  truncating to 0 (disables the limiter entirely) or a requestedTokens hack.
  A four-line Lua script (INCR, EXPIRE on first hit, return TTL) gives exact
  per-minute control and is easy to test deterministically - assert the Nth
  request is the boundary, rather than reasoning about token-bucket timing.
- **Keyed by IP, never by header.** X-Forwarded-For is attacker-controlled on
  any request that reaches this filter directly; trusting it would let a
  client rotate the header per request and never be throttled. The socket
  address is used until a real proxy sits in front (ADR-019), at which point
  this needs a trusted-proxy allowlist, not blind trust of the header.
- **Fails OPEN on a Redis outage**, not closed. Rate limiting is one layer
  among several; losing it during an outage narrows defense in depth. Failing
  closed here would mean a Redis blip locks every user out of logging in at
  all - the same shape of mistake ADR-012 explicitly rejected for the
  revocation map, applied to a second control.

### Added
- `gateway/ratelimit/` - `RateLimitRule`, `RateLimitProperties`,
  `RateLimitFilter`, `scripts/rate_limit.lua`.
- `RateLimitFilterTest` - 6 tests against a real Redis container: exact
  boundary (Nth request), independent per-IP counters, unrelated paths never
  throttled, window reset after real expiry, fail-open on a broken Redis
  connection.

### Notes
- Runs BEFORE JwtAuthenticationFilter (order +50 vs +100): a flooding client
  is turned away before token-parsing work runs at all, not after.
- Response carries `X-RateLimit-Limit` / `X-RateLimit-Remaining` on every
  request, not just the 429 - lets a well-behaved client back off proactively
  rather than discovering the limit by hitting it.
- Fixed window accepts a known imprecision: up to 2x the limit can pass right
  at a window boundary (a burst just before + just after). Documented as an
  accepted trade for this being an anti-abuse control, not a hard security
  boundary - the byte-identical 401 and BCrypt cost do the precise work.
- Verified live through :8080, not just green tests: 10 login attempts pass,
  the 11th returns 429 with an accurate Retry-After; register's limit is
  independent and still admits its own request; /api/v1/events (no rule
  configured) is never touched by either limiter.
- 54 tests green across the whole repo; ./gradlew build passes on all 15
  modules.
