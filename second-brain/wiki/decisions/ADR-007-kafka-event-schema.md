---
title: ADR-007 Kafka Topic Design, Schema, Outbox, and DLQ
type: decision
sources: []
related: [[event-service]], [[booking-service]], [[payment-service]], [[ticket-service]], [[notification-service]], [[search-service]], [[analytics-service]], [[cross-cutting-concerns]], [[ADR-006-saga-booking-orchestration]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

Multiple services already assume Kafka for async domain events
(event-service -> search-service, booking-service -> notification-service,
etc. — see [[system-overview]]) but no topic list, schema format, or
delivery-reliability mechanism was ever formally decided. Two additional
real-world problems needed solving beyond "what topics exist":

- **Dual-write problem**: a service committing a DB state change and
  separately publishing a Kafka event has no atomicity between the two —
  a crash between them either loses the event or publishes one for a
  change that never committed.
- **Poison-message problem**: Kafka preserves order only within a
  partition; a consumer stuck retrying one bad message blocks every
  message behind it in that partition indefinitely.

# Requirements / Constraints

- Producers/consumers already implied: event-service, venue-service,
  booking-service, payment-service, ticket-service (producers);
  search-service, notification-service, analytics-service, ticket-service,
  booking-service (consumers).
- Must not lose an event if the producing service crashes right after
  committing the triggering DB change (same reliability bar as
  [[ADR-006-saga-booking-orchestration]]'s "never silently drop a paid
  booking").
- A malformed/unprocessable message must not permanently block a
  consumer's entire partition.
- Consumers must be able to deduplicate on redelivery (at-least-once
  delivery, not exactly-once) — ties into [[cross-cutting-concerns]]'s
  idempotency requirement.

# Options Considered

## Topic granularity

**A — one topic per producing service** (`booking-service.events`, type
as a payload field). Cons: consumers get every event type from that
service even if they only want one, must filter client-side.

**B — one topic per event type** (`booking.confirmed`, `payment.succeeded`).
Pros: consumers subscribe only to what they need. **Chosen.**

## Event publish mechanism

**A — direct publish** (service calls Kafka producer inline, in the same
code path as the DB write, two separate systems, no atomicity). Cons: the
dual-write problem above.

**B — Transactional Outbox + Debezium/CDC**: write the event into an
`outbox` table in the *same Postgres transaction* as the business change;
a separate CDC process (Debezium, tailing Postgres's write-ahead log)
picks up committed outbox rows and publishes them to Kafka. **Chosen** —
matches the project's "build it like the real thing" priority (same
reasoning already applied to choosing Redis Cluster/Citus over simpler
single-instance setups). Cons: one more running service (Debezium
connector) and topology to operate, vs. a simple polling publisher.

## Schema format

**A — plain JSON**, no enforcement. Cons: a producer can silently rename/
remove/retype a field and nothing catches it until a consumer breaks in
production (concrete example: `bookingId` -> `booking_id` rename ships,
notification-service silently gets nulls).

**B — Avro + Confluent Schema Registry**: every producer/consumer
registers schemas centrally; the registry rejects an incompatible publish
(renamed/removed field, changed type) at publish time, before it ever
reaches Kafka. **Chosen**, same "more infra, real guarantee" reasoning as
the outbox choice above.

# Decision

## Topics (one per event type, key = aggregate ID for per-entity ordering)

```
event.created          key: event_id      producer: event-service
event.updated          key: event_id      producer: event-service
event.cancelled         key: event_id      producer: event-service
venue.updated           key: venue_id      producer: venue-service
booking.confirmed       key: booking_id    producer: booking-service
booking.failed          key: booking_id    producer: booking-service
payment.succeeded       key: payment_intent_id  producer: payment-service
payment.failed          key: payment_intent_id  producer: payment-service
ticket.issued           key: ticket_id     producer: ticket-service
```

Partition key = aggregate ID so all events for the same entity (same
booking, same event) land in the same partition and are processed in
commit order by any one consumer instance — required correctness
property, not just an optimization (e.g. `booking.confirmed` must never
be processed out of order relative to a later `booking.failed` for the
same booking).

## Envelope (every event, all topics)

```json
{
  "eventId": "uuid",
  "eventType": "booking.confirmed",
  "version": 1,
  "occurredAt": "2026-08-06T10:15:00Z",
  "correlationId": "uuid",
  "aggregateId": "booking-id-or-equivalent",
  "payload": { }
}
```

`eventId` is what consumers use for idempotent dedup (at-least-once
delivery means a consumer may see the same event twice — must be a no-op
the second time). `correlationId` ties back to [[cross-cutting-concerns]]'s
tracing requirement, propagated from the originating HTTP request through
every downstream hop, Kafka included.

## Transactional Outbox + Debezium (delivery reliability)

```sql
CREATE TABLE outbox (
  event_id     UUID PRIMARY KEY,
  aggregate_id TEXT NOT NULL,
  event_type   TEXT NOT NULL,
  payload      JSONB NOT NULL,
  traceparent  TEXT NOT NULL,  -- W3C trace context, captured from the
                                -- ACTIVE span at insert time, inside the
                                -- business transaction (see amendment)
  tracestate   TEXT,
  created_at   TIMESTAMPTZ DEFAULT now()
);
```

```
BEGIN
  UPDATE bookings SET status = 'CONFIRMED' ...
  INSERT INTO outbox (event_id, aggregate_id, event_type, payload) VALUES (...)
COMMIT
```

Both writes commit or roll back together — ordinary Postgres transaction
guarantee, no new mechanism needed for that half. Debezium runs as a
Kafka Connect source connector per service database, tails the Postgres
WAL, and publishes each committed `outbox` row to its corresponding Kafka
topic (via Avro converter, registering/validating against Schema
Registry) — near-real-time, no polling delay. Once Debezium has confirmed
the row was captured, the row can be deleted or left for retention (not
re-read).

Guarantee this buys: if the business DB commit happened, the event *will*
reach Kafka — never silently lost to a crash between "commit" and
"publish," because there's no longer a gap between those two steps from
the producing service's own code — the WAL tail is Debezium's job, not
the application's.

## Dead Letter Queue (consumer-side reliability)

Every consumer topic gets a paired `.dlq` topic (`booking.confirmed.dlq`,
etc.):

```
consumer processes message
  fails -> retry with backoff, up to N attempts (exact N/backoff: same
    "starting default, needs real data" category as ADR-004's 75%
    threshold and ADR-006's retry timings)
  still fails after N -> publish to <topic>.dlq, commit the original
    offset anyway (unblocks the partition for subsequent messages),
    fire an ops alert
```

Ops inspects the DLQ topic, fixes root cause, replays manually if needed.
Same retry-then-escalate shape as [[ADR-006-saga-booking-orchestration]]'s
compensation-retry job — consistent pattern across the vault rather than
a new one invented per component.

## Schema compatibility (Avro + Schema Registry)

```
Allowed (backward-compatible): adding a new field WITH a default value.
  Old consumers (unaware of the new field) ignore it. New consumers
  reading an old message (missing the field) fall back to the default —
  safety runs in both directions, which is exactly why a default is
  required for the registry to accept the change.

Rejected: renaming/removing a field, changing a field's type.
  A consumer looking up a field by its old name finds nothing in the new
  schema (or a value of the wrong type) and has no default to fall back
  on. Registry rejects the producer's publish call before the bad schema
  ever reaches Kafka — caught at deploy time, not discovered later as
  silent bad data in a downstream consumer.
```

## Amendment: the outbox breaks trace propagation unless context is persisted

**Defect found**: standard OpenTelemetry Kafka propagation assumes the
producing service calls `KafkaProducer.send()`, so the instrumentation can
inject `traceparent` into the message headers. **This ADR makes that
impossible** — `booking-service` never touches Kafka. It writes an outbox
row; Debezium produces the message later, from a different process, with
no trace context. **The trace dies at the transaction boundary**, and
every async hop (ticket issuance, notifications, analytics) becomes
untraceable back to the originating request.

**Resolution, two parts:**

1. **Persist trace context in the outbox row** (columns added to the DDL
   above). The application populates `traceparent` from the currently
   active span at `INSERT` time — *inside the same transaction as the
   business write*. Trace context therefore inherits the outbox's
   atomicity for free: if the booking committed, its trace context
   committed with it.

2. **Map those columns to Kafka headers in the Debezium connector**, via
   the outbox event-router SMT:

```properties
transforms=outbox
transforms.outbox.type=io.debezium.transforms.outbox.EventRouter
transforms.outbox.table.field.event.id=event_id
transforms.outbox.table.field.event.key=aggregate_id
transforms.outbox.table.field.event.type=event_type
transforms.outbox.table.field.event.payload=payload
transforms.outbox.table.fields.additional.placement=traceparent:header:traceparent,tracestate:header:tracestate
```

`table.fields.additional.placement` with `:header:` is the exact
mechanism. Consumers then receive standard W3C headers, and stock OTel
Kafka consumer instrumentation picks them up with **zero consumer-side
code** — the async hop becomes transparent.

## Amendment: `correlationId` is defined as the W3C trace-id

The envelope's `correlationId` and OpenTelemetry's `trace_id` must not be
two independent identifiers — that is two join keys and guaranteed drift
between logs, traces, and metrics.

**Decision**: `api-gateway` starts the root span, and **that span's
trace-id (32 lowercase hex) *is* the `correlationId`.** The envelope field
stays (it survives sampling and is quotable in a support ticket), but its
value is bound to the trace. One ID across all three observability
pillars.

Consistent with [[cross-cutting-concerns]]'s "correlation ID generated at
api-gateway" — the gateway still generates it, now as a side effect of
starting the root span rather than as a separate UUID. A future reader
must not reintroduce an independent UUID here.

## Amendment: PII payload fields must be Avro `bytes` from schema v1 (BLOCKING)

Crypto-shredding (the planned GDPR right-to-erasure mechanism) requires
PII to be stored and transmitted **encrypted**, so that destroying a
per-subject key renders every copy — including immutable Kafka history —
permanently undecryptable. Kafka events cannot be rewritten or
selectively purged; this ADR keys topics by `booking_id` /
`payment_intent_id`, not by user, so per-user log compaction is not
available even in principle.

**The blocking constraint**: encrypted PII is `bytes`, not `string`. This
ADR's own compatibility rules (above) list **changing a field's type as
rejected** by the Schema Registry. So a `string` -> `bytes` migration is
impossible in place — it would require a v2 topic with dual-write and
consumer migration.

```
Therefore: every PII field must be declared as Avro `bytes` in schema
VERSION 1. Not retrofitted later. Nothing is implemented yet, so this
costs nothing now and is very expensive after the first topic goes live.
```

Envelope gains two fields, both nullable-with-default so the registry
accepts them as backward-compatible under this ADR's own rule:

```
"subjectId":       ["null","string"], default null   -- who the event is about
"encryptionKeyId": ["null","string"], default null   -- which subject DEK
```

Encryption happens **in the application, before the row is written**, and
the same ciphertext goes into both the business table and the outbox row
in one transaction. Encrypting any later (a Kafka interceptor, a Debezium
SMT, database-level TDE) is useless here: plaintext has already entered
the write-ahead log, and Debezium's whole job is to read the WAL.

# Why

Per-event-type topics keep consumers simple (subscribe only to what's
relevant). Outbox+Debezium and Avro+Registry both trade extra
infrastructure for a real correctness/compatibility guarantee instead of
a best-effort one — consistent with every other infra choice this project
has made in favor of learning the real mechanism (Redis Cluster over
single instance, Citus over no sharding, Saga over ad-hoc error handling).

# Consequences

**Easier:** consumers get exactly the event types they care about; a
producer's DB crash can never silently lose an event; a schema-breaking
deploy fails immediately at the producer instead of corrupting a
downstream consumer's data; one poison message never blocks a whole
partition's other traffic.

**Harder:** more moving infrastructure to run and operate locally
(Kafka Connect + Debezium connectors, per-service outbox tables, Schema
Registry) — three additional components beyond bare Kafka. Every service
needs an Avro schema file and an outbox table as part of its own build,
not just a Kafka producer client.

# Revisit When

- If local dev with Debezium + Schema Registry proves too heavy for
  solo iteration speed — a simpler polling outbox publisher + plain JSON
  can substitute without changing the outbox-table/DLQ design shape, only
  the publish mechanism and message encoding.
- Once services are actually built: exact DLQ retry count/backoff needs
  real failure-pattern data, same category as other "starting default"
  numbers across ADR-004/005/006.

## Open Questions

- Exact per-topic partition count and retention policy — not decided,
  needs real throughput estimates once services exist.
- DLQ retry count/backoff values — starting defaults only, needs real
  data.
