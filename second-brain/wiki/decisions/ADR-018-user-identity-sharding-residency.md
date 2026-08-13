---
title: ADR-018 auth-service / user-service Sharding and Residency
type: decision
sources: []
related: [[ADR-005-postgres-sharding]], [[ADR-016-multi-region-cdn]], [[ADR-012-jwt-lifecycle]], [[auth-service]], [[user-service]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

[[ADR-005-postgres-sharding]] only covers `event_id`-keyed tables
(inventory, bookings, tickets). `auth-service` and `user-service` are
keyed by `user_id`, an entirely different shard key with different
residency drivers — flagged as an explicit gap when
[[ADR-016-multi-region-cdn]] designed home-region routing for events and
had to assume, without deciding, that users also have a home region.

# Requirements / Constraints

- A user's PII (credentials, profile, payment method references) must
  respect the same jurisdictional residency rules as event data (an EU
  user's personal data should stay in the EU, same legal driver as
  ADR-005's Class R data).
- Login/profile access should be fast for the user's own region, without
  requiring a global lookup on every request.
- Must reuse existing regional infrastructure (Citus clusters, Patroni/
  etcd HA) rather than standing up a second, parallel sharding system.
- A user buying a ticket for an event homed in a *different* region than
  their own account must still work (the common case, not an edge case —
  most ticket buyers are not local to every event they attend).

# Options Considered

## A — Single global Postgres for auth/user data, no sharding

Pros: simplest possible design. Cons: violates the residency requirement
outright (an EU user's credentials sitting in a US database is exactly
the problem ADR-005 exists to avoid for event data) and doesn't hold at
genuine global user-count scale.

## B — Separate, purpose-built sharding system for user data

Pros: could be tuned specifically for `user_id` access patterns. Cons:
duplicates infrastructure ADR-005 already built and validated (Citus
cluster + Patroni/etcd per region) for no real benefit — user-scoped data
has the same "one entity, self-contained, home region" shape as
event-scoped data.

## C — Reuse ADR-005's regional Citus clusters, new distribution column

Each region's existing Citus cluster gains additional distributed tables,
sharded by `user_id` instead of `event_id`. Same coordinator/worker
topology, same Patroni/etcd HA mechanism, same reshard/failover
machinery — just a different distribution key, living alongside the
event-keyed tables in the same regional infrastructure.

# Decision

**Option C.**

## Home-region assignment — same identifier-prefix trick as events

[[ADR-016-multi-region-cdn]] derives an event's region from a prefix
baked into its ID (`evt_eu1_...`), avoiding a lookup on the routing hot
path. Same mechanism here:

```
At signup: user declares (or the system infers from IP/locale) a home
  jurisdiction. This is a RESIDENCY decision, not a latency-optimization
  one — same distinction ADR-016 draws for events: not computed by a
  hash, deliberately assigned.

user_id is generated WITH the region baked in: usr_eu1_..., usr_us1_...

Routing (login, profile fetch, refresh-token lookup) parses the prefix,
  same as event routing — no global lookup table on the hot path.
```

## Sharding within a region — reuses ADR-005's mechanism directly

```
create_distributed_table('users', 'user_id')
create_distributed_table('saved_payment_method', 'user_id')   -- ADR-011
create_distributed_table('refresh_tokens', 'user_id')          -- ADR-012
create_distributed_table('points_ledger', 'user_id')
```

All of a given user's own data (profile, refresh tokens, payment-method
references, loyalty points) shares the same distribution key, so it
co-locates on the same shard — same single-shard-query benefit ADR-005
established for event-scoped data (a login or profile fetch never needs
a cross-shard join). Shard count, resharding process, and coordinator HA
are **inherited unchanged from ADR-005** — this is the same physical
Citus cluster per region, not a new one.

## Cross-region access (the common case: buying a ticket abroad)

A user's *authentication* never needs a cross-region call —
[[ADR-012-jwt-lifecycle]] already validates JWTs locally via globally-
published JWKS, and [[ADR-016-multi-region-cdn]] already resolves
*issuance* to a one-time cross-region hop at login. This ADR adds: when a
service in the event's home region genuinely needs the buyer's *profile*
data (ticket-service rendering a name on a ticket, notification-service
sending a confirmation email), it makes a **synchronous cross-region call
to user-service in the user's home region** — low volume (once per
booking, not per request), and does not sit on inventory-service's hot
path at all.

```
EU event, US-homed buyer:
  booking flow (hold/confirm) — entirely EU-region, never touches
    US-region infrastructure, no residency issue (Class S data, per
    ADR-016).
  ticket-service needs the buyer's name — one cross-region call to
    us1's user-service, at ticket-issuance time (async, off the
    critical path, per ADR-006's saga design already treating ticket
    issuance as a non-blocking side effect).
```

## Residency vs. latency, same split as ADR-016's data classes

```
Class R (home-region only, never replicated): credentials, profile PII,
  payment-method references, points ledger.
Class G (global): none of user-service's data qualifies — unlike event
  metadata, there's no non-personal subset of a user's own record worth
  replicating everywhere.
```

Unlike events, there is no "read-anywhere" tier for user data — a
profile lookup is either served by the home region (the common case) or
paid for as an explicit cross-region call (the ticket/notification case
above). No caching or replication of PII across regions, consistent with
[[ADR-013-gdpr-crypto-shredding]]'s residency posture.

## Emigration (a user's home region changing) — explicitly manual

Re-homing a single user to a different region means physically moving
their row across two separate regional Citus clusters — not the same
operation as ADR-004/005's node-level resharding (which moves shards
within one cluster). Treated as a rare, manual, admin-triggered data
migration, not an automated path. *Not designed further here* — no
product requirement currently demands self-service region migration.

# Why

Reusing ADR-005's regional Citus clusters avoids building and operating a
second sharding system for a problem with the same shape (single-owner
entity, jurisdiction-bound, needs fast home-region access) that ADR-005
already solved. The identifier-prefix routing trick keeps user-lookup
off any global-consistency dependency, mirroring the reasoning that
already justified it for events.

# Consequences

**Easier:** no new infrastructure — auth-service/user-service inherit
Citus's coordinator/worker HA, resharding, and failover mechanics for
free; residency is enforced by construction (a user's data physically
never leaves their home region's cluster).

**Harder:** cross-region profile lookups (ticket issuance, notifications
for foreign-event bookings) are a new synchronous cross-region call
pattern that didn't exist before — must be built with its own timeout/
retry handling, since it's now a real network hop, not a local query;
emigration between regions has no self-service path and requires manual
ops work if ever needed.

# Revisit When

- If self-service region migration becomes a real product requirement —
  design a proper migration flow instead of the manual placeholder above.
- If cross-region profile-lookup volume (foreign-event ticket buyers)
  ever becomes large enough to need caching — would need a residency-safe
  caching design (e.g. TTL'd, non-PII-only cache), not a default addition.

## Open Questions

- None outstanding — this ADR closes the gap flagged by ADR-016.
