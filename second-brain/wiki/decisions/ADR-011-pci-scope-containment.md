---
title: ADR-011 PCI-DSS Scope Containment
type: decision
sources: []
related: [[payment-service]], [[user-service]], [[cross-cutting-concerns]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`user-service.md` lists "saved payment methods on file." `system-overview.md`
calls this "tokenized references only, never raw card data" — correct
direction, but "reference" is ambiguous between two designs whose PCI
scope differs by an order of magnitude: **SAQ A** (~30 controls) vs
**SAQ D** (~300 controls, quarterly ASV scans, annual pen test, network
segmentation validation).

# Requirements / Constraints

- Raw card data (PAN, CVV) must never be stored — table stakes, not the
  hard part.
- The harder trap: a PAN must never **transit** through this system's own
  servers, even momentarily, even if nothing is stored. If an HTML page's
  own `<input name="card_number">` POSTs to *your* server — even if you
  immediately forward it to Stripe and store nothing — every system in
  that request path (Nginx, api-gateway, payment-service, all their logs,
  all their hosts) enters PCI scope.

# Options Considered

## A — Stripe Payment Element / Checkout (iframe-isolated)

Card fields render inside a Stripe-origin iframe; the browser posts
directly to Stripe; this system's JS cannot read those fields
(cross-origin). No PAN ever reaches Nginx, api-gateway, or
payment-service. Saving a card = client-side `SetupIntent` confirmation ->
Stripe returns `pm_xxx` -> backend receives only the opaque identifier.
**SAQ A.**

## B — Stripe.js `createPaymentMethod` with own `<input>` fields (non-iframe)

Rejected. The page still handles the values -> **SAQ A-EP**, and a
compromised JS dependency exfiltrates cards directly (Magecart-class
attack). The iframe boundary is the entire point of A; this discards it.

## C — Self-hosted card vault

Rejected outright. Becomes a card-storage entity -> SAQ D plus key-
management liability plus breach-notification exposure, for zero learning
upside proportional to the risk. No real company does this without a
compelling reason this project doesn't have.

# Decision

**Option A.** Provider-hosted collection, this system never touches a
PAN. (Braintree/Adyen hosted fields are equivalent architecturally —
provider choice is a DX/fee decision, not a security one; Stripe assumed
throughout for concreteness, resolving `payment-service.md`'s open
question on developer-experience grounds.)

Even at SAQ A, **PCI-DSS v4.0 requirements 6.4.3 and 11.6.1 still apply**:
inventory and integrity-check every script on the payment page, detect
unauthorized header/page changes. Enforce with a strict
`Content-Security-Policy` (`script-src` allowlisting `js.stripe.com`,
`frame-src https://js.stripe.com`), Subresource Integrity on third-party
scripts, and CSP violation reporting — the one control SAQ A systems
consistently miss.

## What each service actually stores

**payment-service (sole holder of provider identifiers):**
```sql
saved_payment_method
  id                    UUID PK        -- internal, the only thing shared
  user_id               UUID           -- pseudonymous FK, no PII
  provider              TEXT           -- 'stripe'
  provider_customer_id  TEXT           -- cus_xxx
  provider_pm_id        TEXT           -- pm_xxx
  brand                 TEXT           -- 'visa'
  last4                 CHAR(4)
  exp_month, exp_year   INT
  card_fingerprint       TEXT          -- Stripe's cross-account card hash
  created_at, deleted_at
```

**user-service (display + ordering only, NOT the provider token):**
```sql
user_payment_method_ref
  id                    UUID PK
  user_id               UUID
  payment_method_id     UUID           -- opaque; meaningless outside
                                        -- payment-service
  display_label         TEXT           -- "Visa ****4242" (denormalized)
  is_default             BOOLEAN
  sort_order             INT
```

Why the extra indirection: `pm_xxx` + the platform's own API key is the
ability to charge — a provider token is not a harmless low-sensitivity
identifier, and letting it spread across services quietly recreates the
problem tokenization was meant to solve. One service holds one class of
secret; provider migration (Stripe -> Adyen) touches one service, not
two. `last4`/`brand` are explicitly **not** cardholder data under PCI
(truncation to last-4 is the sanctioned display form) — safe to
denormalize into user-service without a cross-service call on every
profile page load.

`card_fingerprint` is Stripe's stable hash of the underlying card across
customers — the single strongest cross-account correlation signal in the
system, load-bearing for anti-fraud purchase limits. Lives in
payment-service only; other services query it by reference, never store
it.

## Hard rules, enforced as CI checks

- No Avro schema registered under [[ADR-007-kafka-event-schema]] may ever
  contain a field named for card data — CI greps registered schemas for
  `pan|card_number|cvv|cvc|card_num`.
- Logback pattern-layout scrubber on a 13-19-digit regex, applied at the
  appender level across all services — the real failure mode is always an
  exception message or a logged request body, never a deliberate
  `log.info(pan)`.
- CVV/CVC is never stored by anyone, including Stripe, post-authorization.
  Non-negotiable.
- Correlation IDs ([[cross-cutting-concerns]]) must be random UUIDs, never
  derived from user or card identifiers.

# Why

SAQ A is achievable for free by never letting a PAN transit this system's
own servers — the iframe boundary does that structurally. Any design that
lets this system's own code see raw card values, even transiently, forces
SAQ D for no functional benefit.

# Consequences

**Easier:** annual compliance burden is ~30 controls instead of ~300; a
compromised service can never leak stored card data because none exists
here; provider migration is a payment-service-only change.

**Harder:** user-service needs a cross-service call (or event-driven
denormalization) to render "Visa ****4242" rather than owning the data
outright; CSP/SRI discipline on the checkout page must be maintained as
an ongoing control, not a one-time setup.

# Revisit When

- If a payment provider without iframe-isolated hosted fields is ever
  considered — that would reopen the SAQ A assumption entirely.

## Open Questions

- None outstanding.
