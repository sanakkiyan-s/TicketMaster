# second-brain/ — Vault Rules

## Purpose

Persistent knowledge system for the TicketMaster project. Lets any AI
session (or future you) pick up full context — decisions, architecture,
domain model, progress, rejected approaches, open questions — without
re-deriving prior reasoning or drifting from the established architecture.

This is a **portfolio project**: a Ticketmaster-inspired event ticketing
platform, built to demonstrate Java backend / system-design engineering
judgment. Microservices architecture chosen deliberately (see
[ADR-001](wiki/decisions/ADR-001-microservices-vs-modular-monolith.md)) —
every service must independently justify its existence, not exist because
"microservices are expected."

## Structure

```
second-brain/
├── CLAUDE.md              this file
├── raw/                   source material, owned by me, AI NEVER edits
├── wiki/                  AI-synthesized knowledge, derived from raw/ + actual code
│   ├── index.md           master catalog — read this first, always
│   ├── log.md             append-only activity log
│   ├── architecture/      system-wide maps, diagrams
│   ├── concepts/          durable cross-cutting knowledge
│   ├── decisions/         ADRs
│   ├── domains/           bounded-context docs (booking, payment, ...)
│   ├── flows/             step-by-step critical business flows
│   ├── projects/          one page per repo/service
│   ├── infrastructure/    deployment, CI/CD, Docker, observability
│   ├── api/                API contracts
│   ├── data/                schema/data-ownership docs
│   ├── security/            auth, authz, secrets handling docs
│   └── testing/             test strategy docs
├── journal/                daily notes (format defined when first used)
├── content/                 content pipeline (format defined when first used)
└── .claude/
    ├── commands/            /ingest /query /lint /log
    └── ingest-state.json    last-ingested commit SHA per repo
```

`people/` intentionally omitted — solo project.

## Ownership boundaries

- **`raw/`** — mine. AI never creates, edits, or deletes anything here.
  Source: Claude/ChatGPT exports, Notion/Google Docs specs, Figma
  screenshots, meeting notes, articles, misc notes.
- **`wiki/`, `journal/`, `content/`** — AI's domain. Synthesized from
  `raw/` *and* from actually reading the source code in `backend/*`,
  `frontend/`, `infra/`.
- **Application repos** (`backend/*`, `frontend/`, `infra/`) — AI never
  edits these from a vault task (`/ingest`, `/query`, `/lint`, `/log`)
  unless explicitly asked to implement something.

## Source authority order

1. **Actual source code** — current truth. "What does it do right now?"
2. **`raw/` docs** — intended truth. "What is it supposed to do?"
3. **`wiki/`** — fast entry point, can go stale. Verify against code
   before relying on it for implementation-critical decisions.

When two disagree: **say so out loud**. Write both:

```
Current implementation: ...
Intended behavior: ...
Mismatch: ...
```

Never silently pick one. Never silently work around a stale wiki page —
flag it, offer to fix it.

## Wiki page conventions

Every page starts with frontmatter:

```yaml
---
title:
type: concept | entity | source-summary | comparison | project | person | architecture | decision | domain | flow
sources:
related:
created:
last-updated:
---
```

- One page = one idea. Split rather than let a page grow into a dumping ground.
- Link related pages with `[[wiki-links]]` (vault-relative page name, no extension).
- Doc-derived claims cite the vault-relative raw path:
  `raw/google-docs/ticket-booking-spec.pdf`
- Code-derived claims cite the exact file, not just the repo:
  `backend/inventory-booking-service/src/main/java/.../SeatHoldService.java`
- `## Open Questions` is a real commitment list, not filler. Delete an
  entry the moment it's resolved — move the resolution into an ADR or the
  page body instead of leaving the question there answered.
- Distinguish `## Current Implementation` from `## Target Design` from
  `## Gap` wherever a feature is partially built. Never blur "what exists"
  with "what we decided to build."

## ADRs (`wiki/decisions/`)

Naming: `ADR-NNN-short-slug.md`, sequential, never reused.

Required sections: `# Context`, `# Requirements / Constraints`,
`# Options Considered` (pros/cons per option), `# Decision`, `# Why`,
`# Consequences`, `# Revisit When`.

Only write an ADR for a decision actually made. Never fabricate a decision
to populate the vault. If a decision changes, mark the old ADR
`Status: Superseded by ADR-NNN` — never rewrite or delete it.

## Session startup procedure (every session, every repo)

1. Read `wiki/index.md`.
2. Follow links relevant to the task.
3. Inspect the actual source of the repo in question before any
   implementation-critical decision.
4. Check relevant ADRs.
5. Check `## Open Questions` on relevant pages.

Only then: answer, plan, propose, design, or implement.

## `/ingest`

Diffs each tracked repo against `ingest-state.json`'s last SHA, reconciles
wiki pages against what actually changed, updates `wiki/index.md`, appends
to `wiki/log.md`, updates the SHA only after a successful pass. Never edits
`raw/` or application code. See `.claude/commands/ingest.md`.

## `/query`

Read-only. Answers from `wiki/index.md` + linked pages + source
verification when correctness matters. Distinguishes current vs. planned
behavior, surfaces open questions, flags stale/contradictory wiki content.
Never modifies code. See `.claude/commands/query.md`.

## `/lint`

Validates the vault: frontmatter, broken links, missing/dangling
citations, unindexed pages, stale index entries, duplicate concepts,
oversized pages, stale open questions, ADR reference integrity,
current-vs-target ambiguity. Produces a report, doesn't auto-rewrite.
See `.claude/commands/lint.md`.

## `/log`

Appends a dated entry to `wiki/log.md` (Added / Changed / Decisions /
Architecture / Opened Questions / Resolved Questions — only sections with
real content). Append-only, never rewrites history. See
`.claude/commands/log.md`.

## Final rule

Understand first. Verify second. Design third. Implement fourth. Document
continuously. Never redesign something because the reason for it was
forgotten — that reason lives in this vault; go read it.
