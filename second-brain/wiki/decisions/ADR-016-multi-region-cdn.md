---
title: ADR-016 Multi-Region Routing, Regional Failover, and CDN Strategy
type: decision
sources: []
related: [[ADR-002-seat-locking-strategy]], [[ADR-004-redis-cluster-sharding]], [[ADR-005-postgres-sharding]], [[queue-service]], [[infra]], [[flows/seat-availability-live-updates]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

[[ADR-005-postgres-sharding]] already places an event's data in one
region for data-residency reasons, but never designed how a request finds
the right region, what happens if that region fails, or how a CDN sits in
front of any of it. `cross-cutting-concerns.md`'s CDN mention was never
configured.

**Governing constraint, read before the rest of this ADR**: ADR-005 makes
routing **event-homed, not user-homed**. Standard GeoDNS answers "which
region is the user near" — the wrong question here, since a Tokyo user
buying a London show must reach EU-region Postgres, the only place the
seat rows and `SELECT ... FOR UPDATE` are valid. The edge is global and
anycast; the data plane is single-homed per event.

# Requirements / Constraints

- Routing must resolve to the event's home region, not the client's
  geography.
- Regional failover must not violate ADR-002's single-writer invariant
  (no accidental multi-master seat writes) or ADR-005's residency
  placement.
- CDN caching must never serve stale seat availability — that's a
  correctness bug, not a staleness inconvenience.
- Must give an honest answer, not a hand-wave, about what happens when a
  whole region is lost.

# Decision

## 1. Request routing

**Two-tier: anycast edge (global) + event-home-region steering derived
from the identifier, not looked up.** GeoDNS alone is rejected as primary
(routes by client geography, uncorrelated with where the event's shard
lives, 60s+ DNS-TTL failover). Anycast global LB (Cloudflare's network, or
AWS Global Accelerator) is chosen for sub-second failover independent of
DNS and local TLS termination even when the origin is far away.

```
Client -> anycast VIP -> nearest PoP, TLS terminates at the PoP.

Region is derived from the identifier, not a lookup: event/session IDs
carry a fixed region prefix (evt_eu1_..., ses_eu1_...). Edge parses the
region token — pure string parse, no global-consistency dependency on
the hot path.

Edge proxies over the provider backbone to that region's existing
Nginx -> api-gateway chain (unchanged; this steering layer prepends it).

Requests with no event in scope (login, profile, browse/search) route
to the user's home region for writes, nearest region for reads against
the replicated search projection.
```

**Auth across regions**: since [[ADR-012-jwt-lifecycle]] already validates
JWTs locally against cached JWKS, verification needs no cross-region call
— only **issuance** does. Sign with region-scoped keys (`kid=eu1-2026-08`),
publish every region's JWKS globally, read-only. A EU-homed user logging
in from the US pays one cross-region round trip at login only.

**Gap flagged**: ADR-005 shards `event_id`-keyed tables only.
`auth-service`/`user-service` (`user_id`-keyed) have no sharding/residency
decision — multi-region forces one. This ADR proposes home-region user
identity (above); a dedicated ADR should formalize it if `user_id`
sharding becomes load-bearing.

## 2. Regional failover — the honest answer

**If the EU region is fully lost, a US region cannot sell tickets for an
EU event. Not degraded — impossible.** Two independent reasons: residency
(legal, ADR-005) and ADR-002's single-writer invariant (correctness — a
second writable copy elsewhere while the original may still be alive is
exactly how a seat gets double-sold). The failover unit is therefore
**not** "another continent" — it's another failure domain **inside the
same jurisdiction**: `eu-west-1` primary, `eu-central-1` warm standby,
async streaming replication, **human-gated** promotion (a stretched
etcd/Patroni quorum across regions is rejected — 20-25ms inter-region RTT
makes lease timing fragile, and an automatic cross-region promotion under
partition is the highest-consequence split-brain in the system).

**Degradation tiers when EU is down** (state honestly, don't fake
availability):

```
Tier 0 — still works: static assets/images (CDN, no origin needed);
  browse/search for EU events (search-service's ES projection, already
  a read-only Kafka projection, replicated cross-region — non-PII);
  login/profile for non-EU-homed users.
Tier 1 — degraded: seat map DISPLAY only, stamped with last-replicated
  timestamp, occupancy explicitly marked unavailable (not silently
  stale-shown-as-live).
Tier 2 — unavailable: hold/checkout/confirm/refund/transfer for EU
  events. Return 503 with a real message, don't fake it.
```

