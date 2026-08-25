---
title: ADR-030 Organizer and Admin Authorization Model
type: decision
sources: []
related: [[ADR-009-service-to-service-auth]], [[ADR-012-jwt-lifecycle]], [[ADR-014-anti-bot-anti-scalper]], [[ADR-017-media-service-video]], [[ADR-028-event-cancellation-mass-refund]], [[event-service]], [[analytics-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

event-service owns organizer CRUD, analytics-service serves
organizer-facing dashboards, [[ADR-017-media-service-video]] has
organizers requesting pre-signed upload URLs,
[[ADR-014-anti-bot-anti-scalper]] has an organizer-configurable
purchase-limit and an admin bulk-cancel path, and
[[ADR-028-event-cancellation-mass-refund]] gives admins a real
platform-wide cancellation mechanism. None of this has an authorization
model beyond [[ADR-012-jwt-lifecycle]]'s bare `roles` JWT claim — nothing
decides how organizer X is prevented from managing, uploading to, or
reading sales data for organizer Y's event. This is real multi-tenant
authorization, not covered by anything currently decided.

# Requirements / Constraints

- Must prevent one organizer from managing or reading another
  organizer's event data — the concrete security gap a role-only check
  leaves open.
- Must distinguish three tiers: regular user, organizer (scoped to their
  own events only), admin (platform-wide, per ADR-014/ADR-028).
- Must reuse [[ADR-009-service-to-service-auth]]'s existing
  `X-User-Assertion` token rather than inventing a new auth mechanism.
- Must enforce ownership checks where the ownership data actually lives
  — the gateway does not and should not hold per-resource ownership data
  for every service.
- Must not require a 16th service to independently justify itself
  against [[ADR-001-microservices-vs-modular-monolith]]'s bar.

# Options Considered

## A — Role-only check (JWT `roles` claim, no per-resource ownership check)

Pros: trivial to implement. Cons: an `ORGANIZER`-role holder could
manage, upload to, or read analytics for ANY event, not just their own
— a real security bug, not a theoretical one.

## B — Coarse role gate at api-gateway + fine-grained ownership check enforced by the resource-owning service

Pros: cheap coarse rejection happens early (gateway blocks non-organizers
from organizer-only routes without a DB lookup), while the actual
ownership check happens exactly where the ownership data lives — no new
cross-service lookup infrastructure needed, reuses
[[ADR-009-service-to-service-auth]]'s existing token unchanged.

## C — A centralized authorization/policy service (OPA-style)

Pros: a single place to reason about all authz rules. Cons: a 16th
service for a problem that four existing services can each solve with
one `WHERE organizer_id = ?` clause — doesn't independently justify
itself at this project's actual scale.

# Decision

**Option B.**

## The ownership anchor

```sql
-- event-service's own table, the actual authorization anchor:
events.organizer_id UUID NOT NULL  -- references the organizer's user_id
```

This column already had to exist for any organizer-facing feature to
work at all; this ADR is the first place it's named as the enforcement
anchor, not just a data column.

## Two-layer enforcement

```
Layer 1 — coarse gate, at api-gateway, no DB lookup:
  Routes under /organizer/* require roles contains ORGANIZER.
  Routes under /admin/* require roles contains ADMIN.
  Rejects a plain USER before the request reaches any service. Reuses
  ADR-009's existing X-User-Assertion — no new token field.

Layer 2 — fine-grained ownership, enforced by the OWNING service:
  event-service:      any event CRUD checks
                       events.organizer_id == X-User-Assertion's sub,
                       UNLESS the caller holds ADMIN (admin bypasses
                       ownership by design).
  analytics-service:   dashboard queries scoped by
                        organizer_id == token sub, same pattern.
  media-service:        upload pre-signed-URL request checks the
                        target event's organizer_id (via a call/cached
                        lookup to event-service) before issuing the URL
                        — ADR-017's upload flow gains this check, was
                        previously unstated.
  inventory-service:    the purchase-limit CONFIGURATION endpoint
                        (ADR-014) checks organizer_id ownership the
                        same way — the purchase-limit ENFORCEMENT
                        itself stays anonymous/any-buyer, unaffected.
```

## Admin actions

ADR-014 layer 7's bulk-cancel and ADR-028's admin-triggered path require
`ADMIN` and explicitly **skip** the ownership check — an admin acts
platform-wide by design. Every admin action still emits
`AdminActionPerformed` (already decided in [[cross-cutting-concerns]]),
tying the action to the specific admin's identity for accountability —
this ADR doesn't change that requirement, just confirms it applies here
too.

# Why

Reuses the token/header mechanism [[ADR-009-service-to-service-auth]]
already established rather than building new authorization
infrastructure, and puts each ownership check exactly where its data
already lives — avoiding a gateway that would otherwise need lookup
access into every service's resource-ownership data, a much larger
coupling problem than four `WHERE organizer_id = ?` clauses.

# Consequences

**Easier:** real multi-tenant isolation between organizers, closing a
genuine security gap; one consistent enforcement pattern reused across
every organizer-facing endpoint instead of four different ad hoc checks.

**Harder:** four services (event-service, analytics-service,
media-service, inventory-service's config endpoints) each need to add
an ownership-check step — real implementation surface, not a single
central change.

# Revisit When

- If delegated/shared event management (multiple organizer accounts
  co-managing one event) becomes a real product requirement — would need
  an `organizer_event_permissions` join table instead of a single
  `organizer_id` column; not designed now since no such requirement
  exists yet.

## Amendment: roles become dynamic, ownership model unchanged (ADR-043)

[[ADR-043-dynamic-role-permission-system]] replaces this ADR's hardcoded
`ORGANIZER`/`ADMIN` string checks with a permission-catalog lookup, so
an admin can create new roles at runtime. **The two-layer model itself —
coarse gate at api-gateway, fine-grained ownership enforced by the
owning service, admin bypasses ownership by design — is unchanged.**
What changes:

- Layer 1's `roles contains ORGANIZER` becomes "caller's resolved
  permission set contains `event:manage-own`" (and the equivalent per
  resource type) — same coarse-gate shape, backed by a permission key
  instead of a literal string.
- Layer 2's `UNLESS the caller holds ADMIN` bypass becomes `UNLESS the
  caller holds <resource>:manage-any` — same bypass shape, same
  audit-via-`AdminActionPerformed` requirement, just keyed on a
  permission instead of a hardcoded role name. A future non-ADMIN role
  granted a `*:manage-any` permission gets the same bypass and the same
  audit obligation — this ADR's ownership guarantee (an organizer can
  never touch another organizer's data without an explicit,
  audited bypass capability) is unchanged by who holds that capability.
- The `events.organizer_id` ownership anchor, and every service's
  `WHERE organizer_id = ?` enforcement point, are completely unchanged
  — ADR-043 only changes how the *coarse* gate and the *bypass* check
  are evaluated, never where ownership data lives or how it's checked.

See ADR-043 for the full permission catalog (seeded to reproduce this
ADR's exact current behavior) and the propagation mechanism.

## Open Questions

- Whether organizer-tier CRUD actions (not just admin actions) should
  also emit an audit event — `AdminActionPerformed` currently implies
  admin-only; not decided whether organizer actions need their own
  audit trail.
- Whether an "organizer" is a role on the same `user_id` as a regular
  account (assumed here) or a fully separate identity concept — assumed
  the former, not verified against any other ADR.
