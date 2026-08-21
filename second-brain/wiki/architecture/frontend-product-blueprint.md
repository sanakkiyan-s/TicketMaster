---
title: Frontend Product & UX Blueprint
type: architecture
sources: []
related: [[implementation-roadmap]], [[ADR-036-build-order-and-phasing]], [[ADR-030-organizer-admin-authorization]], [[ADR-043-dynamic-role-permission-system]], [[ADR-002-seat-locking-strategy]], [[ADR-006-saga-booking-orchestration]], [[ADR-022-sse-connection-admission-control]], [[frontend]]
created: 2026-08-21
last-updated: 2026-08-21
---

Status: discovery document, not yet approved. Every claim below traces to
a specific ADR, project page, or directly-inspected code — cited inline.
Nothing is invented because it's a common ticketing-platform feature.
Where the system genuinely hasn't decided something, it's marked **TBD**,
not guessed. **Page inventory (§5) and user journeys (§6) need approval
before wireframes or visual UI design start.**

Legend used throughout:

| Tag | Meaning |
|---|---|
| **[BUILT]** | real code exists today |
| **[DECIDED]** | an ADR specifies it, no code yet |
| **[PARTIAL]** | backend exists, frontend doesn't (or vice versa) |
| **[TBD]** | genuinely open in the docs — not decided anywhere |

Sourced from all 42 ADRs in `second-brain/wiki/decisions/`, all 17 pages
in `second-brain/wiki/projects/`, `second-brain/wiki/flows/seat-availability-live-updates.md`,
and direct inspection of the running backend/frontend code.

---

# 1. Product Understanding

This is an event-ticketing platform built around one hard problem:
**selling a finite number of seats to more people who want them than there
are seats, without ever selling the same seat twice, and without losing
anyone's money if something breaks mid-purchase.** Everything else —
browsing, accounts, organizer tooling, notifications — exists in service
of that one transaction.

The system is explicitly phased ([[ADR-036-build-order-and-phasing]]), and
the phase boundary matters more than any single feature: **Phases 0-4 are
the MVP** — a working, correct, single-region path from *browse → hold a
seat → pay → receive a ticket → get notified*. Phases 5-6 (transfer/resale,
queue, media, multi-region scale) are real, decided, and documented, but
not required for the product to function.

Today, only the **identity, catalog, and browse** slice is real code —
everything downstream of "select a seat" is a fully specified decision
with zero implementation. This blueprint designs the frontend against the
*whole* decided system, but every page and every capability below is
tagged with exactly where it stands right now.

---

# 2. User Types

Exactly three named actors exist anywhere in the ADRs or code — plus one
implicit anonymous visitor. No fourth role (Support, Operations, Venue
Manager) appears in any document.

| User type | Source | Purpose | Main goals |
|---|---|---|---|
| **Anonymous visitor** | no auth token | Discover events without committing to an account. Search-service's one public endpoint exists specifically for this. | Browse, search, decide whether to register. |
| **Customer** **[BUILT]** | JWT with no ORGANIZER/ADMIN role | The buyer. Every ADR's "user" or "fan" language refers to this actor by default. | Find an event, hold a seat, pay, get a ticket that works at the door, manage the account. |
| **Organizer** **[BUILT]** | JWT `roles` contains `"ORGANIZER"` | Owns and runs events. Scoped strictly to their own resources via `organizer_id` ownership ([[ADR-030-organizer-admin-authorization]]) — cannot see or touch another organizer's data. | Publish events/sessions, manage venues and seat maps, watch sales, configure anti-scalping limits, resolve their own disputes. |
| **Admin** **[BUILT]** | JWT `roles` contains `"ADMIN"` | Platform-wide operator. Bypasses ownership checks by design (ADR-030). Every admin action is audited via an `AdminActionPerformed` event. | Ban abusive accounts, rotate signing keys, sign off on bulk bot-order cancellation, trigger platform-wide event cancellation, review disputes. |

> **On dynamic, admin-configurable RBAC — now decided, [[ADR-043-dynamic-role-permission-system]].**
> The three roles above (ORGANIZER, ADMIN, implicit customer) were
> hardcoded strings until this session — no ADR described a dynamic role
> system. That gap is now closed: **ADR-043** lets an admin create new
> roles and assign permissions to them at runtime, while keeping
> permissions themselves a fixed, code-defined catalog (~17 keys, 1:1
> with today's existing checks) — an admin can *combine* real
> enforcement points into new roles, but can't invent a permission string
> that no code path checks. `ORGANIZER`/`ADMIN` are seeded as protected
> (`is_system`) roles reproducing today's exact behavior; every page and
> table below that referenced hardcoded role strings is updated to
> reflect permission-keyed checks instead. See ADR-043 for the full
> catalog, the two new admin pages, and the propagation mechanism
> (reuses ADR-012's revocation topology unchanged).

---

# 3. Role × Permission Matrix

Only capabilities that actually appear in the system — built or decided.
A dash means the role has no path to that capability anywhere in the
docs, not that it was overlooked.

| Capability | Anon. | Customer | Organizer | Admin | Status |
|---|:-:|:-:|:-:|:-:|---|
| Browse / search events | ✓ | ✓ | ✓ | ✓ | **[BUILT]** |
| Register / log in | ✓ | — | — | — | **[BUILT]** |
| View / edit own profile, preferences | — | ✓ | ✓ | ✓ | **[BUILT]** |
| View saved payment methods | — | ✓ | ✓ | ✓ | **[BUILT]** |
| Add / remove a payment method | — | ✓ | ✓ | ✓ | **[PARTIAL]** backend built, no frontend page |
| Select a seat / place a hold | — | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-002) |
| Check out / pay | — | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-006, ADR-011) |
| View own tickets / bookings | — | ✓ | ✓ | ✓ | **[DECIDED]** |
| Transfer a ticket (free) | — | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-029) |
| Resell a ticket (paid, price-capped) | — | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-029) |
| Sign up for "notify me" on a session | ✓ (email only) | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-021) |
| Request data export / erasure (GDPR) | — | ✓ | ✓ | ✓ | **[DECIDED]** (ADR-013), impl. unconfirmed |
| Create / edit / cancel own events | — | — | ✓ | ✓ (bypasses ownership) | **[BUILT]** |
| Manage own venues, sections, seats | — | — | ✓ | ✓ | **[BUILT]** (bulk seat import not built) |
| Manage artists | — | — | ✓ | ✓ | **[BUILT]** |
| Add sessions to an event | — | — | ✓ | ✓ | **[BUILT]** (no list/GET endpoint — see §5) |
| Configure purchase limits per event | — | — | ✓ | ✓ | **[DECIDED]** (ADR-014 layer 4) |
| Upload event trailer video | — | — | ✓ | ✓ | **[DECIDED]** (ADR-017) |
| View own sales / analytics dashboard | — | — | ✓ | ✓ (platform-wide) | **[DECIDED]** |
| See own-event dispute notifications | — | — | ✓ | ✓ | **[DECIDED]** (ADR-035) |
| Ban / revoke a user account | — | — | — | ✓ | **[BUILT]** |
| Rotate JWT signing keys | — | — | — | ✓ | **[BUILT]** (backend), no UI |
| Sign off on bulk bot-order cancellation | — | — | — | ✓ (human sign-off required) | **[DECIDED]** (ADR-014 layer 7) |
| Cancel an event platform-wide / mass-refund | — | — | — | ✓ | **[DECIDED]** (ADR-028) |
| Review payment disputes / chargebacks | — | — | — | ✓ | **[DECIDED]** (ADR-035) |

