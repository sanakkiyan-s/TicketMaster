---
title: ADR-043 Dynamic, Admin-Configurable Role & Permission System
type: decision
sources: []
related: [[ADR-009-service-to-service-auth]], [[ADR-012-jwt-lifecycle]], [[ADR-030-organizer-admin-authorization]], [[ADR-007-kafka-event-schema]], [[auth-service]], [[api-gateway]], [[frontend-product-blueprint]]
created: 2026-08-21
last-updated: 2026-08-21
---

Status: Accepted

# Context

[[ADR-030-organizer-admin-authorization]] hardcodes exactly two roles —
`ORGANIZER`, `ADMIN` — as literal strings checked with
`roles.contains("ORGANIZER")`-style logic in api-gateway and every
downstream service. Adding a new capability tier (e.g. a support agent
who can view but not cancel bookings, or an event moderator scoped to
one category) currently means a code change and a redeploy of every
service that checks that string. The product now needs an admin to
create new roles and assign permissions to them at runtime, without a
deploy. `frontend-product-blueprint.md` §2/§20 flagged this as
genuinely undecided pending this ADR — this is that decision.

[[ADR-030-organizer-admin-authorization]]'s own Option C (a centralized
OPA-style policy service) was considered and rejected for a *different*
reason — it doesn't justify itself as a 16th service when four services
can each run one `WHERE organizer_id = ?` clause. That rejection was
about *where ownership checks run*, not about whether roles can be
dynamic — this ADR doesn't reopen that question. Dynamic roles is
solvable entirely within auth-service's existing schema and the
gateway's existing JWT/revocation infrastructure ([[ADR-012-jwt-lifecycle]]);
no new service is needed here either.

# Requirements / Constraints

- Admin must be able to create a role, name it, and attach/detach
  permissions to it — at runtime, no deploy.
- Must not weaken [[ADR-030-organizer-admin-authorization]]'s existing
  guarantee: an organizer still cannot touch another organizer's data,
  and platform-wide bypass still requires an explicit, audited
  capability — not a side effect of some other permission.
- Must not require inventing a new token/session mechanism —
  [[ADR-012-jwt-lifecycle]]'s access-token shape and 10-minute lifetime
  are staying as-is; this ADR is not reopening JWT design.
- A permission must correspond to a real, code-level authorization check
  somewhere in the system. Letting an admin type an arbitrary permission
  string that no code path ever checks would be a permission with no
  enforcement — meaningless, and a false sense of control. Permissions
  are therefore a **fixed, code-defined catalog**; only which permissions
  a *role* bundles together is admin-configurable. (This is the one place
  this ADR draws a hard line against "fully dynamic" — see Options
  Considered.)
- Must not let an admin lock every admin out of the system (deleting or
  stripping the last role capable of managing roles).
- Today's exact behavior (ORGANIZER can manage own events/venues/artists,
  ADMIN can do everything and bypass ownership) must be reproduced
  byte-for-byte on day one via seed data — this is a mechanism change,
  not a behavior change, until an admin actually uses it.

# Options Considered

## A — Fully dynamic: admin defines both roles AND arbitrary permission strings

Pros: maximum flexibility, no code changes ever needed for a new
capability. Cons: a permission string with no corresponding
`@PreAuthorize`/gateway-route check enforces nothing — an admin could
create a "BILLING_ADMIN" role, grant it a permission called
`billing:refund` that no code anywhere checks, and believe they've
scoped access when they haven't. This turns the permission system into
documentation, not enforcement. Every *new* capability still needs a
code change regardless (the check has to exist somewhere) — Option A's
"no code change ever" promise is false for anything but recombining
existing checks.

## B — Fixed permission catalog (code-defined), dynamic roles (admin-defined bundles of permissions), dynamic role→user assignment

