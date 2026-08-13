---
title: ADR-023 gRPC for Internal Service-to-Service Calls
type: decision
sources: []
related: [[ADR-009-service-to-service-auth]], [[ADR-008-testing-strategy]], [[ADR-007-kafka-event-schema]], [[ADR-015-observability-stack]], [[booking-service]], [[payment-service]], [[inventory-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[system-overview]] and every ADR written so far assumed REST/JSON for
synchronous service-to-service calls (booking-service → inventory-service,
booking-service → payment-service) without ever comparing that choice
against gRPC — REST was simply what got used while writing the other
ADRs, never a deliberate decision against the alternative.

This session surfaced the actual motivation for revisiting it: Spring
Cloud Contract ([[ADR-008-testing-strategy]]) only covers REST — its own
body states "SCC messaging contracts do not apply" to the Kafka/outbox
path, which is instead covered by Avro's schema-compatibility gate
([[ADR-007-kafka-event-schema]]). That leaves two different, inconsistent
answers to "how do we stop a producer breaking a consumer without
noticing" — a hand-maintained stub file for REST, a real schema-enforced
gate for Kafka. gRPC's Protobuf contracts give the same schema-enforced
guarantee Kafka already has, for the synchronous path too.

# Requirements / Constraints

- Must not break [[ADR-009-service-to-service-auth]]'s two-token
  authorization model (service JWT + end-user assertion) — must carry
  equivalently under the new transport.
- Must not require adopting Kubernetes before Compose is retired — same
  constraint ADR-009 already stated and honored.
- Client-facing traffic (browser → api-gateway) is unaffected — browsers
  cannot speak gRPC natively without a `grpc-web` proxy layer; explicitly
  out of scope here.
- Must integrate with the existing OTel tracing pipeline
  ([[ADR-015-observability-stack]]) and correlation-ID propagation.
- Must give the same "producer cannot silently break a named consumer"
  guarantee Spring Cloud Contract gives today for REST.

# Options Considered

## A — Keep REST/JSON internally, keep Spring Cloud Contract (status quo)

Pros: nothing changes, existing ADR-008 tooling stays as-is. Cons:
JSON contracts remain hand-maintained stub files with no compile-time
type safety, and — the actual trigger for this ADR — leaves the
sync-call contract story permanently weaker than and inconsistent with
Kafka's schema-enforced one.

## B — gRPC for all internal service-to-service calls, Protobuf as the contract

Pros: strongly-typed generated clients, binary/faster wire format,
breaking-change detection via CI (`buf breaking`) mirrors the same
"schema is the contract, CI enforces compatibility" guarantee Avro
already gives Kafka — one consistent integration philosophy instead of
two half-measures. Cons: HTTP/2's connection-pileup load-balancing
problem (discussed this session) needs a real answer — neither
client-side gRPC balancing nor a sidecar proxy is currently designed;
adds a protoc/buf toolchain and generated-code step to all 15 services;
[[ADR-009-service-to-service-auth]]'s HTTP-header-based two-token model
must be re-expressed as gRPC metadata; Spring Cloud Contract no longer
covers these calls, needs a replacement gate.

## C — gRPC only on the highest-value path (booking↔inventory↔payment), REST elsewhere

Pros: limits new toolchain surface to the three services under the most
concurrency/perf pressure. Cons: running two transport styles
simultaneously is real ongoing inconsistency, doubles the auth-carriage
work (two mechanisms instead of one), and conflicts with
[[ADR-001-microservices-vs-modular-monolith]]'s discipline against
unjustified per-service special-casing — nothing about payment-service
or ticket-service's traffic pattern actually demands a different
transport than inventory-service's.

# Decision

**Option B.** gRPC for all internal (service-to-service) synchronous
calls. REST/JSON remains, unchanged, at the client-facing edge
(api-gateway ↔ frontend) — out of scope, not touched by this decision.

## Contract mechanism — replaces Spring Cloud Contract for internal calls

```
.proto files ARE the contract — one file per service's exposed RPC
  surface, versioned inside that service's own repo. Same "producer owns
  the contract" principle ADR-008 already established for REST stubs,
  same file location convention, different format.

CI gate: `buf breaking` compares every PR's .proto against the last
  published/tagged version. A removed field, changed field number, or
  incompatible type change fails CI in the PRODUCER's own pipeline,
  before merge — identical guarantee to what Spring Cloud Contract gave
  for REST, and what Avro/Schema Registry already gives Kafka
  (ADR-007).

Generated Java stubs (protoc-gen-grpc-java) published as a versioned
  artifact — same JAR-publishing mechanism ADR-008 already established
  for SCC stubs, so consumers pull typed clients instead of hand-written
  HTTP calls.
```

Spring Cloud Contract's role narrows to REST only — the client-facing
api-gateway ↔ frontend contract, if/when that gets formal contract
testing. Amendment applied to [[ADR-008-testing-strategy]] below.

## Auth model — same tokens, different carriage

Amends [[ADR-009-service-to-service-auth]]'s two-token model, not
replaces it — the tokens, scopes, and `aud`-verification requirement are
unchanged, only how they travel:

```
gRPC metadata replaces HTTP headers 1:1, same fields, same semantics:
  authorization:      Bearer <service token>   (was: Authorization header)
  x-user-assertion:   <end-user token>          (was: X-User-Assertion header)
  x-correlation-id:   <trace id>                 (was: X-Correlation-Id header)
```

A gRPC server interceptor replaces the Spring Security resource-server
filter chain — same JWKS validation, same scope check (the
`@PreAuthorize`-style rule becomes a small authorization interceptor
checked against the called RPC method). `aud` claim verification is
still mandatory, unchanged from ADR-009.

## Load balancing and resilience — three client-side layers, no sidecar

Closes the gap flagged earlier this session. Per-instance health
awareness turns out **not** to require a service mesh — gRPC's
`outlier_detection` LB policy is a generic policy (not xDS/mesh-specific
per its own design doc, grpc/proposal A50), usable directly by a plain
gRPC client. Three layers, each catching a different failure shape,
stacked client-side inside booking-service (and every other caller):

```
Starting point (Compose, today): single instance per service — no real
  LB problem exists yet, deferred rather than solved prematurely.

On k8s (ADR-019's eventual deployment target), headless Service
  (ClusterIP: None) + gRPC client-side channel, configured with:

  Layer 1 — outlier_detection LB policy (instance-level).
    Watches real per-instance call outcomes, quietly ejects a
    specifically sick pod from rotation. Caller code never sees the
    problem — a degraded-but-still-readiness-passing instance stops
    getting traffic without a circuit breaker ever tripping.

  Layer 2 — gRPC retry policy (request-level).
    Catches a single transient failure (e.g. UNAVAILABLE); the retried
    call lands on a different subchannel via round-robin, recovers
    silently.

  Layer 3 — Resilience4j circuit breaker (service-level), reused from
    the pattern already established elsewhere in this project
    (ADR-002's Redis breaker, api-gateway's downstream breakers).
    Only trips when Layers 1-2 can't save the call anymore — i.e. ALL
    instances are genuinely failing (real outage, e.g. the underlying
    payment database is down) — fast-fails instead of exhausting
    booking-service's threads waiting on a dead dependency.
```

Escalate beyond this to a sidecar mesh (Envoy/Linkerd) only if
per-request balancing needs to happen for traffic this client-side stack
still can't handle well (e.g. active canary/percentage-based traffic
shifting, or true cross-service mTLS) — not adopted preemptively,
mirroring ADR-009's own reasoning for deferring mesh mTLS until its
trigger condition is real, not speculative.

