# /query

Read-only vault lookup. Answers project questions from the vault, verifying
against source when correctness matters.

## Steps

1. Read `wiki/index.md`.
2. Identify pages relevant to the question (architecture, domain, flow,
   decision, or project pages).
3. Read those pages fully.
4. If the question is implementation-critical (affects what code should be
   written, or claims something about current behavior), open the cited
   source files and re-verify the claim against actual code — wiki pages
   can be stale.
5. Answer, citing which wiki page(s) informed the answer.
6. Explicitly separate `Current behavior` from `Planned/target behavior`
   when they differ.
7. Surface relevant `## Open Questions` instead of silently picking an
   answer for an unresolved one.
8. If the wiki contradicts the source code or `raw/` docs, say so plainly:
   name the page, name the conflicting evidence, offer to fix the page.

## Hard rules

- Never modify code.
- Never modify `raw/`.
- May propose a wiki edit, but only apply it if asked (or if the
  contradiction is minor and the fix is obvious — state that you did it).
