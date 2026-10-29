---
name: search-best-practices-and-learnings
description: Retrieve the established best practices and learnings for a unit of work by dispatching the `traverse-best-practices-and-learnings` subagent. Use before planning, implementing, or reviewing any coding task, or whenever the user asks to look up, search, find, or surface best practices or past learnings. 
---

# Find best practices and learnings

## Inputs

- **`context`** (required) — the WHOLE work framing the doer would receive: the prompt plus referenced spec/material and stack/domain hints. Treat it as raw and possibly un-distilled; do not assume the caller pre-selected anything. Read referenced files with `Read` only to understand the work. **Code is the exception:** reduce any code in or referenced by the context to a short natural-language summary — its purpose and shape plus the languages, frameworks, libraries, patterns, and idioms it exhibits — and reason about matches from that summary, not the raw blob.
- **`scope-path`** (required) — the absolute path to the calling scope (a feature or epic directory). The learnings flow starts here and walks upward. The scope directories are `.planning/<EPIC-ID>-<epic>` for an epic, `.planning/<EPIC-ID>-<epic>/<ID>-<feature>` for a feature inside one, and `.planning/<ID>-<feature>` for a standalone feature — there is no `epics/` segment.
- **`return_mode`** (optional) — `"content"` (default) or `"manifest"`; see `## Manifest mode`.

## Output

In the default `"content"` mode the returned envelope is a JSON object carrying `agent` (`"traverse-best-practices-and-learnings"`) and four arrays, all always present (possibly empty). For `"manifest"` mode see `## Manifest mode` below.

- **`best_practices[]`** — matching best-practice documents, content VERBATIM and WHOLE. A leaf is collected entire or not at all; there are no section-scoped returns. Item: `{ source_path (absolute best-practice-document path), content (verbatim markdown) }`.
- **`learnings[]`** — matching `<learning>` blocks, VERBATIM. Item: `{ source_path (absolute learnings-file path), content (verbatim block) }`.
- **`flagged[]`** — kept-on-uncertainty hits; judge relevance yourself. Item: `{ kind ("best_practice" | "learning"), source_path, content, reason }`.
- **`conflicts[]`** — unresolved contradictions; decide between them yourself. Item: `{ summary, candidates (≥2 of { kind, source_path, content }) }`. A candidate's `content` is the contradicting claim verbatim — narrower than the collected piece; the piece itself still appears in its own array.

## Manifest mode

`return_mode: "manifest"` returns the same envelope with every `content` field dropped and nothing else changed — same `agent`, the same four arrays always present, the same items minus their bodies. No field is added, and each file appears at most once per array.

- **`best_practices[]`** / **`learnings[]`** — item: `{ source_path }`.
- **`flagged[]`** — item: `{ kind, source_path, reason }`.
- **`conflicts[]`** — item: `{ summary, candidates (≥2 of { kind, source_path }) }`. The `summary` stays: it is the conflict's own, not a body.

```json
{
  "agent": "traverse-best-practices-and-learnings",
  "best_practices": [ { "source_path": "<library root>/typescript/type-system.md" } ],
  "learnings": [ { "source_path": "<abs scope path>/learnings/graphql-codec.md" } ],
  "flagged": [ { "kind": "best_practice", "source_path": "<absolute path>", "reason": "<why uncertain>" } ],
  "conflicts": [ { "summary": "...", "candidates": [ { "kind": "best_practice", "source_path": "..." }, { "kind": "learning", "source_path": "..." } ] } ]
}
```

Use manifest where the consuming work records which guidance governs it rather than reproducing it — see `## Consuming the result`.
