---
title: ADR-042 Manifest Delivery
type: decision
sources: []
related: [[implementation-roadmap]], [[ADR-041-iac-tooling]], [[ADR-038-ci-platform]], [[ADR-010-secrets-management]], [[ADR-033-non-secret-config-management]], [[infra]]
created: 2026-08-20
last-updated: 2026-08-20
---

Status: Accepted

# Context

[[ADR-041-iac-tooling]] decided how the EKS cluster itself gets
provisioned (Terraform), but explicitly scoped Terraform to
infrastructure only — VPC, node groups, IRSA. It deliberately left
"application-level k8s objects (Deployments, Services, ConfigMaps)" as a
separate decision: how manifests actually reach a cluster that now exists.
This was the second half of the "Deployment provisioning gap" opened
2026-08-14.

# Requirements / Constraints

- Must not hand a cluster-admin (or broad deploy-scoped) credential to
  GitHub Actions. The repo is public-facing enough (portfolio project) that
  a leaked CI secret with cluster write access is a real blast-radius
  concern, not a theoretical one.
- Must integrate with the Kustomize staging/prod overlay split already
  decided (staging environment shape decision, 2026-08-20) without adding
  a second templating layer on top.
- Must catch manual cluster drift — the vault has already lost track of
  real infra state once before automation existed (`infra.md`'s "this
  page was stale" note); the same failure mode at the k8s-object level is
  worth actively guarding against, not just hoping doesn't happen.
- Zero additional infrastructure to operate beyond what runs on the
  cluster Terraform already provisions — same solo-project constraint
  [[ADR-038-ci-platform]] and [[ADR-041-iac-tooling]] both already applied.
- [[ADR-038-ci-platform]] explicitly deferred the deploy step and flagged
  that a push-based deploy would live in CI while a pull-based one would
  not — this decision is what resolves that fork.

# Options Considered

## ArgoCD

- **Pro**: pull-based GitOps — the cluster reconciles itself against git,
  CI never holds a deploy credential. Continuous drift detection and
  self-healing, directly answering the drift concern above. Native
  Kustomize support, no extra templating layer. Runs as an in-cluster app
  on infrastructure Terraform already provisions, not a separate server.
  Has a real web UI — genuine portfolio value: a working GitOps pipeline
  with visible sync status/history is a stronger demonstrated artifact
  than a green CI checkmark alone.
- **Con**: another moving part to configure (RBAC, ingress for the UI,
  App-of-Apps structure for 15 services) — real setup cost, even though it
  doesn't require ongoing patching/backup the way a standalone server would.

## Flux

- **Pro**: same CNCF GitOps model as ArgoCD — pull-based, self-healing,
  Kustomize-native (arguably more Kustomize-native, being
  Kustomize-controller-first rather than ArgoCD's broader
  Helm/Kustomize/plain-manifest support).
- **Con**: no first-party web UI (Flux's dashboard story is thinner /
  relies on separate tooling like Weave GitOps or Capacitor). For a
  portfolio project where a demo screenshot has real value, this is the
  deciding gap against an otherwise near-equivalent tool.

## kubectl-from-CI (push-based)

- **Pro**: simplest possible mechanism — no in-cluster controller to run
  at all, `kubectl apply -f` at the end of the existing GitHub Actions
  workflow.
- **Con**: fails the credential-blast-radius requirement directly — CI
  needs a real deploy-scoped kubeconfig or OIDC role, sitting in a
  public-repo workflow. No drift detection: a manual `kubectl edit`
  against the live cluster is invisible and never reconciled back. This is
  exactly the ClickOps-adjacent risk [[ADR-041-iac-tooling]] already
  rejected for infrastructure; adopting it one layer up for application
  manifests would be inconsistent with that decision's own reasoning.

## Helm (as a delivery mechanism)

- Not actually competing with the above — Helm is a packaging/templating
  tool, not a delivery mechanism. It answers "how is a manifest
  parameterized," not "how does a manifest reach the cluster." Either
  ArgoCD or Flux can apply Helm charts directly; this project's existing
  overlay-based approach (Kustomize) doesn't currently need Helm's
  templating on top, so it isn't adopted separately here.

# Decision

**ArgoCD.** One ArgoCD instance running in the EKS cluster Terraform
provisions, watching this repo's `k8s/overlays/{staging,prod}/`
directories (App-of-Apps pattern: one root Application per environment,
child Applications per service, so 15 services don't mean 15 hand-wired
ArgoCD objects). Auto-sync enabled with self-heal on, so manual drift
reverts automatically rather than silently persisting.

CI's role stops at build+test+push-image
([[ADR-038-ci-platform]] — unchanged by this decision). CI never applies
anything to the cluster; it only updates the image tag reference in the
relevant overlay, which is the one write CI needs, scoped to a single
repo file, not a cluster credential.

# Why

The credential-blast-radius requirement rules out kubectl-from-CI outright
— pull-based GitOps was the actual reason [[ADR-038-ci-platform]] left
deploy unresolved rather than just bolting on a `kubectl apply` step at
the time. Between ArgoCD and Flux, both satisfy every hard requirement
equally; ArgoCD's UI is the deciding factor for a portfolio project
specifically, the same kind of portfolio-value reasoning
[[ADR-041-iac-tooling]] already applied when picking Terraform over CDK.

# Consequences

- Closes the "Deployment provisioning gap" opened 2026-08-14 — both halves
  (cluster provisioning, manifest delivery) are now decided. Nothing has
  been *applied* yet; this and [[ADR-041-iac-tooling]] are still Phase 5/6
  work per [[ADR-036-build-order-and-phasing]].
- `k8s/` manifest directories (base + overlays) don't exist yet and need
  creating alongside the first real Terraform apply — this ADR specifies
  their shape (Kustomize, App-of-Apps) but doesn't create them.
- CI's image-tag-bump write to the overlay repo needs its own
  narrowly-scoped write credential (a PAT or GitHub App token limited to
  that path), distinct from — and much smaller than — the cluster-admin
  credential this decision was written specifically to avoid.
- ArgoCD's own secrets (repo-read credentials, any private registry auth)
  are managed the same way as every other service's secrets —
  [[ADR-010-secrets-management]]'s Vault AppRole pattern, not a
  bespoke ArgoCD-specific mechanism.

# Revisit When

- The project stops being solo and ArgoCD's RBAC/multi-team project
  isolation features start mattering — currently unused at solo scale.
- CI wall time or workflow complexity makes bundling image-build and
  overlay-bump into one job worth splitting into two.
- A future need for progressive delivery (canary/blue-green) arises —
  would likely add Argo Rollouts on top rather than reopening this
  decision.