**View / Create / Update / Delete split, where it matters:** Organizer
permissions on events/venues are full CRUD, but scoped by `organizer_id`
ownership at the service layer, not just hidden in the UI — a customer's
token literally cannot list another organizer's events (backend enforces
this regardless of what the frontend renders). Admin's ownership *bypass*
is the one place "Update" and "Delete" mean something structurally
different for admin vs. organizer: an organizer's delete is scoped, an
admin's is platform-wide, and every admin delete/cancel action is
separately audited (`AdminActionPerformed`) in a way organizer actions
currently are not — ADR-030 itself flags this asymmetry as an open
question.

---

# 4. Information Architecture

Four distinct navigation shells — anonymous, customer, organizer, admin —
sharing one shell chrome but never merging their nav trees. Role-gated
links are hidden entirely, not disabled: this is UX only, since the
backend enforces the real boundary regardless of what renders.

```
ANONYMOUS / GUEST
Home (event grid)
├── Search
├── Event Details
├── Sign in
└── Create account

CUSTOMER
Home (event grid)
├── Search
├── Event Details
│   └── Seat Selection → Checkout → Confirmation
├── My Tickets
│   └── Ticket Detail (barcode, transfer, resell)
├── Bookings (history)
├── Notify-Me signups
└── Account
    ├── Profile
    ├── Payment methods
    ├── Notification preferences
    └── Data export / erasure request

ORGANIZER
Organizer Dashboard
├── Events
│   ├── Create event
│   └── Event detail
│       ├── Sessions
│       ├── Purchase-limit config
│       └── Trailer upload
├── Venues
│   └── Venue detail → Sections → Seats
├── Artists
├── Sales / Analytics
└── Disputes (own events)
    [+ Customer nav is still reachable — an organizer is also a buyer]

ADMIN
Admin Console
├── Users
│   └── Ban / revoke
├── Key rotation
├── Bot-order review (bulk-cancel sign-off)
├── Event cancellation (mass-refund trigger)
├── Disputes (platform-wide)
└── Audit log
    [+ Organizer nav and Customer nav both still reachable]
```

**Breadcrumbs** matter in exactly two places: deep organizer resource
trees (`Venues → Fox Theater → Section A → Seat 14`) and the booking flow
itself, where the countdown-timer header (see §7) effectively *is* the
breadcrumb — it's more urgent than a path.

---

# 5. Complete Page Inventory

Every page justified by an actual capability above — nothing added
because "ticketing sites usually have this."

