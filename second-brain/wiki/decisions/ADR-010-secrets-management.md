---
title: ADR-010 Secrets Management
type: decision
sources: []
related: [[ADR-009-service-to-service-auth]], [[ADR-012-jwt-lifecycle]], [[infra]], [[payment-service]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

Postgres credentials (x14 DBs), the JWT signing private key, Stripe keys,
Debezium replication credentials, Redis AUTH, Kafka SASL creds, and 14
service `client_secret`s all need somewhere real to live. Nothing in the
vault currently decides this.

# Requirements / Constraints

- Must work locally on Docker Compose today, and later on AWS/k8s, without
  dev and prod diverging on the fundamental mechanism.
- Rotation must be possible without redeploying every service by hand.
- Nothing secret may ever land in an env var, compose file, or
  `application.yml` — those leak via `/proc`, crash dumps, and Spring
  Boot Actuator's `/env`.

# Options Considered

## A — AWS Secrets Manager + Spring Cloud AWS

Pros: zero ops burden, native RDS rotation Lambdas, IAM-native auth.
Cons: nothing to run locally — dev develops against LocalStack or a
different mechanism entirely, so dev and prod diverge exactly where
divergence is most dangerous. No dynamic per-instance credentials. Teaches
an AWS product, not a transferable mechanism.

## B — Sealed Secrets / SOPS + age

Pros: trivially simple, secrets live in git, no server to run. Cons:
solves *distribution*, not *lifecycle* — every secret is long-lived and
static, rotation is a manual re-encrypt-and-redeploy, no audit trail of
who read what. Worse than A on the dimension that matters most.

## C — HashiCorp Vault

Runs on Compose today, runs on EKS later, and is the only option that
teaches the actually-interesting mechanisms (dynamic credentials,
response-wrapped bootstrap, encryption-as-a-service).

# Decision

**Option C.** Four Vault engines:

**1. Auth — AppRole per service.** `role_id` (non-secret, bakeable into
config) + `secret_id` delivered **response-wrapped**: Vault returns a
single-use wrapping token with a short TTL; the service unwraps it
exactly once. If someone else unwrapped it first, the unwrap fails and
you *know* you were compromised — the honest answer to "secret zero" is
you can't eliminate it, but you can make its theft detectable with a
small window. Later on k8s: swap for the **Kubernetes auth method**
(service-account JWT -> Vault role) — removes secret zero entirely
because the platform vouches for identity, same reasoning as
[[ADR-009-service-to-service-auth]]'s mesh-mTLS note.

**2. Database secrets engine — dynamic Postgres credentials.** The
highest-value piece. A service starts, Vault mints a *unique* Postgres
role for that instance with a 24h TTL / 72h max, revoked (`DROP ROLE`) on
expiry. No shared DB password exists anywhere; a leaked credential
self-destructs; `pg_stat_activity` shows which *instance* ran a query.
Requires HikariCP credential refresh via
`spring-cloud-vault-config-databases` + a `@RefreshScope`d `DataSource` —
budget real implementation time here, it's the piece most likely to bite.

**3. KV v2** for static third-party secrets (Stripe keys, Kafka SASL).
Versioned — a bad rotation is one `vault kv rollback` away.

**4. Transit engine** — encryption-as-a-service. Wraps GDPR subject DEKs
([[ADR-013-gdpr-crypto-shredding]]). **Deliberately NOT used for the JWT
signing key** — auth-service signs on every login/refresh; a network
round-trip per signature on the highest-QPS service is the wrong trade.
Private key stored in KV, loaded into memory at startup, never written to
disk — an accepted risk, stated explicitly rather than left implicit.

## Startup flow

```
bootstrap: Vault Agent (or spring-cloud-vault) auths via AppRole
  -> fetches KV secrets as a Spring PropertySource
  -> requests dynamic DB creds -> spring.datasource.username/password
  -> holds a renewable lease, renews at 2/3 TTL
application: fails fast, refuses to start if any required secret absent
```

Actuator's `/env`, `/configprops`, `/heapdump` disabled or auth-gated on
every service without exception.

## Known friction: Debezium

Kafka Connect does not participate in Vault lease renewal — its Postgres
replication credential is effectively static, unlike every other service
credential in this ADR.

**Original decision (superseded 2026-08-08):** accept a long-lived static
credential, rotated manually on a schedule. Reasoning was that an
automated sidecar/restart risks CDC gaps on forced restarts.

**Correction:** that reasoning was technically inaccurate. Debezium/Kafka
Connect commit the current LSN to the `connect-offsets` topic on a
graceful pause, and Postgres holds WAL for a connector's replication slot
until that offset is confirmed — so a REST-API-triggered restart resumes
from the exact last-committed LSN, no gap. Worst case on an *ungraceful*
crash is a small window of **duplicate** events (at-least-once
redelivery, matching [[ADR-007-kafka-event-schema]]'s delivery
guarantee), not data loss. Postgres won't discard WAL an unconfirmed slot
hasn't flushed, even across a crash — the real failure mode of a stuck
slot is disk growth, not silent loss.

**Current decision: automate Debezium credential rotation**, same spirit
as every other credential in this ADR. Blue/green Postgres role pair
(`debezium_blue` / `debezium_green`), both scoped `REPLICATION`-only, no
table grants. Rotation script:

1. Provisions fresh password on the idle role (Vault-managed KV entry or
   directly against Postgres).
2. `PUT /connectors/<name>/config` on the Kafka Connect REST API,
   switching `database.user`/`database.password` to the idle role.
3. Kafka Connect gracefully pauses the task, flushes the LSN offset,
   restarts on the new credential — sub-few-second pause, no gap.
4. Once task status is `RUNNING`, revoke/scramble the now-idle role's
   password.
5. Next cycle flips blue<->green.

Alternative considered: Kafka Connect `ConfigProvider` pointed at Vault
(`database.password": "${vault:secret/data/postgres/debezium:password}"`)
+ `POST /connectors/<name>/restart` after Vault rotates the KV entry —
simpler, no blue/green role juggling, but the connector still needs an
external trigger to actually restart and pick up the new value. Either
works; blue/green avoids any window where the *old* password is invalid
before the restart completes.

This closes the one manual-process gap the rest of this ADR doesn't
have — Debezium's credential now rotates on the same systemic footing as
dynamic DB creds, KV secrets, and everything else.

## Rotation cadence — *starting defaults, need real data*

Dynamic DB creds 24h TTL. Service `client_secret` 90d. Stripe keys 180d
or immediately on suspicion. JWT signing key 90d (see
[[ADR-012-jwt-lifecycle]]). Vault unseal: 5 shares/threshold 3, or
auto-unseal via cloud KMS in production — manual unseal on every restart
becomes unworkable within a week.

# Why

Vault is the only option of the three that keeps dev and prod on the same
mechanism (no LocalStack divergence) while teaching dynamic-credential
lifecycle management — a genuinely different and more valuable thing to
learn than static-secret distribution.

# Consequences

**Easier:** a leaked DB credential self-destructs within 24h; no shared
passwords anywhere; rotation is systemic, not a redeploy-by-hand exercise.

**Harder:** Vault itself is new operational surface (unseal, HA, backup);
HikariCP dynamic-credential refresh needs real implementation attention;
Debezium's blue/green rotation script is another piece of automation to
build and test (Kafka Connect REST calls + Postgres role toggling), though
it removes what was previously a manual, easy-to-forget process gap.

# Revisit When

- Moving to k8s — swap AppRole for the Kubernetes auth method.
- If Debezium's automated blue/green rotation proves unreliable in
  practice (e.g. REST API calls failing silently, role state drifting) —
  fall back to a static credential with manual rotation, the original
  approach this superseded.

## Open Questions

- Rotation cadences above are starting defaults — same "needs real data"
  category as every other tunable in this vault.
- Debezium blue/green rotation cadence not yet set — should probably
  match or beat the 90d service-secret default above once implemented.
