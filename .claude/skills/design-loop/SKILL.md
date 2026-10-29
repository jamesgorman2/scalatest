---
name: design-loop
description: Designing and specifying and epic or feature. Not for writing a roadmap (roadmap) or implementing a feature (build-loop)
---

Use this when an idea is too big for one agent session and the path to the goal is unclear. Record what is known as a **plan** issue on GitHub with one sub-issue per open decision, then resolve those decisions one at a time, writing each answer into the plan's **output**, until the output meets its definition of done. Start at [Invocation](#invocation); the sections before it are reference.

A **sub-issue** is a question whose answer is a decision, not a slice of the build.

## The goal and the output

Name the goal first: it shapes every sub-issue and fixes the plan's **output**, the artefact every answer is written into and the next session reads for the current state instead of replaying every sub-issue.

- **A spec** (the default), done when it is ready to implement at its scale ([The spec](#the-spec), [Scale](#scale)). The rest of this skill is written for this case.
- **Another artefact**, only when the user names it in the goal or agrees to it while the goal is being named: a decision to lock before planning starts, a change made in place such as a data-structure migration, a research write-up, a domain model. Never infer this from the shape of the idea. The goal then states what the artefact is, where it lives, and its definition of done, because this skill supplies none.

For another artefact, read *output* wherever a section says *spec*, and take from [The spec](#the-spec) and [Scale](#scale) only what still applies: every answer lands in the output, decisions are recorded beside it, and the definition of done is the goal's.

## Decide, don't build

Produce decisions, not deliverables. The urge to just do the work usually means the output is done and it is time to hand off to `build-loop`. The exception is a goal whose output is a change made in place; the plan's **Notes** say how much execution that includes.

## The spec

A spec is a set of documents, and its scale fixes what the set may hold:

- **Epic** — a specification, plus an architecture where the epic changes the system's shape.
- **Feature** — a specification, plus a technical design where the feature carries non-obvious technical choices.

The specification is always present and is the **root**: it states the behaviour and links every other document in the set. Add the second document only when it has something to say.

A spec may also link documents it is written **against** rather than ones it produces, such as a domain model or prior art. Reference their facts; never copy them into a requirement (`SPECIFICATION.md` § Anti-patterns, Design restated as obligation).

A plan whose output is a spec writes exactly one spec and links its root from the plan's **Spec** section; read which spec you are writing off the plan, never assume it.

### Where it lives

A spec is a directory under `.planning/`, named for its scope, not for the plan or sub-issue that started it. The specification is `SPECIFICATION.md` inside it, and the rest of the set sits beside it. A feature spec is a directory inside its epic's directory; a feature belonging to no epic sits at the top.

```
.planning/
  ROADMAP.md                <- shortlist of epics/features to be built
  <epic>/
    SPECIFICATION.md        <- epic scale behavioural specification
    SPECIFICATION-DECISIONS.md
    ARCHITECTURE.md
    ARCHITECTURE-DECISIONS.md
    decisions/              <- a decisions document that has split, and its parts
    ROADMAP.md              <- shortlist of features to be built in the epic
    <feature>/
      SPECIFICATION.md      <- feature scale behavioural specification
      TECHNICAL-DESIGN.md   <- key technical designs for implementation
  <feature>/
    SPECIFICATION.md        <- feature scale behavioural specification
    TECHNICAL-DESIGN.md     <- key technical designs for implementation
```

Each [Invocation](#invocation) mode names its reads; read only what the session needs.

| Document                       | Rules, in this skill's directory             |
|--------------------------------|----------------------------------------------|
| every document                 | `AUTHORING.md`                               |
| specification                  | `SPECIFICATION.md`                           |
| architecture                   | `ARCHITECTURE.md`                            |
| technical design               | `TECHNICAL-DESIGN.md`                        |
| reviewing any of them          | `REVIEW.md` § The spec review                |
| reviewing candidate sub-issues | `REVIEW.md` § Reviewing candidate sub-issues |

## Scale

A spec's scale is epic or feature. It fixes which documents the spec holds and its definition of done:

- **Epic** — done when the spec can be broken into features.
- **Feature** — done when can be broken into tasks.

Take the scale from the specification if it declares one, otherwise from the plan issue's **Scale**. If neither does, settle it while writing the plan: without it the plan has no definition of done.

### What a feature is

A feature is sized per `roadmap` § Feature size. It is too broad when you can name a proper subset that is itself releasable (or verifiable): that subset is the split. A feature that seems to need an architecture of its own is over the ceiling; split on the seam it wants to describe.

### Splitting a feature

Confirm with the user before acting. 

A feature in not in an epic may be promoted to an epic. Do this when the new epic needs to be released as a whole unit, but its new component features are independently validatable. In this case new features are created under the promoted epic.

Otherwise, close the feature to be split and create new features. The new features have the same parent issue, if present, as the original feature.

Copy briefs and specification so the final artefacts obey the rules in `design-loop` and `roadmap`. 

### What a task is

A task is one coherent concern, sized to fit one agent's working context, ending in a deliverable that can be reviewed on its own. Cut both across and down:

- by conceptual domain — implementing a task takes reasoning, and a narrow domain makes that easier;
- by the skills it needs — infrastructure, coding, documentation, manual testing;
- by language — each carries its own practices, and a single-language task lets an agent specialise.

Include incidental work in the task it serves rather than splitting it out.

### What a sub-issue is

A sub-issue earns its place only if the output cannot meet its definition of done without the answer:

- **Epic** — if you can name a plausible set of features without the answer, drop the sub-issue. The feature that owns the question will ask it.
- **Feature** — if you can name a plausible set of tasks without the answer, drop it.
- **Another artefact** — if it can meet the goal's definition of done without the answer, drop it.

The epic specification and architecture are a map for creating features, not a specification of them.

Dropping is not deferring: record the question nowhere. The plan that writes that feature's spec will raise it again in its own terms.

## Refer to issues by title

Never write a bare issue number in anything the user reads. Link the title instead: `[Pick the API layer](url)`.

## The plan

One GitHub issue on this repo, labelled `design-loop:plan`. Its parent is the epic or feature's issue, per the `roadmap` skill. Every question is a **sub-issue** of it.

The plan is a **standing brief**, rarely edited once written: what the plan is for, what it produces, and its definition of done. It records no sub-issue's progress; only **Out of scope** grows, one line per ruled-out sub-issue. Open questions are the plan's open sub-issues, found by query; decisions already made are read from the spec.

```markdown
## Goal

<what finishing this plan looks like: the spec, decision, or change. One or two lines; every session reads this before picking a sub-issue.>

## Spec

[<specification title>](<url or path>) — the spec this plan is writing. Every other document in that spec hangs off this one.

## Scale

<epic or feature, or a pointer to where the specification declares it>

## Notes

<domain; skills every session should call; standing preferences for this plan>

## Out of scope

<work ruled outside the goal>
```

A plan whose output is a spec carries **Spec** and **Scale**. A plan whose output is another artefact carries **Output** in their place — what the artefact is, where it lives (path or URL), and its definition of done:

```markdown
## Output

[<artefact title>](<url or path>) — <what it is>. Done when <the goal's definition of done>.
```

## Sub-issues

Each sub-issue of the plan is sized to one 100K-token agent session. Its body is just the question:

```markdown
## Question

<the decision or investigation this sub-issue resolves>
```

Each sub-issue carries either one `design-loop:<type>` label — `research`, `prototype`, `grilling`, or `chore` ([Sub-issue types](#sub-issue-types)) — or `design-loop:unsharp` alone ([Questions and sub-issues](#questions-and-sub-issues)).

**Claiming.** Assign the sub-issue to yourself **before any work**, so concurrent sessions skip it. Unassign it if the session stops without resolving it. A sub-issue assigned to a session that is no longer running may be reclaimed.

**Blocking.** Use GitHub's native issue dependencies (`blocked by`), not a convention in the body, so GitHub's own UI shows the user what is workable.

**Ready.** A sub-issue is ready when it is open, unassigned, not `design-loop:unsharp`, and every issue blocking it is closed. A session picks up only ready sub-issues.

The answer is posted as a comment on resolution, not written into the body. Link files produced while resolving an issue from the issue; do not paste them in.

## Questions and sub-issues

**Every open question at this spec's scale is a sub-issue**; a question below it is dropped ([What a sub-issue is](#what-a-sub-issue-is)). No spec document has an `## Open questions` section, and no spec holds a document of them: the plan's sub-issues are the only record of what is open.

Precision picks the label. A question you can state precisely now takes a type label and becomes ready. One you cannot takes `design-loop:unsharp` and no type label: no session picks it up, but the user still sees it in GitHub. The test is whether you can state the question now, not whether you can answer it.

When an answer makes an `unsharp` question statable, usually during close-out, rewrite its body as the precise question and swap `design-loop:unsharp` for a type label. Do not split an `unsharp` sub-issue in advance: it may become several sub-issues, or none.

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

**Create a sub-issue and attach it to the plan** (an empty capture means the create failed: stop and read the error):

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
id_of() { gh api "repos/$REPO/issues/$1" --jq .id; }
T=$(gh issue create --title "<sub-issue title>" --label "design-loop:<type>" --body-file question.md | grep -oE '[0-9]+$')
[ -n "$T" ] || { echo "create failed"; exit 1; }
gh api --method POST "repos/$REPO/issues/<plan number>/sub_issues" -F sub_issue_id="$(id_of "$T")"
```

**Mark one sub-issue blocked by another**:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
id_of() { gh api "repos/$REPO/issues/$1" --jq .id; }
gh api --method POST "repos/$REPO/issues/<blocked sub-issue>/dependencies/blocked_by" \
  -F issue_id="$(id_of "<blocking sub-issue>")"
```

**Read the plan**:

```bash
gh issue view "<plan number>" --json title,body -q '.title, .body'
```

**List ready sub-issues**:

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

A blocker call that fails counts the sub-issue as unblocked, so it still reaches the list: a sub-issue missing from this list is indistinguishable from an empty queue, which [Review the output](#review-the-output) check 2 would read as the plan being finished.

**List `unsharp` sub-issues** — never ready, so read them deliberately:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/issues/<plan number>/sub_issues" --paginate \
  --jq '.[] | select(.state=="open"
        and (.labels|map(.name)|index("design-loop:unsharp"))) | "\(.number)\t\(.title)"'
```

**Restate an `unsharp` sub-issue** — rewrite the question, then swap the label for a type:

```bash
gh issue edit "<issue number>" --body-file question.md \
  --remove-label design-loop:unsharp --add-label "design-loop:<type>"
```

**Claim an issue**:

```bash
gh issue edit "<issue number>" --add-assignee @me
```

**Release an issue**:

```bash
gh issue edit "<issue number>" --remove-assignee @me
```

**Resolve an issue** — the answer, then the close:

```bash
gh issue comment "<issue number>" --body-file answer.md && gh issue close "<issue number>"
```

**Edit the plan body**:

```bash
gh issue edit "<plan number>" --body-file plan.md
```

**Read an issue in full**:

```bash
gh issue view "<issue number>" --comments
```

**List the sub-issues resolved so far** — the plan's closed sub-issues, newest last:

```bash
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/issues/<plan number>/sub_issues" --paginate \
  --jq '.[] | select(.state=="closed") | [.closed_at, .number, .title] | @tsv' | sort
```

## Sub-issue types

Types marked *with the user* need the user in the conversation: never answer on their behalf, as a grilling agent that answers its own questions does.

- **Research** — agent alone. Reading documentation, third-party APIs, or local knowledge bases to find a fact a decision waits on. Use when the answer lives outside this working directory. Resolved in two halves. **Reading**: a subagent calls the Skill tool with "research", commits its findings file on the current branch, and posts the findings as a comment linking that file. **Incorporating**: the session that dispatched the subagent runs [Close out the sub-issue](#close-out-the-sub-issue) steps 1–3 on the findings and closes the sub-issue. Reading never edits the output, so research subagents may run in parallel; incorporating does, so the dispatching session does it alone, one sub-issue at a time.
- **Prototype** — with the user. Build something cheap and concrete to react to (an outline, a stub, UI or logic code) by calling the Skill tool with "prototype", on a throwaway `prototype/<name>` branch linked from the sub-issue. Use when the question is how something should look or behave.
- **Grilling** — with the user. Conversation, and the default type. Call the Skill tool twice, for "grilling" and "domain-modeling".
- **Chore** — either. Manual work a decision is stuck on: signing up for a service so its API can be judged, provisioning access, moving data so its shape can be seen. The one type that builds rather than decides. Do it alone where you can; otherwise give the user a precise checklist. The answer records what was done plus any facts later sub-issues need (where credentials live, new URLs, row counts).

## Close out the sub-issue

Run these four steps in order, in the same session as the answer: the review means nothing until the output is updated. A session closing out several sub-issues — its own and the `research` sub-issues it dispatched — runs steps 1–3 for each, one sub-issue at a time, then step 4 once.

1. **Update the output.** Write what the answer settled into the document that owns that subject, not the root by default, following `AUTHORING.md`, § Decisions included, and that document's rules (for another artefact, whatever rules its kind carries). Create and link a new document if the answer needs one. Expect something to change: a sub-issue that changes nothing usually answered nothing, unless the answer confirms what the output already says.
2. **Close the other sub-issues it answered**, each with a comment pointing at the sub-issue that settled it. A sub-issue left open once the spec carries its answer is a contradiction the next session must resolve.
3. **Record what is still open.** If the answer settled only part of the question, close the sub-issue on that part and raise the remainder as a new sub-issue, never leaving it only in the closing comment. Every new or remaining question becomes a sub-issue **straight away** ([Questions and sub-issues](#questions-and-sub-issues)). Run candidates through `REVIEW.md` § Reviewing candidate sub-issues before creating them. Restate any `unsharp` sub-issue this answer has made statable.
4. **Review the output**, below.

## Review the output

Run this once per session that wrote or edited the output, after every answer the session resolves is incorporated: close-out step 4, and the session that writes the plan. Record the review once: as a comment on the plan issue when writing the plan, otherwise in the resolution comment of the sub-issue the session picked. Each `research` sub-issue the session dispatched carries its decision in its own resolution comment and points to that comment for the review.

**First, the automated review.** For a spec, dispatch the reviewers `REVIEW.md` § Dispatch describes and wait for them. For another artefact, dispatch a reviewer against its kind's rules, or against the goal's definition of done where it has none. Apply the findings you accept, and record which you rejected and why. A finding that is really an open question goes through close-out step 3. Never skip this pass.

**Then three checks**, against the whole output, not just the part the answer touched. A session writing the plan that incorporated no research runs the automated review alone. Answer all three where the review is recorded, even when the answer is "no change": an unrecorded review gets skipped.

1. **Is the scope right?** Now this is settled, is anything worth cutting? Has the answer shown something missing? Cuts go to **Out of scope** on the plan issue; additions go through close-out step 3.
2. **Is it done?** For a spec, test it against its [scale](#scale): does it now break into features, or into tasks, by the definitions there? For another artefact, apply the definition of done in the plan's **Output**. If it passes, the plan is finished: say so, close the plan issue, and tell the user the next session runs `build-loop` § Cut the tasks against this spec. Work no more sub-issues.
3. **Are there new questions?** Ask again, deliberately, even though close-out step 3 just looked: a gap invisible while writing up one answer is often obvious against the whole output. Anything found goes through close-out step 3.

## Out of scope

Work past the goal is out of scope: not vague, just not part of this plan.

When an existing sub-issue turns out to sit past the goal (mis-scoped when written, or exposed by an answer), **close it**, so it is never ready, and add one line to **Out of scope**: the gist, why it is out, and a link to the closed sub-issue. Ruling something out is not a decision, so nothing about it goes into the spec.

## Invocation

Two modes. Either way, **work no more than one sub-issue per session**. A session may also close sub-issues its answer already settled ([Close out the sub-issue](#close-out-the-sub-issue) step 2), and may dispatch several `research` sub-issues ([Sub-issue types](#sub-issue-types), Research).

### Write the plan

The user invokes with a loose idea. Read `AUTHORING.md` and `REVIEW.md` first, and `SPECIFICATION.md` unless the user's goal names another artefact.

1. **Name the goal and the output.** Call the Skill tool twice, for "grilling" and "domain-modeling", to pin down what this plan is for: a spec, a decision, or a change. Settle this first; it fixes the scope ([The goal and the output](#the-goal-and-the-output)).
2. **Settle the output.** For a spec: agree with the user which spec this plan writes, then read its root and everything the root links; if that spec does not exist yet, create its specification. Settle the scale per [Scale](#scale), agreeing it with the user where the specification does not declare one. For another artefact: agree what it is, where it lives, and its definition of done, and read whatever of it already exists.
3. **Find the open questions.** Read what is already written down first, as the cheapest source: the `unsharp` sub-issues on any earlier plan for this output, and anything the output leaves visibly unsettled. Then grill again, **broad rather than deep**, to surface the open decisions and the first steps you can take now. **If nothing is unclear** — the output already meets its definition of done and the whole job fits one session — stop and tell the user they do not need a plan.
4. **Create the plan issue** (label `design-loop:plan`): Goal, Spec and Scale (or Output, for another artefact), Notes, and an empty Out of scope. Nothing else goes in the body.
5. **Review the candidate sub-issues** (`REVIEW.md` § Reviewing candidate sub-issues), then **create the survivors** as sub-issues, labelled per [Questions and sub-issues](#questions-and-sub-issues), and wire the blocking edges in a **second pass**, since issues need numbers before they can reference each other.
6. **Resolve the research sub-issues.** Claim every `research` sub-issue, then dispatch one subagent per sub-issue, all in parallel, to do the reading ([Sub-issue types](#sub-issue-types), Research). Put the findings on a separate `research/<name>` branch only when the user asks: a separate branch mostly leaves the results unreachable. As each subagent returns, run [Close out the sub-issue](#close-out-the-sub-issue) steps 1–3 on its findings, wire any sub-issues that created, and close it with the comment [Review the output](#review-the-output) describes.
7. Run [Review the output](#review-the-output) once over whatever this session wrote or edited, including the incorporated research.
8. Stop. Writing the plan resolves no sub-issues but the `research` sub-issues it dispatched.

### Work the plan

The user invokes with a plan (URL or number), and optionally a sub-issue. Without one, you pick. Read `AUTHORING.md`, the rules for each document the sub-issue touches, and `REVIEW.md`.

1. Read the **plan**: the goal and the definition of done, not every sub-issue body. Follow the link in **Spec** or **Output**, read it, and read the documents the sub-issue touches. Read the decisions document beside each only where the sub-issue reopens a decision.
2. Choose the sub-issue: the one the user named, or else the first row the ready-sub-issues query returns. **Claim it** before any work, then tell the user what you are starting: the linked title and **one paragraph summarising the question** — what is undecided, and what in the spec is waiting on it. You may also claim the plan's other ready `research` sub-issues and dispatch their subagents now, as in Write the plan step 6, so their reading runs alongside your own work.
3. Resolve it. Read related or closed sub-issues in full as you need them; call the Skill tool for whichever skills the plan's **Notes** names, and for the sub-issue's type ([Sub-issue types](#sub-issue-types)).
4. Run [Close out the sub-issue](#close-out-the-sub-issue) for your sub-issue and for each `research` sub-issue you dispatched, one at a time, ending in a single [Review the output](#review-the-output).
5. Post the answer, with the review, as a comment and **close** the issue; post and close each dispatched `research` sub-issue as [Review the output](#review-the-output) describes.
6. Wire any sub-issues close-out created, and update or delete sub-issues the answer has invalidated. If the answer shows a sub-issue sits past the goal, rule it out of scope instead of resolving it.

Expect other sessions to edit GitHub at the same time: the user may work ready sub-issues in parallel.

A closed plan may be reopened by `build-loop` § Design questions during a task, to take one more sub-issue raised while the spec was being built. Work it as any other; closing that sub-issue re-runs check 2, which closes the plan again.
