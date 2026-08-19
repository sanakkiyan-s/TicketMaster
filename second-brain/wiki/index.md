# Vault Index

Master catalog. Read this first, every session, before touching
architecture, code, or planning. See `second-brain/CLAUDE.md` for the full
ruleset this vault operates under.

# System

- [[system-overview]] — components, data ownership, communication, failure boundaries (target design; nothing implemented yet)

# Architecture

- [[system-overview]] (`wiki/architecture/system-overview.md`)
- [[implementation-roadmap]] (`wiki/architecture/implementation-roadmap.md`)
  — 13-section implementation plan synthesized from ADR-001 through
  ADR-036: strategy, stack, 6-phase order, dependency map, per-service
  breakdown, DB/backend/frontend build order, testing cadence, 8
  milestones, 37-task numbered backlog. Everything traces to a decided
  ADR; anything never decided (CI runner, frontend state-mgmt, build
  tool, seed data, staging shape) is listed under Open Decisions, not
  guessed.
- [[blueprint]] (`wiki/architecture/blueprint.html`) — interactive service
  map, current through ADR-035 (gRPC, PgBouncer, idempotent consumer,
  EBS backup included); click a node for its ADRs, search/filter by
  layer. Open directly in a browser. Current, unlike the two pages below.
- [[final-architecture-reference]] (`wiki/architecture/final-architecture-reference.md`)
  — full reconstruction across all 22 ADRs: system diagram, service map,
  data ownership, booking/concurrency/event/real-time/search flows, ADR
  traceability table, superseded decisions, open TBDs.

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
- [[media-service]] (`wiki/projects/media-service.md`) — added
  2026-08-06, see [[ADR-017-media-service-video]].

# Domains

None written yet — will be added once a domain has real design decisions
(inventory/booking domain expected first, being the concurrency core).

# Critical Flows

- [[seat-availability-live-updates]] — SSE + Redis Pub/Sub broadcast of
  seat status changes to browsers, both expiry triggers (sweep + lazy).

Still expected: seat-reservation-flow, checkout-flow, payment-flow —
these are effectively covered piecemeal across [[ADR-002-seat-locking-strategy]]
and [[ADR-006-saga-booking-orchestration]] already, not yet consolidated
into standalone flow pages.

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
- [[ADR-007-kafka-event-schema]] — Kafka topic-per-event-type design,
  Avro + Schema Registry, Transactional Outbox + Debezium/CDC, DLQ.
- [[ADR-008-testing-strategy]] — test pyramid, Spring Cloud Contract,
  concurrency-proof harness, k6 load testing (11-experiment calibration
  map), Toxiproxy/Chaos Mesh failure injection, CI pipeline shape.
- [[ADR-009-service-to-service-auth]] — signed internal service JWTs
  (OAuth2 client-credentials), two-token model (service + user
  assertion), network policy floor, mTLS deferred to k8s.
- [[ADR-010-secrets-management]] — HashiCorp Vault: AppRole with
  response-wrapped bootstrap, dynamic Postgres credentials, KV v2,
  Transit engine for GDPR key wrapping.
- [[ADR-011-pci-scope-containment]] — provider-hosted iframe card
  collection (SAQ A); payment-service is the sole holder of provider
  tokens/card fingerprint, user-service stores only a display reference.
- [[ADR-012-jwt-lifecycle]] — 10min access / 30d rotating opaque
  refresh with reuse detection; 4-phase zero-downtime key rotation;
  revocation via compacted Kafka topic pushed into gateway memory
  (deliberate fail-closed carve-out from the project's fail-open
  convention).
- [[ADR-013-gdpr-crypto-shredding]] — per-subject DEK envelope
  encryption so erasure reaches immutable Kafka history; erasure saga
  with a 14-service completion ledger; shred key last.
- [[ADR-014-anti-bot-anti-scalper]] — nine defense layers, structural-
  first (Verified Fan registration, randomized queue draw, bound
  single-use admission tokens, purchase limits enforced in
  inventory-service, rotating ticket barcodes); each layer annotated for
  survival under a fraud-service outage.
- [[ADR-015-observability-stack]] — OpenTelemetry + Tempo/Mimir/Loki
  (NOT the search Elasticsearch cluster); domain-specific SLIs
  (three-way hold outcome, saga-latency-to-TTL ratio, webhook-silence
  deadman); `PaidUserUnresolved` P1 alert; cardinality-tiered routing.
