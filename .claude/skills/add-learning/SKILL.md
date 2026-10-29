---
name: add-learning
description: Record a durable learning when you have encountered something unexpected that a future agent is likely to need to know. 
allowed-tools: Read, Write, Edit, Bash
---

<global-standards>

## Communication

Terse in every response, however many turns in, and in everything you author. Skip non-essential context, keep examples minimal, no filler or hedging. Keep articles and full sentences; professional but tight. Technical terms exact, code blocks unchanged, errors quoted exact. 

Write for your real audience — never for reviewers — and never narrate the thing being written.

When referencing a document, give its path at least once plus the section or lines; with several documents in play, name which. Omit the path within the same document. Applies in the console and in documents.

</global-standards>

# Add a learning

Record one learning on its own.

## What you need

- The learning itself: what happened, why it matters, what to do differently.

## Judge first

Record a learning only when BOTH hold:

1. **Non-obvious** — a fresh agent would get it wrong or waste effort without it. Test it stripped of provenance and repo-internal pointers: if a competent agent could derive what remains in under a minute, drop it.
2. **It transfers** — true beyond this one instance, and it changes what a future agent does. A learning that only records what the project's code currently does fails this test; that belongs in the project's own documents.

Anything that fails either test:
- something that can be actioned immediately - do it.
- something that requires action but should be deferred - add new work item
- anything else - drop.

This skill records learnings and nothing else. An observation that routes elsewhere stops here: recommend to the user the correct course of action.

## Write the learning

<add-learning>

Record a learning in `.docs/learnings/` directory. Do the edits yourself. 

Do not re-judge the learning within `<add-learning>`, except as described below.

Do not commit within `<add-learning>`.

If `.docs/learnings/INDEX.md` is missing, create from the template `<learnings-index-md>`.

<writing-learnings>

## Writing a learning

Append, supersede or amend a `<learning>` block in a kind file and keep `INDEX.md` current. You edit the files yourself.

A register is an `INDEX.md` plus one file per kind. `add-epic` and `add-feature` create `INDEX.md` at scaffold time; if it is missing, create it from <learnings-index-md> verbatim before writing.

### Order of work

Check the register first: read `INDEX.md` and the candidate kind files and apply the `<keep-current-rules>` below — dedup, supersede, amend, merge, split — before you write. With more than one learning to record, group the batch by kind and write each group together; never mint a kind for a learning that a sibling in the batch already places.

### Pick the kind file

A **kind** is a class of learnings a future agent looks up together — the question it is holding when it opens the register, not the topic of any one learning.

Match against the rows in the target `INDEX.md`, judging each by the class its description names, not by keyword overlap with this learning's wording. Take the first kind whose lookup question this learning answers.

**Default to widening.** A learning at the edge of an existing kind goes in that file, and that file's `description` widens to cover it. Prefer widening even when the fit is imperfect.

**Minting is the exception.** Create a kind file and its `INDEX.md` row only when the learning answers a lookup question no existing kind covers AND you can write a `description` that will hold further learnings. If the description you would write only describes the learning in hand, you have not found a kind — widen an existing one. Before minting in a feature's or epic's register, read `.docs/learnings/INDEX.md` and reuse a kind name from there when one fits, so closeout roll-up merges by name instead of creating a parallel kind.

### Write the learning

Write the learning using `<clean-content>` rules below.

1. If adding a new kind file, write the frontmatter `<learnings-frontmatter>` followed by the learning record `<learning-record>`.

2. If using an existing kid file, update the frontmatter if required.
   - If the learning modifies an existing learning, delete the old learning
   - Append the new learning with the date it was created or last updated

3. Keep `INDEX.md` current. Every kind file has exactly one `INDEX.md` row, and the row's description is identical to the file's frontmatter `description`. Widening a kind updates both. Minting adds a row; merging deletes the merged file's row; splitting adds the new kind's row and rewrites the original's.

**Created or updated date** Take today's date in ISO `YYYY-MM-DD` from the session context or `date +%F`; never guess it. Amending or superseding a block updates its `date`; a merge or split moves bodies and their dates verbatim.

<clean-content>

- **Write for the audience** — design, planning, and execution agents.
- **State the fact, why it bites, and the rule** — that is the whole block; the rest is scaffolding.
- **No meta** — the learning does not justify its own existence; drop self-commentary.
- **Self-contained** — the learning can be read on its own without other learnings.
- **No provenance narration** — no session or review history, no "measured, not reasoned", no "the intuitive answer was wrong", no inline `SUPERSEDED:` / `AMENDED:` markers. A learning states what is true now: fold a correction in and delete the belief it corrects. Record an open question only where the uncertainty is itself the operational fact.
- **Cite outward, not inward** — cite external code freely: library symbols, library source paths, versions, upstream issues. Do not cite the project's own code: no project-internal file paths, no `File.ext:123-145` line ranges, no names of its classes, suites, or fixtures. They rot within weeks, and they are not the learning — state the behaviour and the rule instead. The one exception is a learning whose subject *is* a project-internal command or gate, where the commands and output paths a reader must type are the learning. This governs what a learning body cites; where the global `## Communication` standard requires paths and line numbers for document references, that standard governs how the agent references documents in its own output, not what a learning body cites.
- **Simplify language** — terse; no filler or hedging; keep articles and full sentences; professional but tight. Technical terms exact; code blocks unchanged. No ALL-CAPS emphasis that disambiguates nothing.
- **No unnecessary repetition** — for any repeated content, cut it unless the repetition is necessary, including end-of-file reference lists. Do not restate a point already made in the same block.

