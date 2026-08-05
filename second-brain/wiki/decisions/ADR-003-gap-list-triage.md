---
title: ADR-003 Gap List Triage — Fraud/Analytics Services vs Folded Features vs Deferred
type: decision
sources: []
related: [[ADR-001-microservices-vs-modular-monolith]], [[system-overview]], [[cross-cutting-concerns]]
created: 2026-08-05
last-updated: 2026-08-05
---

Status: Accepted

# Context

Follow-up review surfaced a longer feature gap list against the real
Ticketmaster feature set: multi-currency/tax, refunds/cancellations, group
booking limits, loyalty, season passes/bundles, add-ons (parking),
dynamic/surge pricing, CAPTCHA/bot detection, organizer analytics, audit
log, CDN, SMS/push channel split, idempotency, distributed tracing,
feature flags, GDPR, and dedicated fraud detection. Need to decide, per
item: new service, folded into an existing service, cross-cutting concept,
or explicitly deferred — without defaulting to "add a service for every
noun" (see [[ADR-001-microservices-vs-modular-monolith]] discipline).

# Requirements / Constraints

Same as ADR-001: every service must have an independent scaling/ownership
justification, not exist because it sounds complete.

# Options Considered

Per item, the real option was always "own service" vs "feature of an
existing service" vs "cross-cutting concept, no service." Evaluated
individually below rather than as a single either/or.

# Decision

## New services (2)

- **`fraud-service`** — real-time risk scoring: device fingerprinting,
  purchase velocity checks, bot detection, enforces bulk-purchase limits.
  Called synchronously by both `queue-service` (admission-time scoring)
  and `booking-service` (checkout-time scoring) — a shared judgment call
  used by two independent call paths, with its own low-latency signal
  store (velocity counters), justifies its own service rather than living
  inside either caller.
- **`analytics-service`** — organizer sales dashboards, real-time
  sell-through rate, reporting. Different consumer (organizers, not
  buyers), different read pattern (aggregation, not OLTP), must never sit
  on the booking write path — pure async Kafka consumer of
  booking/payment/ticket events. Independent scaling/tech justification
  (may use a column store later).

## Folded into existing services (no new service)

- **Multi-currency / tax** → `event-service` (price fields per
  currency/locale) + `payment-service` (tax calc at charge time). Data
  modeling concern, not a scaling concern.
- **Refunds / cancellations** → `payment-service` (refund execution),
  triggered by `event-service`'s `EventCancelled`, orchestrated through
  `booking-service`. Not a new service — it's the existing payment state
  machine's failure/reversal path.
- **Group booking / bulk purchase limits** → enforced in
  `booking-service`/`inventory-service` (max N per checkout), detected by
  `fraud-service` (velocity across accounts/devices).
- **Loyalty / rewards** → `user-service` extension (points ledger).
  Deferred to a later phase — not core MVP.
- **Multi-day / season passes / bundles** → data modeling in
  `event-service` (a bundle references multiple sessions) and
  `ticket-service` (one ticket entity can grant multiple session
  entries). Not a new service.
- **Parking passes / add-ons** → generalize `inventory-service`'s state
  machine to "inventory item" (seat is one item type, parking pass is
  another) rather than a separate service — same concurrency problem,
  same owner.
- **CAPTCHA / bot detection** → signal feeds into `fraud-service`;
  enforcement point is `queue-service` (admission gate).
- **SMS / push channel split** → already `notification-service` design
  (channel-per-transport), no change needed.

## Cross-cutting concepts (documented once, not a service)

See [[cross-cutting-concerns]] for idempotency keys, distributed
tracing/observability, feature flags, GDPR/data deletion, audit
logging, and CDN — each applies across most/all services, none justifies
its own deployable.

**Audit log specifically decided NOT to be a standalone service**: no
independent scaling reason: it's a write-time concern of whichever service
performed the admin action (each service writes its own audit table +
emits an `AdminActionPerformed` event for `analytics-service` to
aggregate). A dedicated audit-service would just be another database with
no distinct read/write pattern of its own at this project's scale.

## Explicitly deferred, not decided now

- **Dynamic/surge pricing engine** — real feature, would justify its own
  service (`pricing-service`) *if built*, per the user's own reasoning
  (needs an audit trail of price changes). Not building now — no ADR
  written for it, no service directory created, since inventing the
  decision before it's needed would violate the "don't fabricate
  decisions" rule. Tracked as an open question on `event-service`.

# Why

Matches ADR-001's standard: every service added here (fraud, analytics)
has a concrete, distinct scaling/ownership/consumer reason. Everything else
either is naturally owned by an existing service's data, or is a pattern
applied consistently across services (cross-cutting), not a domain of its
own.

# Consequences

**Easier:** feature list from the gap review is now fully accounted for —
nothing silently dropped. Fraud and analytics get proper isolation
(fraud's low-latency requirement, analytics' async/aggregation
requirement) instead of being bolted onto booking-service.

**Harder:** two more services to stand up and operate (14 backend services
total now). `fraud-service` sits in the synchronous checkout/queue path —
its latency and availability now directly affect booking, so it needs
tight SLOs and a fail-open-or-closed decision (open question below).

# Revisit When

- If `fraud-service` becomes a latency bottleneck in the checkout path,
  revisit whether risk scoring can be partially async (score after
  admission, block only on high-confidence signals) rather than fully
  synchronous.
- If pricing needs actually materialize, write a dedicated ADR for
  `pricing-service` at that time — don't retroactively justify this ADR
  as having decided it.

## Open Questions

- `fraud-service` fail-open vs fail-closed if it's unavailable — not
  decided (fail-open risks bot abuse, fail-closed blocks all checkouts on
  a fraud-service outage).
- Dynamic/surge pricing — deferred, see above.