RPO is non-zero and must be stated — async replication means a confirmed
booking can be lost on promotion. Recovery leans on the ADR-007
transactional outbox mirrored cross-region (MirrorMaker 2) as an
independent record past the replica's LSN, plus the payment provider
(external, survives the region) as the authoritative money record.
Post-promotion recovery is outbox-replay + provider reconciliation, not
"hope the replica was current."

## 3. Data residency vs. availability — the actual tension

Residency removes geography as an availability lever — you cannot buy
uptime with distance, only with redundancy *inside* the boundary, which
is more expensive and has correlated-failure modes (same regulatory
regime, often same power grid). Splitting data into classes clarifies
what's actually possible:

```
Class G — global (event metadata, venue geometry, price tiers):
  no residency, replicate everywhere, CDN-able freely.
Class S — operational, non-personal (seat.status, held_until, hold
  token, and — corrected below — the bare pseudonymous user_id
  REFERENCE on a booking/ticket row): no LEGAL residency, but must not
  be dual-mastered — that limit is ADR-002's, not GDPR's.
Class R — residency-bound (credentials, profile PII, payment methods,
  the ACTUAL PII behind a user_id, ticket-holder name): home region
  only, never leaves.
```

**Amendment: "booking→user_id" reclassified — was imprecise, created a
real contradiction.** As originally written, this table implied the
booking/ticket rows themselves (`event_id`-sharded, event's region per
[[ADR-005-postgres-sharding]]) were Class R — but those rows genuinely
must live in the event's region, not the user's, for
[[ADR-002-seat-locking-strategy]]'s single-writer correctness to hold.
The precise rule, matching [[ADR-013-gdpr-crypto-shredding]]'s own
framing ("a booking row keyed by a pseudonymous UUID whose PII is
unrecoverable is anonymous data, out of GDPR scope"): the bare `user_id`
UUID *reference* on a booking/ticket row is Class S — it identifies
nothing on its own, unrecoverable without a lookup against
user-service's home-region-only PII, which stays Class R. This was a
table-precision bug, not a data-placement bug — no data actually needs
to move; the classification now matches what ADR-005/ADR-013 already
require in practice.

The insight: splitting S from R means a non-home region could legally
*read* seat availability for an EU event. It still cannot *write* — that
limit is ADR-002's correctness constraint, independent of the legal one.
Solving residency does not unlock multi-region writes; stating this
plainly avoids the common hand-wave where "we'll replicate for HA"
quietly assumes away the correctness constraint.

Realistic availability ceiling: single-jurisdiction redundancy with
human-gated cross-region failover caps around three nines — the stated
price of residency, not a number to discover mid-incident.

## 4. CDN strategy

Cloudflare recommended as a single anycast edge doing both CDN and origin
steering (vs. CloudFront + Global Accelerator as two separate control
planes with no first-class cache-tag purge) — **a vendor decision that
deserves its own ADR when the time comes**, flagged as a recommendation
here, not a final lock-in, especially since `infra.md` notes "eventually
AWS" as a target.

**Core structural move — split the seat map into geometry and
occupancy:**

```
GET /venues/{id}/layout/{layoutVersion}.svg  — venue geometry, thousands
  of paths, potentially multi-MB. IMMUTABLE, TTL 1 year, versioned URL.
  Fully cacheable — this is the heavy payload.
GET /sessions/{id}/occupancy — a few KB of seat-id -> status.
  NEVER cached. SSE deltas per the existing live-updates flow.
```

Client composes the two. Without this split, the only choices are
caching nothing or caching sold-out seats as available.

**Cache policy**: immutable/versioned assets (JS/CSS, layout SVGs, event
images) — `public, max-age=31536000, immutable`. Event details JSON —
`max-age=60, stale-while-revalidate=300` + active tag purge. Search
results — `max-age=30` (already eventually consistent by design).
**Seat availability, hold state, queue position, bookings, tickets, SSE —
`no-store, private`, plus an explicit edge deny-list on
`/api/inventory/*`, `/api/queue/*`, `/api/bookings/*`, `/api/tickets/*`.**
The deny-list matters more than headers — defense in depth against one
bad deploy caching "available" across an entire PoP during a sellout.

**SSE requires `X-Accel-Buffering: no`** at Nginx and disabled proxy
buffering at the CDN, or seat updates arrive batched on buffer flush —
the kind of thing that only surfaces in production if not written down
now.

**Invalidation, three layers**: versioned URLs (best invalidation is
none needed) for immutable assets; cache-tag/surrogate-key purge for
event details, driven by a small `cdn-invalidator` Kafka consumer
(lives in `infra/`, explicitly not a 15th service) subscribing to
`event.updated`/`venue.updated` from [[ADR-007-kafka-event-schema]] —
reuses the existing outbox->Debezium->Kafka pipeline, purges are
naturally idempotent so at-least-once delivery is fine; short TTL as the
backstop bounding blast radius when the invalidator itself is down.

**Ticket QR/PDF**: never public-cacheable, `private, no-store`,
short-lived signed URLs, rotating tokens (also the anti-resale mechanism
in [[ADR-014-anti-bot-anti-scalper]]).

## 5. Virtual queue: per-region, not global

**The queue is per-show, homed with the show's inventory — same
home-region rule as its data.** "One global on-sale for a world tour" is
N regional on-sales starting at the same instant, not one global queue.
A globally distributed queue with cross-region consensus ordering is
rejected — it either needs a single leader (collapses back to per-region)
or trusted global clocks; putting cross-region consensus on the single
hottest path in the system is the wrong tradeoff.

**Two layers**: an edge waiting room (anycast, per-PoP, non-authoritative
— absorbs the pre-open thundering herd before it reaches the home-region
origin) in front of the authoritative queue (home region, Redis Cluster
per [[ADR-004-redis-cluster-sharding]], ordered by a monotonic counter —
`INCR queue:{sessionId}:seq` — not wall-clock timestamps, so no
cross-region clock sync is needed).

**Fairness artifact, stated honestly**: at a synchronized on-sale instant,
a London user reaches the EU sequencer in ~10ms and a Sydney user in
~250ms — a systematic geographic disadvantage. Mitigated, not eliminated,
by [[ADR-014-anti-bot-anti-scalper]]'s randomized-join-window mechanism —
converts the latency race into a lottery.

**Conflict flagged against ADR-004**: its hot-shard mitigation splits keys
by `{sessionId}:{sectionId}` — that does not help the queue sequencer,
which is section-agnostic and structurally a single key. Already resolved
via batched sequence allocation in ADR-004's amendment section.

**Multi-show tour queues explicitly not supported** — "queue once for the
whole tour" would need cross-region admission coordination across shows
in different jurisdictions, reintroducing the rejected global-consensus
path. If needed, implement as a presale-registration/code model instead
(fraud-service already covers velocity/bulk limits).

# Why

Deriving region from the identifier rather than looking it up keeps
routing on the hot path free of a global-consistency dependency.
Honestly stating that cross-region failover is impossible for a
single-writer, residency-bound event (rather than promising "HA" that
would silently violate ADR-002 or the law) is more valuable than a
design that looks resilient on paper and breaks a real constraint in
practice.

# Consequences

**Easier:** routing has no cross-region lookup on the hot path; the
geometry/occupancy CDN split makes seat maps fast globally without ever
risking stale availability; residency and correctness constraints are
separated so future features can be evaluated against the right one.

**Harder:** genuine RPO/RTO exist and must be communicated to users
during an incident, not hidden; the queue's geographic fairness gap is
real and only partially mitigated; CDN vendor choice (Cloudflare vs.
AWS-native) is a real tradeoff against `infra.md`'s stated AWS direction,
deserving its own decision when it matters.

# Revisit When

- If a genuine multi-show/tour queue product requirement emerges — revisit
  via the presale-registration model, not by reopening cross-region
  consensus.
- CDN vendor choice, when `infra.md`'s AWS target firms up — write a
  dedicated ADR weighing Cloudflare's cache-tag purge against
  CloudFront's AWS-native integration.

## Open Questions

- Cross-region RTO/RPO targets, standby capacity ratio, queue
  join-randomization window, sequence block size — all starting defaults,
  need real inter-region-link and load-test data.
- `auth-service`/`user-service` sharding/residency — flagged gap in
  ADR-005's scope, not yet a dedicated ADR.
