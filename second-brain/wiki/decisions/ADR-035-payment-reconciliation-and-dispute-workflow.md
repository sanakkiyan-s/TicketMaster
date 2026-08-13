---
title: ADR-035 Payment Reconciliation Job and Dispute/Chargeback Workflow
type: decision
sources: []
related: [[ADR-020-payment-event-ledger]], [[ADR-028-event-cancellation-mass-refund]], [[ADR-011-pci-scope-containment]], [[payment-service]], [[ticket-service]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: Accepted

# Context

[[ADR-020-payment-event-ledger]] defines `DISPUTED` as a terminal status
in its transition graph and names its own Open Questions as covering
"full transition-graph edge cases (partial refunds, multiple disputes)"
— but nothing decides what happens operationally once a payment reaches
`DISPUTED`, nor whether this system's internal ledger is ever checked
against the payment provider's own records for drift. Two gaps, closely
related (both are "is our recorded money state actually correct"
questions), closed together here.

# Requirements / Constraints

- A chargeback must have a real, designed consequence — ticket access
  cannot silently remain valid for a disputed (potentially fraudulent)
  charge indefinitely.
- Must reuse [[ADR-020-payment-event-ledger]]'s append-only ledger and
  webhook-driven model — a dispute is just another webhook event type,
  not a reason to invent a second payment-state mechanism.
- Reconciliation must detect drift between this system's ledger and the
  provider's records without assuming webhooks are perfectly reliable —
  the entire reason a reconciliation job is worth having is that webhook
  delivery, despite ADR-020's dedup handling, is not a proof of
  completeness, only of non-duplication.
- Must not require a new provider integration beyond what
  [[ADR-011-pci-scope-containment]] already established (Stripe, SAQ A).

# Options Considered

## A — No reconciliation job; trust the webhook stream completely

Cons: a missed webhook (provider outage during send, a bug in the outbox
consumer, a silently-failed retry past ADR-007's DLQ threshold) leaves
the internal ledger permanently wrong with no detection mechanism at
all — exactly the kind of silent drift a real payments system cannot
tolerate.

## B — Nightly reconciliation job comparing the ledger against Stripe's Balance Transactions API + a defined dispute-handling saga extending ADR-020's transition graph

Pros: reconciliation catches drift regardless of *why* a webhook was
missed, rather than trying to make webhook delivery itself provably
complete (an unwinnable guarantee for any provider-pushed system).
Dispute handling reuses ADR-006's Saga/compensation shape already
trusted for booking cancellation. **Chosen.**

## C — Real-time balance verification on every webhook

Cons: turns every webhook processing call into a synchronous
provider-API round trip, adding latency and a new failure mode
(provider API down blocks webhook processing) to the hot path for a
problem that is inherently a batch/eventual-consistency concern, not a
per-event one.

# Decision

**Option B.**

## Reconciliation job

```
Trigger: nightly (off-peak), reads Stripe's Balance Transactions API for
  the prior 24h window, compares against payment-service's own
  payment_events ledger for the same window.

Check: every provider-side event_id must have a matching row in
  payment_events (webhook completeness check, not just correctness —
  catches a webhook that never arrived at all, which ADR-020's dedup
  logic cannot detect since dedup only guards against processing an
  ARRIVED event twice).

Drift found: does NOT auto-correct the ledger — an automated silent
  correction of financial records is worse than a flagged discrepancy.
  Emits an ops alert (same PaidUserUnresolved-style P1 severity as
  ADR-015/ADR-006's stuck-payment alerting) with the specific missing/
  mismatched event_id for manual reconciliation.
```

## Dispute/chargeback workflow (extends ADR-020's `DISPUTED` terminal status)

```
1. `charge.dispute.created` webhook arrives -> payment-service writes it
   to payment_events (same append-only pattern, no new mechanism) ->
   status derivation reaches DISPUTED.

2. payment-service emits `payment.disputed` via the existing outbox
   (ADR-007) — ticket-service consumes it and flips the associated
   ticket's status to `DISPUTE_HOLD`: barcode stops validating at venue
   entry (reuses ADR-014's live-barcode-generation design — no separate
   invalidation step needed, same emergent property ADR-029 already
   named for transfers) but ownership itself is not yet altered, since
   the dispute may still be resolved in the merchant's favor.

3. Organizer/admin notified via notification-service (existing consumer
   pattern) — a dispute is exactly the kind of event
   [[ADR-030-organizer-admin-authorization]]'s ownership model already
   scopes correctly (the owning organizer sees it, not every organizer).

4. Resolution arrives via `charge.dispute.closed` webhook (won/lost):
     Won  -> ticket status reverts to its prior state (barcode valid
             again), payment_events gets the resolution event, no ticket
             state was ever destroyed, only held.
     Lost -> reuses [[ADR-028-event-cancellation-mass-refund]]'s
             single-booking compensation path (the same mechanism
             already built for cancellation/fraud bulk-cancel, run for
             one booking instead of a batch) — ticket VOIDED, seat
             re-released to inventory if the event is still on sale.
```

# Why

Reconciliation-by-comparison rather than trying to make webhook delivery
itself provably complete matches how real payment systems handle this
class of problem, and treating a dispute as "another saga trigger
reusing ADR-028's compensation path" rather than a new mechanism is
consistent with this vault's repeated preference for reuse over
invention (ADR-028 itself, ADR-029, ADR-031).

# Consequences

**Easier:** silent ledger drift now has a real detection mechanism
instead of trusting webhook delivery blindly; dispute handling has an
actual designed consequence (ticket hold) instead of `DISPUTED` being a
terminal status nothing downstream ever reacts to.

**Harder:** the reconciliation job is a new scheduled integration against
Stripe's Balance Transactions API — real implementation surface, and its
alerts require an actual manual-reconciliation runbook at
implementation time, not designed here.

# Revisit When

- If dispute volume ever becomes high enough that manual reconciliation
  review is itself a bottleneck — would need a dedicated ops tool, not
  designed now since no such volume exists.

## Open Questions

- Partial-refund interaction with the `DISPUTE_HOLD` ticket state (a
  partially refunded, then disputed booking) — not decided, genuine edge
  case ADR-020 already flagged as open.
- Exact reconciliation window/schedule tuning — starting default
  (nightly) only, same "needs real data" category as every other numeric
  default across this vault.
