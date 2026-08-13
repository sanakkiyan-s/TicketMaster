---
title: ADR-014 Anti-Bot / Anti-Scalper Defense Layers
type: decision
sources: []
related: [[fraud-service]], [[queue-service]], [[ticket-service]], [[ADR-002-seat-locking-strategy]], [[ADR-004-redis-cluster-sharding]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

Scalper bots are the defining adversary of this domain. `fraud-service.md`
already decided fail-open on outage — defensible only if defenses beyond
fraud-service remain effective without it. This ADR resolves that
condition explicitly, and along the way resolves open questions on
`queue-service.md` (fairness) and `ticket-service.md` (transfer/resale).

# Requirements / Constraints

- Detection-based defenses (fraud-service scoring) are effective against
  commodity bots but lose to well-resourced adversaries with real
  residential IPs and human-solved CAPTCHAs. Structural controls — ones
  that don't depend on detecting anything — must carry the defense for
  the top adversary tier.
- Every layer must be annotated with whether it survives a fraud-service
  outage, since fraud-service fails open by design.

# Decision — nine layers, structural-first

Adversary tiers, for context: (1) commodity scripts — beaten by edge rate
limits/JS challenges; (2) headless browser farms — beaten by TLS/HTTP2
fingerprinting, PoW cost; (3) residential-proxy + real-device farms —
beaten only by identity-based/structural controls; (4) account farms —
beaten by payment-instrument correlation, post-hoc cancellation. Layers
below target tiers 3-4, where detection-based scoring loses.

**1. Verified Fan registration (highest leverage).** For designated
high-demand on-sales, close registration N days before the sale — verified
email, verified phone (SMS OTP, reject known VoIP/disposable ranges),
account older than the registration window, real payment method on file
(`SetupIntent`, issuer-approved card). Converts the attack from "win a
200ms race" into "acquire and age thousands of phone-verified accounts
with distinct real cards, weeks in advance" — a real cost floor no proxy
rotation removes. *Survives fraud-service outage: yes, registration
closed days earlier.*

**2. Randomized queue admission, not FIFO.** Resolves `queue-service.md`'s
open question. Strict FIFO makes ordering a function of *latency*, which
an attacker can buy (colocated instances, pre-warmed TLS) — a bot beats a
human by ~400ms, deterministically, every time. **Decision: randomized —**
everyone joining within a window is pooled; positions assigned by a
publicly-committed-then-revealed shuffle at window close, so fairness is
verifiable, not asserted. Neutralizes speed, not scale — a bot with 1000
accounts still gets 1000 lottery entries, which is why layers 1 and 4 are
necessary complements. *Survives fraud-service outage: yes.*
*Join-window length: starting default, needs real data on global RTT
distribution.*

**3. Single-use, bound admission tokens.** `queue-service.md` says tokens
are issued but doesn't specify them — unspecified, they become a
tradeable commodity (resold on Discord within an hour of on-sale start).

```
Admission token: JWT, signed by queue-service's own key
  aud: booking-service, inventory-service
  sub: user:<uuid>       -- bound to ONE user
  evt: <event_id>        -- bound to ONE event
  nonce: <uuid>          -- SINGLE USE
  exp: iat + 120s
```

Single-use enforced by atomic Redis nonce consumption — the same `SET ...
NX` + Lua pattern [[ADR-002-seat-locking-strategy]] already establishes
for seat locks, reused deliberately rather than inventing a second
pattern. booking-service and inventory-service must **both** verify the
token, and inventory-service must check `sub` against the
`X-User-Assertion` subject from [[ADR-009-service-to-service-auth]] —
otherwise an admitted user's token books on behalf of others. *Survives
fraud-service outage: yes — enforced independently by queue-service and
inventory-service.*

**4. Purchase limits enforced in inventory-service, not fraud-service.**
Direct consequence of fraud-service's fail-open decision, and easy to get
wrong: `fraud-service.md` currently describes bulk-purchase-limit
"enforcement" as a fraud-service concern. **If fraud-service fails open,
an attacker's cheapest move is inducing a fraud-service outage and buying
in bulk.** fraud-service must *advise*; the limit must be *enforced*
transactionally where the state actually lives:

```sql
purchase_limit_counter
  event_id, limit_key_type, limit_key_hash, count
  UNIQUE (event_id, limit_key_type, limit_key_hash)
  CHECK (count <= max_allowed)
```

`limit_key_type` dimensions, increasing evasion cost: `user_id` (free,
trivially farmable) -> `payment_card_fingerprint` (Stripe's stable
per-card hash from [[ADR-011-pci-scope-containment]] — the strongest
available cross-account signal; 500 accounts sharing 20 real cards is
common account-farm economics, this catches it) -> `delivery_identity`
(phone hash from layer 1). Default 4-6 tickets/event/dimension, *starting
default, needs to be organizer-configurable per event, not global*.
*Survives fraud-service outage: yes — a DB constraint in the transaction
path, structurally identical to ADR-002's reasoning for why Postgres is
the correctness authority.*

**5. Edge TLS/HTTP2 fingerprinting at Nginx.** `infra.md` gives Nginx TLS
termination — the only place that sees the raw ClientHello. JA3/JA4
fingerprint (cipher suite order, extensions, curves) identifies the *TLS
library*, not the claimed User-Agent — a request claiming
`Chrome/126 on Windows` with a Python `requests` JA4 hash is automated
with near-certainty. Nginx computes and forwards the hash; downstream uses
it as a coarse gate even when fraud-service is unreachable. Fallback rule
must be "unknown fingerprint -> harder challenge," not "-> block" (avoids
blocking legitimate niche browsers). *Survives fraud-service outage: yes,
with a static fallback difficulty.*

**6. Challenge at queue-join, not at checkout.** Cost the attacker
*before* they touch the scarce resource. Cloudflare Turnstile or
self-hosted proof-of-work (Altcha) — PoW specifically because it's
unsolvable by outsourcing (a $1/1000 CAPTCHA farm doesn't help against a
compute cost that scales with parallelism). Difficulty is a runtime lever
per [[cross-cutting-concerns]]'s feature-flag mechanism: none for normal
browsing, Turnstile at on-sale queue-join, escalating PoW under detected
attack — changeable mid-sale without a redeploy. *Survives fraud-service
outage: yes, with a static per-event default difficulty.*

**7. Post-hoc cancellation.** Accept that tier-3/4 adversaries get through
in real time; cancel bot orders after the sale and re-release inventory.
Batch analysis over hours has signals real-time scoring can't use:
cross-account card-fingerprint clustering, identical device fingerprints
across accounts, purchase-timing distributions too uniform to be human.
Requires ticket-service's admin-cancel + inventory-re-release path,
payment-service's programmatic refund (both already in scope), and an
`AdminActionPerformed` audit event per [[cross-cutting-concerns]]. Human
sign-off before bulk cancellation — a false positive here cancels a real
fan's tickets, worse than one scalper getting through. *Survives
fraud-service outage: yes — an offline batch job.*

**8. Kill the resale economics.** Bot economics only work if the ticket
resells at a markup. Resolves both `ticket-service.md` open questions:
**rotating barcode** (SafeTix-style — TOTP-derived value refreshing every
~15s, rendered only in-app; a screenshot is worthless, single-handedly
kills off-platform resale where scalper margin actually lives) and
**transfer-only delivery + price-capped in-platform resale** (ownership
change is a ticket-service state transition, already the model; cap at
face value + fees where jurisdiction permits; log every transfer — rapid
transfer chains feed layer 7). *Survives fraud-service outage: yes.*

**9. Account-takeover hardening.** Aged, verified, payment-attached
accounts (layer 1's own creation) become a target — credential stuffing
is the cheapest route to a Verified Fan account. Breached-password check
at registration/password-change via HIBP's k-anonymity range API (send 5
hex chars of the SHA-1 prefix, never the password). Step-up OTP for:
adding a payment method, using a saved method from an unrecognized
device, ticket transfer, email change — gated on the `amr` claim from
[[ADR-012-jwt-lifecycle]]. Notify on every new-device login
(notification-service already exists for this).

## Compounding risk: Redis is a single point of anti-abuse failure

Already documented as an amendment in [[ADR-004-redis-cluster-sharding]]
— a Redis outage simultaneously removes gateway rate limiting, the entire
queue (Redis-only state), fraud velocity counters, and inventory's
fast-gate. Each fail-open is individually defensible; the aggregate is a
coordinated collapse. Mitigations (gateway degrades to a local limiter;
queue-service fails **closed** on the on-sale path) are recorded there,
cross-referenced here since it directly bears on this ADR's premise that
"structural controls survive fraud-service outages" — several of them
also depend on Redis being up.

## Anti-tell principle

Never tell a bot it was detected. Blocked requests return a plausible
business outcome ("no seats currently available," a slow response, a
long queue) — not `403 Bot Detected`. A clear signal is a free gradient
for an attacker to optimize against; ambiguity is expensive for them.
Constrains error-response design in queue-service and booking-service
from the start — cheap now, annoying to retrofit.

# Why

Detection loses to a well-resourced adversary by construction — it scores
signals an attacker can eventually mimic. Structural controls (identity
cost, randomized ordering, transactional limits, unforgeable single-use
tokens, unscreenshot-able tickets) don't depend on detecting anything, so
they remain effective specifically during the condition fraud-service's
fail-open decision creates: no scoring available.

# Consequences

**Easier:** the system's defense doesn't collapse to zero the moment
fraud-service is unavailable; several open questions across
queue-service/ticket-service/fraud-service resolve as a side effect of
one coherent threat model instead of independently.

**Harder:** Verified Fan registration adds real product friction for
legitimate fans on high-demand events; rotating barcodes require an
always-connected app rather than a static PDF/screenshot, a real UX
constraint; purchase-limit dimensions beyond `user_id` require
payment-service to expose a fingerprint lookup on the checkout path, a
new cross-service coupling.

# Revisit When

- If layer 2's join-window length or layer 4's default limits prove
  miscalibrated against real bot behavior once on-sales actually run.
- If a real adversary defeats layer 8's rotating barcode (e.g. via a
  compromised official app) — would need a different anti-resale
  mechanism, not a tweak.

## Open Questions

- Join-window randomization length, PoW difficulty curve, purchase-limit
  defaults — all starting defaults, need real on-sale data.