| Page | Role | Purpose | Status |
|---|---|---|---|
| Home / Discover | Anon + all | Event grid, search entry point | **[BUILT]** |
| Event Details | Anon + all | Everything needed before choosing to book | data gap — see below |
| Seat Selection | Customer+ | Live seat map, hold seats | **[DECIDED]** |
| Checkout | Customer+ | Pay via Stripe iframe | **[DECIDED]** |
| Booking Confirmation | Customer+ | Success/failure resolution of the saga | **[DECIDED]** |
| My Tickets | Customer+ | List of owned tickets | **[DECIDED]** |
| Ticket Detail | Customer+ | Live rotating barcode, transfer, resell | **[DECIDED]** |
| Booking History | Customer+ | Past bookings incl. failed/refunded | **[DECIDED]** |
| Waiting Room / Queue | Customer+ | Admission control before high-demand on-sale | **[DECIDED]** |
| Login | Anon | Authenticate | **[BUILT]** |
| Register | Anon | Create account | **[BUILT]** |
| Account · Profile | Customer+ | Display name, phone, avatar | **[BUILT]** |
| Account · Payment methods | Customer+ | Saved cards (add via Stripe SetupIntent) | **[PARTIAL]** backend only |
| Account · Notification preferences | Customer+ | Email/SMS toggles | **[BUILT]** |
| Account · Data export / erasure | Customer+ | GDPR self-service, step-up re-auth required | **[TBD]** impl. status unconfirmed |
| Organizer · Events list | Organizer | Own events only | **[BUILT]** |
| Organizer · Create event | Organizer | New event form | **[BUILT]** |
| Organizer · Event detail | Organizer | Edit, sessions, cancel | **[PARTIAL]** sessions have no list endpoint |
| Organizer · Purchase-limit config | Organizer | Anti-scalper limits per event | **[DECIDED]** (ADR-014 §4) |
| Organizer · Trailer upload | Organizer | Video upload for an event | **[DECIDED]** (ADR-017) |
| Organizer · Venues list | Organizer | Own venues | **[BUILT]** |
| Organizer · Venue detail (sections/seats) | Organizer | Seat-map construction | **[PARTIAL]** single-create only |
| Organizer · Artists | Organizer | Artist CRUD | **[BUILT]** |
| Organizer · Sales / Analytics | Organizer | Own-event sales dashboard | **[DECIDED]** |
| Organizer · Disputes | Organizer | Own-event disputes only | **[DECIDED]** (ADR-035) |
| Admin · Users | Admin | Ban / revoke | **[PARTIAL]** ban exists, no list/search UI |
| Admin · Key rotation | Admin | 4-phase JWT key rotation control | **[PARTIAL]** backend only |
| Admin · Bot-order review | Admin | Human sign-off before bulk-cancel | **[DECIDED]** (ADR-014 §7) |
| Admin · Event cancellation | Admin | Platform-wide cancel + mass-refund trigger | **[DECIDED]** (ADR-028) |
| Admin · Disputes | Admin | Platform-wide dispute review | **[DECIDED]** (ADR-035) |
| Admin · Audit log | Admin | `AdminActionPerformed` history | events exist, no UI |
| Admin · Roles | Admin (`role:manage`) | List/create/rename/delete non-system roles | **[DECIDED]** (ADR-043), not built |
| Admin · Role Detail | Admin (`role:manage`) | Attach/detach permissions (fixed catalog) to a role | **[DECIDED]** (ADR-043), not built |

> **The Event Details data gap.** Search-service's `EventSearchResult` —
> the only data a public event page could read today — carries exactly
> six fields: `eventId, organizerId, venueId, title, status, region`. No
> description, no dates, no price, no image. `EventService.toPayload()`
> on event-service never publishes more than that to the outbox. An Event
> Details page as specified in §13 is fully justified by the domain model
> (events have sessions, sessions have dates/prices), but the fields it
> needs to render aren't flowing through the pipeline yet — a backend
> gap, not a frontend one.

---

# 6. User Journeys

The two journeys that matter: buying a ticket, and an organizer standing
up an event. Every arrow below is a real API call or a real state
transition from the ADRs — nothing is inferred UX convention.

## Customer booking journey

1. **Discover** — Home renders the event grid from `GET /api/v1/events`
   (public, no auth). API: search-service. Fails: shows a retry state,
   never blocks browsing.
2. **Event Details → choose a session** — customer picks a date/show.
   **[DECIDED, not built]** — needs the data gap above closed first.
3. **Seat Selection** — live seat map (SSE), user picks seats, sends a
   hold request per seat. State: `AVAILABLE → HELD`. Real-time: yes, see
   §8. Fails: seat taken mid-pick → tile flips under them, reselect.
4. **Hold placed — countdown starts** — 5-minute client-side countdown
   from `held_until`, no server round-trip to render it. Hard ceiling 15
   min total including one extension. Fails: hold expires → seat silently
   returns to AVAILABLE, kicked back to Seat Selection.
5. **Checkout** — Stripe Payment Element renders in an iframe — the
   frontend never sees card data. `Idempotency-Key` (UUID v4) generated
   once and reused across retries. API: booking-service saga start.
   State: `HOLD_PLACED → PAYMENT_CHARGED`.
6. **Payment submitted → hold extension checkpoint** — if <3 min remained
   on the hold at this exact moment, it's extended by 5 min (once,
   hard-capped at 15 min total) — invisible to the user unless the timer
   visibly jumps.
7. **Confirm** — saga's final step. Two failure branches exist and must
   render differently (see §7):
   - Payment declined → hold released, nothing charged, back to checkout
     with a clear reason.
   - Payment succeeded but the seat's hold expired first → refund runs
     automatically, user sees: *"seat was taken while your payment was
     processing, fully refunded"* (the exact copy from ADR-006's worked
     example). State: `PAYMENT_CHARGED → CONFIRMED` or
     `COMPENSATING → FAILED`.
8. **Confirmation** — booking confirmed. Ticket issuance is async (Kafka),
   so this page may show "issuing your ticket…" for a moment before My
   Tickets has it.
9. **My Tickets** — live rotating barcode, ~15s refresh, generated on
   render — never a static image or PDF.

## Organizer event-setup journey

1. **Create venue** — venue → sections → seats, built today (single-seat
   creation only, no bulk import).
2. **Create event** — `POST /api/v1/organizer/events`, status starts
   `DRAFT`.
3. **Add sessions** — built (`POST`), but there's no `GET` to list them —
   today's frontend keeps sessions in component state only, they vanish
   on reload. Real backend gap, not a UI choice.
4. **Publish** — flip to `PUBLISHED`. **Gap:** today, event-service emits
   `event.created` on every create regardless of status, so a DRAFT
   event is already technically visible to anyone calling search-service's
   public API directly.
5. **Configure purchase limits, upload trailer** — **[DECIDED, not built]**.
6. **Watch sales** — **[DECIDED, not built]** — analytics-service,
   organizer-scoped queries only.

---

# 7. Booking UX — Seat → Hold → Pay → Confirm

The single most important screen in the product. Every state below is a
real value or a real transition from ADR-002, ADR-006, and the
seat-availability flow doc — not a UX convention borrowed from elsewhere.

## Seat states the UI must render

