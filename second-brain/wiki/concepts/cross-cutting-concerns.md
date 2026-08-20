---
title: Cross-Cutting Concerns
type: concept
sources: []
related: [[ADR-003-gap-list-triage]], [[system-overview]], [[ADR-015-observability-stack]]
created: 2026-08-05
last-updated: 2026-08-20
---

Patterns applied consistently across most/all services rather than owned
by one domain. See [[ADR-003-gap-list-triage]] for why none of these
became a standalone service.

## Idempotency keys

Resolved — see [[ADR-025-idempotency-key-policy]]: `Idempotency-Key`
header (client-supplied UUID v4, mirrors Stripe's own convention), key +
request-body hash stored on the resource row itself (same shard as the
resource, no separate table), duplicate-with-same-body replays the
current state, duplicate-with-different-body is rejected. Not yet
implemented anywhere — policy decided, required before
`booking-service`/`payment-service` are built.

## Distributed tracing / observability

Resolved — see [[ADR-015-observability-stack]]: OpenTelemetry Java agent
(not Jaeger/Zipkin), Tempo for trace storage, Loki for logs, Mimir for
metrics. Built and verified live 2026-08-20 against the 3 services that
exist. The original plan described a custom correlation-ID header
generated at `api-gateway` and propagated manually — that was never
implemented (`backend/api-gateway/src` has no correlation-ID code) and is
superseded by this decision: the OTel agent generates a real W3C
`traceparent` automatically on every request and propagates it through
HTTP headers and Kafka message headers (via Debezium's EventRouter SMT
for the outbox boundary specifically), doing the same job without
hand-rolled code. Every log line gets `trace_id`/`span_id` attached
automatically by the agent's Logback instrumentation — no
`logging.structured.format` change was needed.

## Feature flags

Needed for high-risk on-sale events (ability to gradually roll out
queue-service changes, kill-switch a misbehaving fraud-service check
without a redeploy). Tooling not yet decided — could start as a simple
config table, graduate to a real flag service if needed.

## GDPR / data deletion

`user-service` needs data export and deletion endpoints. Deletion must
cascade correctly: anonymize (not hard-delete) records other services
reference for financial/compliance reasons (e.g. payment-service
transaction history), hard-delete what's purely personal (preferences).
Exact per-service deletion/anonymization rules not yet designed.

## Audit logging

Each service that performs privileged/admin actions (price change,
inventory override, refund override) writes its own audit record and
emits an `AdminActionPerformed` event. `analytics-service` aggregates
these for a compliance view. Decided explicitly not to be a standalone
audit-service — see [[ADR-003-gap-list-triage]].

## CDN

Static assets (event images, venue seat-map images) served through a CDN
in front of object storage — an `infra` concern, not application code.
Not yet configured.

## Open Questions

- Feature flag tooling — not decided.
- Per-entity GDPR deletion/anonymization rules — not decided.
