---
title: ADR-029 Ticket Transfer and Resale Consistency
type: decision
sources: []
related: [[ADR-014-anti-bot-anti-scalper]], [[ADR-002-seat-locking-strategy]], [[ADR-006-saga-booking-orchestration]], [[ADR-011-pci-scope-containment]], [[ADR-020-payment-event-ledger]], [[ticket-service]]
created: 2026-08-10
last-updated: 2026-08-10
---

Status: Accepted

# Context

[[ADR-014-anti-bot-anti-scalper]]'s layer 8 makes transfer-only delivery
and price-capped in-platform resale load-bearing anti-scalper
infrastructure, but `ticket-service.md` describes the mechanism only as
"a ticket ownership state transition" — no state machine, no
compensation, no handling for the case that actually matters: a **paid**
resale involves a second payment, a second party, and a real race (two
buyers attempting to purchase the same listed ticket simultaneously) —
the same class of concurrency problem [[ADR-002-seat-locking-strategy]]
already solved for original seat sales, never extended here.

# Requirements / Constraints

- Must prevent two simultaneous transfer/purchase attempts on the same
  ticket (a real race, not hypothetical, for a popular resale listing).
- Must keep resale payment inside the same PCI-safe pattern as original
  purchase — a peer-to-peer payment outside the platform would both
  reopen PCI scope and defeat the price cap ADR-014 depends on.
- Must enforce the resale price cap **server-side** at listing time, not
  trust the client.
- Must log every transfer — ADR-014 already states this feeds its
  layer-7 fraud-chain detection.
- Must not require re-inventorying the seat — this is ownership of an
  already-issued ticket changing hands, a different domain than
  inventory-service's seat-sale concern.

# Options Considered

## A — Ticket ownership as a bare `UPDATE`, no state machine

Pros: simplest to imagine. Cons: no protection against the concurrent
double-transfer race; a payment failure mid-resale can leave the ticket
in an ambiguous state (buyer charged, ownership not transferred, or the
reverse) with no defined recovery.

## B — A lightweight transfer/resale state machine, reusing ADR-002's row-lock for the ownership flip and ADR-006's Saga shape (simplified) for the paid case

Pros: reuses two already-trusted mechanisms instead of inventing new
concurrency primitives; free transfer degrades naturally to the simple
2-state case of the same machine, no separate code path.

## C — Route resale through booking-service as a new "booking" against a special resale inventory item

Pros: none specific. Cons: conflates two different domains —
inventory-service/booking-service own *seat* inventory (which, for an
already-sold ticket, no longer exists as sellable inventory); forcing
resale through a seat-sale-shaped saga is the wrong domain fit.

# Decision

**Option B.**

## Schema

```sql
ALTER TABLE tickets ADD COLUMN transfer_status TEXT DEFAULT 'NONE';
  -- NONE | LISTED_FOR_RESALE | TRANSFER_PENDING
ALTER TABLE tickets ADD COLUMN listed_price NUMERIC;
ALTER TABLE tickets ADD COLUMN pending_payment_intent_id TEXT;

CREATE TABLE ticket_transfer_log (
  id                 UUID NOT NULL,
  event_id           UUID NOT NULL,   -- Citus distribution column
  ticket_id          UUID NOT NULL,
  from_user_id       UUID NOT NULL,
  to_user_id         UUID NOT NULL,
  transfer_type      TEXT NOT NULL,   -- FREE | RESALE
  price              NUMERIC,
  transferred_at     TIMESTAMPTZ DEFAULT now(),
  PRIMARY KEY (event_id, id)
);
```

## Free transfer (gift)

```
1. SELECT ... FOR UPDATE on the ticket row — same primitive as ADR-002's
   seat lock, prevents a concurrent second transfer on the same ticket.
2. Flip owner_id. transfer_status stays NONE (no payment involved).
3. Log to ticket_transfer_log. Emit ticket.transferred (notifies both
   parties).
```

## Paid resale

```
1. Owner lists: SELECT ... FOR UPDATE, set transfer_status =
   LISTED_FOR_RESALE, listed_price. Server validates listed_price
   against the ORIGINAL purchase price + fees, read from
   payment-service's payment_events ledger (ADR-020) for that ticket's
   booking — enforces ADR-014's cap server-side, not client-trusted.

2. Buyer purchases: SELECT ... FOR UPDATE on the ticket row (blocks a
   second concurrent buyer, same primitive). If still LISTED_FOR_RESALE,
   flip to TRANSFER_PENDING, create a PaymentIntent via payment-service
   — reuses ADR-011's Stripe iframe flow (SAQ A) exactly; the resale
   buyer's card never touches this system's servers either.

3. Payment webhook succeeds (payment_events ledger, ADR-020) ->
   ticket-service consumes payment.succeeded scoped to this transfer's
   payment_intent_id -> flips owner_id to the buyer, transfer_status
   back to NONE.

4. Payment fails/times out -> transfer_status reverts to
   LISTED_FOR_RESALE (or NONE if the listing is also cancelled) — same
   compensation shape as ADR-006, proportionally simpler since only
   ticket ownership + one payment intent are involved, not seat
   inventory.

5. Log to ticket_transfer_log regardless of outcome — feeds ADR-014
   layer 7's rapid-transfer-chain fraud detection as already intended.
```

## Barcode correctness — falls out of the design for free, worth stating explicitly

[[ADR-014-anti-bot-anti-scalper]]'s rotating TOTP-style barcode is
generated **live**, from current `ticket.owner_id`, at render time — it
is never cached or pre-computed. This means no separate
"invalidate the old owner's barcode" step is needed at all: the instant
`owner_id` flips inside the row-locked transaction above, the old
owner's app would generate a barcode the server no longer validates
against, and the new owner's app generates a correct one — correctness
follows automatically from the ownership row being the single source of
truth. Not obvious until traced through; stated here deliberately rather
than left as an accidental property.

# Why

Reuses ADR-002's row-lock primitive for the transfer/purchase race
instead of a new concurrency mechanism, and ADR-006's Saga shape
(simplified proportionally — two states, not five) for the
payment-involved case — consistent with this project's repeated
preference for reuse over invention (ADR-014's nonce pattern, ADR-023's
resilience layers, ADR-028's fan-out mechanism).

# Consequences

**Easier:** the double-transfer/double-purchase race closes using a
mechanism already trusted elsewhere; barcode correctness on transfer
needs zero extra code — an emergent property of the existing rotating-
barcode design, not new work.

**Harder:** seller payout (getting resale proceeds to the original
ticket holder, not payment-service's usual counterparty) is a genuinely
unresolved piece — flagged, not solved, likely needs Stripe Connect or
equivalent; price-cap validation adds a cross-service read from
ticket-service into payment-service's ledger that didn't exist before.

# Revisit When

- If payout volume/complexity justifies its own dedicated ADR (Stripe
  Connect vs. a manual settlement process) — deliberately out of scope
  here.

## Open Questions

- Seller payout mechanism — not decided, flagged above as the largest
  remaining gap this ADR does not close.
- Whether unsold `LISTED_FOR_RESALE` listings expire automatically as
  the event date approaches — not decided.
