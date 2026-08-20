---
title: ADR-039 Dual-Tier Login Rate Limiting
type: decision
sources: []
related: [[api-gateway]], [[auth-service]], [[ADR-004-redis-cluster-sharding]], [[ADR-014-anti-bot-anti-scalper]], [[ADR-032-api-gateway-ha-and-probe-semantics]]
created: 2026-08-19
last-updated: 2026-08-19
---

Status: Accepted

# Context

`api-gateway.md`'s original rate-limiting section described one control:
Spring Cloud Gateway's `RequestRateLimiter`, Redis-backed, keyed by
`userId:endpoint`. That design has no answer for `/api/v1/auth/login` and
`/api/v1/auth/register` specifically — there is no `userId` yet at the
point credentials are being exchanged, which the page never called out as
a gap.

An IP-only limiter at the edge was considered as the naive fix and
rejected on its own: IP is not a proxy for "one attacker." A university
library, a corporate NAT, or a coffee shop puts many real users behind one
address — an IP-keyed limit tight enough to slow credential stuffing is
also tight enough to lock out everyone else sharing that address.

Separately, the gateway's first rate-limit implementation (now replaced)
was a fixed-window counter (`INCR`+`EXPIRE` per window). A fixed window
lets a client burst up to the full limit twice back-to-back — once at the
end of window N, again immediately at the start of window N+1 — an
instant of double the intended rate at every boundary.

# Requirements / Constraints

- Must not let a shared IP (NAT/corporate/library) cost one legitimate
  user their own login budget.
- Must not require the gateway to buffer the request body: Spring Cloud
  Gateway is a streaming WebFlux/Netty proxy by design (ADR-032); forcing
  it to read a POST body to extract a username before routing defeats
  that non-blocking model and opens a memory-exhaustion DoS surface.
- Must smooth bursts rather than reset fully at a fixed window boundary.
- Must survive a Redis outage without becoming a second way to lock every
  user out of login — matches this project's general fail-open convention
  (ADR-014's compounding-risk note already flags Redis as a shared
  single point of failure across rate limiting, the queue, and fraud
  velocity counters).
- Must not reopen the account-enumeration protection `auth-service`'s
  `InvalidCredentialsException` already provides (identical 401 body and
  timing for unknown-email vs wrong-password vs locked-account).

# Options Considered

## A — Single IP-keyed limiter at the gateway only

Cons: exactly the shared-IP problem above. No amount of retuning the
number fixes it — the failure mode is structural, not a calibration
issue.

## B — Single username-keyed limiter at the gateway

Cons: requires buffering the request body at the gateway to read the
username before routing, which conflicts with the streaming-proxy
requirement above.

## C — Two independent limiters: IP-keyed at the gateway, username-keyed in auth-service

Pros: each layer runs where it has the information it needs. The gateway
never needs the body — it stays a volumetric shield, loose and IP-keyed,
protecting network/CPU capacity from dumb floods. auth-service, where
Spring MVC has already parsed the body into `LoginRequest`, runs the
tight, username-keyed check and can additionally persist a DB-backed
lockout that survives across the gateway layer's short window.

## For the gateway's own algorithm: hand-rolled leaky-bucket Lua vs Spring Cloud Gateway's built-in `RequestRateLimiter`

Spring Cloud Gateway's built-in limiter takes `replenishRate` and
`burstCapacity` as **integers, per second**. At the login limit first
chosen (10/min, later reconsidered at 50/min), neither number divides
evenly into a whole per-second rate (10/60 = 0.166, 50/60 = 0.833) — the
built-in option could only express 0/min (broken) or 60/min (6x looser
than intended) at those numbers, so a hand-rolled Lua script computing the
leak rate as a float was adopted instead, mirroring the same pattern
`login_attempt.lua` in auth-service later reused for its own atomic
INCR+EXPIRE.

Once the login limit was reconsidered a second time and set to exactly
60/min, the integer constraint stopped being a problem (60/60 = 1, exact)
and the hand-rolled script was retired in favour of the built-in
`RequestRateLimiter` — see Decision below. Register's 20/min doesn't
reduce to a whole `replenishRate` on its own (20/60 = 0.333), but
`requestedTokens: 3` against the same 1-token/sec refill nets exactly
1/3 request per second without any rounding loss.

## For the username-keyed layer: hand-rolled Lua vs a rate-limiting library (Resilience4j / Bucket4j)