Pros: every permission in the catalog corresponds to a real enforcement
point, so granting it always does something real. Admin gets exactly
the flexibility actually requested — new roles, custom bundles, at
runtime — without the false-flexibility trap of Option A. Reuses
[[ADR-012-jwt-lifecycle]]'s existing revocation-topic mechanism
unchanged for propagating permission-set changes (see Decision). Cons:
a genuinely new capability (a check that doesn't exist yet) still needs
a code change — but that was always true, no option removes it.

## C — Permissions baked directly into the JWT at issuance

Resolve the caller's full permission set at login and put it in the
token (`permissions: ["event:create", "event:cancel", ...]`) instead of
role *names*. Pros: zero lookup cost per request — every service already
just reads JWT claims. Cons: [[ADR-012-jwt-lifecycle]]'s 10-minute access
token means a permission *revoked* from a role stays live in every
already-issued token for up to 10 minutes — the same staleness ADR-012
already accepts for the `roles` claim today, EXCEPT this makes the
token bigger (a real user could hold many permissions across many
roles) and duplicates the same information the role→permission map
already encodes, for no benefit over just carrying role names and
resolving permissions server-side.

# Decision

**Option B**, with permissions resolved server-side from role names
already carried in the JWT (rejecting Option C's token-bloat path).

## Data model (auth-service — the service that already owns identity)

```sql
-- Fixed catalog. Seeded via Flyway migration, one row per real
-- authorization check point in the system. NOT admin-creatable —
-- see Requirements above for why.
CREATE TABLE permissions (
  id          UUID PRIMARY KEY,
  key         TEXT UNIQUE NOT NULL,   -- e.g. "event:create", "event:manage-any"
  description TEXT NOT NULL
);

-- Dynamic. Admin creates/renames/deletes rows here at runtime.
CREATE TABLE roles (
  id         UUID PRIMARY KEY,
  name       TEXT UNIQUE NOT NULL,     -- e.g. "ORGANIZER", "ADMIN", or an
                                        -- admin-created "EVENT_MODERATOR"
  is_system  BOOLEAN NOT NULL DEFAULT false,  -- ORGANIZER/ADMIN seeded true
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Admin attaches/detaches permissions to a role at runtime, including
-- to the seeded ORGANIZER/ADMIN roles (is_system does not mean
-- immutable — see "system role protection" below for what it DOES mean).
CREATE TABLE role_permissions (
  role_id       UUID NOT NULL REFERENCES roles(id),
  permission_id UUID NOT NULL REFERENCES permissions(id),
  PRIMARY KEY (role_id, permission_id)
);

-- auth-service.md already documents user_roles as rows-not-a-column
-- ("one user can hold USER + ORGANIZER"); this ADR is what makes that
-- table's role_id a real FK instead of a bare string.
CREATE TABLE user_roles (
  user_id UUID NOT NULL,
  role_id UUID NOT NULL REFERENCES roles(id),
  PRIMARY KEY (user_id, role_id)
);
```

**System role protection**, not immutability: `roles.is_system = true`
(seeded on `ORGANIZER` and `ADMIN`) blocks *deletion and renaming* of
that row only — an admin can still freely add or remove permissions on
a system role. This exists solely to stop the lockout scenario in
Requirements (deleting the `ADMIN` role, or renaming it out from under
whatever bootstraps the first admin account). Enforced at the row level
in `roles`, checked by the role-management endpoint before any
delete/rename — not a separate mechanism.

**Bootstrap invariant, checked on every role-management write**: at
least one role must exist holding the `role:manage` permission (defined
below) with at least one user assigned to it. A write that would violate
this (deleting the last `role:manage`-holding role, or stripping
`role:manage` from every role that has it) is rejected with 409 — this
is the concrete mechanism behind "must not lock every admin out."

## Permission catalog, seeded to reproduce today's exact behavior

| Permission key | What it gates | Seeded on |
|---|---|---|
| `event:manage-own` | Create/edit/cancel events where `organizer_id == caller` | `ORGANIZER` |
| `event:manage-any` | Same, ownership check bypassed (ADR-030's admin bypass, generalized) | `ADMIN` |
| `venue:manage-own` | Venue/section/seat CRUD, own venues | `ORGANIZER` |
| `venue:manage-any` | Same, bypassed | `ADMIN` |
| `artist:manage` | Artist CRUD (no ownership concept today, per ADR-030) | `ORGANIZER`, `ADMIN` |
| `analytics:view-own` | Own-event sales dashboard | `ORGANIZER` |
| `analytics:view-any` | Platform-wide | `ADMIN` |
| `dispute:view-own` | Own-event disputes (ADR-035) | `ORGANIZER` |
| `dispute:manage-any` | Platform-wide dispute review, resolution | `ADMIN` |
| `purchase-limit:configure` | Per-event anti-scalper limits (ADR-014 §4) | `ORGANIZER`, `ADMIN` |
| `media:upload` | Trailer upload, own events only (ADR-017) | `ORGANIZER`, `ADMIN` |
| `user:ban` | `POST /admin/users/{id}/ban` | `ADMIN` |
| `key:rotate` | JWT signing-key rotation admin endpoints | `ADMIN` |
| `bot-order:review` | Bulk-cancel sign-off (ADR-014 §7) | `ADMIN` |
| `event:cancel-any` | Platform-wide cancellation / mass-refund trigger (ADR-028) | `ADMIN` |
| `role:manage` | Create/rename/delete roles, assign permissions to a role | `ADMIN` |
| `user-role:assign` | Assign/remove a role on a user account | `ADMIN` |

Every row above already existed as a hardcoded `ORGANIZER`/`ADMIN`
string check somewhere in the current codebase (event-service,
venue-service, auth-service's `AdminUserController`/
`RotationAdminController`, api-gateway's route gate) — this table is a
1:1 rename of an existing check into a permission key, not new
authorization surface. Seeding it this way is what makes day-one
behavior identical to today.

## Propagation: reuse ADR-012's revocation mechanism exactly, don't invent a second one

The problem this solves: a role *name* is still what's in the JWT
(unchanged — see Decision header). If an admin edits `ORGANIZER`'s
permission set, every already-issued JWT with `roles: ["ORGANIZER"]`
needs the *services* to resolve that name against the *new* permission
set, not a stale in-memory copy — the exact same "how does a change
propagate to every gateway/service instance without waiting for token
expiry" problem [[ADR-012-jwt-lifecycle]] already solved for
revocation.

```
Role/permission change (auth-service's role-management endpoint)
        │  writes role_permissions, same transaction as an outbox row
        ▼
outbox table (ADR-007 pattern, already exists in auth-service)
        │  Debezium
        ▼
auth.role-permissions   (compacted Kafka topic, key = role name)
        │  value: {permissions: ["event:manage-own", ...], updatedAt}
        ▼
api-gateway AND every service that runs a permission check
        │  materializes the topic into an in-memory map, same pattern
        │  as auth.revocation's RevocationConsumer
        ▼
Next request: roles from JWT -> union of each role's permission set
from the in-memory map -> check membership.
```

This is deliberately the *same* topology as `auth.revocation` (own
compacted topic, in-memory materialization, no synchronous lookup on
the request path) — not a new pattern to design, learn, or operate.
Same fail-closed carve-out ADR-012 already applies to revocation
extends here: a gateway/service instance that hasn't caught up on
`auth.role-permissions` at startup must not serve requests with a
stale or empty permission map (readiness gate, per [[ADR-032-api-gateway-ha-and-probe-semantics]]'s
named fail-closed exception list).

**Role *assignment* to a user** (adding/removing a row in `user_roles`)
does **not** get this real-time treatment — it takes effect on the
user's next token refresh, same as today's role-claim staleness
(≤10 minutes, [[ADR-012-jwt-lifecycle]]'s access-token TTL). Only a
role's *permission set* needs real-time propagation, because that's the
thing an already-issued token's claim implicitly points at.

## What changes in ADR-009 and ADR-030 (amendments, not rewrites)

- **api-gateway's coarse gate** ([[ADR-030-organizer-admin-authorization]]
  Layer 1): route config changes from `roles contains ORGANIZER` to
  `caller's resolved permission set contains <permission-key>`. YAML
  still owns which route needs which permission key (same
  YAML-owns-routing / Java-owns-behavior split `api-gateway.md` already
  established); Java's filter now resolves permissions via the cached
  map above instead of a literal string match.
- **Service-level ownership bypass** (ADR-030 Layer 2): every
  `roles.contains("ADMIN")` bypass check becomes
  `hasPermission(caller, "<resource>:manage-any")` — same shape, backed
  by the permission catalog instead of a hardcoded string.
- **ADR-009's two-token model is unchanged.** `X-User-Assertion` still
  carries the original access token unmodified; this ADR only changes
  how the *roles claim inside it* gets interpreted downstream. No new
  header, no token-shape change.

Full amendment text is appended to both ADR-009 and ADR-030 themselves
(this vault's convention: append, never rewrite — see
`second-brain/CLAUDE.md`).

## Frontend implications

- `LoginResponse.user` gains a `permissions: string[]` field — resolved
  server-side at login (same place `roles` is already populated), used
  purely for UI nav-gating (`RoleGate` from `frontend-product-blueprint.md`
  §14 becomes `PermissionGate`, checking permission keys instead of role
  strings). This is informational only, same "not a trust boundary in
  the browser" reasoning already established for `roles` — the backend
  still enforces for real.
- New admin pages, following the pattern already scoped in
  `frontend-product-blueprint.md`: **Admin · Roles** (list/create/rename/
  delete non-system roles, gated on `role:manage`) and **Admin · Role
  Detail** (attach/detach permissions from the fixed catalog to a role).
  User→role assignment folds into the existing **Admin · Users** page
  (gated on `user-role:assign`).
- Like `roles` today, a page rendered from a stale `permissions` array
  (up to 10 minutes old, per the propagation note above) can show a nav
  link whose backend call then 403s — same class of harmless staleness
  this app already tolerates for revocation and key rotation, not a new
  failure mode.

# Why

Gives the admin exactly the runtime flexibility requested — new roles,
custom permission bundles, at any time, no deploy — without the
false-flexibility trap of letting permission strings exist that no code
anywhere enforces (Option A). Reuses two mechanisms this project already
built and operates (ADR-007's outbox/Kafka projection, ADR-012's
compacted-topic + in-memory-materialization pattern for revocation)
instead of inventing a third real-time propagation scheme, and reproduces
today's exact ORGANIZER/ADMIN behavior via seed data so this is a
mechanism change, not a day-one behavior change.

# Consequences

**Easier:** an admin can create a new role (e.g. "EVENT_MODERATOR" with
only `event:manage-any` minus `event:cancel-any`) without a code change
or redeploy; permission changes propagate in roughly the same window as
account revocation already does (seconds, not the 10-minute token TTL);
every organizer/admin authorization check in the codebase becomes one
consistent `hasPermission(caller, key)` call instead of scattered
`roles.contains("STRING")` checks.

**Harder:** api-gateway and every service currently doing a literal role
string check (event-service, venue-service, analytics-service,
media-service, inventory-service's config endpoints, auth-service's own
admin controllers) needs to switch to the permission-lookup call — real
implementation surface across six services, not a single central
change. auth-service gains three new tables and a role-management API
surface (with the bootstrap-invariant check) that didn't exist before.
A genuinely new capability (no existing enforcement point) still
requires a code change to add the check itself, plus a new
`permissions` catalog row and a migration — dynamic roles compose
*existing* checks, they don't create new ones from nothing.

# Revisit When

- If the permission catalog grows large enough (dozens of fine-grained
  keys) that admins need permission *groups*/templates to compose roles
  practically — not needed at today's ~17-permission catalog size.
- If cross-service permission checks need to become synchronous (e.g. a
  service that can't tolerate the propagation-lag window) — would need
  a different consistency model than the eventually-consistent
  in-memory map described here; no such requirement exists today.

## Open Questions

- Whether `role:manage` and `user-role:assign` should themselves be
  splittable (e.g. "can assign roles but not create new ones") — kept
  as two permissions, not further split, since no concrete need for
  finer granularity has come up yet.
- Exact propagation-lag SLI/alerting for `auth.role-permissions` (mirror
  of ADR-015's Debezium-lag alerting on `auth.revocation`) — not
  designed here, same starting-default treatment as other lag alerts in
  this vault.