**Caveat, not verified — flag before implementation:** `outlier_detection`
was slated for C++/Java/Go/Node roughly 2022 per the A50 proposal; this
session could not confirm grpc-java's current config maturity/ergonomics
for it specifically. Verify against grpc-java's current docs/release
notes at actual implementation time before committing — see Open
Questions.

## Tracing

OTel's gRPC instrumentation (client + server interceptors) covers this
natively — no change to [[ADR-015-observability-stack]]'s Tempo/Mimir/
Loki pipeline; correlation-ID propagation continues via the
`x-correlation-id` metadata key above.

# Why

Gives the internal call graph the same "schema is the contract, CI
enforces compatibility" guarantee Kafka already has via Avro
([[ADR-007-kafka-event-schema]]), instead of REST's hand-maintained
stub-file approximation of the same idea. One consistent integration
philosophy across sync and async paths, rather than two different
half-measures — directly closes the gap this session surfaced: Spring
Cloud Contract only ever covered REST, and gRPC needed its own answer,
not an assumption that the REST tooling silently extended to cover it.

# Consequences

**Easier:** one consistent "contract = schema, CI enforces
compatibility" story across Kafka and internal RPC; typed generated
clients remove a whole class of integration bugs (wrong field name,
wrong type) at compile time instead of runtime; the internal LB gap
flagged earlier this session now has a concrete, staged answer instead
of being silently unaddressed.

**Harder:** every one of 15 services gains a protoc/buf build step;
ADR-009's auth enforcement must be rewritten as gRPC interceptors
instead of reused Spring Security filters; the HTTP/2 connection-pileup
behavior must actually be configured correctly (headless Service +
client-side resolver) or silently degrades to "all traffic hits one
instance"; debugging raw wire traffic is harder than REST/JSON (binary,
needs `grpcurl`/server reflection enabled, not curl-able by default).

# Revisit When

- If browser/client-facing calls ever need gRPC too — would require
  `grpc-web` plus an Envoy proxy in front, a materially larger decision
  not assumed here.
- If per-service instance count grows enough that k8s headless-Service
  client-side balancing starts producing hot instances — escalate to a
  sidecar mesh (Envoy/Linkerd) at that point, not before.

## Open Questions

- `buf` vs. plain `protoc` + a hand-rolled diff check for breaking-change
  detection — `buf` assumed as the more standard modern choice, not
  compared/load-tested here.
- Per-service `.proto` file organization (one shared proto repo vs.
  per-service proto packages) — not decided.
- grpc-java's current `outlier_detection` LB policy maturity/config
  ergonomics — not verified this session; confirm against grpc-java's
  actual docs/release notes before implementing Layer 1 of the
  resilience stack above.