Resilience4j's `RateLimiter` is strictly in-memory, per-JVM, with no
distributed backend — ruled out immediately given ADR-032 commits
auth-service to 3+ replicas behind a load balancer; a per-instance counter
lets an attacker multiply their effective budget by the replica count.

Bucket4j does have Redis-backed modules (`bucket4j-redis`,
`bucket4j-lettuce`) that solve the distributed-counter problem the same
way a hand-rolled Lua script does — atomically, in Redis. It was not
adopted because the counter itself was never the hard part: this layer
also needs failure-only counting (not counting successful logins),
identical-response enumeration safety, and a DB-persisted lockout —
none of which a generic rate-limiting library provides. A ~15-line Lua
script already fully understood and owned was judged not worth trading
for a new dependency that only replaces the one piece that was already
simple.

# Decision

**Two independent layers, adopted together:**

**Gateway — volumetric shield, IP-keyed, Spring Cloud Gateway's built-in
`RequestRateLimiter`.** `RateLimitConfig.ipKeyResolver` resolves the
remote socket address (never `X-Forwarded-For` — attacker-controlled
input on any request reaching this filter directly, until a trusted-proxy
allowlist exists per ADR-019). Route-level filter args, not a shared
prefix rule, since login and register need different numbers:

```
auth-service-login:    replenishRate=1, burstCapacity=60,  requestedTokens=1  (60/min)
auth-service-register: replenishRate=1, burstCapacity=60,  requestedTokens=3  (20/min)
```

Deliberately loose (60/min): large enough that a shared NAT/library IP is
never the thing that trips it, tight enough to stop a dumb flood from
eating gateway/network capacity. This is a token bucket (starts full,
refills continuously), not a leaky bucket — accepted as close enough in
shape to the leaky-bucket instinct that motivated retiring the fixed
window, given it no longer requires owning custom Lua for a case the
built-in now expresses exactly.

**auth-service — account-abuse control, username-keyed.**
`LoginAttemptLimiter` (Redis, atomic via `login_attempt.lua`'s
INCR+EXPIRE) allows 5 failed attempts per email per minute, keyed on the
lower-cased raw input email — deliberately identical whether the email
belongs to a real account or not, so the counter itself cannot become a
second enumeration oracle. Checked before any DB lookup or BCrypt call.
Increments only on `InvalidCredentialsException` (never on success),
resets on success.

Behind it, `User.failedLoginAttempts`/`lockedUntil` (V2 migration) is a
persistent backstop: 10 failures (which may span many separate Redis
windows an attacker paced to stay under 5/min) locks the account for 15
minutes. A locked account fails through the exact same
`InvalidCredentialsException` path as a wrong password — same BCrypt
cost, same 401, same body — so the lock's existence is never observable
from the response.

Both layers fail open on a Redis outage (`DataAccessException` caught,
logged, request allowed) — losing either narrows defense in depth, but
neither may become a second way to lock every user out of login.

# Why

Neither IP alone nor username alone covers the actual threat model: IP
alone punishes shared connections for one attacker's behaviour; username
alone (at the gateway) requires breaking the gateway's streaming
architecture to read a body it was never meant to parse. Splitting the
concern to where each layer has the information it actually needs — the
gateway sees only connection metadata, auth-service sees the parsed
credential — resolves both without compromising either.

# Consequences

**Easier:** the gateway stays a stateless, streaming proxy with zero
custom rate-limit code to maintain now that 60/min lands on a clean
integer boundary; auth-service owns the one thing that actually needs
domain knowledge (this is a login, this is a failure, this identity has
been guessed at 5 times).

**Harder:** the two layers must be reasoned about together — a review of
"is login rate-limited" now has to check both `application.yml` files,
not one. Retiring the hand-rolled gateway script also means any future
limit that isn't a clean multiple of 60 forces a choice between
`requestedTokens` tricks (as used for register) or reintroducing custom
Lua — this is now a real constraint on future numbers, not a one-time
cost.

# Revisit When

- If a future gateway-level limit needs a ratio `requestedTokens` can't
  cleanly express, or if per-role tiering (ADR-030's `role:userId:endpoint`
  scheme, for authenticated routes generally) needs to compose with this
  IP-keyed login/register carve-out.
- If real traffic data shows 60/min or 20/min miscalibrated — both are
  starting defaults, same category as every other numeric tunable in this
  vault.

## Open Questions

None currently.
