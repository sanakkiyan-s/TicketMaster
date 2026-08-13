---
title: ADR-012 JWT Lifecycle — Lifetimes, Key Rotation, Revocation
type: decision
sources: []
related: [[auth-service]], [[api-gateway]], [[ADR-009-service-to-service-auth]], [[ADR-004-redis-cluster-sharding]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`auth-service.md` has two open questions this ADR resolves: refresh-token
storage (DB vs Redis) and revocation mechanism. The binding constraint is
`api-gateway.md`'s already-decided design: JWTs are validated **locally**
against cached JWKS, no per-request call to auth-service — so revocation
state must reach the gateway by push, not pull.

# Requirements / Constraints

- Key rotation must not invalidate live tokens or force mass logouts.
- Revocation ("log out everywhere," ban an account) must work despite the
  gateway never calling auth-service per request.
- Must not create a DoS amplification vector against auth-service (the
  system's highest-QPS service by construction — every request passes
  through JWT validation).

# Decision

## Token design

```
Access token   RS256, 10 min       -- starting default, needs real data
Refresh token  opaque 256-bit random, 30 days, rotating, family-tracked
Service token  RS256, 5 min        -- see ADR-009
```

Access claims: `iss`, `sub` (`user:<uuid>`), `aud`, `exp`, `iat`
(load-bearing for revocation below), `jti`, `sid` (session uuid, for
selective per-device logout), `roles`, `amr` (auth methods, for step-up
gating).

**Refresh tokens are opaque and stored in Postgres**, not Redis
(resolves `auth-service.md`'s open question). Reasoning: refresh happens
once per ~10 minutes per active session — low QPS — so durability matters
far more than latency. Redis would trade a latency win nobody perceives
for a new failure mode: lose Redis, log out every user in the world. Hash
with **SHA-256, not bcrypt** — the token is already 256 bits of CSPRNG
entropy, nothing to brute-force, so bcrypt's work factor costs real CPU
for zero benefit.

**Rotation with reuse detection**: each refresh returns a new refresh
token and invalidates the old one. A refresh token presented a second
time means it was stolen and replayed — invalidate the entire token
family (every descendant of that original login), force re-auth. This is
what makes a 30-day refresh token acceptable to keep opaque and long-
lived.

## Key rotation without invalidating live tokens

JWKS is a **set**, not a single key — every token carries a `kid` naming
which key signed it. Rotation is a four-phase overlap, durations derived
from two numbers: max access-token lifetime (10 min) and gateway JWKS
cache TTL (5 min):

```
Phase 0 (steady state)   auth-service signs with K1. JWKS = [K1].

Phase 1 PUBLISH  (>= JWKS cache TTL + margin, ~15 min)
  Generate K2. JWKS = [K1, K2]. Still SIGNING with K1.
  Purpose: guarantee every gateway instance has K2 cached BEFORE any
  token signed by K2 exists. Skipping this is the classic failure —
  flip signing keys and every warm-cached gateway rejects every token
  for up to one cache TTL.

Phase 2 CUTOVER  (instant)
  Start signing with K2. JWKS still [K1, K2]. Live K1 tokens keep
  validating. Zero user impact.

Phase 3 DRAIN  (>= max access-token lifetime, use 30 min)
  No new K1 tokens exist after phase 2. Wait for outstanding ones to
  expire naturally.

Phase 4 RETIRE
  JWKS = [K2]. Destroy K1 private key.
```

Total window ~45 min, fully automated, zero downtime, zero forced
logouts. Run every 90 days on schedule, or immediately on suspected
compromise (skip straight to phase 4 — every live access token dies, but
refresh tokens are opaque and unsigned by K1, so clients silently
re-auth via `/refresh` with a one-round-trip hiccup, not a forced login).
This is a direct argument for opaque refresh tokens over JWT refresh
tokens.

## The JWKS cache attack (must be designed, not assumed away)

Naive implementation: unknown `kid` -> gateway fetches JWKS. An attacker
sending requests with random `kid` values turns unauthenticated traffic
into an amplified attack on auth-service. Required behavior: JWKS cached
5 min, refreshed proactively in the background, never lazily on the
request path; on unknown `kid`, **do not fetch** — reject and record;
allow at most one out-of-band refetch per 60s per gateway instance,
globally rate-limited, only if the unknown `kid` was seen from multiple
distinct sources; negative-cache unknown `kid` for 60s. A correctly-run
rotation (phase 1 above) never produces an unknown `kid` in normal
operation — the refetch path is an emergency backstop only.

## Revocation

Option A (short TTL, accept the window) is insufficient alone — banning a
scalper account still lets them buy for up to 10 more minutes, thousands
of tickets on a hot on-sale. Option B (Redis blocklist) is **rejected**:
it collides with this project's fail-open convention
([[ADR-002-seat-locking-strategy]], `fraud-service`) — a Redis outage
would silently un-revoke every banned account, and fail-*closed* on Redis
instead means a Redis blip logs out the entire user base. Neither branch
acceptable.

**Decision — Option C: revocation epochs pushed via a compacted Kafka
topic, materialized in gateway memory:**

```
Topic:    auth.revocation  (log-compacted, key = subject)
Producer: auth-service, via the ADR-007 transactional outbox
Consumer: every api-gateway instance, own consumer group, reads from
          earliest on startup, materializes a local map

Record:   key = "user:<uuid>" or "session:<sid>"
          value = { revokeBefore: <epoch ms>, reason }
```

Gateway check, entirely in-process after local signature validation:
`if revocationMap["user:"+sub] present && token.iat < revokeBefore -> 401`
(same for `session:<sid>`, giving single-device logout). Bounded memory —
an entry only matters while tokens issued before `revokeBefore` could
still be alive (10 min), tombstoned after that; even a million bans/hour
is a few thousand live entries.

**Deliberate fail-closed carve-out**: a gateway instance that cannot reach
Kafka at startup must refuse its readiness probe rather than serve with
an empty revocation map. This is a **named exception** to this project's
fail-open convention — fail-open on fraud scoring loses one signal among
several ([[fraud-service]]'s own argument); fail-open on revocation
*silently un-bans every banned account*, exactly during the incidents
that matter most. A running instance that loses Kafka mid-flight keeps
serving from its last known map and alarms — degrading toward staleness
is acceptable, degrading toward empty is not.

**Synchronous checks retained for a small tier**: refunds, ticket
transfers, saved-payment-method changes, admin actions call auth-service
directly to confirm the session is live and `amr` includes recent
step-up. Single-digit QPS — naming this tier explicitly prevents a future
reader from over-applying "gateway never calls auth-service" and shipping
an unrevocable admin endpoint.

# Why

Set-based JWKS with a four-phase overlap is the standard mechanism for
zero-downtime signing-key rotation and directly avoids the two failure
modes (forced mass logout, or an unknown-`kid` DoS amplifier) that a naive
rotation produces. Kafka-pushed revocation reuses infrastructure this
project already decided on ([[ADR-007-kafka-event-schema]]) instead of
introducing Redis into a role that conflicts with its own fail-open
convention.

# Consequences

**Easier:** key rotation is a scheduled, zero-downtime, fully automated
event; revocation propagates in sub-second time without a per-request
auth-service call; compromise response (skip to phase 4) has a known,
bounded user-visible cost.

**Harder:** every gateway instance must run a Kafka consumer and maintain
an in-memory revocation map with its own tombstone/cleanup logic; the
fail-closed-on-Kafka-loss requirement is a deliberate, documented
exception to the rest of the system's convention and must not be
"fixed" toward consistency by a future reader who missed the reasoning.

## Amendment: `auth.revocation` must be explicitly mirrored cross-region

**Gap found**: this ADR's revocation topic assumes every `api-gateway`
instance consumes it, but never states which Kafka cluster produces it
against. [[ADR-016-multi-region-cdn]] establishes that Kafka runs
**per-region** (Postgres/Redis are region-homed by the same logic; the
search-catalog projection is explicitly called out there as "a read-only
Kafka projection, replicated cross-region" via MirrorMaker 2 — implying
most topics do NOT cross regions by default). `auth-service` is itself
region-sharded by `user_id` per [[ADR-018-user-identity-sharding-residency]],
so a ban issued against a EU-homed user is produced onto EU's local
`auth.revocation` topic. Without explicit action, a US-region `api-gateway`
instance never sees it — the exact scalper-ban-doesn't-stick failure this
ADR was written to prevent, just relocated to a cross-region gap instead
of a same-region timing gap.

**Decision**: `auth.revocation` is mirrored cross-region via MirrorMaker 2,
the same mechanism [[ADR-016-multi-region-cdn]] already uses for the
search-catalog projection and the booking outbox DR copy — reused, not
invented. Every region's `api-gateway` fleet consumes the **union** of all
regions' `auth.revocation` topics (own-region direct + every other
region's mirrored copy), materializing one global in-memory revocation
map per gateway instance, not a per-region one. This is consistent with
revocation needing to be globally effective (a banned account must be
blocked everywhere, not just in its home region) even though most other
per-user data stays region-bound for residency (ADR-016/ADR-018) —
revocation state is not PII, so mirroring it cross-region does not
reopen the residency constraint those ADRs enforce for actual user data.

**Added lag**: cross-region MirrorMaker replication adds a bounded delay
(typically low-single-digit seconds under normal operation, unbounded
during a cross-region network partition) on top of this ADR's existing
same-region propagation. During a partition, a foreign-region gateway
serves from its last-mirrored revocation state and alarms — same
degrade-toward-staleness-not-emptiness principle already decided for the
single-region case above, extended rather than re-litigated for the
cross-region case.

# Revisit When

- If revocation lag (bounded by access-token TTL) proves too slow for a
  real scalper-response scenario — the lever is dropping access-token TTL
  toward 5 min, not adding a per-request auth call.
- Instrument refresh-endpoint QPS and revocation-map cardinality before
  tuning any of the "starting default" numbers below.

## Open Questions

- Access token TTL (10 min) is the load-bearing default — it sets
  revocation lag, revocation-map size, and refresh QPS simultaneously.
  Needs real instrumentation data before tuning.
