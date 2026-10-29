---
name: design-loop
description: Work through a design too big for one agent session — a GitHub plan issue holding one sub-issue per open decision, resolved one at a time, each answer written into the plan's output. The output is a spec ready to implement by default, or another artefact the user's goal names.
disable-model-invocation: true
---

Use this when an idea is too big for one agent session and the path to the goal is unclear. Record what is known as a **plan** issue on GitHub with one sub-issue per open decision, then resolve those decisions one at a time, writing each answer into the plan's **output**, until the output meets its definition of done. Start at [Invocation](#invocation); the sections before it are reference.

A **ticket** is a question whose answer is a decision, not a slice of the build.

## The goal and the output

Name the goal first: it shapes every ticket and fixes the plan's **output**, the artefact every answer is written into and the next session reads for the current state instead of replaying every ticket.

- **A spec** (the default), done when it is ready to implement at its scale ([The spec](#the-spec), [Scale](#scale)). The rest of this skill is written for this case.
- **Another artefact**, only when the user names it in the goal or agrees to it while the goal is being named: a decision to lock before planning starts, a change made in place such as a data-structure migration, a research write-up, a domain model. Never infer this from the shape of the idea. The goal then states what the artefact is, where it lives, and its definition of done, because this skill supplies none.

For another artefact, read *output* wherever a section says *spec*, and take from [The spec](#the-spec) and [Scale](#scale) only what still applies: every answer lands in the output, decisions are recorded beside it, and the definition of done is the goal's.

## Decide, don't build

Every ticket answers a question: produce decisions, not deliverables. The urge to just do the work usually means the output is done and it is time to hand off to `build-loop`. The exception is a goal whose output is a change made in place; the plan's **Notes** say how much execution that includes.

## The spec

A spec is a set of documents, and its scale fixes what the set may hold:

- **Epic** — a specification, plus an architecture where the epic changes the system's shape.
- **Feature** — a specification, plus a technical design where the feature carries non-obvious technical choices.

The specification is always present and is the **root**: it states the behaviour and links every other document in the set. Add the second document only when it has something to say.

A spec may also link documents it is written **against** rather than ones it produces, such as a domain model or prior art. Reference their facts; never copy them into a requirement (`.claude/skills/design-loop/SPECIFICATION.md` § Anti-patterns, Design restated as obligation).

A plan whose output is a spec writes exactly one spec and links its root from the plan's **Spec** section; read which spec you are writing off the plan, never assume it. The specification links back to its plan (`.claude/skills/design-loop/SPECIFICATION.md` § Sections).

### Where it lives

A spec is a directory under `.planning/`, named for its scope, not for the plan or ticket that started it. The specification is `SPECIFICATION.md` inside it, and the rest of the set sits beside it. A feature spec is a directory inside its epic's directory; a feature belonging to no epic sits at the top.

```
.planning/
  <epic>/
    SPECIFICATION.md        <- epic scale
    SPECIFICATION-DECISIONS.md
    ARCHITECTURE.md
    ARCHITECTURE-DECISIONS.md
    <feature>/
      SPECIFICATION.md      <- feature scale
      TECHNICAL-DESIGN.md
  <feature>/
    SPECIFICATION.md        <- feature scale
    TECHNICAL-DESIGN.md
```

A `-DECISIONS.md` sits beside the document it belongs to, created with that document's first decision and linked from it (`AUTHORING.md` § Decisions). Consider splitting a document past ~400 lines; its parts sit beside it, named for their topic (`AUTHORING.md` § Length).

Before writing a document, read `AUTHORING.md` and that document's rules, all in this skill's directory. Read only what the session needs; each [Invocation](#invocation) mode names its reads.

| Document | Rules, in this skill's directory |
|---|---|
| every document | `AUTHORING.md` |
| specification | `SPECIFICATION.md` |
| architecture | `ARCHITECTURE.md` |
| technical design | `TECHNICAL-DESIGN.md` |
| decisions | `AUTHORING.md` § Decisions |
| reviewing any of them | `REVIEW.md` § The spec review |
| reviewing candidate tickets | `REVIEW.md` § Reviewing candidate tickets |

Write each answer into the document that owns its subject, not into the root by default. Link a new document as soon as it is created, from the root or from the document that owns it, so every document is reachable from the root.

## Scale

A spec's scale is epic or feature. It fixes which documents the spec holds and its definition of done:

- **Epic** — done when the spec breaks into features.
- **Feature** — done when it breaks into tasks.

Take the scale from the specification if it declares one, otherwise from the plan issue's **Scale**. If neither does, settle it while writing the plan: without it the plan has no definition of done.

An output other than a spec has no scale. The goal's definition of done stands in for it in the done check and the ticket test below.

### What a feature is

A feature is the smallest unit of change that can be released to a consumer — the end user of an application, the calling code of a library — who must see the change as a coherent whole. *Released* is the point at which that consumer can invoke the change without the rest of the feature existing.

- **Floor** — at least one end-to-end scenario runs against the delivered thing, depending on nothing unbuilt. No such scenario: too narrow.
- **Ceiling** — no proper subset of the scope satisfies the floor. Such a subset exists: too broad.

Test each by finding a concrete example rather than by judging coherence: a scenario for the floor, a subset for the ceiling. The ceiling test is the split question.

A feature that seems to need an architecture of its own is over the ceiling. The shape it wants to describe is the seam: split on it.

### What a task is

A task is one coherent concern, sized to fit one agent's working context, ending in a deliverable that can be reviewed on its own. Cut both across and down:

- by conceptual domain — implementing a task takes reasoning, and a narrow domain makes that easier;
- by the skills it needs — infrastructure, coding, documentation, manual testing;
- by language — each carries its own practices, and a single-language task lets an agent specialise.

Include incidental work in the task it serves rather than splitting it out.

### What a ticket is

A ticket earns its place only if the output cannot meet its definition of done without the answer:

- **Epic** — if you can name a plausible set of features without the answer, drop the ticket. The feature that owns the question will ask it.
- **Feature** — if you can name a plausible set of tasks without the answer, drop it.
- **Another artefact** — if it can meet the goal's definition of done without the answer, drop it.

The epic specification and architecture are a map for creating features, not a specification of them.

Dropping is not deferring: record the question nowhere. The plan that writes that feature's spec will raise it again in its own terms. A dropped question is not `design-loop:unsharp`, which is for a question at this scale that you cannot yet state precisely.

## Refer to issues by title

Never write a bare issue number in anything the user reads. Link the title instead: `[Pick the API layer](url)`.

## The plan

One GitHub issue on this repo, labelled `design-loop:plan`. Every ticket is a **sub-issue** of it.

The plan is a **standing brief**, rarely edited once written: what the plan is for, what it produces, and its definition of done. It records no ticket's progress; only **Out of scope** grows, one line per ruled-out ticket. Open questions are the plan's open tickets, found by query; decisions already made are read from the spec.

```markdown
## Goal

<what finishing this plan looks like: the spec, decision, or change. One or two lines; every session reads this before picking a ticket.>

## Spec

Root: [<specification title>](<url or path>) — the spec this plan is writing. Every other document in that spec hangs off this one.

## Scale

<epic or feature, or a pointer to where the specification declares it>

## Notes

<domain; skills every session should call; standing preferences for this plan>

## Out of scope

<work ruled outside the goal; see "Out of scope" below>
```

A plan whose output is a spec carries **Spec** and **Scale**. A plan whose output is another artefact carries **Output** in their place — what the artefact is, where it lives (path or URL), and its definition of done:

```markdown
## Output

[<artefact title>](<url or path>) — <what it is>. Done when <the goal's definition of done>.
```

## Tickets

A ticket is a sub-issue of the plan, sized to one 100K-token agent session. Its body is just the question:

```markdown
## Question

<the decision or investigation this ticket resolves>
```

Each ticket carries one `design-loop:<type>` label: `research`, `prototype`, `grilling`, or `chore` ([Ticket types](#ticket-types)). A question that cannot yet be stated precisely carries `design-loop:unsharp` and no type label ([Questions and tickets](#questions-and-tickets)).

**Claiming.** Assign the ticket to yourself **before any work**, so concurrent sessions skip it. Unassign it if the session stops without resolving it. A ticket assigned to a session that is no longer running may be reclaimed.

**Blocking.** Use GitHub's native issue dependencies (`blocked by`), not a convention in the body, so GitHub's own UI shows the user what is workable.

**Ready.** A ticket is ready when it is open, unassigned, not `design-loop:unsharp`, and every ticket blocking it is closed. A session picks up only ready tickets.

The answer is posted as a comment on resolution, not written into the body. Link files produced while resolving a ticket from the issue; do not paste them in.

## Questions and tickets

**Every open question at this spec's scale is a ticket**; a question below it is dropped ([What a ticket is](#what-a-ticket-is)). No spec document has an `## Open questions` section, and no spec holds a document of them: the plan's sub-issues are the only record of what is open.

Precision picks the label. A question you can state precisely now takes a type label and becomes ready. One you cannot takes `design-loop:unsharp` and no type label: no session picks it up, but the user still sees it in GitHub. The test is whether you can state the question now, not whether you can answer it.

When an answer makes an `unsharp` question statable, usually during close-out, rewrite its body as the precise question and swap `design-loop:unsharp` for a type label. Do not split an `unsharp` ticket in advance: it may become several tickets, or none.

## GitHub operations

The tracker is GitHub Issues, driven by `gh`. Shell state does not persist between commands, so each snippet runs as one command with its own prelude. Substitute the `<…>` placeholders before running.

Write bodies with the `Write` tool to the session's scratch directory and pass them by path, so no prose goes through shell quoting; `plan.md`, `question.md` and `answer.md` stand for those paths.

The sub-issue and dependency endpoints take an issue's **database id** (`.id`), not its number; the `id_of` helper resolves it.

**Create the labels** (once per repo; `--force` re-runs without error, and resets each label's colour):

```bash
for l in plan research prototype grilling chore unsharp; do
  gh label create "design-loop:$l" --force
done
```

**Create the plan** — `gh issue create` has no `--json`, so read the number off the URL it prints:

```bash
gh issue create --title "<plan title>" --label design-loop:plan --body-file plan.md
```

**Create a ticket and attach it to the plan** (an empty capture means the create failed: stop and read the error):

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
id_of() { gh api "repos/$REPO/issues/$1" --jq .id; }
T=$(gh issue create --title "<ticket title>" --label "design-loop:<type>" --body-file question.md | grep -oE '[0-9]+$')
[ -n "$T" ] || { echo "create failed"; exit 1; }
gh api --method POST "repos/$REPO/issues/<plan number>/sub_issues" -F sub_issue_id="$(id_of "$T")"
```

**Mark one ticket blocked by another**:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
id_of() { gh api "repos/$REPO/issues/$1" --jq .id; }
gh api --method POST "repos/$REPO/issues/<blocked ticket>/dependencies/blocked_by" \
  -F issue_id="$(id_of "<blocking ticket>")"
```

**Read the plan**:

```bash
gh issue view "<plan number>" --json title,body -q '.title, .body'
```

**List ready tickets** — open, unassigned and not `unsharp`, keeping only those with no open blocker:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/issues/<plan number>/sub_issues" --paginate \
  --jq '.[] | select(.state=="open" and (.assignees|length)==0
        and ((.labels|map(.name)|index("design-loop:unsharp"))|not)) | "\(.number)\t\(.title)"' |
while IFS=$(printf '\t') read -r n title; do
  blockers=$(gh api "repos/$REPO/issues/$n/dependencies/blocked_by" \
    --jq '[.[] | select(.state=="open")] | length' 2>/dev/null || echo 0)
  [ "${blockers:-0}" -eq 0 ] && printf '%s\t%s\n' "$n" "$title"
done
```

A blocker call that fails counts the ticket as unblocked, so it still reaches the list: a ticket missing from this list is indistinguishable from an empty queue, which [Review the output](#review-the-output) check 2 would read as the plan being finished.

**List `unsharp` tickets** — never ready, so read them deliberately:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/issues/<plan number>/sub_issues" --paginate \
  --jq '.[] | select(.state=="open"
        and (.labels|map(.name)|index("design-loop:unsharp"))) | "\(.number)\t\(.title)"'
```

**Restate an `unsharp` ticket** — rewrite the question, then swap the label for a type:

```bash
gh issue edit "<ticket number>" --body-file question.md \
  --remove-label design-loop:unsharp --add-label "design-loop:<type>"
```

**Claim a ticket**, before any work:

```bash
gh issue edit "<ticket number>" --add-assignee @me
```

**Release a ticket**, when the session stops without resolving it:

```bash
gh issue edit "<ticket number>" --remove-assignee @me
```

**Resolve a ticket** — the answer, then the close:

```bash
gh issue comment "<ticket number>" --body-file answer.md && gh issue close "<ticket number>"
```

**Edit the plan body** — only to add an Out of scope line, or to correct the brief itself:

```bash
gh issue edit "<plan number>" --body-file plan.md
```

**Read a ticket in full**:

```bash
gh issue view "<ticket number>" --comments
```

**List the tickets resolved so far** — the plan's closed sub-issues, newest last:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/issues/<plan number>/sub_issues" --paginate \
  --jq '.[] | select(.state=="closed") | [.closed_at, .number, .title] | @tsv' | sort
```

## Ticket types

Types marked *with the user* need the user in the conversation: never answer on their behalf, as a grilling agent that answers its own questions does. `design-loop:unsharp` is a state, not a type, and picks no route.

- **Research** — agent alone. Reading documentation, third-party APIs, or local knowledge bases to find a fact a decision waits on. Use when the answer lives outside this working directory. Resolved in two halves. **Reading**: a subagent calls the Skill tool with "research", commits its findings file on the current branch, and posts the findings as a comment linking that file. **Incorporating**: the session that dispatched the subagent runs [Close out the ticket](#close-out-the-ticket) steps 1–3 on the findings and closes the ticket. Reading never edits the output, so research subagents may run in parallel; incorporating does, so the dispatching session does it alone, one ticket at a time.
- **Prototype** — with the user. Build something cheap and concrete to react to (an outline, a stub, UI or logic code) by calling the Skill tool with "prototype", on a throwaway `prototype/<name>` branch linked from the ticket. Use when the question is how something should look or behave.
- **Grilling** — with the user. Conversation, and the default type. Call the Skill tool twice, for "grilling" and "domain-modeling".
- **Chore** — either. Manual work a decision is stuck on: signing up for a service so its API can be judged, provisioning access, moving data so its shape can be seen. The one type that builds rather than decides. Do it alone where you can; otherwise give the user a precise checklist. The answer records what was done plus any facts later tickets need (where credentials live, new URLs, row counts).

## Close out the ticket

Run these four steps in order, in the same session as the answer: the review means nothing until the output is updated. A session closing out several tickets — its own and the `research` tickets it dispatched — runs steps 1–3 for each, one ticket at a time, then step 4 once.

1. **Update the output.** Write what the answer settled into the document that owns that subject, following `AUTHORING.md` and that document's rules (for another artefact, whatever rules its kind carries), without linking the ticket (`AUTHORING.md` § References). An answer that clears the bar in `AUTHORING.md` § Decisions also gets an entry, in the format fixed there, in the decisions document beside the document it changed; create and link that decisions document if this is its first entry. Where the answer overturns a recorded decision, rewrite that entry as the decision that now stands rather than adding one. Create and link a new document if the answer needs one. Expect something to change: a ticket that changes nothing usually answered nothing, unless the answer confirms what the output already says.
2. **Close the other tickets it answered**, each with a comment pointing at the ticket that settled it. A ticket left open once the spec carries its answer is a contradiction the next session must resolve.
3. **Record what is still open.** If the answer settled only part of the question, close the ticket on that part and raise the remainder as a new ticket, never leaving it only in the closing comment. Every new or remaining question becomes a ticket **straight away**, labelled `design-loop:unsharp` if you cannot yet state it precisely ([Questions and tickets](#questions-and-tickets)). Run candidates through `REVIEW.md` § Reviewing candidate tickets before creating them. Restate any `unsharp` ticket this answer has made statable.
4. **Review the output**, below.

New or refined terminology goes in `.docs/ubiquitous-language.md` via the "domain-modeling" skill, not in the spec.

## Review the output

Run this once per session that wrote or edited the output, after every answer the session resolves is incorporated: close-out step 4, and the session that writes the plan. Record the review once: as a comment on the plan issue when writing the plan, otherwise in the resolution comment of the ticket the session picked. Each `research` ticket the session dispatched carries its decision in its own resolution comment and points to that comment for the review.

**First, the automated review.** For a spec, dispatch the reviewers `REVIEW.md` § Dispatch describes and wait for them. For another artefact, dispatch a reviewer against its kind's rules, or against the goal's definition of done where it has none. Apply the findings you accept, and record which you rejected and why. A finding that is really an open question goes through close-out step 3. Never skip this pass.

**Then three checks**, against the whole output, not just the part the answer touched. A session writing the plan that incorporated no research runs the automated review alone. Answer all three where the review is recorded, even when the answer is "no change": an unrecorded review gets skipped.

1. **Is the scope right?** Now this is settled, is anything worth cutting? Has the answer shown something missing? Cuts go to **Out of scope** on the plan issue; additions go through close-out step 3.
2. **Is it done?** For a spec, test it against its [scale](#scale): does it now break into features, or into tasks, by the definitions there? For another artefact, apply the definition of done in the plan's **Output**. If it passes, the plan is finished: say so, close the plan issue, and tell the user the next session runs `build-loop` § Cut the tasks against this spec. Work no more tickets.
3. **Are there new questions?** Ask again, deliberately, even though close-out step 3 just looked: a gap invisible while writing up one answer is often obvious against the whole output. Anything found goes through close-out step 3.

## Out of scope

Work past the goal is out of scope: not vague, just not part of this plan.

When an existing ticket turns out to sit past the goal (mis-scoped when written, or exposed by an answer), **close it**, so it is never ready, and add one line to **Out of scope**: the gist, why it is out, and a link to the closed ticket. Ruling something out is not a decision, so nothing about it goes into the spec.

## Invocation

Two modes. Either way, **work no more than one ticket per session**. A session may also close tickets its answer already settled ([Close out the ticket](#close-out-the-ticket) step 2), and may dispatch several `research` tickets: their reading runs in subagents, so they cost the session only the incorporating.

### Write the plan

The user invokes with a loose idea. Read `AUTHORING.md` and `REVIEW.md` first, and `.claude/skills/design-loop/SPECIFICATION.md` unless the user's goal names another artefact.

1. **Name the goal and the output.** Call the Skill tool twice, for "grilling" and "domain-modeling", to pin down what this plan is for: a spec, a decision, or a change. Settle this first; it fixes the scope. The output is a spec unless the user names another artefact here ([The goal and the output](#the-goal-and-the-output)).
2. **Settle the output.** For a spec: agree with the user which spec this plan writes, then read its root and everything the root links; if that spec does not exist yet, create its specification. Settle the scale per [Scale](#scale), agreeing it with the user where the specification does not declare one. For another artefact: agree what it is, where it lives, and its definition of done, and read whatever of it already exists.
3. **Find the open questions.** Read what is already written down first, as the cheapest source: the `unsharp` tickets on any earlier plan for this output, and anything the output leaves visibly unsettled. Then grill again, **broad rather than deep**, to surface the open decisions and the first steps you can take now. **If nothing is unclear** — the output already meets its definition of done and the whole job fits one session — stop and tell the user they do not need a plan.
4. **Create the plan issue** (label `design-loop:plan`): Goal, Spec and Scale (or Output, for another artefact), Notes, and an empty Out of scope. Nothing else goes in the body.
5. **Review the candidate tickets** (`REVIEW.md` § Reviewing candidate tickets), then **create the survivors** as sub-issues — with a type label where you can state the question precisely, `design-loop:unsharp` otherwise — and wire the blocking edges in a **second pass**, since issues need numbers before they can reference each other. Every survivor goes into the tracker.
6. **Resolve the research tickets.** Claim every `research` ticket, then dispatch one subagent per ticket, all in parallel, to do the reading ([Ticket types](#ticket-types), Research). Put the findings on a separate `research/<name>` branch only when the user asks: a separate branch mostly leaves the results unreachable. As each subagent returns, incorporate its answer yourself, one ticket at a time: run [Close out the ticket](#close-out-the-ticket) steps 1–3, wire any tickets that created, post the decision as a comment pointing at the plan issue for the review, and close the ticket.
7. Run [Review the output](#review-the-output) once over whatever this session wrote or edited, including the incorporated research, and comment on the plan issue with the review: the three checks if any research was incorporated, and the findings you rejected and why. Skip only if the session changed nothing.
8. Stop. Writing the plan resolves no tickets but the `research` tickets it dispatched.

### Work the plan

The user invokes with a plan (URL or number), and optionally a ticket. Without one, you pick. Read `AUTHORING.md`, the rules for each document the ticket touches, and `REVIEW.md`.

1. Read the **plan**: the goal and the definition of done, not every ticket body. Follow the link in **Spec** or **Output**, read it, and read the documents the ticket touches. Read the decisions document beside each only where the ticket reopens a decision.
2. Choose the ticket: the one the user named, or else the first row the ready-tickets query returns. **Claim it** before any work, then tell the user what you are starting: the linked title and **one paragraph summarising the question** — what is undecided, and what in the spec is waiting on it. You may also claim the plan's other ready `research` tickets and dispatch their subagents now, as in Write the plan step 6, so their reading runs alongside your own work.
3. Resolve it. Read related or closed tickets in full as you need them; call the Skill tool for whichever skills the plan's **Notes** names, and for the ticket's type ([Ticket types](#ticket-types)).
4. Run [Close out the ticket](#close-out-the-ticket) for your ticket and for each `research` ticket you dispatched, one at a time, ending in a single [Review the output](#review-the-output).
5. Post the answer as a comment — the decision, the three review checks, and any findings you rejected with why — and **close** the issue. Post each dispatched `research` ticket's decision as a comment pointing at this one for the review, and close it too.
6. Wire any tickets close-out created, and update or delete tickets the answer has invalidated. If the answer shows a ticket sits past the goal, rule it out of scope instead of resolving it. If check 2 closed the plan, end by naming the `build-loop` invocation.

Expect other sessions to edit GitHub at the same time: the user may work ready tickets in parallel.

A closed plan may be reopened by `build-loop` § Design questions during a task, to take one more ticket raised while the spec was being built. Work it as any other; closing that ticket re-runs check 2, which closes the plan again.
