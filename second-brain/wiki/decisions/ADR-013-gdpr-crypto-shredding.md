---
title: ADR-013 GDPR Right-to-Erasure via Crypto-Shredding
type: decision
sources: []
related: [[ADR-007-kafka-event-schema]], [[ADR-010-secrets-management]], [[user-service]], [[search-service]], [[analytics-service]], [[cross-cutting-concerns]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`cross-cutting-concerns.md` states the erasure goal but leaves per-entity
rules undecided. This architecture has personal data in five separate
places by design: 14 service-owned Postgres databases, Kafka topics
(immutable, retained, replayable per [[ADR-007-kafka-event-schema]]),
Postgres WAL (Debezium republishes everything written, including
already-deleted rows), Elasticsearch (search-service), and
analytics-service's derived aggregates. A plain `DELETE FROM users` does
not touch four of those five.

# Requirements / Constraints

- Kafka history cannot be rewritten. Compaction only removes superseded
  records for the same key; ADR-007 keys by `booking_id`/
  `payment_intent_id`, not by user — per-user compaction is not available
  even in principle.
- Erasure must not break replay, a stated architectural property of the
  outbox/CDC pipeline.
- Financial/legal retention obligations (tax, chargebacks) must survive
  erasure of the person.

# Options Considered

## A — Hard delete + shorten Kafka retention

Rejected: breaks replay, doesn't touch backups, doesn't touch already-
consumed derived state (analytics aggregates, search index).

## B — Pseudonymize in place across all 14 services

Overwrite PII columns with `deleted-user-<hash>` on an erasure event.
Rejected alone: does not touch Kafka — any replay of `booking.confirmed`
re-hydrates a deleted user's email into notification-service. Requires
all 14 services to be correct forever; a 15th service written next year
silently breaks compliance. Retained as a belt-and-braces companion to C,
not sufficient by itself.

## C — Crypto-shredding (envelope encryption, per-subject DEK)

# Decision

**Option C, combined with B.**

## Key hierarchy

```
Vault Transit KEK  (transit/keys/pii-kek)   -- never leaves Vault
   wraps
Per-subject DEK    (AES-256-GCM, one per user, generated at registration)
   encrypts
PII fields, everywhere in the system
```

`subject_key` lives in **user-service** — it already owns identity-
adjacent data and the erasure API; a dedicated key-service would be a
15th service that [[ADR-001-microservices-vs-modular-monolith]]'s
"must independently justify its existence" rule would reject.

```sql
subject_key
  subject_id      UUID PK
  wrapped_dek     BYTEA          -- DEK sealed by Vault transit KEK
  key_version     INT
  created_at      TIMESTAMPTZ
  destroyed_at    TIMESTAMPTZ    -- non-null == shredded
```

**Erasure = overwrite one row's `wrapped_dek` with NULL.** Every
ciphertext of that subject — in every Postgres database, every Kafka
topic, every Elasticsearch document, every backup, every WAL segment,
every Debezium snapshot — becomes permanently undecryptable in a single
atomic operation. Nothing else reaches into an immutable log this way;
this property is what justifies the added complexity.

## What is encrypted (explicit, enforced list)

| Encrypted with subject DEK | Plaintext, retained |
|---|---|
| name, email, phone, address | `user_id` (pseudonymous UUID) |
| date of birth | `booking_id`, `seat_id`, `event_id` |
| IP address, device fingerprint | amounts, currency, timestamps |
| free-text support notes | `payment_intent_id`, provider refs |
| ticket-holder name on barcode | seat/inventory state |

Right-hand column retained under GDPR Art. 17(3)(b)/(e) — legal
obligation (tax/accounting) and defense of legal claims (chargebacks). A
booking row keyed by a pseudonymous UUID whose PII is unrecoverable is
anonymous data, out of GDPR scope — the financial record survives, the
person does not.

## Where encryption happens — the critical ordering constraint

Encrypt in the application, **before** the row is written, and write the
*same ciphertext* into both the business table and the ADR-007 outbox row
in the same transaction. Encrypting anywhere later (a Kafka interceptor,
a Debezium SMT, database-level TDE) is too late — plaintext has already
entered the WAL, and Debezium's whole job is to publish the WAL.
Spring Data `AttributeConverter` on the entity field is the natural
hook; make it the *only* path PII can be persisted through, and lint for
direct native queries touching PII columns.

Per-request DEK unwrap via Vault `transit/decrypt` is unacceptable at
booking volume — cache the unwrapped DEK in a bounded in-memory cache
(Caffeine, ~5 min TTL, size-capped, never spilled to disk) keyed by
subject. Shredding then has a bounded propagation lag equal to that TTL —
acceptable against GDPR's 30-day statutory window, stated explicitly
rather than discovered later.

## Required amendment to ADR-007 (blocking, already applied)

ADR-007's envelope gains `subjectId`/`encryptionKeyId` (nullable, backward
compatible). PII payload fields are declared Avro `bytes` **from schema
v1** — ADR-007's own compatibility rules reject a later `string`->`bytes`
change, so this had to be decided before any topic goes live. Already
folded into [[ADR-007-kafka-event-schema]]'s amendment section.

