## Project overview

This is a fork of `ScalaTest` that that keeps test output readable, by people and by tools, on parallel and multi-module runs. 

## Skills to use

- `design-loop` - discovering and designing work, at both epic and feature scale
- `search-best-practices-and-learnings` - when writing a technical design, or writing, testing or reviewing code. **After you have understood the problem, but before you act.** Do not use this for documentation, general architecture, or system design. When no `.planning/<feature>/` directory exists, pass `<repo-root>/.planning` as its scope-path, so that it searches `.docs/learnings/`.
- `codegraph` - before exploring, navigating, reviewing, or planning edits in code

### Document amendments

Edit documents in place. A document states only what is true now; git carries the amendment history.

Do not record amendment history inside the document: no dated
`> Corrected <date> during <scope>` restatements, no `SUPERSEDED:` / `AMENDED:`
markers, no ordinal amendment paragraphs. Delete the claim a change supersedes
and write the new one in its place.

A correction must reach every field that carries a claim — title, summary and
headers as well as body prose. A stale title reads as a live claim, and a stale
summary stops the document being found.

## Persisting durable knowledge

Do not use the auto-memory system on this project. Memory files live outside
the repo, are not visible to the user, and accumulate as garbage.

## Quoting shell commands

- Single-quote an argument holding `*` or `{`: `'package.Prefix*'`.
- On GitBash,
  - quote an argument containing spaces as `'"…"'`: `-z '"AC-007 An expected"'`.
  - always write `^` as `^^^^`.
  - always write `!` as `"\!"`. You must escape and you double quotes.