| Backend state | What the seat tile shows | Selectable? |
|---|---|---|
| `AVAILABLE` | Neutral, price-tier color | ✓ |
| `HELD`, held_by = me | Highlighted as "yours," countdown attached | — |
| `HELD`, held_by = someone else | Dimmed / struck, "temporarily unavailable" | ✗ |
| `PURCHASED` | Sold, greyed permanently | ✗ |

> Note: ADR-002's actual schema and pseudocode only ever store
> `AVAILABLE`, `HELD`, and `PURCHASED` — there is no separate `EXPIRED`
> row value (a hold simply flips straight back to `AVAILABLE` once
> `held_until` passes, either via a ~1-minute sweep or lazily on the next
> hold attempt). One project page's prose implies a fourth state; the
> ADR's actual pseudocode doesn't have one — going with the ADR as
> authoritative per this vault's own source-order rule, flagged as an
> inconsistency in §19.

## The full transition, with what the user sees at each hop

```
AVAILABLE
   │  user clicks a seat
   ▼
Optimistic "selecting…" (client-only, no state exists yet)
   │  POST hold request
   ▼
HELD (mine)  ─── 5:00 countdown starts, computed client-side from held_until
   │                                    no server round-trip to tick the clock
   │
   ├── user reaches checkout, <3min left on the clock
   │     → ONE extension: +5min, hard ceiling 15min from the ORIGINAL hold
   │
   ├── another user's SSE "seat-updated" event arrives for a DIFFERENT seat
   │     → that tile updates in place, no refresh, doesn't touch my hold
   │
   ├── my own SSE "hold-expired" event arrives (targeted to me specifically)
   │     → "Your hold expired" — bounced back to seat selection
   │
   └── I submit payment before expiry
         ▼
       PURCHASED  (booking saga's final step)
```

## Someone else takes a seat you're looking at (not holding)

```
AVAILABLE  (visible to me, not selected)
   │  another user holds it first
   ▼
Redis Pub/Sub → SSE "seat-updated" {seatId, status:"HELD"} → my browser
   │
   ▼
Tile flips to dimmed/unselectable, no page refresh, no toast needed —
the seat map itself is the notification.
```

## Countdown & expiry UX rules

- Timer is **always client-computed** from a timestamp the server already
  sent (`held_until`) — never a server push tick. This is explicit in
  ADR-002.
- The SSE `hold-expired` event is the authoritative "you lost it" signal,
  but the client timer reaching zero should independently disable the
  "continue" action too — don't wait on the network for a locally-obvious
  deadline.
- An extension (checkout-triggered) must visibly move the countdown, not
  silently extend it — a timer that jumps without explanation reads as a
  bug.

## Checkout / payment states

| State | UI |
|---|---|
| Loading (seat/session data) | Skeleton, not spinner-only |
| Payment processing | Stripe Element's own inline state; disable the submit button, no separate app-level spinner racing it |
| Payment declined | Card-specific reason from Stripe if available, generic otherwise. Hold is still alive — offer retry with a different card, same seats |
| Reservation expired mid-payment | *"seat was taken while your payment was processing, fully refunded"* (exact ADR-006 copy) — never a raw error code |
| Booking failed (saga compensating) | Same message pattern — never expose "COMPENSATING" or saga internals |
| Duplicate submit (double-click, back-button retry) | Same `Idempotency-Key` means the backend returns the existing booking's current state — render that, not an error |
| Session/token expired mid-checkout | See §11 — silent refresh first, only surface a re-login prompt if that fails |

---

# 8. Real-Time UX

Exactly one real-time mechanism is decided end-to-end: **Server-Sent
Events, not WebSocket**, for seat availability (ADR-022 + the
seat-availability-live-updates flow doc). A second real-time feature —
queue position — is named but its transport is explicitly still an open
"WebSocket or SSE" choice in `queue-service.md`, not resolved by any ADR.
Don't conflate the two.

## Seat availability (confirmed: SSE)

```
Postgres commit (seat HELD/AVAILABLE/PURCHASED)
        │
        ▼
Redis Pub/Sub  channel: session:{sessionId}:seat-updates
        │  (fire-and-forget, no durability, no replay)
        ▼
inventory-service instance holding the open SSE connection
        │
        ▼
Browser EventSource — two event types:
  "seat-updated" {seatId, status}   → broadcast to everyone on that session's map
  "hold-expired" {seatId}           → targeted, only the affected user's connection
        │
        ▼
React: update just that one seat tile's local state. No refetch.
```

Degrades gracefully: if Redis Pub/Sub is down, the seat map simply stops
getting live pushes — hold/purchase correctness is untouched, since
Postgres is still the source of truth. The UI's fallback is the
client-side countdown and a manual refresh, not a broken booking flow.

## Connection admission (why an SSE connection can be refused)

Four independent caps exist before a connection is even accepted:
per-user connect-rate (gateway), per-IP concurrent connections (Nginx),
per-instance open-SSE count (in-memory, rejects with `503 + Retry-After`
at capacity), and an aggregate metric feeding autoscale. The
frontend-relevant one is the 503: **retry with backoff, don't treat it
as "seat map broken."**

## Queue position (TBD transport)

`queue-service.md`'s target design says live position updates use
"WebSocket or SSE... instead of polling" — genuinely undecided which.
Given ADR-022 already built the SSE admission-control machinery for the
seat map, SSE is the more consistent choice architecturally, but this
isn't being decided here — marked TBD in §20, should get its own line in
whichever ADR eventually covers it.

## Where real-time is deliberately NOT used

Booking status, ticket issuance, notification delivery — all async via
Kafka consumption and polling/refetch on the relevant page, not pushed
live. Nothing in the ADRs asks for a live-updating "My Tickets" list; a
ticket appearing after a short "issuing…" wait, discovered on next load
or a manual refresh, is the documented behavior.

---

# 9. Search UX

What exists today is intentionally small — search-service currently
supports exactly one thing: title substring match. Everything past that
is undesigned, not hidden.

