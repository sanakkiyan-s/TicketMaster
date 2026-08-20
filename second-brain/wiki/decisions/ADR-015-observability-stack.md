---
title: ADR-015 Observability Stack — Traces, Metrics, Logs, Alerting
type: decision
sources: []
related: [[ADR-002-seat-locking-strategy]], [[ADR-004-redis-cluster-sharding]], [[ADR-006-saga-booking-orchestration]], [[ADR-007-kafka-event-schema]], [[cross-cutting-concerns]], [[flows/seat-availability-live-updates]]
created: 2026-08-06
last-updated: 2026-08-20
---

Status: Accepted

## Verification Status (2026-08-20)

Built and verified live against the 3 services that actually exist
(auth-service, api-gateway, user-service) — see
[[infra]] for the infra-side writeup.

**Live and confirmed with a real request** (register + login through the
gateway):
- OTel Java agent instrumentation on all 3 services (traces, metrics,
  logs, one `-javaagent` flag in `backend/Dockerfile`, no per-service
  code).
- Single-Collector topology (not the two-tier agent/gateway split this
  ADR's literal text describes) — deliberate simplification at
  3-service/single-host scale; every service points at "the collector"
  via one env var, so upgrading to two-tier later is a topology change,
  not an app change. Revisit per this ADR's own "Revisit When" framing
  once horizontal scaling makes tail-sampling-at-scale relevant.
- Tempo: full cross-service trace (api-gateway → auth-service, 16 spans,
  real JDBC/Redis/Hibernate detail) confirmed for a real login request.
- Loki: `trace_id` structured-metadata correlation confirmed — same
  trace ID present on both the Tempo trace and the matching auth-service
  log line.
- Mimir: real per-route/per-service request-count series confirmed
  (`http_server_request_duration_seconds_count`) for the exact
  register/login calls made during verification.
- Debezium/JMX exporter on kafka-connect: real metric names confirmed
  (`debezium_postgres_connector_metrics_millisecondsbehindsource`,
  `_snapshotdurationinseconds`), flowing through Prometheus (agent mode)
  → Mimir.
- Outbox tracing (`traceparent`/`tracestate` on outbox rows →
  `RevocationPublisher.java` → Debezium EventRouter SMT → Kafka headers)
  is live now that the agent generates real W3C traceparent headers — no
  code change was needed, that path already did the right thing (see
  that file's own comment, written before this ADR was implemented).
- Grafana dashboard (`TicketMaster - Service Overview`: request
  rate/p95 latency/error rate per service, JVM heap/GC, Debezium lag)
  and one alert rule (`OutboxStalled`, >30s lag for 5m) provisioned and
  loaded by Grafana with no errors, and **live-fire tested end to end**
  on 2026-08-20 — genuinely confirmed, not just config-checked. Two real
  findings from that test:
  - The originally-planned test method (pause the connector, generate an
    outbox event, wait) doesn't exercise the alert at all: Debezium's
    `MilliSecondsBehindSource` only recalculates when it processes a new
    event, so it *freezes* at its last value while the connector is
    paused rather than climbing — a paused connector produces no lag
    signal, not a growing one. Verified via the raw JMX exporter output
    before and after a pause+event+wait cycle: value never moved.
  - The real test method (temporarily lower the threshold below the
    current real value, confirm Alerting, restore) uncovered a genuine
    bug: Grafana's threshold expression node needs a top-level
    `expression: A` field naming which query to operate on —
    `conditions[].query.params` alone isn't enough. Without it the rule
    provisions with **no error** but fails at evaluation time
    (`"failed to parse expression 'C': no variable specified to
    reference for refId C"`), and Grafana's default
    `execErrState: Alerting` makes a broken rule look like it fired. Now
    fixed in `infra/grafana/provisioning/alerting/rules.yml`. After the
    fix, the alert was confirmed to transition to a genuine `Alerting`
    state with a real evaluated value (not an error) and reverted
    cleanly to `inactive` once the threshold was restored to 30000.

**Explicitly deferred, not silently dropped** — this ADR designs against
domain concepts (bookings, sagas, payments, holds) that don't exist yet:
- The other 4 P1 alerts (`PaidUserUnresolved`, `DoubleSellDetected`,
  `PaymentWebhooksSilent`, `OnSaleCriticalPathDown`) — nothing exists yet
  for them to alert on.
- All domain-specific SLIs below (saga latency, three-way hold outcome,
  payment webhook deadman, SSE lag) — same reason.
- MinIO/S3 object storage for Tempo/Loki/Mimir — filesystem storage used
  instead at current scale (each tool's own documented simplest
  local/single-binary mode); revisit if retention or multi-instance
  needs force it.

**One real bug found and fixed during verification, worth knowing about**:
Mimir's ingester/store-gateway rings default to `replication_factor: 3`
(built for a multi-ingester cluster) — with exactly one Mimir process,
every write and every query failed outright until
`infra/mimir/mimir.yaml` explicitly set `replication_factor: 1` on both
rings. Anyone standing this up fresh should not be surprised by "at
least 2 live replicas required" / "too many unhealthy instances in the
ring" — it's already fixed, just noted here for anyone who forks the
config elsewhere.

# Context

[[ADR-004-redis-cluster-sharding]] already assumes Prometheus + a Redis
exporter for its reactive autoscale trigger. `cross-cutting-concerns.md`
mentions correlation IDs and tracing but never picks tooling.
`seat-availability-live-updates.md` has an explicitly-incomplete
Observability Requirements section. This ADR picks concrete tooling and
designs the domain-specific SLIs/alerts this system actually needs, not a
generic checklist.

# Requirements / Constraints

- Must preserve ADR-004's Prometheus assumption — any metrics decision
  that breaks its query surface silently breaks that ADR's autoscale path.
- Must trace one booking end-to-end across the Saga, including the async
  Kafka hops introduced by [[ADR-007-kafka-event-schema]]'s Transactional
  Outbox (the producing service never calls Kafka directly — see that
  ADR's tracing amendment, already applied).
- Must not let per-seat/per-user metrics blow up Prometheus cardinality.
- Must implement the alert ADR-006 already promised
  (`PaidUserUnresolved`) and give it teeth.

# Decision

## Instrumentation and collector topology

OpenTelemetry Java agent (auto-instruments Spring MVC/JDBC/Lettuce/Kafka/
HTTP client) + Micrometer Observation API (manual, for domain concepts
the agent can't know: `seat.hold`, `saga.step`, `queue.admit`) — Spring
Boot 3's OTel bridge means one API produces both the span and the timer.
Services export OTLP to a two-tier Collector, not directly to backends —
this is where the cardinality allowlist (below) is enforced centrally,
which is unenforceable if left to 14 services' discipline individually.

```
service (OTLP) -> Collector agent tier (batch, resource attrs, redaction)
              -> Collector gateway tier (loadbalancing exporter, key=traceId)
              -> tail_sampling -> Tempo
                 metrics -> Prometheus/Mimir
                 logs    -> Loki
```

The `loadbalancing` exporter keyed on trace ID in the gateway tier is
required, not optional — tail sampling only works if every span of a
trace lands on the same collector instance; skipping this silently drops
half of each trace.

## Backend choice per pillar

**Traces: Grafana Tempo**, not Jaeger. Tempo is object-storage backed
(S3/MinIO) — retention costs almost nothing, so 100% of failed-saga
traces can be kept for 90 days. The interesting trace here is rare (a
compensating saga, a lost hold race); you want all of those forever and
almost none of the happy path, which object storage + tail sampling makes
affordable and an indexed store (Jaeger's ES/Cassandra backend) does not.

**Metrics: Prometheus (agent mode) -> Grafana Mimir**, not Prometheus
alone. Preserves ADR-004's assumption verbatim; Mimir adds long-term
storage and a per-tenant `max_series` cap — a real enforcement point for
the cardinality budget below. Plain Prometheus has no long-term retention
or global view, directly at odds with ADR-004/005's global-scale framing.

**Logs: Loki, explicitly NOT the search-service Elasticsearch cluster.**
This is the sharpest tradeoff here, and reusing ES is the wrong call
despite the apparent consolidation win:

```
1. Correlated failure by shared resource: search-service's ES is a
   user-facing, latency-critical read path (discovery during an
   on-sale). Log volume peaks at exactly the same moment (on-sale =
   100x requests = 100x log lines). Sharing means log-write pressure
   degrades seat-map discovery latency at the worst possible moment —
   same class of coupling ADR-002 exists to avoid for the Postgres pool.
2. Your observability store must not depend on the thing you're
   debugging. If ES degrades, you lose both search AND the ability to
   see why.
3. Opposite tuning profiles: search wants many small shards, frequent
   refresh, heavy caching; logs want time-rolling indices, ILM, low
   refresh. One cluster can't be tuned for both.
4. Structured JSON + correlation ID doesn't need full-text scoring —
   the access pattern is "every line where trace_id=X," Loki's exact
   strength at a fraction of the cost.
```

**Verdict: bad coupling, not smart consolidation. Consolidate the skill,
not the cluster.** Audit logs stay out of the log pipeline entirely —
`cross-cutting-concerns.md` already decided each service writes its own
audit table + emits `AdminActionPerformed`; that's durable business data,
not a log stream with a retention policy.

## Domain-specific SLIs (not generic infra metrics)

All numeric targets below are *starting defaults, need real data* per
this project's established convention. What is not a starting default is
the set of things measured at zero:

**Correctness invariants (target = exactly 0, always):** double-sell rate
(constraint-violation count on ADR-002's partial unique index — a nonzero
value means the backstop caught a real defect); paid-and-unresolved
bookings (`COMPENSATING`, `payment_refunded=false`, past the escalation
window — this is ADR-006's worst-case condition, made measurable);
holds past the 15min hard ceiling; outbox rows unpublished beyond a few
minutes (proof-of-life for ADR-007's delivery guarantee).

**Three-way hold outcome** (per this session's ADR-002 amendment):
`won` / `lost_race` (normal, expected during an on-sale) / `infra_failure`
(the only one that's an error). A binary success/fail metric makes a
*successful* on-sale look like an outage.

**Saga latency and compensation rate**: p99 `saga_duration_seconds`
tracked against the hold TTL ratio — as `p99(saga_duration)/hold_ttl`
approaches 1.0, compensation volume is about to spike; this is a leading
indicator, not a lagging one. Compensation success broken out by attempt
number (1/2/3), directly matching ADR-006's retry ladder — this is the
literal instrument that produces the data ADR-006's own Open Question
needs.

**Debezium replication lag** (added 2026-08-13, closes the mitigation half
of [[ADR-026-backup-pitr-strategy]]'s honestly-stated residual risk):
tracks the gap between "WAL record written" and "Debezium published it to
Kafka," per connector. This is not zero-target like the invariants above
— some lag is structurally unavoidable — but it directly measures the
size of the unrecoverable window if a crash happens at that exact moment
(ADR-026: outbox-replay can only recover what Debezium already read).
Alert if sustained lag exceeds a threshold (starting default, needs real
data, same category as every other numeric default in this vault) —
shrinks the exposure window operationally; does not close it to zero,
which ADR-026 already states plainly is not achievable without
restructuring to a broker-first architecture this project rejected for
booking's correctness requirements. Payment-side exposure in this same
window is separately mitigated by [[ADR-035-payment-reconciliation-and-dispute-workflow]]'s
nightly reconciliation against Stripe's own record — an external system
of truth outside this failure mode entirely. Booking/ticket data has no
equivalent external record, so this SLI is the only mitigation available
for that side, and the residual risk stays real, not a false sense of
full coverage.

**Payment webhook lag** (not request latency — `payment-service.md`
already establishes the webhook as the source of truth, so webhook
arrival lag is the real critical-path number) with a **deadman
companion**: zero webhooks while charges are in flight is invisible to
every latency metric and is the hardest failure to detect, since nothing
looks like it's failing — nothing is happening.

**SSE observability** (fills the incomplete section in
`seat-availability-live-updates.md`): end-to-end push lag (Postgres
commit -> socket write, not just publish-half), publish success/failure,
fanout ratio (subscribers reached / expected — catches a misrouted
sharded-pubsub hash tag silently reaching nobody), active connections per
instance, reconnect/churn rate (a spike means clients fell back to full
Postgres reads — hidden load during an on-sale). **Deliberately not an
SLO**: message delivery guarantee — the flow doc already says Pub/Sub is
fire-and-forget by design; don't set a target on something architecturally
not guaranteed.

## Alerting — page only on money/trust damage, not CPU

```
P1 (pages 24/7):
  PaidUserUnresolved   — bookings COMPENSATING, unrefunded, past 40min.
                          ADR-006's promised alert, now concrete, tied
                          to its own retry ladder — retune both together.
  DoubleSellDetected    — the ADR-002 backstop fired. Page on one
                          occurrence.
  OutboxStalled         — Debezium lag/down. ADR-007's guarantee is not
                          currently holding.
  PaymentWebhooksSilent — deadman: zero webhooks while intents are being
                          created. The hardest failure to see, so page
                          fast rather than wait for the compensation wave
                          it will eventually cause.
  OnSaleCriticalPathDown — burn-rate alert on hold/booking/pay endpoints,
                          measured at Nginx so a request that never
                          reaches the JVM still counts.

P2 (business hours / active on-sale): Redis hold-gate fail-open rate,
  saga latency approaching hold TTL, DLQ arrivals, queue estimate
  accuracy.

P3 (ticket, never pages): SSE lag, search staleness, Redis threshold
  breaches that auto-resolved via ADR-004's reactive path.
```

**On-sale mode gating**: alert thresholds must be aware of on-sale
windows — `lost_race` hold rejections legitimately spike 100x during a
successful on-sale; naive rejection-rate alerting pages on a *working*
system. Reuse ADR-004's existing high-demand-event flag as the gating
signal rather than inventing a second source of "is this an on-sale
right now."

## Distributed tracing across the outbox boundary

Mechanism (schema/SMT changes) already specified in
[[ADR-007-kafka-event-schema]]'s amendment and
[[ADR-006-saga-booking-orchestration]]'s amendment — `traceparent`/
`tracestate` persisted in the outbox row inside the business transaction,
mapped to Kafka headers via Debezium's event-router SMT, `correlationId`
defined as the trace-id. The payment-webhook resume (saga suspends,
resumes minutes later on a possibly different instance) is handled via an
OTel **span link** from a new root trace back to the stored
`saga_traceparent`, not a single artificially-long span — recorded in
ADR-006. Tail-based sampling (not head-based — head sampling at any
practical rate discards nearly every compensating saga, which are exactly
the traces that matter) with policies that always keep: error status,
`booking.status IN (COMPENSATING, FAILED)`, `infra_failure` hold
outcomes, and slow (>5s) traces.

## Cardinality control

The failure mode: `seat_hold_total{event_id, session_id, seat_id, user_id,
outcome}` at realistic scale is hundreds of millions of series before
`user_id` is even added — Prometheus dies in the low single digits of
millions. The fix is routing each question to the store built for its
cardinality, not fighting Prometheus into holding more:

```
Tier 1 Prometheus/Mimir  ~10^4 series/metric   "is the system healthy now"
Tier 2 Tempo (traces)     unbounded, by trace ID "what happened to THIS booking"
Tier 3 Loki                bounded labels        "every log line for trace X"
Tier 4 analytics-service   unbounded              "which seats had contention"
```

**Per-event business visibility is a tier-4 question**, not tier-1 — it
doesn't need 15-second freshness or an alert. analytics-service already
consumes the Kafka event stream ([[ADR-007-kafka-event-schema]]); feed it
the same events rather than re-deriving business analytics from the
alerting store, which is the decision that prevents the blowup at its
source.

Enforced rules: no `seat_id`/`user_id`/`booking_id`/`session_id` as a
Prometheus label, ever (centrally enforced in the collector, not left to
service-level discipline — 14 services will not stay disciplined for two
years). `event_id` only via a bounded allowlist reusing ADR-004's
high-demand-flag set (the same events anyone is watching get full
dashboards; everything else aggregates to `event_id="__other__"`).
Per-entity detail lost from metrics is recovered via **exemplars** —
every histogram bucket carries a trace ID, so aggregate view lives in
Prometheus and individual drill-down lives one click away in Tempo.

# Why

Domain-specific SLIs (three-way hold outcome, saga-latency-to-TTL ratio,
webhook-silence deadman) catch the failure modes this system's own ADRs
already worry about, where generic infra metrics (CPU/memory) would not.
Routing cardinality to the right-sized store, rather than fighting
Prometheus to hold more, is the only approach that scales with the number
of concurrent events without an ever-growing metrics budget.

# Consequences

**Easier:** ADR-006's promised human escalation has a concrete
implementation; a booking's full lifecycle is traceable end-to-end
despite the outbox pattern's async boundary; alert noise stays low
because thresholds are on business-damage conditions, not resource
utilization.

**Harder:** meaningful new infra footprint (Collector, Tempo, Mimir, Loki,
Vault-adjacent complexity already exists elsewhere); the outbox-tracing
mechanism requires disciplined schema changes now, before any topic is
live, mirroring the urgency of ADR-007's PII-as-bytes constraint; cardinality
discipline must be enforced centrally or it silently regresses via
ordinary-looking PRs.

# Revisit When

- If VictoriaMetrics's better cardinality tolerance becomes necessary
  once real load-test data (ADR-008's E1-E11) shows Prometheus/Mimir
  genuinely straining despite the tiering above — documented escape
  hatch, not a now-decision.

## Open Questions

- Cardinality budget numbers (500k total series, 50-event allowlist cap)
  — starting defaults, need real data.
- Tail-sampling `decision_wait` (30s) — must exceed the synchronous
  portion of the saga's p99; needs real measurement once built.
