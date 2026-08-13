---
title: ADR-025 Idempotency Key Policy
type: decision
sources: []
related: [[ADR-006-saga-booking-orchestration]], [[ADR-002-seat-locking-strategy]], [[ADR-008-testing-strategy]], [[cross-cutting-concerns]], [[booking-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[cross-cutting-concerns]] has listed idempotency-key format/storage as
an open question since the vault's early sessions. [[ADR-008-testing-strategy]]
adds an ArchUnit rule requiring every state-changing `@RestController`
method to *declare* an idempotency-key parameter, but never specifies
what happens with it. [[ADR-006-saga-booking-orchestration]]'s
`bookings.idempotency_key` column (just corrected for Citus colocation in
this session) *assumes* "retry returns the original result" without ever
defining the actual semantics — what happens on a retry that arrives
*while the first attempt is still in flight*, or a retry that reuses a
key with a different request body. This is the last unresolved piece the
booking Saga's own correctness quietly depends on.

# Requirements / Constraints

- Must prevent a retried `POST /bookings/hold` from starting a second
  Saga — the concrete risk this closes, directly feeding
  [[ADR-006-saga-booking-orchestration]]'s `PaidUserUnresolved` failure
  mode if left open.
- Must distinguish three cases, not just two: no prior attempt (proceed
  normally), a prior attempt with the *same* request body (safe replay —
  return the original result), a prior attempt with a *different* body
  under the same key (client bug — reject, don't silently process either
  version).
- Must handle a genuinely **concurrent** duplicate (second request
  arrives before the first has reached a terminal state), not only an
  already-completed one.
- Must work correctly under Citus sharding — reuses this session's
  colocation fix rather than introducing a new cross-shard lookup.
- Storage must not grow unboundedly.

# Options Considered

## A — Client-supplied key, uniqueness only, no body check

Pros: simplest possible implementation. Cons: doesn't catch the case
where a client bug reuses a key across two genuinely different requests
— the second one either silently fails or, worse, silently returns the
first request's unrelated result.

## B — Client-supplied key + stored request-body hash, compared on every reuse

Pros: catches key-reuse-with-different-payload as a real, detectable
error rather than a silent mismatch; reuses this project's established
"a DB constraint is the real guarantee" pattern
([[ADR-002-seat-locking-strategy]], [[ADR-020-payment-event-ledger]])
instead of inventing app-level locking.

## C — Server-issued idempotency tokens (client fetches a token first, submits later)

Pros: removes any risk of client-side key misuse. Cons: two round trips
for every state-changing call, real added latency and complexity this
project's actual client flows (a browser submitting a booking form) have
no need for — over-engineered for the actual risk here.

# Decision

**Option B.** `Idempotency-Key` request header (client-generated UUID
v4) — deliberately mirrors Stripe's own idempotency-key convention,
consistency with the provider already integrated in
[[ADR-011-pci-scope-containment]]. The key and a SHA-256 hash of the
normalized request body are stored **on the resource row itself**, not
in a separate idempotency-keys table — same colocation principle just
applied fixing ADR-006/ADR-020/ADR-021's shard-key omissions, avoiding a
second cross-shard lookup for every write.

```sql
-- bookings row already carries idempotency_key (ADR-006, corrected this
-- session); add the body hash alongside it:
ALTER TABLE bookings ADD COLUMN idempotency_request_hash BYTEA NOT NULL;
```

## Handling behavior — three cases, concretely

```
1. No existing row for (event_id, idempotency_key):
   Proceed normally. INSERT succeeds, becomes the record of truth for
   any future retry under this key.

2. Existing row, SAME request-body hash (safe replay):
   Whether the original attempt is still in flight or already terminal,
   this is a legitimate retry — return the CURRENT state of that
   booking (its current status, not a re-run of side effects). If still
   PENDING/HOLD_PLACED, the UNIQUE constraint on
   (event_id, idempotency_key) blocks the second INSERT at the DB level
   — the same "DB constraint is the real guarantee" pattern as
   ADR-002's seat lock — caller gets a 409 with the original booking_id
   and a Retry-After, not a duplicate saga.

3. Existing row, DIFFERENT request-body hash (client bug):
   Reject with 422 — this key was already used for a different request,
   never silently process either version. Logged as a likely client
   defect (regenerating a key incorrectly, or a genuine collision,
   vanishingly unlikely at UUID v4 scale).
```

## Scope beyond booking-service

Every state-changing endpoint ADR-008's ArchUnit rule already requires
an idempotency-key parameter on follows the same pattern: key + body
hash colocated with whatever row that endpoint creates or mutates,
sharded consistently with that table's existing distribution column.
This ADR defines the *policy*; ADR-006's schema is simply its first
concrete application.

# Why

Reuses a pattern this project already trusts (a database constraint,
not application-level locking, as the actual correctness guarantee) and
avoids introducing a second idempotency-specific table that every write
would need to cross-reference. Directly closes the gap ADR-006 quietly
assumed away — "retry returns the original result" now has real,
testable mechanics instead of being a comment in a schema.

# Consequences

**Easier:** the exact failure shape ADR-006's `PaidUserUnresolved` alert
exists to catch — a retried request starting a second Saga — is now
structurally prevented, not just detected after the fact; the same
policy generalizes to every other state-changing endpoint without a new
mechanism per service.

**Harder:** every state-changing endpoint needs a body-hash comparison
step, not just a bare uniqueness check; client code (the React frontend)
must generate the key once per logical user action and persist it
correctly across retries — a client that mints a fresh UUID on every
retry attempt defeats the entire mechanism, and this must be documented
as a frontend contract, not assumed.

# Revisit When

- If a client flow genuinely can't safely generate its own key
  client-side (none identified currently) — would justify Option C for
  that specific flow only, not a wholesale switch.

## Open Questions

- Endpoints whose action has no natural long-lived row to attach the key
  to (a bare action with no created/mutated resource) would need a
  dedicated `idempotency_keys` table with its own TTL/cleanup policy —
  not yet identified whether any such endpoint exists in this system,
  flagged for review once services are actually being built.
