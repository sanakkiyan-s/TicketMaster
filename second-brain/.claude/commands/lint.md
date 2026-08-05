# /lint

Validate the vault. Read-only — produces a report, does not bulk-rewrite.

## Checks

1. **Frontmatter** — every `wiki/**/*.md` (except `index.md`, `log.md`,
   `README.md`) has `title`, `type`, `sources`, `related`, `created`,
   `last-updated`. `type` is one of the allowed values.
2. **Broken wiki links** — every `[[link]]` resolves to an actual page.
3. **Missing/dangling citations** — every claim citing a `raw/` path or a
   repo source path: confirm the path exists.
4. **Unindexed pages** — every page under `wiki/` (except `index.md`,
   `log.md`) appears in `wiki/index.md`.
5. **Stale index entries** — every entry in `wiki/index.md` points to a
   page that still exists.
6. **Suspiciously stale pages** — `last-updated` far older than the
   related repo's last commit (per `ingest-state.json`), suggesting the
   page wasn't reconciled.
7. **Duplicate concepts** — two pages describing the same thing under
   different names.
8. **Oversized pages** — pages that have grown into multiple ideas and
   should be split.
9. **Stale open questions** — `## Open Questions` entries that a current
   ADR or page section has already answered but weren't removed.
10. **Contradictory architecture statements** — two pages asserting
    different things about the same component/flow without one citing the
    other as superseded.
11. **ADR reference integrity** — `Status: Superseded by ADR-NNN` points to
    an ADR that exists; superseding ADR links back.
12. **Current-vs-target ambiguity** — pages describing a partially built
    feature without clearly separated `## Current Implementation` /
    `## Target Design` / `## Gap` sections.

## Output

A report grouped by check, each finding as `page/path — issue`. End with a
short summary count. Do not silently fix anything found — list it, let the
user decide what to fix (small obvious fixes may be proposed inline, not
applied).
