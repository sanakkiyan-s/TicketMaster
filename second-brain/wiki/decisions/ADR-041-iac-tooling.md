---
title: ADR-041 IaC Tooling
type: decision
sources: []
related: [[implementation-roadmap]], [[ADR-019-cdn-vendor-choice]], [[ADR-032-api-gateway-ha-and-probe-semantics]], [[ADR-033-non-secret-config-management]], [[ADR-010-secrets-management]], [[ADR-038-ci-platform]], [[infra]]
created: 2026-08-20
last-updated: 2026-08-20
---

Status: Accepted

# Context

`implementation-roadmap.md` has carried "IaC tooling not named (Terraform
vs alternatives)" as an Open Decision since the roadmap was written, filed
under a wider "Deployment provisioning gap": twelve wiki pages assume
Kubernetes exists (ADR-032's replicas/HPA/probes, ADR-033's ConfigMaps,
ADR-009's NetworkPolicy, ADR-010's Vault AppRole, ADR-008's `kubectl
rollout undo`) but nothing states how a cluster gets created. Only
`infra/docker-compose.yml` exists today — everything k8s-shaped in the
vault is currently undeployable, not just unautomated.

[[ADR-019-cdn-vendor-choice]] already settled the target split: Cloudflare
for edge/CDN, **AWS for compute**. This ADR only decides how AWS resources
(and the k8s cluster on top of them) get provisioned — not whether AWS is
the target, that's already decided.

# Requirements / Constraints

- Must provision a real k8s cluster on AWS (EKS, per the ADR-019 split) —
  every ADR-032/033/009/010 assumption needs somewhere to actually run.
- Must provision the data-plane pieces ADR-004/005/026 already committed
  to running as real infra rather than managed equivalents: Citus
  (Postgres extension, not available on RDS/Aurora) and its worker nodes,
  Redis Cluster, Kafka — these need VPC networking, EC2 node groups or
  in-cluster StatefulSets, and IAM wiring, not a managed-service checkbox.
- Must produce reproducible, diffable infrastructure — the entire reason
  this is an Open Decision rather than already-ClickOps'd is that
  undocumented console state is exactly what a solo portfolio project
  cannot afford to lose track of.
- Zero infrastructure to operate beyond what's provisioned. Same
  solo-project constraint [[ADR-038-ci-platform]] already applied to CI.
- Portfolio value matters: the tool choice is also a demonstrated skill,
  same reasoning [[ADR-004-redis-cluster-sharding]] and
  [[ADR-005-postgres-sharding]] already used to justify real
  infrastructure "ahead of actual target load."

# Options Considered

## Terraform

- **Pro**: cloud-agnostic HCL, by far the largest module ecosystem
  (`terraform-aws-modules/eks`, `/vpc`, `/rds` cover most of this
  project's AWS surface directly), the most widely recognized IaC skill
  for a portfolio to demonstrate, mature remote-state + locking story
  (S3 backend + DynamoDB lock table).
- **Con**: HCL is a bespoke DSL, not a general-purpose language — no
  loops/conditionals as expressive as a real language. State file is a
  real operational object that must be stored and locked correctly.

## Pulumi

- **Pro**: real programming language (TypeScript/Python/Go) instead of a
  DSL — arbitrary logic, reuses this project's existing language
  investment.
- **Con**: smaller module ecosystem than Terraform for AWS specifically,
  adds a second execution runtime (language toolchain) most Terraform
  users don't carry, and the "real language" advantage matters more for
  complex conditional infra than the fairly standard VPC/EKS/node-group
  shape this project needs.

## AWS CDK

- **Pro**: first-party AWS tool, TypeScript/Python, synthesizes to
  CloudFormation so it inherits AWS's own drift detection.
- **Con**: AWS-only — zero portability as a demonstrated skill, and
  generates CloudFormation under the hood, which is slower to apply and
  has a worse multi-resource dependency-graph story than Terraform's own
  planner. Ties the project to a single cloud's tooling for no benefit
  given AWS was already the fixed target regardless of IaC tool.

## Raw CloudFormation / ClickOps

- **Pro**: CloudFormation needs nothing extra installed; ClickOps needs
  nothing at all.
- **Con**: CloudFormation's JSON/YAML is significantly more verbose than
  HCL for the same resource graph, with a smaller module ecosystem.
  ClickOps is explicitly the failure mode this Open Decision exists to
  avoid — the vault already lost track of `infra/docker-compose.yml`'s
  real state once (see [[infra]]'s "this page was stale" note); undocumented
  console state at the cloud-account level is the same mistake at higher
  cost.

# Decision

**Terraform.** Modules split by concern under `infra/terraform/modules/`
(`network`, `eks`, `data-plane`, `observability`), one root module per
environment (`infra/terraform/envs/staging/`, `.../prod/`) matching the
Kustomize overlay split already decided for staging shape — infra state
and k8s manifest state both fork at the same environment boundary, never
independently. Remote state in S3 with a DynamoDB lock table, one state
file per environment.

Terraform provisions: VPC, an EKS cluster + node groups, IAM roles for
IRSA (pod-level AWS permissions, e.g. media-service's S3 access), and the
EC2/node-group capacity Citus/Kafka/Redis run on. It does not provision
application-level k8s objects (Deployments, Services, ConfigMaps) — that
boundary is deliberate and is exactly what the next Open Decision
(manifest delivery) owns.

# Why

AWS was already fixed by [[ADR-019-cdn-vendor-choice]]; this decision is
purely about tooling on top of that fixed target. Terraform's module
ecosystem covers this project's actual AWS surface (VPC/EKS/node-groups)
directly, its remote-state model is a solved problem at solo-project
scale (S3 + DynamoDB, no server to run), and it's the most transferable
IaC skill to have demonstrated — unlike CDK, which buys AWS-native
convenience at the cost of being a single-cloud-only skill for a project
that was never going to be multi-cloud anyway, so that convenience has no
matching payoff. Pulumi's real-language advantage doesn't pay for itself
against a fairly standard VPC/EKS/node-group shape with no complex
conditional infrastructure to justify it.

# Consequences

- Unblocks the manifest-delivery Open Decision (ArgoCD/Flux/
  kubectl-from-CI/Helm) — that decision was waiting on a cluster existing
  to deliver manifests *to*.
- A Terraform state file becomes a real operational object: S3 backend +
  DynamoDB lock table must be bootstrapped once, by hand, before any other
  `terraform apply` — the one deliberate exception to "everything is
  reproducible," since state storage can't provision itself.
- EKS specifically (not self-managed kubeadm/k3s) is now implied as the
  cluster flavor, since ADR-019 already fixed AWS as compute and EKS is
  the AWS-native path Terraform's own module ecosystem best supports.
  This was not a separately raised Open Decision and is treated as a
  direct consequence of ADR-019 + this ADR together, not a new decision
  requiring its own options analysis.
- No deploy step exists yet. [[ADR-038-ci-platform]] explicitly deferred
  this; this ADR provisions infrastructure, it does not wire CI to it.

# Revisit When

- EKS control-plane cost becomes a real burden for a solo portfolio
  project — a self-managed k3s-on-EC2 cluster would remove that fee at
  the cost of losing managed control-plane upgrades.
- The project stops being solo and Terraform's plan/apply workflow needs
  real collaboration guardrails (Terraform Cloud/Enterprise, or a
  CI-gated apply) beyond a DynamoDB lock table.
- Manifest delivery (next decision) turns out to want a tool that also
  wants to own infra provisioning (e.g. a Crossplane-style
  infra-as-k8s-object approach) — would fold this decision into that one
  rather than keeping them separate.