## Erasure orchestration (reuses the Saga shape, not a new pattern)

```
1. user-service: POST /users/{id}/erasure
   -> verify identity (re-auth + step-up; erasure is irreversible and a
      prime account-takeover target)
   -> create erasure_request {id, subject_id, requested_at, status: PENDING}
   -> emit user.erasure.requested (via outbox, ADR-007)

2. Each of the 14 services consumes it, performs LOCAL cleanup:
   - hard-delete rows with no retention obligation (preferences, search
     history, device records, notification log bodies)
   - pseudonymize any plaintext PII predating the encryption scheme
     (Option B, belt-and-braces)
   - emit user.erasure.completed {subjectId, service, at}

3. user-service maintains a 14-row completion ledger. Missing acks after
   7 days -> ops alert. GDPR deadline is 30 days; alerting at 7 leaves
   room to fix manually.

4. Only after all acks (or a hard 21-day timeout with manual sign-off):
   subject_key.wrapped_dek := NULL, destroyed_at := now()
   emit user.erasure.finalized
```

**Shred the key last.** Services may need to *read* their own encrypted
data to correctly delete or pseudonymize it — destroying the key first
leaves undecryptable orphan rows no service can identify or clean up.

## Special cases

- **search-service**: encrypted names are useless for search; any indexed
  PII must be genuinely `delete_by_query` + force-merged (unmerged
  segments retain deleted docs). Better answer: don't index user PII at
  all — search-service is an event/venue discovery index by design, keep
  it that way and this problem disappears.
- **analytics-service**: pure count/sum aggregates by `event_id` contain
  no personal data, need no action. Any per-user table must either
  encrypt with the subject DEK or be dropped on erasure.
- **Logs/traces**: crypto-shredding does nothing for a plaintext email in
  a log line. Structured JSON logging + a PII-scrubbing appender +
  bounded retention (90 days, starting default) is the actual control.
  Correlation IDs must be random, never PII-derived.
- **Backups**: a backup predating erasure contains the wrapped DEK and is
  therefore recoverable if restored. Mitigation: `subject_key` lives in a
  separate database with shorter backup retention; any restored backup
  gets erasure-replay applied as part of the documented restore runbook.
  This is crypto-shredding's genuine weak point — stated, not glossed
  over.
- **Portability (Art. 20)**: same fan-out, opposite direction —
  `user.export.requested` -> each service returns its slice ->
  user-service assembles a signed, short-TTL, one-time download URL.
  Reuses the same ledger machinery.

# Why

Crypto-shredding is the only mechanism in this list that reaches into an
immutable Kafka log and every downstream replica/backup with a single
atomic action. Pseudonymization alone cannot touch history that's already
been replayed and consumed; hard deletion breaks replayability the
architecture depends on.

# Consequences

**Easier:** erasure is one row update, provably complete across every
current and future copy of the data, including ones not yet built.

**Harder:** every service touching PII needs the `AttributeConverter`
discipline from day one — retrofitting encryption onto a live topic is
the exact `string`->`bytes` problem ADR-007's amendment exists to avoid;
per-request decrypt caching adds real implementation surface; backups
remain a genuine gap requiring a documented runbook, not a clean solve.

# Revisit When

- If EDPB or a specific regulator guidance treats crypto-shredding as
  insufficient for a specific data category — would need per-category
  reconsideration, not a wholesale redesign.

## Open Questions

- Log retention window (90 days) — starting default, needs a real policy
  decision, not just a technical one.
- Erasure-ledger alert threshold (7 days) and hard timeout (21 days) —
  starting defaults.
