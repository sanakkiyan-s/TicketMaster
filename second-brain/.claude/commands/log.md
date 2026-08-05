# /log

Append a dated entry to `wiki/log.md` recording meaningful project
activity.

## Steps

1. Determine today's date (`YYYY-MM-DD`).
2. If today already has a `## YYYY-MM-DD` section in `wiki/log.md`, append
   to it instead of creating a duplicate header.
3. Fill only the sections that have real content — omit empty ones:

```markdown
## YYYY-MM-DD

### Added
- ...

### Changed
- ...

### Decisions
- ...

### Architecture
- ...

### Opened Questions
- ...

### Resolved Questions
- ...
```

4. Append to the end of the file. Never edit or delete a prior day's entry.

## Hard rules

- Append-only. `wiki/log.md` history is never rewritten.
- If nothing meaningful happened, don't create an entry.
