---
title: ADR-033 Non-Secret Configuration Management
type: decision
sources: []
related: [[ADR-010-secrets-management]], [[infra]], [[ADR-004-redis-cluster-sharding]], [[ADR-007-kafka-event-schema]]
created: 2026-08-13
last-updated: 2026-08-13
---

Status: Accepted

# Context

[[ADR-010-secrets-management]] decided where *secrets* live (Vault) and
explicitly excludes non-secret values from that mechanism — but nothing
decides where non-secret config lives instead. This vault already has a
real, growing pile of it: every "starting default, needs real data"
numeric tunable named across ADR-004 (75% autoscale threshold), ADR-006
(saga retry backoff 1min/5min/30min), ADR-007 (DLQ retry count/backoff),
ADR-002 (hold TTL), ADR-031 (dedup retention) — each currently lives only
as a number written in an ADR's prose, with no stated mechanism for how a
running service actually reads it, or how it gets changed without a
redeploy.

# Requirements / Constraints

- Must let a tunable value (e.g. the autoscale threshold) change without
  rebuilding/redeploying the service that reads it — the entire point of
  externalizing config instead of hardcoding.
- Must be clearly separated from [[ADR-010-secrets-management]]'s Vault
  mechanism — mixing the two either weakens secret handling (accidentally
  easy config paths for something secret) or over-burdens Vault with
  values that were never sensitive.
- Must work identically in local Docker Compose and later k8s/AWS,
  matching ADR-010's same cross-environment requirement.
- Must have one clear place a developer looks to find/change a tunable
  currently scattered as prose across a dozen ADRs.

# Options Considered

## A — Everything in each service's `application.yml`, baked into the image

Cons: exactly the redeploy-to-change problem this ADR exists to avoid;
also the practice ADR-010 already implicitly warns against for anything
adjacent to secrets (Actuator `/env` exposure risk applies to config
values too, even non-secret ones, if they reveal internal topology).

## B — k8s ConfigMap per service, mounted as a Spring Boot config file, `kubectl` edit + rolling restart to change

Pros: no new infrastructure beyond k8s itself, which every service
already runs on; values are structured YAML, versionable in git
alongside the k8s manifests; a change is a normal, auditable git-tracked
deploy action, not a hidden runtime mutation. **Chosen.**

## C — Spring Cloud Config Server (centralized config service, git-backed, push-to-refresh via `/actuator/refresh`)

Pros: true zero-redeploy runtime refresh, native Spring ecosystem fit.
Cons: a 16th-ish piece of infrastructure to run and operate for a
capability ConfigMaps mostly already cover at this project's scale — same
"doesn't independently justify itself" bar ADR-030/ADR-028 already
applied to rejecting a dedicated service for a smaller problem.

# Decision

**Option B.** One ConfigMap per service, git-tracked alongside its k8s
manifests, mounted as `application.yml` (or `application-{profile}.yml`
per environment) — Spring Boot's native externalized-config mechanism,
no framework change needed.

```
Structure:
  infra/k8s/<service>/configmap.yaml   -- non-secret tunables, git-tracked
  infra/k8s/<service>/secret.yaml      -- NEVER committed; Vault-injected
                                           per ADR-010, kept structurally
                                           separate so the two are never
                                           confused at review time.

Example (booking-service):
  saga:
    compensation-retry-backoff: [60s, 300s, 1800s]  # ADR-006
  redis:
    autoscale-threshold-pct: 75                      # ADR-004
  kafka:
    dlq-retry-count: 5                                # ADR-007

Change process: edit the ConfigMap YAML in git, PR + merge (same review
  bar as any other code change — a tunable is still a production-behavior
  change), `kubectl rollout restart` picks it up. Not live-reloaded
  mid-process — a restart is cheap for a stateless service (per
  [[ADR-032-api-gateway-ha-and-probe-semantics]]'s HA model) and avoids
  the correctness risk of a config value changing under a request
  already in flight.
```

# Why

Reuses k8s itself rather than adding a dedicated config service, matching
this project's repeated bar of not standing up a new component for a
problem existing infrastructure already covers at this scale (same
reasoning ADR-030 applied to rejecting a policy service, ADR-028 to
rejecting a cancellation service). Git-tracking config changes gives every
tunable an audit trail for free — directly useful given how many of this
vault's numbers are explicitly marked "starting default, needs real data"
and will actually change once real load-test/production data exists.

# Consequences

**Easier:** every scattered "starting default" number across ADR-002/004/
006/007/031 now has one real place to live and change, instead of being
prose that nobody's code actually reads from; config changes are
git-auditable like any other change.

**Harder:** a config change still requires a rolling restart, not true
live-reload — acceptable at this project's scale, explicitly a tradeoff
against Option C's added infrastructure, not an oversight.

# Revisit When

- If a tunable needs to change faster than a rolling restart allows
  (sub-minute reaction, e.g. mid-incident) — Spring Cloud Config's
  `/actuator/refresh` becomes worth its added infrastructure at that
  point, not before.

## Open Questions

- Whether to consolidate every ADR's "starting default" numbers into one
  master reference doc cross-linking to each ConfigMap key, versus
  leaving them documented only in their originating ADR — not decided,
  a documentation-organization question rather than an architectural one.
