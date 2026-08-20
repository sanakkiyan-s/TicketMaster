# TicketMaster — Root Project Instructions

This project root contains multiple repositories/services plus a persistent
knowledge vault at `second-brain/`.

## MANDATORY: read the vault first

Before answering architecture questions, planning features, modifying
architecture, or writing code anywhere in this project:

1. Read `second-brain/wiki/index.md` first.
2. Follow relevant linked wiki pages (`architecture/`, `domains/`,
   `decisions/`, `flows/`, `projects/<service>.md`).
3. Inspect the actual source code of the relevant service before making any
   implementation-critical decision.
4. Check relevant ADRs under `second-brain/wiki/decisions/`.
5. Check `## Open Questions` sections on relevant pages.

Only after that: answer, propose, plan, or implement.

## Source authority order (never violate)

1. **Actual source code** — wins for "what does it do right now."
2. **`second-brain/raw/` documentation** — wins for "what is it supposed to
   do" (intent/spec). Never modify files in `raw/`.
3. **`second-brain/wiki/`** — synthesized, fastest way in, but can go stale.
   Treat as a strong hint. Verify against source code before relying on it
   for implementation decisions.

If any two of these disagree, **say so explicitly** — document the mismatch
(current implementation vs. intended behavior) rather than silently picking
one or working around it.

## Architecture stability

Do not redesign existing architecture just because another pattern is
possible. Check `second-brain/wiki/decisions/` (ADRs) before proposing an
architecture change. If an existing decision looks insufficient, explain
concretely why — cite the ADR you'd supersede.

## Repositories in this project

| Path | Service | Status |
|---|---|---|
| `backend/api-gateway` | Edge routing, auth token validation, rate limiting | in progress — see [[api-gateway]] |
| `backend/auth-service` | Identity, JWT issuance/refresh, roles | in progress — see [[auth-service]] |
| `backend/user-service` | User profile, payment methods on file, preferences | in progress — see [[user-service]] |
| `backend/event-service` | Events, sessions/shows, artist/performer data | not started |
| `backend/venue-service` | Venues, seating layout/seat maps | not started |
| `backend/search-service` | Denormalized search/discovery index (fed by event/venue events) | not started |
| `backend/inventory-service` | Seat inventory state machine (AVAILABLE/HELD/PURCHASED) — concurrency core | not started |
| `backend/booking-service` | Orchestrates hold → checkout → confirm, talks to inventory + payment | not started |
| `backend/queue-service` | Virtual queue / waiting-room admission control for high-demand on-sales | not started |
| `backend/payment-service` | Payment intents, webhook handling, idempotency, refunds | not started |
| `backend/ticket-service` | Digital ticket issuance, transfer, resale | not started |
| `backend/notification-service` | Email/SMS/push on booking/payment/event updates | not started |
| `backend/fraud-service` | Risk scoring, bot detection, velocity/bulk-limit checks | not started |
| `backend/analytics-service` | Organizer sales dashboards, reporting (async) | not started |
| `backend/media-service` | Object storage, video trailer upload + FFmpeg transcoding | not started |
| `frontend` | React/TypeScript client | in progress — see [[frontend]] |
| `infra` | Docker Compose, CI/CD, deployment config | not started |

See `second-brain/wiki/projects/` for the detailed page per repo, and
`second-brain/wiki/architecture/system-overview.md` for how they connect.

Full vault rules (ownership, citation format, ADR process, commands): see
`second-brain/CLAUDE.md`.
