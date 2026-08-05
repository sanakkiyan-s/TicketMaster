# /ingest

Reconcile the wiki against what actually changed in code and in `raw/`
since the last ingestion.

## Steps

1. Read `second-brain/.claude/ingest-state.json`.
2. For each tracked repo entry:
   a. Resolve `path` relative to project root.
   b. Get current `HEAD` SHA (`git -C <path> rev-parse HEAD`). If the repo
      has no commits yet, treat as "nothing to diff" and skip to step 3.
   c. If `lastIngestedCommit` is null/empty, this is first ingestion —
      review the full current tree of the repo instead of a diff.
   d. Otherwise diff: `git -C <path> diff <lastIngestedCommit>..HEAD --stat`
      then inspect the full diff for files that matter (source, config,
      migrations, build files) — skip generated/build artifacts.
3. Check `second-brain/raw/*/` for files newer than the last `/ingest` run
   (compare against the timestamp of the last `wiki/log.md` entry, or ask
   the user which files are new if unclear).
4. For each changed/added item, determine which wiki page(s) it affects:
   - New/changed controller, service, entity → relevant `wiki/projects/<repo>.md`
     and any `wiki/domains/*.md` that owns that logic.
   - New/changed API endpoint → `wiki/api/`.
   - New/changed schema/migration → `wiki/data/`.
   - New/changed security config → `wiki/security/`.
   - New/changed messaging/topic → relevant `wiki/flows/*.md` and `wiki/domains/*.md`.
   - New doc in `raw/` → the wiki page(s) it informs; note the citation.
5. Read the changed source files / raw docs fully before writing anything.
6. Update affected wiki pages:
   - Bump `last-updated` in frontmatter.
   - Update `## Current Implementation` vs `## Target Design` vs `## Gap`
     sections precisely — don't blur them.
   - If code and `raw/` docs now disagree, write the mismatch explicitly
     instead of picking one silently.
7. Create a new wiki page only if something genuinely new needs one (new
   service, new domain, new critical flow) — not for every small change.
8. Update `wiki/index.md` if pages were added/removed.
9. Append one dated entry to `wiki/log.md` summarizing what changed (use
   the `/log` format).
10. Only after all of the above succeed, update
    `ingest-state.json.repositories.<repo>.lastIngestedCommit` to the new
    `HEAD` SHA.

## Hard rules

- Never edit files under `second-brain/raw/`.
- Never edit application source code (`backend/*`, `frontend/`, `infra/`).
- If code and docs disagree, document the mismatch — do not silently
  resolve it in either direction.
- Don't rewrite `wiki/log.md` history — append only.