</clean-content>

<keep-current-rules>

Apply these at both levels: `<learning>` blocks within a file, and kinds across the register.

### Within a file

- **Supersede** — a new learning replaces an existing one: remove the old, add the new.
- **Amend** — a new learning updates an existing one: remove the old, add a merged entry carrying all *current* information of both.
- **No-longer-applies** — an existing learning is obsolete, or its information now lives in a more specific docs file: remove it.

### Across kinds

The register must be able to shrink, not only grow. While reading `INDEX.md` to pick a kind, judge the register's shape. Two signals mean consolidate before writing: more rows than a register readable at a glance holds — roughly a dozen kinds at most — or rows whose descriptions read as summaries of a single learning.

- **Merge** — two kinds whose descriptions answer the same lookup question, or a kind that has held one learning across several visits and belongs inside a broader neighbour: move the blocks verbatim into the surviving file, widen its `description`, delete the merged file, update `INDEX.md`.
- **Split** — a kind whose learnings have outgrown its description, so a reader arriving on that description would not expect half of what is there: move the outlying blocks verbatim into a new kind, narrow the original's `description`.
- **Bodies move verbatim.** A merge or split rewrites frontmatter descriptions and `INDEX.md` rows only; learning bodies and their `date` values are never re-authored.

Do not merge two kinds whose audiences differ — one incident can be a learning for two kinds of reader. Cross-reference them from each `description` instead.

</keep-current-rules>

## Templates

<learnings-index-md>

### `learnings/INDEX.md`

```markdown
# Learnings index

Each row is one kind — a class of learnings a future agent looks up together — held in the per-kind file of that name in this directory. Kinds are coarse: widen an existing one before minting a new one. A description states what class the file holds and when to read it, not what is currently in it.

| Kind | Description |
|---|---|

## Curating learnings

Edit this register only after reading the `add-learning` skill (`.claude/skills/add-learning/SKILL.md`), and always follow its rules.
```

</learnings-index-md>

<learnings-frontmatter>

```markdown
---
kind: {file name without extension}
description: {the same text as the INDEX.md row}
---
```

`description` states **what class of learning this file holds and when to read it** — a routing line, never a summary of the blocks in it. One or two sentences, under roughly 35 words. Where the kind has a triggering event, name it ("Read before …", "Read when …").

</learnings-frontmatter>

<learning-record>

```markdown
<learning>
date: {YYYY-MM-DD}
---
{Markdown learning body}
</learning>
```

</learning-record>

</writing-learnings>

</add-learning>

<routing-observations>

## Routing observations

Route every observation the work surfaced, one at a time. Apply branches 1 to 3 in order, first match wins; then check branch 4 as well, whichever of the first four matched.

### 1. Do nothing

Drop the observation when it needs no action, because it is already recorded or because there is nothing in it to act on:

- The code that implements a settled contract, interface or behaviour is authoritative; do not copy it into a document.
- The owning document, a best-practices document, an agent, or the scope's learnings already says it.
- It is narration of what you did, or transient state (what you tried, which file you opened first).

A fact about code that is not yet written is recorded nowhere — it falls through to branch 2 or 3.

### 2. Fix it in place now

Take this branch when you know the exact change, it is inside the scope you are already working in, and making it needs no separate review or approval. Make the edit now and route nothing further for it.

- A resolved ambiguity or a kept deviation belongs in the owning document: a behaviour decision goes to the feature specification, a cross-feature fact to the epic architecture.
- A wrong or stale line in code or a document gets corrected where it is wrong.

### 3. Create a task

Take this branch when the observation is actionable but you cannot or should not act now — it belongs to another scope, it is larger than the change in hand, or it depends on work not yet done. Bar: you can name the outcome someone would act on, and the scope that holds it. Write the task so it can be acted on without this session's memory: what to do, where, and what happens if it is not done.

### 4. Record a learning

Record a learning alongside an edit, task or risk only when it is a durable learning in its own right, not a restatement of what you just recorded, and only when BOTH hold: **a fresh agent would get it wrong or waste effort without it** — non-obvious, and it cost real discovery effort; test it stripped of provenance and repo-internal pointers, and drop it if a competent agent could derive what remains in under a minute — and **it transfers**: true beyond this one instance, and it changes what a future agent does. A learning that only records what the project's code currently does fails the second test; that belongs in the project's own documents. Drop anything that fails either test, or that the documents you edited in branch 2 now say.

</routing-observations>