```
Search (built)
├── Text input → GET /api/v1/events?q={query}
│     empty query → GET /api/v1/events (all events)
├── No filters (date/location/genre/price) — schema not designed yet
├── No sort — results return in index order
├── No pagination — full result set every time
└── Empty state: "No events match that search."
```

The search index is **deliberately eventually-consistent** —
`search-service.md` states plainly a brand-new event may take a few
seconds to appear after an organizer publishes it, via the Kafka
projection lag. That's a documented tradeoff, worth a small "just
published — may take a moment to appear" note in the organizer's own
create-event success state, not something to hide.

`search-service.md`'s own Open Questions list facet/filter schema (date,
location, genre, ticket type, accessibility) and venue-data
denormalization as undecided — genuinely nothing to design a filter
panel against yet. When that lands, the frontend's job is mapping filter
chips to whatever query params the ADR eventually defines, not the other
way around.

---

# 10. Admin / Organizer UX

## Organizer dashboard — what's real vs. decided

No dashboard "metrics" exist in any ADR beyond what `analytics-service.md`
actually specifies: **organizer-scoped sales queries, cross-org isolation
enforced**. Nothing is being fabricated here — until analytics-service
exists, an organizer's dashboard is legitimately just their events list,
which is what's built today.

## Event management

Create → edit → publish → cancel, all scoped to `organizer_id`. Sessions
and artists nest under an event. The one real UX consequence of the
missing session-list endpoint: an organizer who reloads mid-setup loses
their in-progress session list from the screen (not from the database —
the sessions exist, there's just no page that can read them back). Worth
a visible "reload will hide sessions you've already added (they're
saved)" note until that endpoint exists.

## Admin console

Everything here is either audited (`AdminActionPerformed`) or explicitly
requires a human in the loop before it fires — ADR-014's bulk-cancel is
blunt about it: *"a false positive here cancels a real fan's tickets,
worse than one scalper getting through."* That single sentence should
shape the bulk-cancel review page directly: show the evidence
(card-fingerprint clustering, device clustering, timing uniformity)
before the confirm button, never a one-click "cancel all flagged."

