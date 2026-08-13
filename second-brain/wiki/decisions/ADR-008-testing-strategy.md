---
title: ADR-008 Testing Strategy — Pyramid, Contract, Concurrency, Load, Chaos
type: decision
sources: []
related: [[ADR-002-seat-locking-strategy]], [[ADR-004-redis-cluster-sharding]], [[ADR-005-postgres-sharding]], [[ADR-006-saga-booking-orchestration]], [[ADR-007-kafka-event-schema]], [[infra]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`wiki/testing/` was empty. Across ADR-004, ADR-005, ADR-006, ADR-007, and
this session's amendments to ADR-002, many numeric values are explicitly
documented as "starting default, needs real data" — the Redis 75%
autoscale trigger, etcd lease duration, saga compensation retry backoff
(1min/5min/30min), DLQ retry counts, hold TTL, the new Redis command
timeout. This ADR's primary job is producing the experiments that
actually generate those numbers, not just describing a generic pyramid.

Nothing is implemented yet — every `backend/*` directory is empty. This is
target design, verified against no running code.

# Requirements / Constraints

- Must prove the double-sell invariant under real concurrency, not just
  assert it in a mock.
- Must produce the specific numbers every ADR above defers, with an
  explicit mapping from experiment to the ADR/number it resolves.
- Must verify the strong claims already made about degraded behavior
  ("Redis down degrades to Postgres-only, never incorrect," "fraud-service
  fails open," "Patroni promotes a standby") — currently untested
  assertions, not proven ones.
- Must fit a 14-service microservices build with real infra (Citus, Redis
  Cluster, Kafka+Debezium+Schema Registry) without making local iteration
  unusable.

# Decision

## Test pyramid

```
L1 Unit (~70%)        pure functions, no Spring context, <5s/service
L2 Integration (~20%) Testcontainers — the load-bearing layer
L3 Contract           synchronous REST edges (Kafka already covered by
                       ADR-007's Schema Registry)
L4 E2E (~5%)          full docker-compose stack, only what no lower
                       layer can express
+ ArchUnit            architectural rules as a fifth, cheap, per-commit
                       layer
```

**L1** — seat state machine as a table-driven transition test (every
legal AND illegal transition); the saga step-transition function from
ADR-006 as a pure function of `(status, hold_reference, payment_intent_id,
hold_released, payment_refunded)`, exhaustively enumerated including
states that "can't happen" (a crash produces exactly those); hold-
extension arithmetic from ADR-002/006 as a property test (invariant:
`held_until <= original_held_until + 15min` for all inputs and call
orders).

**L2, no H2, ever.** `FOR UPDATE SKIP LOCKED`, partial unique indexes, and
`now()` semantics don't translate to H2 — testing ADR-002's constraint
backstop against H2 would make the thing ADR-002 calls "the actual
correctness guarantee" untestable while producing green tests.
**Redis Cluster mode, not standalone**, for inventory/queue/fraud tests —
standalone Redis has one keyspace and cannot surface `CROSSSLOT`, which is
exactly the bug class ADR-004 warns a missing hash tag causes. Use a
singleton container + `withReuse(true)` per service base class; without
reuse, 14 services' worth of container startup is unusable.

**ArchUnit rules, enforced per-commit:**
- No service imports another service's entity/repository package
  (ADR-001's data-ownership rule).
- All Redis keys built via one `SeatKeys`/`QueueKeys` helper — no raw
  string concatenation anywhere. Converts ADR-004's "key-naming discipline
  required everywhere" from a comment into a build failure.
- No direct `Instant.now()`/`LocalDateTime.now()` in production code —
  must go through an injected `Clock` (required by this session's ADR-002
  amendment).
- Every state-changing `@RestController` method declares an
  `Idempotency-Key` parameter (per [[cross-cutting-concerns]]).

## Contract testing: Spring Cloud Contract, not Pact

Producer-driven, stubs published as JARs — no new running component (Pact
would need a Broker service alongside the already-heavy Kafka
Connect/Debezium/Schema Registry footprint). Recover the consumer-driven
benefit at zero infra cost by keeping one contract file per (producer,
consumer) pair inside the producer repo — the producer physically cannot
delete a field without deleting a named consumer's contract file.

**Amendment: scope narrowed to REST/client-facing calls only (ADR-023).**
[[ADR-023-grpc-internal-service-calls]] moves internal service-to-service
calls to gRPC — those calls are no longer covered by Spring Cloud
Contract. Their contract guarantee comes from `.proto` schemas plus a
`buf breaking` CI gate instead (same "producer cannot silently break a
named consumer" property, schema-enforced rather than stub-file-enforced
— mirrors Avro's role for Kafka below). SCC's remaining scope is the
client-facing api-gateway ↔ frontend REST contract, if/when that gets
formal contract testing.

Covers the synchronous REST edges Schema Registry cannot see at all:
`booking-service -> inventory-service` (hold/confirm/release — must
contractually distinguish "hold expired" / "seat taken by another" /
"transient failure" as three distinct responses, since ADR-006's
compensation logic branches on that distinction), `booking-service ->
payment-service`, `*-service -> fraud-service`, `api-gateway -> every
service`.

**Conflict flagged against ADR-007**: SCC's messaging support assumes the
producer calls a Kafka template it can intercept. Under ADR-007's
Transactional Outbox, the producer never calls Kafka — it inserts an
outbox row; Debezium publishes later. SCC messaging contracts do not
apply. Async coverage is a three-way split instead: Avro structural
compatibility (Schema Registry, CI-gated), "right event at the right
moment" (L2 test asserting the outbox row, not a Kafka message), and
outbox->Kafka delivery (one Testcontainers Debezium pipeline test per
producer). Recording this so "we have contract tests" is not misread as
covering Kafka.

## Concurrency testing

**Generating true concurrency in a JVM test** (the part usually done
wrong, and a false pass here is worse than no test):

```
1. Pre-warm: each thread hits a throwaway seat first (pays JIT/pool/TCP
   cost before the real race).
2. CyclicBarrier(N+1) release, then volatile-flag busy-wait — barrier
   release alone costs tens of microseconds, wide enough to serialize a
   fast SETNX if not controlled for.
3. Record arrival nanoTime per thread; FAIL the test if spread > 5ms —
   this assertion matters as much as the outcome assertion.
4. Platform threads for N<=200. Above that, virtual threads don't help
   (JDBC driver's synchronized blocks pin carrier threads) — drive
   external load with k6 instead.
5. @RepeatedTest(50) with randomized per-thread jitter, run both
   in-process and against a containerized instance (in-process misses
   connection-pool/proxy behavior).
```

**Test 1 — N concurrent holds on one seat, exactly one wins.** N=200
threads, one seat, barrier-released. Assert: exactly one success; DB
count of HELD/PURCHASED for that seat is exactly 1; the other 199 are a
clean 409 `SEAT_UNAVAILABLE`, zero 5xx, zero timeouts (ADR-002: "losers
rejected fast" — a timeout is not that); losers' p99 latency below the
winner's (if losers are slower, the Redis fast-gate isn't doing its job).
Run in three Redis modes — healthy, connection-refused, **blackholed**
(Toxiproxy timeout toxic) — all three must hold the same invariants; this
is the direct test of this session's ADR-002 timeout/breaker amendment.

**Test 2 — proving the unique-constraint backstop actually catches a
bug.** Do not verify by editing production code. Swap in a
`@TestConfiguration`-provided repository whose `lockSeatForUpdate` issues
plain `SELECT` (no `FOR UPDATE`), rerun Test 1's harness. Assert: DB count
still exactly 1 (the database caught it), losers still get a clean 409
not a 500 (proves the `DataIntegrityViolationException` -> domain
rejection translator is wired to *this* constraint's name specifically).
Companion tests: assert the partial unique index exists with the exact
predicate after migration (catches a Flyway migration written but
conditionally skipped); assert it does NOT reject same-seat rows in
`AVAILABLE`/`EXPIRED` (catches an over-broad predicate). **Run against a
Citus-enabled container**, asserting the constraint holds cross-shard —
this is the direct test of this session's ADR-002 x ADR-005 amendment
(`event_id` in the key).

**Test 3 — the ADR-006 payment race, deterministically.** Requires the
Clock-injection amendment from ADR-002 (SQL `now()` cannot be driven from
a test). Choreography: A holds, submits payment (WireMock holds the
charge response open); jump the test Clock past the 15min ceiling; sweep
expires A's hold; B holds+pays+confirms; release A's held response, A's
payment succeeds; A's `confirm()` is rejected. Assert: A's booking reaches
`COMPENSATING -> FAILED` with a reason; refund called exactly once (then
re-drive the compensation job, assert *still* exactly one — idempotency);
seat is `PURCHASED` by B, never lost, never double-sold; standing
invariant query returns zero bookings with "payment captured, neither
CONFIRMED nor refunded." Companion: nine-case crash-resume matrix (every
combination of status x hold_released x payment_refunded), each asserted
to reach a terminal state via the resume job.

## Load testing: k6

| | k6 | Gatling |
|---|---|---|
| Generator overhead | Go, goroutine/VU, one node drives tens of thousands | JVM, competes with the SUT for the same tuning surface |
| Load model | `ramping-arrival-rate` — open model, correct shape for a spike | also open-model capable |
| Prometheus | native remote-write | plugin |

Gatling's Java DSL is a defensible pick for a Java shop, but k6 wins on
one decisive point: ADR-004's reactive autoscaler triggers off
Prometheus+redis_exporter, and calibrating that trigger means load-
generator metrics and shard metrics must sit on the same timeline at the
same scrape resolution. k6's native remote-write gives that directly. A
JVM generator's own GC pauses would also show up indistinguishable from
SUT latency.

**Scenarios**: S1 hot on-sale (defining scenario — full journey
queue-join through confirm, **seat selection Zipf-distributed, not
uniform** — uniform selection produces almost no contention and makes
ADR-002 look unnecessary, the single most common load-test error here);
S2 steady background browse/search (always running underneath every other
scenario); S3 multi-event mix (required for ADR-004/005 sharding —
S1 alone pins to one shard by hash-tag design); S4 single-seat stampede
at thousands-VU scale (external version of concurrency Test 1); S5
payment-latency sweep (200ms-30s, bimodal 3D-Secure-shaped); S6 2h soak.

**Standing invariant auditor** — separate process, always on during load
and chaos runs, polling every few seconds for: more than one active
hold/purchase per seat; any booking `COMPENSATING` past the escalation
window; any captured payment whose booking is neither confirmed nor
refunded; any hold past its 15min ceiling. A load test without this
measures throughput while possibly double-selling seats silently.

### Experiment -> ADR mapping

| Exp | What it does | Resolves |
|---|---|---|
| E1 | Redis node capacity benchmark + command-timeout/breaker sweep under blackhole | ADR-004 per-node ceiling; ADR-002 Redis timeout (this session's amendment) |
| E2 | Autoscale trigger sweep (60/70/75/80/85% x 30/60/120s sustain) vs S1 spike | ADR-004 "75% for 60s" |
| E3 | Hot-shard mitigation: flat vs `{sessionId}:{sectionId}` tag, per-shard skew vs demand level | ADR-004 high-demand flag criteria |
| E4 | etcd lease sweep under Postgres-primary/etcd partition, 2h soak for false-failover rate | ADR-005 etcd lease duration |
| E5 | Hold-TTL calibration: think-time distributions, abandoned-hold rate, compensation rate | ADR-002 hold TTL; ADR-006 extension margin |
| E6 | Compensation-retry calibration: injected provider failure patterns, attempt 1/2/3 success share | ADR-006 retry backoff/escalation |
| E7 | DLQ calibration: poison-pill injection, N=3/5/10 retries, per-partition stall time | ADR-007 DLQ retry count/backoff |
| E8 | Topic sizing from S1/S3 throughput + E7's replay window | ADR-007 partition count/retention |
| E9 | Redis gate ON vs OFF under S4, unrelated-booking success rate/p99 during a stampede | ADR-002 "Revisit When" — empirical proof Option D beats Option A |
| E10 | Citus topology under S3: per-worker skew, coordinator saturation point | ADR-005 shard-count/node-headroom validation |
| E11 | Queue admission-rate sweep against E9's saturation point | queue-service admission-rate open question |

Two honesty notes: E2's spike replay is compressed and cannot fully
reproduce a real 5-15min organic viral spike — the resulting trigger value
carries that fidelity caveat. E1's per-node number is meaningless without
pinned instance specs — record instance type/vCPU/memory/network class
alongside every published number, or the calibration isn't transferable.
No test-environment sizing decision exists yet; must be made before E1
runs.

## Chaos / failure injection

**Toxiproxy** (deterministic, in-JUnit, CI-gated — down/timeout/latency
per connection) for C1/C4/C5. **Chaos Mesh** (pod kills, network
partitions, `TimeChaos` clock skew) for what Toxiproxy can't reach.
Avoid raw `docker kill` as a primary method — not repeatable enough to be
a regression gate. Every experiment runs with the invariant auditor
active; verdict is "invariant held AND the claimed degradation actually
happened," not just "nothing crashed."

| Exp | Untested claim | Injection | Key assertion |
|---|---|---|---|
| C1 | "Redis down degrades to Postgres-only, never incorrect" (ADR-002) | refused / blackhole / latency | invariants hold in all 3 modes; breaker opens, thread pool never exhausts |
| C2 | Per-shard Redis failover (ADR-004) | PodKill a master | slot-unavailability window measured; negative test: zero-replica shard stays permanently unavailable |
| C3 | Patroni promotion + split-brain prevention (ADR-005) | partition primary from etcd (not a kill — must exercise self-fencing) | old primary self-fences; write to both addresses during the partition, exactly one accepts |
| C4 | "fraud-service fails open" | down, and separately slow | `bypass_log_count == request_count` for the outage window |
| C5 | "Pub/Sub down = no live updates, booking still works" | block SPUBLISH | booking p99/success unchanged; reconnecting client converges via Postgres |
| C6 | Outbox+Debezium loses nothing (ADR-007) | stop connector N min, kill a broker | outbox event_id set == consumed event_id set, no gaps |
| C7 | "notification-service outage never blocks confirmation" | kill notification-service | booking unaffected; lag drains fully on restart |
| C8 | Saga crash-resume (ADR-006) | random PodKill across a long S1 run | zero non-terminal bookings after drain, statistical, weekly soak |
| C9 | "search outage never blocks event/venue writes" | kill Elasticsearch | writes succeed; index catches up from replay |
| C10 | Clock skew (no ADR covers this — a real gap) | TimeChaos +-30s one inventory instance | expiry stays correct — validates the Clock-injection amendment |
| C11 | Compound (Redis shard loss during S1 peak + payment outage) | game day | no single assertion — where compound failures surface, weekly/pre-release |

## CI pipeline shape

```
Per commit/PR (<10min): compile+lint, ArchUnit, L1, L2 for changed
  services only (singleton+reuse), migration+constraint-existence check,
  SCC producer tests + stub publish, Avro compatibility check against
  Schema Registry, fast concurrency gate (N=50, 10 reps, Redis-healthy
  only, ~60-90s) — non-negotiable on any inventory/booking-service change.

Per merge to main (+20-30min): full L4 E2E on the infra/ compose stack;
  full concurrency suite (N=200 x 50 reps, all 3 Redis modes); constraint-
  backstop injection test incl. Citus co-location; payment-race + crash-
  resume matrix; deterministic chaos C1/C4/C5.

Nightly: full contract matrix across all 14 services (not just changed);
  k6 S1 at ~10% peak as a regression gate (only useful once experiments
  produce real thresholds); Chaos Mesh C2/C3/C6/C7/C9/C10; PIT mutation
  testing on inventory-service and booking-service ONLY (line coverage is
  near-meaningless for concurrency/saga code; mutation testing is not —
  not worth the cost on the other 12 services).

Weekly/pre-release: full-scale k6 S1+S3; 2h soak S6 with invariant
  auditor; C8 (saga soak); C11 (game day).

On demand: E1-E11 calibration runs. Not CI gates — each produces a number
  written into wiki/testing/ and then amended into the owning ADR, exactly
  like this session's process for ADR-002/004/006/007. Once an experiment
  yields a number, it becomes a nightly CI threshold and the experiment
  demotes to periodic re-validation.
```

## Amendment: deployment and rollback shape (CI pipeline gets you to "green," this covers what happens next)

**Gap found**: the CI pipeline above ends at "merge to main passes."
Nothing decides how a passing build actually reaches production, or —
the sharper gap — what happens when a deployed change turns out bad. A
test suite this thorough with no rollback story just moves the failure
point later, from "caught in CI" to "caught in production with no
defined way back."

```
Deploy mechanism: rolling update (k8s Deployment default), NOT
  blue/green or canary — this project's services are stateless per
  service (durable state lives in Postgres/Redis/Kafka, per every
  service's own ADR), so a rolling update's brief mixed-version window
  is already the same "old and new app versions run simultaneously"
  condition [[ADR-027-schema-migration-strategy]]'s expand/contract
  discipline already requires every schema change to tolerate — reusing
  that existing constraint rather than adding a second deployment
  strategy to satisfy it.

Rollback trigger: automatic, based on the SAME signals ADR-015's
  observability stack already emits — a spike in the target service's
  error rate or its specific SLI (e.g. inventory-service's three-way
  hold-outcome ratio, booking-service's saga-latency-to-TTL ratio)
  immediately post-deploy aborts the rollout and reverts to the prior
  image. Reuses existing metrics, no new signal invented for this.

Rollback mechanism: `kubectl rollout undo` (revert to the prior
  ReplicaSet) — NOT a data rollback. This is a load-bearing distinction:
  reverting application CODE is safe and fast; reverting a database
  MIGRATION is not attempted by this mechanism at all.

Migration/rollback interaction (the actual hard case): a bad deploy
  whose migration already ran cannot be undone by `rollout undo` alone —
  the old code now runs against a newer schema. This is exactly why
  [[ADR-027-schema-migration-strategy]]'s expand/contract discipline is
  mandatory, not optional: phase 1-2 (additive, dual-write) are the ONLY
  phases where a code rollback stays safe against the current schema.
  Phase 5 (contract — actually dropping the old shape) must never ship in
  the same deploy as the code that stops needing it; a rollback window
  must fully elapse first. A future reader must not treat this rollback
  ADR and ADR-027's migration ADR as unrelated — they are the same
  constraint viewed from two sides.
```

# Why

Testing this system's concurrency claims by mocking would test the mocks,
not the guarantees ADR-002/005/006 actually depend on (Postgres row
locking under Citus sharding, Redis atomicity, saga crash-resume). Real
containers and real fault injection are the only way to make "the
partial unique constraint is the actual correctness guarantee" a verified
statement instead of an assertion. Calibration is treated as a first-class
deliverable — every "starting default, needs real data" number across the
vault has a named experiment that produces it, closing the loop this
project's own convention opened.

# Consequences

**Easier:** every strong claim in ADR-002/004/005/006/007 about
degradation becomes a verified, re-runnable fact instead of a design-doc
assertion; calibration numbers arrive with a documented, repeatable
source instead of being guessed at implementation time.

**Harder:** significant local infra footprint (Testcontainers Postgres +
Redis Cluster + Kafka + Connect/Debezium + Schema Registry + Elasticsearch
+ Toxiproxy, per relevant service); concurrency and chaos suites are
genuinely hard to write correctly (see the "generating true concurrency"
section — a naive version silently produces a false pass); calibration
runs need a decided, pinned test-environment sizing before their numbers
mean anything transferable.

# Revisit When

- Once inventory-service and booking-service exist, the concurrency and
  crash-resume suites above become the actual PR gate for any change to
  either — if they're not red-green-verified against real code within the
  first sprint of building those services, this ADR's central claim
  (verified, not assumed, correctness) has not been delivered.
- If E1-E11 produce numbers that materially miss the "starting default"
  guesses in ADR-002/004/005/006/007, amend those ADRs with the real
  values — don't let the guesses linger once real data exists.

## Open Questions

- Test-environment sizing (instance specs for calibration runs) — not yet
  decided, blocks E1 and therefore several downstream experiments.
- PIT mutation-testing score threshold for inventory-service/
  booking-service — not yet decided, needs a baseline run first.
