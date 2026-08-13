---
title: ADR-019 CDN / Edge Vendor Choice — Cloudflare vs AWS-Native
type: decision
sources: []
related: [[ADR-016-multi-region-cdn]], [[ADR-014-anti-bot-anti-scalper]], [[infra]]
created: 2026-08-06
last-updated: 2026-08-06
---

Status: Accepted

# Context

[[ADR-016-multi-region-cdn]] recommended Cloudflare but explicitly deferred
a final vendor decision, since `infra.md` states "eventually AWS
deployment target." [[ADR-014-anti-bot-anti-scalper]] already assumes
Cloudflare Turnstile for queue-join challenges. This ADR resolves the
vendor choice directly instead of leaving it a standing tension.

# Requirements / Constraints

- Must support the geometry/occupancy CDN split and cache-tag purge design
  from [[ADR-016-multi-region-cdn]] (event-detail cache invalidation via a
  Kafka-driven purge consumer).
- Must not force abandoning `infra.md`'s AWS direction for the compute
  tier (backend services, Postgres, Redis, Kafka).
- Must support the anycast edge routing and bot-challenge mechanisms
  already assumed by ADR-016/ADR-014.

# Options Considered

## A — AWS-native (CloudFront + Global Accelerator + Lambda@Edge)

Pros: single vendor/billing with the rest of `infra.md`'s AWS target,
deep IAM integration, one support relationship.

Cons: CloudFront has **no cache-tag/surrogate-key purge** — only
path-based invalidation, slower and costlier at the granularity ADR-016's
event-detail purge needs (invalidating everything derived from one
`event.updated` message). CloudFront and Global Accelerator are two
separate control planes requiring manual integration, rather than one
coherent edge product. Lambda@Edge has real cold-start and deployment-
latency constraints compared to purpose-built edge compute.

## B — Cloudflare (single anycast edge: CDN + DNS + Workers + Turnstile)

Pros: cache-tag purge is a first-class feature — matches ADR-016's design
directly, no workaround needed. One coherent product for anycast
routing + CDN + edge compute (Workers, used for the region-prefix parsing
in ADR-016) + bot management (Turnstile, already assumed in ADR-014) —
fewer moving parts to integrate versus stitching together three separate
AWS products.

Cons: a second vendor relationship alongside AWS, not a single-vendor
setup.

# Decision

**Option B — Cloudflare for the edge/CDN layer.** This is not actually in
conflict with `infra.md`'s AWS direction — clarifying the split rather
than reopening it:

```
Cloudflare: edge/CDN layer only — anycast routing, TLS termination
  at the PoP, static asset + geometry caching, cache-tag purge,
  Turnstile/bot challenges, edge Workers for region-prefix parsing.

AWS: everything BEHIND the edge — compute (EKS/EC2 for the 15
  services), Postgres/Citus, Redis Cluster, Kafka, object storage.
```

This is a common real-world pattern (Cloudflare in front of an
AWS-hosted origin), not a hybrid compromise invented for this project.
`infra.md`'s "eventually AWS" statement is about where the *application*
runs — it says nothing that requires the edge/CDN vendor to also be AWS,
and reading it that way was the source of the flagged tension, not an
actual constraint.

# Why

Cache-tag purge is not a nice-to-have here — it's the mechanism
[[ADR-016-multi-region-cdn]]'s cache-invalidation design is built around
(a small Kafka consumer issuing tag purges on `event.updated`). Choosing
CloudFront would mean redesigning that invalidation flow around a weaker
primitive (path-based invalidation) for the sake of vendor uniformity
that isn't actually required by anything in `infra.md`.

# Consequences

**Easier:** ADR-016's cache-invalidation design works exactly as
specified, no workaround; one coherent edge product instead of
integrating three separate AWS services by hand; Turnstile (already
assumed in ADR-014) is native, not a bolted-on third-party addition.

**Harder:** two vendor relationships/billing accounts instead of one;
DNS and edge configuration live outside the AWS console, a second place
to manage.

# Revisit When

- If AWS's cache-tag/surrogate-key purge support materially improves —
  would remove the primary reason B beat A.
- If a genuine single-vendor requirement emerges (procurement,
  compliance) that outweighs the cache-purge advantage.

## Open Questions

- None outstanding.
