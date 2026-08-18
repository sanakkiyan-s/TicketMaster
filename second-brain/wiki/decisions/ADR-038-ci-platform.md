---
title: ADR-038 CI Platform
type: decision
sources: []
related: [[implementation-roadmap]], [[ADR-034-rest-edge-versioning-openapi]], [[ADR-008-testing-strategy]], [[ADR-023-grpc-internal-service-calls]], [[ADR-036-build-order-and-phasing]]
created: 2026-08-18
last-updated: 2026-08-18
---

Status: Accepted

# Context

`implementation-roadmap.md` has carried "CI runner/platform (GitHub Actions
vs GitLab CI vs Jenkins)" as an Open Decision since the roadmap was written.
It stopped being theoretical once three separate decisions started depending
on a CI runner that does not exist:

- [[ADR-034-rest-edge-versioning-openapi]] requires a step that diffs the
  generated OpenAPI spec against the committed one and flags drift.
- [[ADR-023-grpc-internal-service-calls]] requires `buf breaking` on every
  `.proto` change, and that one BLOCKS.
- [[ADR-008-testing-strategy]]'s integration tier requires a Docker daemon on
  the runner, because those tests start real Postgres containers via
  Testcontainers rather than mocking the database.

Until this is settled, all three are decisions with no enforcement.

# Requirements / Constraints

- Must provide a Docker daemon on the runner (non-negotiable: ADR-008).
- Must cache the Gradle dependency graph. 15 Spring Boot modules is a large
  resolve to repeat per run.
- Monorepo: backend and frontend must fail independently.
- Zero infrastructure to operate. This is a solo portfolio project; a CI
  system that itself needs maintaining is a cost with no upside here.
- The repo already lives on GitHub.

# Options Considered

## GitHub Actions

- **Pro**: already where the code is, so no second account, no webhook wiring,
  no mirroring. `ubuntu-latest` ships a running Docker daemon, so ADR-008's
  Testcontainers tier works with no setup step. First-party Gradle and Node
  caching actions. Free for public repositories.
- **Con**: vendor-coupled — workflow syntax does not port. Self-hosted runners
  are possible but are exactly the operational cost this project does not want.

## GitLab CI

- **Pro**: strong monorepo support (`rules:changes` for path filtering), and a
  genuinely better pipeline DSL for complex graphs.
- **Con**: the repository is on GitHub. Adopting it means mirroring or moving,
  which is real work whose only benefit is a DSL preference. Docker-in-Docker
  needs deliberate configuration for Testcontainers.

## Jenkins

- **Pro**: maximum control, plugin for everything, runs anywhere.
- **Con**: it is a server. Somebody patches it, backs it up, and fixes it when
  a plugin update breaks a build. For one developer that is the single largest
  operational burden on the list, and it buys nothing the other two lack.

# Decision

**GitHub Actions**, one workflow at `.github/workflows/ci.yml`, with two
independent jobs.

- `backend`: JDK 21 (Temurin), `gradle/actions/setup-gradle@v4` for caching,
  `./gradlew build`, then ADR-034's spec-drift check, then test reports
  uploaded with `if: always()` so they survive a failed build.
- `frontend`: Node 20, `npm ci`, `npm run typecheck`, `npm run build`.

Two jobs rather than one, deliberately: in a single job the first failure
masks everything after it, so a broken frontend hides a broken backend and the
result is one red X that says nothing about which half is wrong.

`concurrency` with `cancel-in-progress` — a second push to a branch makes the
first run's verdict worthless, so paying for it is waste.

The spec-drift step uses `continue-on-error: true` and emits a `::warning`.
This matches ADR-034's own wording: it FLAGS, it does not block, because
pre-launch REST evolution is deliberately more fluid than the gRPC contract.
`buf breaking` will block when protos exist. The distinction is intentional
and should not be "tidied up" into consistency.

# Why

The repository is on GitHub, `ubuntu-latest` has the Docker daemon ADR-008
needs without any setup step, and there is no server to operate. GitLab CI's
advantages are real but are paid for with a migration whose only return is
pipeline syntax; Jenkins' flexibility is paid for in ongoing maintenance,
which for a solo project is the worst trade available.

Vendor coupling is accepted with eyes open: the *logic* being enforced (build,
test, diff a spec, check protos) is all in Gradle and CLI tools, so a move
would rewrite the YAML around it, not the checks themselves.

# Consequences

- ADR-034's drift gate becomes real, for the first time. It has already proven
  itself locally: adding the JWKS endpoint regenerated the committed spec with
  no manual step.
- ADR-023's `buf breaking` step is still NOT implemented — the protobuf plugin
  is declared `apply false` at the root and applied by no module, so there are
  no `.proto` files to check. That gap is unchanged by this ADR and stays open.
- Testcontainers on the runner may hit the same docker-java API negotiation
  problem seen locally on Docker Engine 29.x. The root build already pins
  `systemProperty("api.version", "1.44")`, which a runner's older engine
  accepts, since servers support older API versions than their own.
- No deploy step. Deployment is blocked on the IaC and manifest-delivery Open
  Decisions, which this ADR does not touch.

# Revisit When

- The project stops being solo, or a private repo makes minutes billable
  enough to matter.
- Deployment is decided, since a push-based deploy would live here while a
  pull-based one (ArgoCD) would not.
- CI wall time grows enough that per-path job filtering is worth the
  complexity — GitHub Actions is weaker at that than GitLab CI, and it is the
  most likely reason this decision gets revisited.
