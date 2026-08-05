---
title: Cross-Cutting Concerns
type: concept
sources: []
related: [[ADR-003-gap-list-triage]], [[system-overview]]
created: 2026-08-05
last-updated: 2026-08-05
---

Patterns applied consistently across most/all services rather than owned
by one domain. See [[ADR-003-gap-list-triage]] for why none of these
became a standalone service.

## Idempotency keys

Every state-changing endpoint that can be legitimately retried
(booking-service checkout, payment-service charge/refund) must accept a
client-supplied idempotency key and return the original result on replay
rather than re-executing. Not yet implemented anywhere — required before
`booking-service`/`payment-service` are built (see their Open Questions).

## Distributed tracing / observability

Correlation ID generated at `api-gateway`, propagated through every
downstream call (HTTP header + Kafka message header) so a single booking
attempt can be traced across services. Structured logging (JSON) with the
correlation ID on every log line. Specific tooling (e.g. OpenTelemetry +
Jaeger/Zipkin) not yet decided.

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

- Idempotency key format/storage (per-service dedup table vs. shared
  approach) — not decided.
- Tracing tool choice — not decided.
- Feature flag tooling — not decided.
- Per-entity GDPR deletion/anonymization rules — not decided.