Reconciliation drift (ADR-035's nightly Stripe-vs-ledger job) and the
`PaidUserUnresolved` / `mass_cancellation_stuck_count` alerts are
**ops-facing, not app-facing** — this project already has a real Grafana
alerting stack (ADR-015) for exactly this class of signal. Building a
second in-app "alerts" page would duplicate infrastructure that already
exists — not doing that.

---

# 11. Error & Edge-Case UX

| Backend condition | Signal | Frontend behavior |
|---|---|---|
| Bad login | 401, one generic body for wrong-password / unknown-email / locked account alike | One generic message always — never differentiate, that differentiation is the exact oracle the backend spent effort closing |
| Too many login attempts (fast window) | 429 `TooManyLoginAttemptsException` | "Too many attempts, try again shortly" — no exact unlock time shown (backend doesn't expose it either) |
| Any JWT failure (expired/bad sig/unknown kid/revoked) | 401, identical shape for all four | Route to login, preserve `from` location for redirect-back |
| Cross-user resource access (e.g. another user's payment method) | 404, not 403 | Render as "not found," never "forbidden" — matches the backend's deliberate anti-enumeration design |
| Idempotent retry, same key + body, still in flight | 409 + `Retry-After` header | Poll/retry after the given delay, or show current booking state — never a raw error |
| Idempotency key reused with a different body | 422 | Client bug signal — regenerate the key and retry as a fresh request, don't surface raw 422 text |
| Seat taken between selection and hold request | Hold request fails / SSE flips the tile first | Reselect prompt, seat map already reflects reality |
| Hold expired | SSE `hold-expired` + local timer both agree | "Your hold expired," return to seat selection |
| Payment failed | Saga compensates step 1 only (hold released) | Stay on checkout, allow retry, seats are gone though — reselect if hold also expired by then |
| Payment succeeded, hold already expired | Saga compensates step 2 (refund) | Exact copy: "seat was taken while your payment was processing, fully refunded" |
| Bot/abuse suspected | Deliberately looks like an ordinary outcome — "anti-tell principle" | Frontend must render these identically to real "sold out"/slow responses — there is no special bot-blocked UI state to design, by design |
| SSE connection capacity hit | 503 + `Retry-After` | Backoff retry, seat map shows "reconnecting…" not "broken" |
| Search-service unreachable | Network/5xx on `GET /api/v1/events` | "Couldn't load events right now" + retry — never blocks the rest of the app |
| Rate-limited (any authenticated route) | 429, role-tiered bucket | Generic "slow down" message, no bucket internals exposed |
| Reconciliation-flagged / disputed payment | Ticket flips to `DISPUTE_HOLD` | Barcode stops validating; customer sees an explanatory state, not a silent failure at the venue door |
| Event cancelled by organizer/admin | `event.cancelled` → async per-booking compensation | Affected users see their booking move to a refunded/cancelled state — timing (immediate vs batched) is **genuinely undecided** (ADR-028 Open Question), don't promise instant notification in copy |

---

# 12. Route Architecture

```
/                                    Home / Discover          built
/login                                                        built
/register                                                     built
/account                            Profile / prefs / sign out built
/events/:eventId                    Event Details             decided
/events/:eventId/sessions/:id/seats Seat Selection             decided
/checkout/:bookingId                Checkout                   decided
/checkout/:bookingId/confirmation   Booking Confirmation        decided
/queue/:sessionId                   Waiting Room                 decided
/tickets                            My Tickets                    decided
/tickets/:ticketId                  Ticket Detail                  decided
/bookings                           Booking History                 decided

/organizer/events                                              built
/organizer/events/new                                          built
/organizer/events/:id                                          built
/organizer/artists                                             built
/organizer/venues                                               decided*
/organizer/venues/:id                                            decided*
/organizer/analytics                                              decided
/organizer/disputes                                                decided

/admin/users                                                    decided
/admin/keys                                                      decided
/admin/bot-review                                                  decided
/admin/cancellations                                                decided
/admin/disputes                                                       decided
/admin/audit-log                                                       decided
/admin/roles                                                            decided
/admin/roles/:id                                                        decided
```

\* Venue CRUD backend is built; the current frontend doesn't route to it
yet — routes marked "decided" above mean the underlying capability is
confirmed, not necessarily that the route exists in `main.tsx` today.
Cross-reference §5's status per page.

---

# 13. Page Specifications

Full spec for the pages where the shape genuinely matters — booking's
critical path, plus one representative organizer and one representative
admin page. The remaining pages follow the same CRUD/list/detail shape
already established in §5 and don't need repeating in full.

## Seat Selection
`/events/:eventId/sessions/:sessionId/seats` · **[DECIDED, not built]**

- **Roles:** Customer, organizer, admin (anyone authenticated)
- **Data required:** Seat map geometry (venue-service), live seat states
  (inventory-service), session price tiers
- **API calls:** Seat map fetch (REST), hold request (REST, per seat),
  SSE subscribe `session:{sessionId}:seat-updates`
- **User actions:** Select/deselect seat, view price by tier, proceed to
  checkout
- **States:** loading map · seat AVAILABLE · seat HELD (mine) · seat HELD
  (other) · seat PURCHASED · hold expiring <30s · hold expired · no
  seats available
- **Real-time:** Yes — SSE, see §8
- **Navigation:** Forward to Checkout on selection; back to Event Details

## Checkout
`/checkout/:bookingId` · **[DECIDED, not built]**

- **Roles:** Customer, organizer, admin
- **Data required:** Held seats + price, remaining hold time, saved
  payment methods (optional)
- **API calls:** Saga start (booking-service), Stripe Payment Element
  mount (client-side, iframe), hold-extension checkpoint (implicit,
  server-side on submit)
- **User actions:** Enter/select payment method, submit, retry on
  decline
- **States:** loading · ready to pay · processing · declined · hold
  expired mid-payment · success
- **Real-time:** No push; countdown is local, hold-expired still arrives
  via the same SSE connection if still open
- **Navigation:** Success → Confirmation. Failure → stay, or back to
  Seat Selection if seats are gone

## My Tickets → Ticket Detail
`/tickets/:ticketId` · **[DECIDED, not built]**

- **Roles:** Ticket owner only
- **Data required:** Ticket status, live barcode seed, transfer/resale
  eligibility
- **API calls:** Ticket fetch, barcode regeneration (live, ~15s cadence,
  in-app only), transfer/list-for-resale mutation
- **User actions:** Show barcode at venue, transfer free, list for
  resale (price capped server-side)
- **States:** NONE (valid) · LISTED_FOR_RESALE · TRANSFER_PENDING ·
  DISPUTE_HOLD
- **Real-time:** No — barcode regenerates on a local timer, not pushed
- **Navigation:** Back to My Tickets; resale purchase flow reuses
  Checkout's Stripe iframe

## Organizer · Event Detail
`/organizer/events/:id` · **[PARTIAL — built, sessions list gap]**

- **Roles:** Owning organizer, or admin (bypasses ownership)
- **Data required:** Event fields, sessions (currently component-state
  only, not persisted-read), artists
- **API calls:** `GET/PUT /organizer/events/:id`, `POST .../sessions`,
  `POST .../cancel`
- **User actions:** Edit, cancel, add session, link artist
- **States:** DRAFT · PUBLISHED · CANCELLED · not found (404, not-yours
  or nonexistent, identical)
- **Real-time:** No
- **Navigation:** Back to Events list; forward to purchase-limit config,
  trailer upload (once built)

## Admin · Bot-Order Review
`/admin/bot-review` · **[DECIDED, not built]**

- **Roles:** Admin only
- **Data required:** Flagged-order evidence: card-fingerprint
  clustering, device fingerprint clustering, purchase-timing uniformity
- **API calls:** Fetch flagged batch, submit sign-off → triggers
  per-booking compensation (refund + void + re-release seat) fan-out
- **User actions:** Review evidence per flagged batch, approve or
  dismiss — never a blind bulk button
- **States:** pending review · approved → compensating · dismissed
- **Real-time:** No
- **Navigation:** Every approval logs to Audit Log

---

# 14. Reusable Component Architecture

Derived from actual repetition across the pages above — not a generic
React starter-kit list.

| Component | Purpose |
|---|---|
| `EventCard` | Grid tile — title, status pill, region. Used on Home and (eventually) search results. |
| `SeatMap` | Hand-rolled SVG + CSS keyed on `[data-status]`, not utility classes — SSE updates flip an attribute, not re-render classes. |
| `Seat` | Single tile, four visual states, click-to-select. |
| `CountdownTimer` | Client-computed from a timestamp prop. Used on Seat Selection and Checkout. |
| `StatusBadge` | Booking/ticket/event status → color + label. One mapping table, every page reuses it. |
| `BarcodeDisplay` | Live-rendering rotating barcode, ~15s refresh. Ticket Detail only. |
| `PaymentForm` | Wraps Stripe Payment Element — the only component allowed near card data, and even it never touches raw values. |
| `BookingSummary` | Seats + price breakdown. Shared by Checkout and Confirmation. |
| `OwnershipGate` | Wraps organizer resource pages — redirects on a 404 (not 403), matching the backend's own anti-enumeration shape. |
| `RoleGate` | Hides nav/route entries by role — UX only, never the real boundary. |
| `AuditLogRow` | `AdminActionPerformed` event → who/what/when. Admin console only. |
| `FormField` | Already exists (organizer forms) — reused everywhere a labeled input appears. |
| `EmptyState` | Per-page copy, not a generic icon+text — see §11 for tone. |
| `Pagination` | Not yet needed anywhere — search-service returns full result sets. Build when facets/paging land. |

---

# 15. Frontend State Architecture

Already decided in `frontend.md` and the implementation roadmap —
restated here mapped to this product's actual state, not abstract
categories.

| Layer | Tool | Holds |
|---|---|---|
| Server state | TanStack Query | Events, sessions, bookings, tickets, profile, seat-map geometry (not live status) |
| Client state | Zustand — small stores by concern | `authStore` (session/token, built), `seatSelectionStore` (in-progress picks + live SSE status), `queueStore` (admission token/position), `checkoutStore` (saga step, `Idempotency-Key`) |
| Real-time state | Zustand, written from outside React | `EventSource.onmessage` writes directly into `seatSelectionStore` — no context plumbing of the connection itself |
| URL state | React Router params/search params | Selected event/session/booking ids — never duplicated into a store |

Rule carried over from `frontend.md`, worth restating because Seat
Selection is exactly the case it was written for: **subscribe with a
selector** (`useSeatStore(s => s.seats[id])`), never the whole store — a
400-seat map re-rendering on every SSE tick would be the one place this
app could visibly jank.

---

# 16. API Integration Map

| Frontend page | Service | Endpoint(s) | Auth |
|---|---|---|---|
| Home / Discover | search-service | `GET /api/v1/events`, `GET /api/v1/events?q=` | None (public) |
| Login / Register | auth-service | `POST /api/v1/auth/login`, `/register`, `/refresh` | None |
| Account | user-service | `GET/PUT /api/v1/users/me`, `/me/preferences`, `/me/payment-methods` | Bearer |
| Organizer Events/Sessions/Artists | event-service | `/api/v1/organizer/events/**`, `/artists/**` | Bearer, ORGANIZER |
| Organizer Venues | venue-service | `/api/v1/organizer/venues/**` | Bearer, ORGANIZER |
| Seat Selection | inventory-service *(not built)* | Seat map read, hold write, SSE subscribe | Bearer |
| Checkout | booking-service, payment-service *(not built)* | Saga start, Stripe Payment Element (client-direct to Stripe) | Bearer + `Idempotency-Key` |
| My Tickets / Ticket Detail | ticket-service *(not built)* | List/get ticket, transfer, list-for-resale | Bearer |
| Waiting Room | queue-service *(not built)* | Join queue, admission token issuance, position updates | Bearer |
| Admin · Users | auth-service | `POST /admin/users/{id}/ban` | Bearer, ADMIN |
| Admin · Key rotation | auth-service | `RotationAdminController` endpoints | Bearer, ADMIN |
| Admin · Bot review / Cancellations / Disputes | inventory-, booking-, payment-service *(not built)* | Per ADR-014 §7, ADR-028, ADR-035 | Bearer, ADMIN |

Every frontend call goes to a relative `/api` path routed through
api-gateway — never a direct service URL. That's already the enforced
convention in this codebase (Vite's dev proxy is the only exception, and
only in dev).

---

# 17. Frontend Architecture Diagram

```
                         React Application (Vite + TS)
                                     │
        ┌───────────────┬───────────┴───────────┬───────────────┐
        │               │                        │               │
      Pages         Feature modules           State layer     API layer
   (route-level)   (browse/, organizer/,     TanStack Query   fetch wrapper
                    admin/, booking/,          + Zustand      → relative /api
                    tickets/, account/)        stores           paths only
        │               │                        │               │
        └───────────────┴───────────┬────────────┴───────────────┘
                                     │
                          RoleGate / OwnershipGate
                          (UX-only — backend is the real boundary)
                                     │
                     ┌───────────────┼────────────────┐
                     │               │                 │
                  REST (fetch)   SSE (EventSource)   Stripe iframe
                     │               │                 │  (never touches
                     ▼               ▼                 ▼   this app's JS)
                        api-gateway  (JWT validation, rate limits,
                          revocation check, role-tiered routing)
                     │
     ┌────────┬───────┼────────┬─────────┬──────────┬───────────┬──────────┐
     │        │       │        │         │          │           │          │
   auth    user    event     venue    search    inventory*   booking*   payment*
 service  service service   service  service    service      service    service
                                                  *decided, not built yet
```

---

# 18. Product-Level UX Flow

```
User
 │  clicks a seat on the map
 ▼
Page (Seat Selection)
 │  onClick handler
 ▼
Action (hold request, POST)
 │
 ▼
API (inventory-service, via api-gateway)
 │  Redis SETNX fast-reject → Postgres SELECT...FOR UPDATE
 ▼
Backend state change
 │  seat row: AVAILABLE → HELD, held_until = now()+5min
 │  Redis Pub/Sub publish (fire-and-forget)
 ▼
UI update
 │  optimistic tile flip + countdown starts
 │  (SSE confirms/corrects for every OTHER connected client)
 ▼
User sees: their seat, highlighted, ticking down.
```

---

# 19. ADR → Frontend Traceability

| ADR / Decision | Frontend impact | Pages affected | Components affected |
|---|---|---|---|
| ADR-002 (seat locking) | Seat states, hold TTL, client-side countdown, no server-tick timer | Seat Selection, Checkout | SeatMap, Seat, CountdownTimer |
| ADR-006 (booking saga) | Two distinct failure copy paths; exact refund message string | Checkout, Confirmation | BookingSummary, PaymentForm |
| ADR-009 (service-to-service auth) | Gateway strips client-sent auth headers — confirms the frontend never needs to set `X-User-Assertion` itself | All | API layer |
| ADR-011 (PCI scope) | Card fields MUST be Stripe's iframe — this app's JS structurally cannot touch raw card data | Checkout, Account · Payment methods | PaymentForm |
| ADR-012 (JWT lifecycle) | 10-min access token, silent refresh via httpOnly cookie, revocation → forced 401 | All authenticated pages | SessionGate, ProtectedRoute (built) |
| ADR-013 (GDPR crypto-shredding) | Erasure requires step-up re-auth; export produces a signed one-time download link | Account · Data export/erasure | (new) ErasureConfirmDialog |
| ADR-014 (anti-bot/anti-scalper) | Anti-tell principle constrains ALL blocked-request copy; purchase-limit config UI; queue admission UI; bot-review evidence UI; live-barcode requirement | Waiting Room, Checkout, Organizer · Purchase Limits, Admin · Bot Review, Ticket Detail | BarcodeDisplay, EmptyState copy |
| ADR-020 (payment ledger) | Payment status is derived, not overwritten — frontend should treat status as eventually-settled, not instantaneous | Checkout, Booking History | StatusBadge |
| ADR-021 (notify-me / broadcast) | Per-session (not per-event) signup; anonymous email fallback; push may simply never arrive (TTL) — needs in-app fallback messaging | Event Details, Notify-Me signups | (new) NotifyMeButton |
| ADR-022 (SSE admission control) | 503 + Retry-After is a real, expected response — not an error state | Seat Selection | SeatMap (reconnect logic) |
| ADR-025 (idempotency-key policy) | Client must mint the key ONCE per logical action and persist it across retries — a real frontend implementation obligation, not just a header to set once | Checkout | checkoutStore |
| ADR-028 (event cancellation) | Refund timing to users is undecided — don't promise immediacy in copy | My Tickets, Booking History, Admin · Cancellations | StatusBadge, EmptyState |
| ADR-029 (ticket transfer/resale) | Live barcode (never static/PDF); resale price capped server-side, UI should reflect the cap, not just accept any number | Ticket Detail | BarcodeDisplay, (new) ResaleListingForm |
| ADR-030 (organizer/admin authorization) | Two-layer model: coarse role gate + fine ownership. Cross-org access is 404, not 403. Admin bypasses ownership visibly (sees everyone's data) | All organizer + admin pages | RoleGate, OwnershipGate |
| ADR-043 (dynamic role/permission system) | `RoleGate` → `PermissionGate`, checks permission keys not role strings; `LoginResponse.user` gains `permissions: string[]`; two new admin pages for role/permission management | Admin · Roles, Admin · Role Detail, Admin · Users (role assignment), every RoleGate consumer | PermissionGate (renamed from RoleGate) |
| ADR-034 (REST edge versioning) | All calls stay on `/api/v1`; a v2 migration would be additive, not a breaking rewrite of the API layer | All | API layer |
| ADR-035 (reconciliation/dispute) | `DISPUTE_HOLD` ticket state must render distinctly (barcode invalid) from ordinary cancellation | Ticket Detail, Admin · Disputes, Organizer · Disputes | StatusBadge |
| ADR-039 / ADR-040 (login rate limiting) | 429 on fast-window; 15-min DB lockout renders through the SAME generic 401 as a wrong password — frontend must not special-case it | Login | (existing) LoginPage |
| seat-availability-live-updates.md | SSE, not WebSocket, for the seat map specifically | Seat Selection | SeatMap |

---

# 20. Open Questions / TBD

Only things genuinely undecided anywhere in the docs — not a place to
sneak in a guess.

- ~~Dynamic, admin-configurable role/permission system~~ — **resolved**
  by [[ADR-043-dynamic-role-permission-system]]: fixed permission
  catalog, admin-configurable roles, propagated via ADR-012's existing
  revocation topology. §2, §5, §12, §19 above updated accordingly. Not
  yet built — genuinely open only on *when* it gets implemented, not
  *how*.
- **Queue-position real-time transport.** `queue-service.md`: "WebSocket
  or SSE... instead of polling" — not resolved by any ADR, unlike the
  seat map's confirmed SSE choice.
- **Refund timing/communication on organizer/admin-triggered
  cancellation.** ADR-028 Open Question, verbatim: "immediate vs.
  batched notification — product-level choice, not yet decided." Directly
  affects what the cancellation-confirmation UI can promise.
- **Search filters/facets and sort.** `search-service.md`: date,
  location, genre, ticket-type, accessibility filters — schema
  undesigned. No sort order decided. Nothing to build a filter panel
  against yet.
- **Seller payout mechanism for resale.** ADR-029's own largest flagged
  gap — likely needs Stripe Connect or equivalent, not decided. Affects
  whether Ticket Detail's resale flow can promise a payout timeline.
- **True-anonymous notify-me signup erasure.** ADR-021: an email-only
  (no account) signup has no `subject_id` to run ADR-013's erasure saga
  against. Encrypting the email doesn't solve a request to erase "by
  email address alone." Genuinely open — affects whether the anonymous
  Notify-Me flow can promise GDPR erasure at all.
- **GDPR erasure/export endpoint — implementation status.** Fully
  designed in ADR-013, but no project page confirms it as built or
  not-built. Verify against actual user-service code before building the
  Account · Data export/erasure page — don't assume either way.
- **Whether organizer actions get their own audit trail.** ADR-030 Open
  Question: `AdminActionPerformed` currently implies admin-only. Affects
  whether an organizer-facing "activity log" page is ever justified.
- **Seat state naming: is there a real `EXPIRED` value?**
  `inventory-service.md`'s prose implies one; ADR-002's actual
  schema/pseudocode never stores one (expiry is just `HELD → AVAILABLE`
  on a sweep). §7 above is designed against the ADR as authoritative —
  worth a one-line confirmation before backend work starts, since it's
  cheap to settle and currently just a documentation inconsistency.
- **Bulk-cancel / work-queue fan-out mechanism.** ADR-028: "Kafka
  message-per-booking vs. a work-queue table with a polling worker — both
  viable, not chosen." Backend implementation detail, no frontend impact
  either way — listed for completeness.
