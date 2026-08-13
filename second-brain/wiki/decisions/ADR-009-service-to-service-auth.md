---
title: ADR-009 Service-to-Service Authentication
type: decision
sources: []
related: [[api-gateway]], [[auth-service]], [[inventory-service]], [[booking-service]], [[cross-cutting-concerns]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

`system-overview.md` states "no service reads another service's database
directly" but says nothing about API-level caller identity. Nothing
currently stops any pod on the internal network from calling
`POST /internal/holds/{id}/confirm` on inventory-service — the concurrency
core has no authorization boundary at all. `wiki/security/` is empty.

# Requirements / Constraints

- Must distinguish caller *service* identity from the *end-user* identity
  the request is acting on behalf of — collapsing them is the common
  mistake.
- Must work on Docker Compose today (per [[infra]]) and not block on
  adopting Kubernetes early.
- A compromised or buggy service (e.g. notification-service) must not be
  able to call inventory-service's confirm endpoint just by being on the
  network.

# Options Considered

## A — Service mesh mTLS (Istio/Linkerd + SPIFFE)

Pros: zero app code, cryptographic identity, automatic rotation, L7
authz policy for free. Cons: Kubernetes-native — running a mesh on
Compose isn't real. Adopting it now means adopting k3d/kind immediately,
on top of 14 services + Kafka + Connect + Debezium + Schema Registry +
Redis Cluster + Elasticsearch on one dev machine. Also authenticates the
*workload*, not the *end user* — still need user-context propagation on
top.

## B — Manual mTLS (self-managed CA)

Pros: works on Compose, real PKI learning. Cons: you become the CA —
issuance, distribution, rotation, revocation (CRL/OCSP), truststore
reloads across 14 services, by hand. Highest toil per unit of security
gained; authorization is coarse (a cert says "a service," not "allowed to
confirm seats") unless SANs are parsed into authorities.

## C — Signed internal JWTs, OAuth2 client-credentials grant

Each service is an OAuth2 client registered in auth-service
(`client_id`+secret from Vault, [[ADR-010-secrets-management]]). Fetches
a service token via `client_credentials` at startup/expiry. Callee
validates against the **same cached JWKS** the gateway already uses
([[api-gateway]]) — reuses existing trust infrastructure instead of
adding a new root. Pros: ~15 lines of Spring Security config per service,
works identically on Compose and later k8s, **scope-level** authorization
(a compromised service is limited to its granted scopes, not the whole
network). Cons: bearer tokens are replayable if intercepted — no
transport binding, no east-west encryption. Acceptable inside a trusted
network segment for this project's threat model.

# Decision

**Option C now, Option A later** (when the project moves to k8s/AWS —
mTLS becomes the transport layer, the service JWT stays as the
authorization layer; they compose, this work isn't thrown away), **both
on top of Option D as a floor**:

**D — network policy, not optional**: Compose — internal services on an
`internal` network with no published ports; only api-gateway sits on
`edge`. K8s later — default-deny `NetworkPolicy`, explicit allow-pairs.
This blocks the *external* attacker and the *misrouted* call — it does
NOT block a compromised-but-internal service, which is exactly the stated
threat. C and D are complementary, neither substitutes for the other.

## Two-token model

```
booking-service -> inventory-service

Authorization:     Bearer <service token>
  iss: auth-service, sub: svc:booking-service, aud: inventory-service,
  scope: "inventory:hold inventory:release inventory:confirm", exp: now+5m

X-User-Assertion:   <original end-user access token, unmodified>
  iss: auth-service, sub: user:42, aud: ticketmaster-api, ...

X-Correlation-Id:   <propagated per cross-cutting-concerns.md>
```

`Authorization` answers "may this caller confirm seats"
(`@PreAuthorize("hasAuthority('SCOPE_inventory:confirm')")`).
`X-User-Assertion` answers "on whose behalf" — needed for per-user
purchase limits and audit records. Only booking-service's client
registration carries `inventory:confirm`; queue-service,
notification-service, analytics-service get no inventory scopes at all —
a compromised notification-service presenting its own valid token to
inventory-service gets a 403, no scope match.

`aud` must be the callee's service name and the callee **must verify
it** — without audience checking, a token minted for search-service is
replayable against inventory-service and the whole scheme degrades to
"are you any service at all."

**Gateway boundary rule**: api-gateway must **strip**
`Authorization`/`X-User-Assertion`/any `X-Internal-*` header arriving
from the client, before routing — as a global default filter, not a
per-route list (one forgotten route is a full authz bypass). Otherwise a
client forges `X-User-Assertion` for another user.

**Service token lifetime**: 5 min, refreshed at 60% of lifetime.
*Starting default, needs real data* — tradeoff is auth-service token-
endpoint QPS (trivial at 14 services x N instances / 5min) against
leaked-token blast radius. 5 min errs toward small blast radius.

# Why

Fits the existing stack exactly — reuses the gateway's already-decided
JWKS infrastructure rather than adding a new trust root, gives real
scope-level authorization instead of network-location-based trust, and
works on Compose today without forcing an early Kubernetes adoption.

# Consequences

**Easier:** a compromised service is contained to its granted scopes;
authorization decisions are explicit and auditable per service pair; no
new infrastructure beyond auth-service (already exists) and Vault
([[ADR-010-secrets-management]]).

**Harder:** every service needs an OAuth2 resource-server config and a
client-credentials fetch loop; no transport-level encryption or replay
protection until mesh mTLS is added later — an intercepted service token
is valid until it expires (5 min blast radius, not zero).

# Revisit When

- Moving to k8s + Linkerd (lighter than Istio, sane defaults) — mTLS
  becomes the transport layer, this JWT scheme stays as authorization.
- If 5 min service-token TTL proves too chatty against auth-service QPS,
  or too long against a leaked-token incident — tune from real data.

## Amendment: transport moved to gRPC, token model unchanged (ADR-023)

[[ADR-023-grpc-internal-service-calls]] moves internal service-to-service
calls from REST to gRPC. The two-token model, scopes, and mandatory `aud`
verification described above are **unchanged** — only the carriage
mechanism changes, 1:1:

```
Authorization header      -> authorization gRPC metadata key
X-User-Assertion header   -> x-user-assertion gRPC metadata key
X-Correlation-Id header   -> x-correlation-id gRPC metadata key
```

The Spring Security resource-server filter chain described above is
replaced by a gRPC server interceptor doing the same JWKS validation and
scope check. The gateway-boundary stripping rule (client-supplied
`Authorization`/`X-User-Assertion`/`X-Internal-*` headers must never pass
through) still applies at the REST edge, unchanged — api-gateway remains
REST-facing per ADR-023's scope.

## Open Questions

- None outstanding — service token TTL flagged as starting default above.