- [[ADR-016-multi-region-cdn]] — event-homed (not user-homed) anycast
  routing; honest regional-failover analysis (cross-jurisdiction
  failover is architecturally impossible, not just undesigned);
  geometry/occupancy CDN split for seat maps; per-region virtual queue.
- [[ADR-017-media-service-video]] — object storage (real gap, needed
  regardless of video), pre-signed direct uploads, async FFmpeg
  transcoding via Kafka, new `media-service` (15th service).
- [[ADR-018-user-identity-sharding-residency]] — auth-service/
  user-service reuse ADR-005's regional Citus clusters, sharded by
  `user_id` instead of `event_id`; same identifier-prefix routing trick
  as ADR-016; cross-region profile lookups for foreign-event ticket
  buyers explicitly designed as a low-volume async call, not caching.
- [[ADR-019-cdn-vendor-choice]] — Cloudflare for edge/CDN (cache-tag
  purge is load-bearing for ADR-016's invalidation design), AWS for
  compute — not a conflict with `infra.md`'s AWS target, a layer split.
- [[ADR-020-payment-event-ledger]] — payment-service's internal state
  is an append-only `payment_events` ledger, not a mutable status
  column; current status derived from the full transition graph so
  out-of-order webhook delivery can't produce a wrong state; webhook
  signature verification + dedup + outbox integration specified.
  Surfaced by comparing this vault against an external reference
  design, not the multi-agent review.
- [[ADR-021-notify-me-and-broadcast-alerts]] — "Notify Me" per-session
  signup capture (event-service), closing ADR-004's previously-assumed
  "presale signups" input; mass on-sale broadcast via FCM topic fan-out
  (one message, not N sends), explicitly handing the resulting traffic
  to queue-service rather than solving the stampede itself. Also
  surfaced via external-reference comparison.
- [[ADR-037-service-internal-architecture]] - closes a gap 36 ADRs left
  open: how a single service is organised inside. Package by feature
  (`registration/`, `login/`, `token/`) with cross-cutting concerns by
  type (`config/`, `shared/`, plus a shared domain package). Decided on
  enforcement, not taste: a feature's classes sit in one package so the
  service class can be package-private and the compiler refuses
  cross-feature reaches, which package-by-layer can only ask for
  politely. Explicitly forbids sub-dividing a feature by layer (that
  re-splits the package and forces public again), mandates one RFC 9457
  `ProblemDetail` shape per service since ADR-034 publishes it as
  contract, and bans `@Data`/`@ToString`/`@EqualsAndHashCode` on JPA
  entities. Hexagonal rejected on cost for CRUD-shaped services, with
  inventory/booking named as the revisit trigger. auth-service is the
  reference implementation.
- [[ADR-038-ci-platform]] - GitHub Actions, one workflow, two independent
  jobs (backend / frontend) so one half's failure cannot mask the other's.
  Chosen because the repo is already on GitHub and `ubuntu-latest` ships a
  running Docker daemon, which ADR-008's Testcontainers tier requires with
  no setup step; GitLab CI would have cost a migration for a nicer DSL, and
  Jenkins is a server somebody has to operate. Makes ADR-034's OpenAPI
  drift gate real for the first time - as a `::warning` that flags rather
  than blocks, matching that ADR's own wording, unlike ADR-023's
  `buf breaking`, which blocks. No deploy step: deployment is still blocked
  on the IaC and manifest-delivery Open Decisions.
- [[ADR-039-dual-tier-login-rate-limiting]] - closes the gap the original
  `userId:endpoint` gateway rate-limit scheme left at login/register
  (no `userId` exists pre-auth). Two independent layers: a loose,
  IP-keyed `RequestRateLimiter` at the gateway (60/min login, 20/min
  register - Spring Cloud Gateway's built-in limiter, not hand-rolled,
  now that 60/min lands on a clean per-second integer) purely as a
  volumetric shield, since the gateway cannot safely buffer the request
  body to key by username; a tight, username-keyed `LoginAttemptLimiter`
  (Redis, atomic Lua) in auth-service backed by a DB-persisted
  10-failure/15-minute lockout on `User`, since that is where the parsed
  credential actually exists. Resilience4j rejected (no distributed
  backend, breaks under ADR-032's multi-replica gateway); Bucket4j's Redis
  module rejected on cost - the counter was never the hard part, the
  enumeration-safe response shape and DB lockout still need hand-written
  code regardless of which library sits underneath.
- [[ADR-036-build-order-and-phasing]] — closes the vault's oldest open
  question. Six dependency-ordered phases (bootstrap → identity/edge →
  catalog → transaction core → support consumers → secondary features →
  scale/multi-region), gated by ADR-008's existing CI tiers; Phase 3
  (inventory→booking→payment→ticket) must pass the full concurrency-proof
  suite before Phase 4 starts — the one hard gate in the sequence.
- [[ADR-035-payment-reconciliation-and-dispute-workflow]] — nightly
  reconciliation job against Stripe's Balance Transactions API (detects
  missed webhooks, does not auto-correct); dispute workflow extends
  ADR-020's `DISPUTED` status into a real `DISPUTE_HOLD` ticket state,
  won/lost resolution reuses ADR-028's per-booking compensation path.
- [[ADR-034-rest-edge-versioning-openapi]] — `/api/v1/` URI versioning at
  api-gateway (the one remaining REST surface after ADR-023 moved
  internal calls to gRPC); OpenAPI 3 generated via springdoc-openapi,
  never hand-maintained, CI-diffed against the prior committed spec.
- [[ADR-033-non-secret-config-management]] — per-service k8s ConfigMap,
  git-tracked, mounted as `application.yml`; structurally separate from
  ADR-010's Vault-backed secrets; gives every "starting default" number
  scattered across the vault a real, changeable home.
- [[ADR-032-api-gateway-ha-and-probe-semantics]] — gateway is stateless
  by construction (JWKS cache, Redis-backed rate limits, Kafka-rebuilt
  revocation map), min 3 replicas across AZs, HPA on CPU+request-rate;
  formalizes a generic liveness (never dependency-aware) vs readiness
  (may be, fail-open by default, fail-closed named per-case like
  ADR-012's Kafka carve-out) split for every service, not just the
  gateway.
- [[ADR-031-idempotent-kafka-consumer-pattern]] — closes the mechanism
  ADR-007 named but never designed: `processed_events(event_id UUID PK)`
  per consumer service, `INSERT` + external work in one transaction
  (rejects a two-phase `IN_PROGRESS`/`DONE` status design considered and
  found not to actually close the crash race it targeted); manual Kafka
  offset commit only after that transaction commits; composes cleanly
  with ADR-007's DLQ retry/replay. Explicitly states the one race it
  cannot close app-side alone (crash between external-call-success and
  commit) and defers to provider-side idempotency keys where available.
- [[ADR-030-organizer-admin-authorization]] — closes the missing
  multi-tenant authz gap: coarse role gate at api-gateway (ORGANIZER/
  ADMIN routes) + fine-grained `organizer_id` ownership check enforced
  by each owning service (event-service, analytics-service,
  media-service, inventory-service's config endpoints); admin bypasses
  ownership by design, still audited via `AdminActionPerformed`.
- [[ADR-029-ticket-transfer-resale-consistency]] — closes ADR-014 layer
  8's undesigned mechanism: free transfer and paid resale reuse
  ADR-002's row-lock for the ownership-flip race and ADR-006's Saga
  shape (simplified) for the payment-involved case; rotating-barcode
  correctness on transfer is an emergent property of the existing
  design, needs no extra code; seller payout mechanism flagged
  unresolved.
- [[ADR-028-event-cancellation-mass-refund]] — closes both ADR-003's
  event-cancellation gap and ADR-014 layer 7's admin-bulk-cancel gap
  with one Kafka-fan-out mechanism reusing ADR-006's existing
  compensation path per booking; the only real difference between the
  two triggers is whether the seat gets re-released.
- [[ADR-027-schema-migration-strategy]] — Flyway + mandatory
  expand/contract discipline for breaking changes + a CI gate blocking
  any migration on a PII column that would violate ADR-007's
  Avro-bytes-from-v1 constraint.
- [[ADR-026-backup-pitr-strategy]] — cloud volume-snapshot (EBS) + WAL
  chosen as the primary/production mechanism (amended 2026-08-13, for
  learning value, matching ADR-004/005's real-infra-over-simple pattern);
  pgBackRest kept as the Compose-local dev fallback only. 30-day main /
  7-day `subject_key` retention; multi-node Citus restore procedure now
  replays EVERY outbox topic (bookings/payments/tickets/erasure), not
  just erasure — widened 2026-08-13, states honestly that it can't
  recover anything Debezium hadn't yet published before the crash; a
  separate scratch-clone-and-merge path added for single-object
  recovery (bad migration/accidental DROP) where prod never went down.
  Redis explicitly not backed up (never authoritative).
- [[ADR-025-idempotency-key-policy]] — `Idempotency-Key` header (UUID
  v4, mirrors Stripe's convention) + request-body hash colocated with
  the resource row; same-body replay returns current state,
  different-body reuse is rejected 422; closes the gap ADR-006's saga
  quietly assumed away. **Amended 2026-08-14**: uniqueness scoped per
  user — `UNIQUE (event_id, user_id, idempotency_key)` — so one user's
  key can never block another's booking and a key leaked through logs is
  useless against anyone but its owner; legal under Citus because the
  tuple still *contains* the distribution column. Depends on `user_id`
  being NOT NULL (no guest checkout, confirmed 2026-08-14) — NULLs never
  compare equal in a unique index, so introducing guest checkout without
  revisiting this would silently disable idempotency for guest bookings.
- [[ADR-024-pgbouncer-connection-pooling]] — PgBouncer, transaction
  pooling mode, one deployment per region in front of each Citus
  coordinator; closes the connection-pool-exhaustion failure mode
  ADR-002 already named (`infra_failure`) but never solved; must follow
  Patroni failover via `on_role_change` callback wiring (not automatic);
  session-level Postgres features audited and confirmed incompatible
  with transaction mode, ADR-002's row-lock pattern confirmed compatible.
- [[ADR-023-grpc-internal-service-calls]] — internal service-to-service
  calls moved from REST to gRPC; `.proto` + `buf breaking` CI gate
  replaces Spring Cloud Contract for these calls (SCC narrows to the
  client-facing REST edge); ADR-009's two-token auth model carried via
  gRPC metadata instead of HTTP headers, unchanged otherwise; resilience
  is 3 client-side layers, no sidecar — gRPC `outlier_detection` LB
  policy (per-instance ejection), gRPC retry policy (request-level),
  Resilience4j circuit breaker (service-level, last resort); deferred
  entirely on Compose (single instance, no LB problem yet).
- [[ADR-022-sse-connection-admission-control]] — closes a gap in
  [[flows/seat-availability-live-updates]]: request-rate limiting
  (gateway per-user, Nginx per-IP) doesn't bound concurrent *open* SSE
  connections. Adds per-instance concurrent-connection cap (fail-closed
  at capacity) and Nginx `limit_conn`; deliberately does NOT route SSE
  through queue-service's admission control — seat-map viewing isn't a
  scarce resource, so it's treated as a capacity/autoscaling problem, not
  a fairness problem. Surfaced via direct comparison against a real
  production 4-layer rate-limiting pattern during a Q&A session.

**Amendments applied 2026-08-06** (defects found by a multi-agent design
review of the decided architecture — see `log.md`):

- ADR-002 — unique constraint must include `event_id` or Citus enforces
  it per-shard only (the double-sell backstop was not global); Redis
  command timeout + circuit breaker (fail-open was unimplementable
  against a blackholed Redis); app-supplied timestamps instead of SQL
  `now()`; three-way hold-outcome instrumentation.
- ADR-004 — queue sequencer is an irreducible hot key the hot-shard
  mitigation cannot address (batched allocation); composite Redis
  fail-open collapse across gateway/queue/fraud/inventory.
- ADR-006 — `PaidUserUnresolved` named as the concrete P1 alert;
  `saga_traceparent` column; span-link tracing across the
  payment-webhook boundary.
- ADR-007 — outbox must persist `traceparent`/`tracestate` or trace
  context dies at the transaction boundary; `correlationId` defined as
  the W3C trace-id; **PII payload fields must be Avro `bytes` from
  schema v1** (blocking — cannot be changed after a topic goes live).

**Amendments applied 2026-08-13**:

- ADR-012 — `auth.revocation` must be explicitly mirrored cross-region
  (MirrorMaker 2, reusing ADR-016's existing mechanism) since Kafka runs
  per-region and auth-service is itself region-sharded (ADR-018); a ban
  issued in one region otherwise never reaches another region's gateway.
- ADR-008 — deployment/rollback shape added: rolling update, automatic
  rollback on ADR-015's own post-deploy SLI signals, `kubectl rollout
  undo` for code only (never a data rollback); explicit interaction with
  ADR-027's expand/contract phases (a rollback is only safe during the
  additive/dual-write phases, not after the contract phase ships).

# Infrastructure

See [[ADR-016-multi-region-cdn]] (multi-region routing, regional
failover, CDN) and [[ADR-017-media-service-video]] (object storage,
media-service) — no dedicated `wiki/infrastructure/` pages yet.

# Data

None written yet.

# APIs

None written yet.

# Security

See [[ADR-009-service-to-service-auth]], [[ADR-010-secrets-management]],
[[ADR-011-pci-scope-containment]], [[ADR-012-jwt-lifecycle]],
[[ADR-013-gdpr-crypto-shredding]], [[ADR-014-anti-bot-anti-scalper]] —
no dedicated `wiki/security/` pages yet, decisions live in the ADRs
above.

# Testing

See [[ADR-008-testing-strategy]] — no dedicated `wiki/testing/` pages yet;
calibration experiment results (E1-E11) will land there once services
exist and land as amendments to the ADRs they resolve.

# Concepts

- [[cross-cutting-concerns]] — idempotency, tracing/observability, feature
  flags, GDPR, audit logging, CDN.
- [[etcd-raft-consensus]] — how etcd achieves strong consistency (Raft,
  majority-write, leases/watch/compare-and-swap), why it's sync while
  Redis replication is async, odd-node-count reasoning for etcd vs Redis.

# People

Skipped — solo project.

# Open Questions

See `wiki/architecture/system-overview.md#open-questions` for the current
live list. Summary:

- ~~Kafka topic/event schema~~ — resolved, see
  [[ADR-007-kafka-event-schema]].
- ~~Build order across 15 services~~ — resolved, see
  [[ADR-036-build-order-and-phasing]].
- ~~2026-08-06 design review write-up~~ — done: ADR-009 through
  ADR-017 written (security x6, observability, multi-region/CDN,
  media/video). Resolved in passing: `auth-service` refresh-token
  storage + revocation ([[ADR-012-jwt-lifecycle]]); `ticket-service`
  barcode + resale model ([[ADR-014-anti-bot-anti-scalper]]);
  `queue-service` fairness (randomized, not FIFO) and admission-token
  design ([[ADR-014-anti-bot-anti-scalper]]); `payment-service`
  provider choice toward Stripe on DX grounds
  ([[ADR-011-pci-scope-containment]]).
- ~~auth-service/user-service sharding/residency gap~~ — resolved, see
  [[ADR-018-user-identity-sharding-residency]].
- ~~CDN vendor choice~~ — resolved, see [[ADR-019-cdn-vendor-choice]].
- ~~media-service rendition ladder / upload limits~~ — resolved, see
  [[ADR-017-media-service-video]]'s amendment.
- ~~api-gateway technology choice~~ — resolved: Spring Cloud Gateway
  behind Nginx, see [[api-gateway]] / [[infra]].
- ~~api-gateway route-config format (YAML vs Java DSL)~~ — resolved
  2026-08-14: YAML declares routes and their numbers (ConfigMap-mounted
  per [[ADR-033-non-secret-config-management]]), Java filter beans own
  behaviour. See [[api-gateway]]'s "Route configuration format".
- ~~Frontend state management / routing / build tool~~ — resolved
  2026-08-14: Vite + React Router + Zustand (client state) + TanStack
  Query (server state). Recorded in [[frontend]] and
  [[implementation-roadmap]], not promoted to an ADR — none of it
  constrains a backend guarantee, which is this vault's ADR bar.
- ~~fraud-service fail-open vs fail-closed~~ — resolved: fail-open,
  logged, see [[fraud-service]].
- **Deployment provisioning gap** — opened 2026-08-14. Twelve wiki pages
  assume Kubernetes (ADR-032's replicas/HPA/probes, ADR-033's ConfigMaps,
  ADR-009's NetworkPolicy, ADR-010's Vault AppRole, ADR-008's `kubectl
  rollout undo`), but nothing states how a cluster gets created (IaC
  tooling — Terraform et al.) or how manifests reach it (ArgoCD / Flux /
  kubectl-from-CI / Helm). Only `infra/docker-compose.yml` exists today.
  Deliberately deferred to Phase 5/6 rather than decided early — see
  [[implementation-roadmap]]'s Open Decisions for the full note and the
  risk if it is never closed.
- Dynamic/surge pricing — deferred, see [[ADR-003-gap-list-triage]].
- ~~Hold TTL base duration~~ — resolved: 5 min flat, see
  [[ADR-002-seat-locking-strategy]].
- ~~Payment-succeeded-but-hold-expired refund path~~ — resolved, see
  [[ADR-006-saga-booking-orchestration]] (compensation + hold-extension
  mitigation).
- ~~SSE observability details (publish metrics, subscriber lag)~~ —
  resolved, see [[ADR-015-observability-stack]]'s SSE observability
  section.
