---
title: Implementation Roadmap
type: architecture
sources: []
related: [[ADR-036-build-order-and-phasing]], [[ADR-008-testing-strategy]], [[final-architecture-reference]], [[blueprint]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: synthesized from ADR-001 through ADR-036. Nothing here is a new
decision — every technology/order choice traces to an accepted ADR.
Where something was never decided, it's listed under **Open Decisions**,
not guessed.

# 1. Implementation Strategy

Dependency-and-risk ordered, per [[ADR-036-build-order-and-phasing]] — not
feature-priority, not org-chart. Two governing rules:

- **Build the concurrency-critical core early and prove it before
  anything depends on it being correct.** Seat locking (ADR-002) and the
  booking saga (ADR-006) are the one place a mistake compounds across
  every later phase — cheaper to find broken in isolation than after five
  services integrate against it.
- **Scale/multi-region work is explicitly "ahead of actual load," built
  for learning value** (ADR-004, ADR-005, ADR-016) — it must not gate a
  working single-region system.

**MVP boundary**: Phases 0-4 below constitute a working, correct,
single-region ticket-buying platform (browse → book → pay → receive
ticket, notified). Phases 5-6 are real but not required to have a
functioning product.

# 2. Technology Stack

| Layer | Choice | Why (ADR) |
|---|---|---|
| Backend language/framework | Java 21, Spring Boot | Project's stated learning goal (`CLAUDE.md`) |
| Internal service calls | gRPC + Protobuf, `buf breaking` CI gate | ADR-023 |
| Public REST edge | Spring MVC, `/api/v1`, springdoc-openapi (generated, not hand-written) | ADR-034 |
| Database | PostgreSQL + Citus extension, sharded by `event_id`/`user_id` | ADR-005, ADR-018 |
| Connection pooling | PgBouncer, transaction mode, per-region | ADR-024 |
| Cache / fast-path | Redis Cluster, hash-tagged sharding | ADR-004 |
| Event backbone | Kafka + Debezium CDC (Transactional Outbox) + Confluent Schema Registry + Avro | ADR-007 |
| Auth | Custom `auth-service`, JWT RS256, two-token model (service + user) | ADR-009, ADR-012 |
| Secrets | HashiCorp Vault, AppRole, dynamic Postgres creds | ADR-010 |
| Non-secret config | k8s ConfigMap per service, git-tracked | ADR-033 |
| Object storage | S3-compatible: MinIO (local), S3 (prod) | ADR-017 |
| CDN / edge | Cloudflare | ADR-019 |
| Compute (prod target) | AWS EC2, self-managed (not RDS/Aurora — deliberate, learning value) | `infra.md`, ADR-026 |
| Compute (local dev) | Docker Compose | `infra.md` |
| Orchestration (prod) | Kubernetes | ADR-032, ADR-033 |
| Payments | Stripe, provider-hosted iframe (Payment Element), SAQ A | ADR-011 |
| Search | Elasticsearch, Kafka-fed projection (CQRS) | search-service, ADR-007 |
| Push notifications | Firebase Cloud Messaging, topic fan-out | ADR-021 |
| Backup / PITR | EBS snapshot + WAL (prod), pgBackRest (local dev only) | ADR-026 |
| Schema migrations | Flyway, mandatory expand/contract for breaking changes | ADR-027 |
| Observability | OpenTelemetry + Tempo (traces) / Mimir (metrics) / Loki (logs) | ADR-015 |
| Testing | JUnit 5, Spring Cloud Contract (REST edge), `buf breaking` (gRPC), k6 (load), Chaos Mesh + Toxiproxy (fault injection), PIT (mutation, inventory/booking only) | ADR-008 |
| CI | Tiered pipeline (per-commit / per-merge / nightly / weekly) | ADR-008 |
| CD | Rolling update (k8s), automatic rollback on ADR-015 SLI signals | ADR-008 amendment |
| Frontend | React + TypeScript, Vite (build), React Router (routing), Zustand (client state), TanStack Query (server state) | `CLAUDE.md`, `frontend.md` |
| API Gateway route config | YAML route definitions (ConfigMap-mounted, ADR-033) + Java filter beans for behaviour | `api-gateway.md` |

## Open Decisions (stack)

- ~~**CI runner/platform**~~ — DECIDED 2026-08-18: GitHub Actions, see
  [[ADR-038-ci-platform]]. Original entry: (GitHub Actions vs GitLab CI vs Jenkins) —
  never named anywhere in the vault; only the pipeline *shape* (tiers,
  timing) is decided (ADR-008).
- **Infrastructure-as-Code tooling** (Terraform vs Pulumi vs
  CloudFormation vs none) — opened 2026-08-14. Twelve wiki pages assume
  Kubernetes (ADR-032's replicas/HPA/probes, ADR-033's ConfigMaps,
  ADR-009's NetworkPolicy, ADR-008's `kubectl rollout undo`,
  ADR-010's Vault AppRole), and ADR-016/018 add multi-region clusters —
  but **no decision anywhere covers how a cluster or its managed services
  get created**. The only infrastructure that exists today is
  `infra/docker-compose.yml` for local dev. Needed by Phase 5/6; harmless
  before then. Flagged now because the gap was invisible: the ADRs read as
  though a deployment target already exists.
- **Kubernetes manifest delivery** (ArgoCD vs Flux vs kubectl-from-CI vs
  Helm) — opened 2026-08-14, same root gap as above. ADR-008's rolling
  update and automatic SLI-triggered rollback both presuppose *something*
  applies manifests to a cluster; that something is unnamed. GitOps
  tooling is optional for a solo project — `kubectl apply` from CI is
  defensible — so this should not be decided by default.

  **Risk if never closed**: several ADRs describe k8s-dependent behaviour
  (fail-open readiness probes, cross-AZ replica spread, HPA on request
  rate) that would never be exercised on Compose. Those decisions would be
  documented but unproven.
- ~~Frontend state management, routing library, build tool~~ — resolved
  2026-08-14: **Vite** (build), **React Router** (routing), **Zustand**
  (client state), **TanStack Query** (server state). See the frontend
  section below and `frontend.md`.
- ~~API Gateway route-config format (YAML vs Java DSL)~~ — resolved
  2026-08-14: **YAML for route definitions, Java for filter behaviour**.
  See `api-gateway.md`.

# 3. Implementation Order

Six phases (verbatim from [[ADR-036-build-order-and-phasing]]), each
gated by [[ADR-008-testing-strategy]]'s existing CI tiers — a phase is
"done" when its per-merge tier passes, not when code merely exists.

```
Phase 0 — Platform bootstrap (no product code)
Phase 1 — Identity & edge (auth-service, api-gateway, user-service)
Phase 2 — Catalog, read-side (event-service, venue-service, search-service)
Phase 3 — Transaction core (inventory -> booking -> payment -> ticket)
          ** HARD GATE: full concurrency-proof CI tier must pass **
Phase 4 — Support consumers (notification, fraud, analytics)
Phase 5 — Secondary features (queue-service, media-service, transfer/
          resale, cancellation, dispute/reconciliation)
Phase 6 — Scale & multi-region hardening
```

# 4. Dependency Mapping

| Step | Depends on | Unlocks | Parallelizable? |
|---|---|---|---|
| Phase 0 (platform) | nothing | everything | Internally yes — Postgres, Redis, Kafka, Vault setup are independent of each other |
| auth-service | Phase 0 (Postgres, Vault) | api-gateway, every other service's auth | No — hard blocker for all of Phase 1+ |
| api-gateway | auth-service (JWT validation) | all client-facing traffic | No |
| user-service | auth-service | booking (buyer identity), payment (display refs) | Yes, parallel with event/venue-service |
| event-service, venue-service | Phase 1 complete | inventory-service, search-service, booking | Yes — parallel with each other |
| search-service | event-service, venue-service (data to index), Kafka | discovery/browse UI | Yes, can start once event-service emits events, doesn't block booking |
| inventory-service | event-service, venue-service (seat/event data), Redis, Postgres | booking-service | No — booking cannot be built meaningfully without it |
| booking-service | inventory-service, payment-service (for the saga's payment leg) | ticket-service, cancellation (ADR-028), notification triggers | No |
| payment-service | Phase 0 (Vault for Stripe keys), PgBouncer | booking-service's payment leg, reconciliation (ADR-035) | Can be built in parallel WITH booking-service if stubbed, but saga integration is a hard join point |
| ticket-service | booking-service (confirmed booking to issue against) | transfer/resale (ADR-029), dispute-hold (ADR-035) | No |
| notification, fraud, analytics-service | Phase 3 complete (real events to consume) | none downstream — leaf nodes | Yes — fully parallel with each other |
| queue-service | Phase 3 complete, Redis | high-demand on-sale admission only | Yes, can be deferred arbitrarily late within Phase 5 |
| media-service | event-service (organizer ownership check, ADR-030) | trailer display only | Yes, fully independent of transaction core |
| Phase 6 (scale/multi-region) | a working Phase 0-5 system | nothing blocks on it | N/A — explicitly last |

**Blocking dependencies, stated plainly**: auth-service blocks everything
client-facing. inventory-service blocks booking-service. booking-service
blocks ticket-service. Phase 3's concurrency-proof gate blocks Phase 4.
Nothing else in Phases 1-5 is a hard sequential blocker beyond these.

# 5. System Components

One row per service; full purpose/ADR detail already lives in
[[blueprint]] (interactive) — summarized here for the roadmap:

| Component | Tech | Depends on | Priority | Complexity | Test focus |
|---|---|---|---|---|---|
| api-gateway | Spring Cloud Gateway | auth-service | P0 | Medium | Contract tests (REST edge), rate-limit correctness |
| auth-service | Spring Boot, Postgres | Vault | P0 | High (key rotation, revocation) | Rotation zero-downtime, revocation propagation lag |
| user-service | Spring Boot, Postgres | auth-service | P1 | Low | Standard CRUD + PCI display-ref boundary |
| event-service | Spring Boot, Postgres, Kafka producer | Phase 1 | P1 | Medium | Outbox delivery, organizer ownership checks |
| venue-service | Spring Boot, Postgres | Phase 1 | P1 | Low | Seat-map geometry correctness |
| search-service | Spring Boot, Elasticsearch, Kafka consumer | event/venue-service | P2 | Medium | Projection lag, index consistency |
| inventory-service | Spring Boot, Redis, Postgres | event/venue-service | **P0 (highest)** | **Very high** | Full concurrency suite, double-sell = zero tolerance |
| booking-service | Spring Boot, Postgres, Kafka | inventory, payment | **P0** | **Very high** | Saga crash-resume, compensation correctness |
| payment-service | Spring Boot, Postgres (ledger), Stripe SDK | Vault, PgBouncer | P0 | High | Webhook idempotency, ledger derivation correctness |
| ticket-service | Spring Boot, Postgres | booking-service | P1 | Medium | Barcode live-generation, transfer race |
| queue-service | Spring Boot, Redis | inventory-service | P3 | Medium | Admission fairness under load |
| notification-service | Spring Boot, Kafka consumer, FCM/SMTP | Phase 3 | P2 | Low-Medium | Idempotent-consumer dedup (ADR-031) |
| fraud-service | Spring Boot, Kafka consumer | Phase 3 | P2 | Medium | Fail-open behavior under its own outage |
| analytics-service | Spring Boot, Kafka consumer, Postgres | Phase 3 | P3 | Low | Organizer-scoped query correctness |
| media-service | Spring Boot, S3, FFmpeg worker | event-service | P3 | Medium | Multipart resume, transcoding pipeline |
| frontend | React + TypeScript | api-gateway (v1 REST) | P1 (parallel to backend) | High | Component tests, E2E on golden path |

# 6. Database Implementation

Every table below is already fully specified in its owning ADR — this
section is an index, not a re-derivation.

| Table | Owning service | Shard key | Defined in |
|---|---|---|---|
| `events`, `sessions` | event-service | `event_id` | event-service.md, ADR-030 (`organizer_id`) |
| `venues`, `seat_maps` | venue-service | `venue_id` | venue-service.md |
| `session_notify_me` | event-service | `event_id` | ADR-021 |
| `outbox` (per service) | every producer | matches owner table | ADR-007 |
| `processed_events` | every consumer | N/A (dedup table) | ADR-031 |
| `bookings` | booking-service | `event_id` | ADR-006, `PRIMARY KEY(event_id, booking_id)`, `UNIQUE(event_id, user_id, idempotency_key)` (ADR-025 amendment 2026-08-14 — per-user scope; requires `user_id` NOT NULL, valid because there is no guest checkout) |
| `payment_events`, `payment_intents` | payment-service | `event_id` | ADR-020 |
| `saved_payment_method` | payment-service | N/A | ADR-011 |
| `user_payment_method_ref` | user-service | N/A | ADR-011 |
| `tickets`, `ticket_transfer_log` | ticket-service | `event_id` | ADR-014, ADR-029 |
| `trailer_asset` (+ chunk array) | event-service | N/A | ADR-017 amendment |
| `auth.revocation` (Kafka topic, not a table) | auth-service | N/A | ADR-012 |
| `subject_key` (GDPR DEK store) | separate DB, shorter retention | N/A | ADR-013, ADR-026 |

**Migrations**: Flyway, mandatory 5-phase expand/contract for any
breaking change (ADR-027). **Seed data**: not decided — flag under Open
Decisions. **Backup**: EBS snapshot + WAL prod / pgBackRest local
(ADR-026), full outbox-topic replay procedure on restore.

## Open Decisions (database)

- Seed/fixture data strategy for local dev and staging — never decided.
- Exact index list beyond primary/unique keys already named in each
  ADR — implementation-time detail per service.

# 7. Backend Implementation Order (per service)

```
1. Project skeleton (Spring Boot, Gradle/Maven — build tool not decided,
   Open Decision) + ArchUnit rules (ADR-008)
2. Flyway migration baseline for the service's own schema
3. Domain model + repository layer
4. Business logic / core service methods
5. gRPC server (internal) + .proto contract, OR REST controller if
   api-gateway edge-facing (ADR-023/ADR-034)
6. Kafka producer (outbox table + Debezium connector config) if the
   service emits events (ADR-007)
7. Kafka consumer + processed_events dedup table if it consumes
   (ADR-031)
8. Auth: JWT validation via gRPC metadata (ADR-009/ADR-023) or gateway-
   forwarded assertion
9. Validation, error handling, structured logging with the OTel
   trace_id (agent-attached automatically, see [[ADR-015-observability-stack]]
   and cross-cutting-concerns.md)
10. External integrations (Stripe for payment-service, S3 for media-
    service, FCM for notification-service) last, once internal logic
    is proven
```

# 8. Frontend Implementation Order

Only React + TypeScript is decided; everything else below is a
reasonable order given the backend's shape, not a vault decision — flag
accordingly.

```
1. App shell, routing skeleton
2. Auth flow (login/refresh against auth-service via api-gateway)
3. Event browse/search pages (read-only, lowest risk)
4. Seat map view (geometry from CDN per ADR-016, occupancy live via SSE)
5. Booking/checkout flow (hold -> Stripe iframe -> confirm)
6. My tickets / transfer-resale UI
7. Organizer dashboard (scoped by ADR-030's ownership model)
8. Admin views (bulk-cancel, dispute review)
```

## Open Decisions (frontend)

**Correction (2026-08-14)**: an earlier draft of this page listed the
build tool as undecided. That was wrong — `frontend.md` line 21 already
specifies **Vite**, alongside React and TypeScript. Only the data layer
was ever open, and `frontend.md`'s own open question scoped it as "to be
picked once API contracts exist," which ADR-034 has now satisfied.

- **Data-fetching / server state**: the Phase-1 scaffold uses
  **TanStack Query**, and **React Router** for routing. Chosen at
  implementation time, recorded here rather than promoted to an ADR —
  both are replaceable without touching a backend guarantee, which is
  the bar this vault uses for what deserves an ADR.
- **Global client-state library**: **Zustand**, decided 2026-08-14
  (supersedes this page's earlier "deliberately none" position). Scope is
  *client* state only — TanStack Query stays the owner of server state,
  and server responses are not mirrored into Zustand. Small stores by
  concern, not one god store:
  - `authStore` — session/token state (ADR-012)
  - `seatSelectionStore` — in-progress seat picks + live seat status
    arriving over SSE (ADR-022, `seat-availability-live-updates`)
  - `queueStore` — admission token + queue position (ADR-014)
  - `checkoutStore` — saga step + the in-flight booking's
    `Idempotency-Key` (ADR-025)

  Chosen over Redux (boilerplate outsized for this state volume) and
  Context (every consumer re-renders on any change — bad fit for the
  high-frequency SSE seat-status push, which is the only unusual state
  load in this frontend). Zustand's selector-scoped subscriptions bound
  re-renders to the seats that actually changed, and its store is
  writable from outside React, which is what an `EventSource.onmessage`
  handler needs — no context plumbing of the SSE connection.

  Rule for components: subscribe with a selector
  (`useSeatStore(s => s.seats[id])`), never the whole store.

  Recorded here, not as an ADR — same bar as the routing/data-layer
  choices above: replaceable without touching a backend guarantee.
- **CSS approach / component library**: **Tailwind CSS + shadcn/ui**,
  decided 2026-08-14. shadcn components are copied source under
  `src/components/ui/`, not an npm dependency; real deps are Tailwind and
  the `@radix-ui/*` primitives. Decided on two system constraints rather
  than preference: runtime CSS-in-JS (styled-components/Emotion/MUI)
  would permanently require `style-src 'unsafe-inline'` against the CSP
  `frontend/index.html` carries for ADR-011's SAQ A scope, and ADR-016's
  multi-MB layout SVG has already spent the page's byte budget. Radix is
  carried for dialog/menu a11y correctness. The seat map is explicitly
  outside this: hand-rolled `<svg>` + a plain CSS file keyed on
  `[data-status]`, since SSE-driven seat updates should flip an attribute,
  not churn utility class strings. See `frontend.md`.

# 9. Infrastructure & DevOps

```
Local dev:      Docker Compose, all services + Postgres/Redis/Kafka/
                MinIO/Vault in one compose file (`infra.md`)
Secrets:        Vault, AppRole (ADR-010) — local and prod both
Config:         k8s ConfigMap per service (ADR-033) — Compose uses env
                files locally as the equivalent
CI:             tiered pipeline per ADR-008 (runner platform: Open
                Decision)
CD:             rolling k8s update, automatic rollback on ADR-015 SLIs
                (ADR-008 amendment)
IaC:            NOT DECIDED — Open Decision. Nothing in the vault says how
                the cluster or managed services get provisioned. Terraform
                is the obvious candidate; never discussed.
Manifests:      NOT DECIDED — Open Decision. ADR-033 assumes ConfigMaps
                exist and ADR-032 assumes Deployments with HPA, but no
                decision covers how either reaches a cluster (ArgoCD /
                Flux / kubectl-from-CI / Helm).
Staging:        not explicitly decided — Open Decision (whether staging
                mirrors prod's AWS target or runs on Compose)
Production:     AWS EC2 (self-managed Postgres/Citus, not RDS),
                Cloudflare edge (ADR-019), EBS snapshot backup (ADR-026)
Monitoring:     OpenTelemetry -> Tempo/Mimir/Loki (ADR-015)
Alerts:         PaidUserUnresolved P1, Debezium-lag SLI, mass-
                cancellation-stuck-count, and others named per-ADR
                (ADR-006, ADR-015, ADR-028)
Rollback:       kubectl rollout undo (code only, never data) — see
                ADR-008 amendment for the migration-phase interaction
```

# 10. Testing Strategy (when, not just what)

Directly from [[ADR-008-testing-strategy]] — testing is continuous, not
end-loaded:

```
Every commit/PR (<10min):   unit + integration (changed services only),
                             ArchUnit, fast concurrency gate (N=50)
Every merge to main:        full E2E, full concurrency suite (N=200x50,
                             3 Redis modes), deterministic chaos tests
Nightly:                    full contract matrix (all services), k6
                             regression gate, wider chaos, mutation
                             testing (inventory/booking only)
Weekly/pre-release:         full-scale load test, 2h soak, game-day
                             compound failure test
On demand:                  E1-E11 calibration experiments (produce the
                             real numbers behind every "starting
                             default" tunable)
```

Security testing: PCI-adjacent flows (payment-service, ADR-011) get
CSP/SRI checks as part of the checkout page's own CI, not a separate
end-of-project security pass.

# 11. Milestones

**Milestone 1 — Platform Foundation** (Phase 0)
Goal: every service can boot and reach Postgres/Redis/Kafka/Vault.
Deliverable: `docker-compose up` brings up empty but connected infra.
DoD: CI skeleton green on an empty service.

**Milestone 2 — Identity & Edge** (Phase 1)
Goal: a user can register, log in, get a JWT validated at the gateway.
Deliverable: auth-service + api-gateway + user-service, deployed.
DoD: JWT issuance/refresh/revocation all pass their ADR-012 test cases.

**Milestone 3 — Catalog** (Phase 2)
Goal: events/venues browsable, searchable.
Deliverable: event-service, venue-service, search-service.
DoD: Kafka projection lag within target, search returns real data.

**Milestone 4 — Transaction Core** (Phase 3) — the hard gate
Goal: a seat can be held, paid for, and issued as a ticket, correctly,
under concurrent load.
Deliverable: inventory, booking, payment, ticket services.
DoD: full concurrency-proof CI tier passes — zero double-sell, zero
paid-and-unresolved bookings.

**Milestone 5 — Support Consumers** (Phase 4)
Goal: users get notified, fraud signals collected, organizers see data.
Deliverable: notification, fraud, analytics services.
DoD: idempotent-consumer dedup verified (ADR-031), no duplicate sends
under redelivery test.

**Milestone 6 — Secondary Features** (Phase 5)
Goal: full product surface (queue, media, transfer/resale, cancellation,
disputes).
Deliverable: remaining services + saga extensions.
DoD: each feature's own ADR-defined test cases pass.

**Milestone 7 — Scale & Multi-Region** (Phase 6)
Goal: system survives real-scale load and regional failure scenarios.
Deliverable: Citus/Redis Cluster scaled, multi-region routing live.
DoD: k6 load tests at target scale, regional failover drill executed.

**Milestone 8 — Production Readiness**
Goal: real deploy, real monitoring, real on-call posture.
Deliverable: production AWS environment, alerting wired, backup/restore
drill actually run once (ADR-026 — "a documented-but-never-tested
runbook is not a safety net").
DoD: a real restore drill completes successfully; rollback tested once
in a non-emergency.

# 12. Parallel vs Sequential Work

**Must be sequential**: Phase 0 → Phase 1 → (inventory → booking →
payment → ticket within Phase 3) → Phase 3's gate → Phase 4.

**Can run in parallel**:
- Within Phase 0: Postgres, Redis, Kafka, Vault setup are independent.
- Within Phase 2: event-service, venue-service can be built simultaneously.
- Within Phase 4: notification, fraud, analytics-service are fully
  independent of each other.
- Frontend build can start as soon as api-gateway's v1 contract exists
  (Phase 1 complete) — doesn't need to wait for Phase 3.
- media-service (Phase 5) has no dependency on the transaction core at
  all — can start any time after event-service exists.

**Can start early**: OpenTelemetry instrumentation should be wired in
from Phase 0, not retrofitted later — same reasoning ADR-015 already
states (metrics/tracing are load-bearing for every later phase's
correctness gates, not a Phase 6 add-on).

**Must wait for architecture validation**: Phase 6 (scale/multi-region)
explicitly waits for a working Phase 0-5 system — building it earlier
risks tuning against a system that hasn't proven its baseline correctness
yet.

# 13. Final Implementation Backlog

| ID | Task | Component | Dependency | Priority | Complexity | Deliverable | DoD |
|---|---|---|---|---|---|---|---|
| T01 | Provision Postgres/Citus cluster (local Compose) | Platform | none | P0 | Med | Running Citus coordinator+worker | Coordinator reachable, `pg_dist_shard` queryable |
| T02 | Provision Redis Cluster (local) | Platform | none | P0 | Med | Running Redis Cluster | Hash-tag routing verified |
| T03 | Provision Kafka + Debezium + Schema Registry | Platform | none | P0 | High | Running broker, one test topic | Outbox row → topic message round-trip works |
| T04 | Provision Vault (dev mode) | Platform | none | P0 | Med | Running Vault, AppRole configured | Service can fetch a dynamic Postgres cred |
| T05 | CI pipeline skeleton | Platform | T01-T04 | P0 | Med | Green pipeline on empty repo | Per-commit tier runs and passes |
| T06 | ConfigMap/env convention established | Platform | none | P1 | Low | Template configmap.yaml | One service reads a tunable from it |
| T07 | auth-service: schema + JWT issuance | Identity | T01, T04 | P0 | High | Login returns valid JWT | Signature verifies against JWKS |
| T08 | auth-service: key rotation (4-phase) | Identity | T07 | P1 | High | Rotation runs without forced logout | Old+new tokens both validate during overlap |
| T09 | auth-service: revocation (Kafka-pushed) | Identity | T03, T07 | P1 | High | Revoked token rejected at gateway | Sub-second propagation in test |
| T10 | api-gateway: routing + JWT validation | Identity | T07 | P0 | Med | Gateway rejects invalid JWT | Contract test passes |
| T11 | api-gateway: rate limiting (role-tiered) | Identity | T10 | P2 | Med | Per-role buckets enforced | ADMIN/ORGANIZER/USER limits verified separately |
| T12 | user-service: profile CRUD | Identity | T07 | P1 | Low | CRUD endpoints live | Standard test suite green |
| T13 | event-service: events/sessions CRUD + outbox | Catalog | T10, T03 | P1 | Med | Event creation emits `event.created` | Outbox → Kafka verified |
| T14 | venue-service: venue/seat-map CRUD | Catalog | T10 | P1 | Low | Seat map retrievable | Geometry payload correct |
| T15 | search-service: Kafka consumer + ES index | Catalog | T13, T14 | P2 | Med | Search returns indexed event | Projection lag measured |
| T16 | inventory-service: seat state schema + Redis fast-gate | Core | T13, T14, T02 | **P0** | **Very High** | Hold/release works single-threaded | Unit tests green |
| T17 | inventory-service: Postgres backstop (FOR UPDATE + unique idx) | Core | T16 | **P0** | **Very High** | Double-sell impossible under test | Concurrency suite N=50 passes |
| T18 | booking-service: saga skeleton (hold→confirm) | Core | T17 | **P0** | **Very High** | Happy-path booking completes | E2E happy path green |
| T19 | booking-service: compensation/crash-resume | Core | T18 | **P0** | **Very High** | Crash mid-saga recovers correctly | Chaos test (PodKill) passes |
| T20 | payment-service: ledger schema + Stripe integration | Core | T04, T24(pgbouncer) | **P0** | High | PaymentIntent created, webhook processed | Webhook idempotency test passes |
| T21 | booking↔payment saga integration | Core | T18, T20 | **P0** | **Very High** | Full paid booking flow | Full concurrency suite N=200×50 passes |
| T22 | ticket-service: issuance + rotating barcode | Core | T21 | P1 | Med | Ticket issued on booking confirm | Barcode validates live |
| T23 | idempotency-key middleware (ADR-025) | Core | T18 | P1 | Med | Duplicate request returns cached result | Retry test passes |
| T24 | PgBouncer deployment in front of Citus | Core | T01 | P1 | Med | Pooled connections, count bounded | Load test shows bounded connection count |
| **GATE** | **Phase 3 full concurrency-proof CI tier** | Core | T16-T24 | **P0** | — | CI report | Zero double-sell, zero stuck bookings under full chaos matrix |
| T25 | notification-service: consumer + idempotent dedup | Support | GATE | P2 | Med | No duplicate sends on redelivery | ADR-031 dedup test passes |
| T26 | fraud-service: velocity scoring, fail-open | Support | GATE | P2 | Med | Score computed, service-down doesn't block booking | Fail-open chaos test passes |
| T27 | analytics-service: organizer dashboard queries | Support | GATE | P3 | Low | Scoped query returns own-org data only | Cross-org isolation test passes |
| T28 | queue-service: admission control | Secondary | GATE, T02 | P3 | Med | Fair random draw under load | Load test at simulated on-sale traffic |
| T29 | media-service: multipart upload + transcoding | Secondary | T13 | P3 | Med | Trailer uploads, resumes on failure | Resume test (kill mid-upload) passes |
| T30 | ticket transfer/resale (ADR-029) | Secondary | T22 | P3 | Med | Free transfer + paid resale work | Concurrent-purchase race test passes |
| T31 | event cancellation / mass-refund (ADR-028) | Secondary | T21 | P3 | Med | Bulk cancel refunds all bookings | Batch job completes, no double-refund |
| T32 | payment reconciliation + dispute workflow (ADR-035) | Secondary | T20 | P3 | Med | Nightly job flags drift | Reconciliation catches a seeded missing webhook |
| T33 | Redis Cluster autoscale (reactive) | Scale | GATE | P4 | High | Cluster scales under load | k6 test triggers real scale-out |
| T34 | Citus resharding / multi-region | Scale | GATE | P4 | High | Cross-region routing works | Regional failover drill |
| T35 | Cross-region revocation mirror | Scale | T09, T34 | P4 | Med | Ban propagates across regions | MirrorMaker lag measured |
| T36 | Production deploy + monitoring wiring | Production | all above | P0 (final) | High | Live system, dashboards live | Alert fires on seeded incident |
| T37 | Backup/restore drill (actually executed) | Production | T36 | P0 (final) | Med | Restore completes | Real restore, not just documented |

## Open Decisions (summary, collected)

- ~~CI runner/platform not named.~~ RESOLVED 2026-08-18 by
  [[ADR-038-ci-platform]]: GitHub Actions, two independent jobs.
- IaC tooling not named (Terraform vs alternatives) — needed Phase 5/6.
  Every k8s assumption in the vault currently has no provisioning story.
- k8s manifest delivery not named (ArgoCD / Flux / kubectl-from-CI /
  Helm) — needed Phase 5/6.
- ~~Frontend CSS approach / component library~~ — resolved 2026-08-14:
  Tailwind CSS + shadcn/ui, see the frontend section above. (Build tool
  was never open — `frontend.md` specifies Vite; data layer resolved in
  the Phase-1 scaffold.)
- Backend build tool: **Gradle**, chosen 2026-08-14 — 15 modules with
  per-service protobuf codegen (ADR-023) make incremental build and
  build-cache behaviour matter against ADR-008's <10min per-commit CI
  tier. Recorded here, not as an ADR: it constrains no runtime guarantee.
- Seed/fixture data strategy not decided.
- Staging environment shape not decided.
