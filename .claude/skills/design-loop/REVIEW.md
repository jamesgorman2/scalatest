# Reviews

The design loop's automated passes, run by subagents: one over the spec after it is written, one over candidate tickets before they are created. The session that produced the work is the worst reader of it — it knows what it meant, so it reads its intent rather than the text on the page. Reviewers that never saw the writing do not.

Reviewers **report, they never edit**, and they never touch GitHub. The session applies what it accepts and says what it rejected and why — in the ticket's resolution comment, or on the plan issue when the session is writing the plan.

## The spec review

An automated pass over the spec.

### Dispatch

Two kinds of reviewer, all dispatched in one batch and run in parallel.

- **One per document** the session created or edited, reviewing that document and its decisions document together. They are one reviewer's job: a decision is judged against the thing it justifies.
- **One whole-spec reviewer**, reviewing every document in the set, edited or not.

Each reviewer gets the prompt for its role, with every `<placeholder>` filled and every path absolute (`~/.claude/CLAUDE.md` § Subagents). Name every document by path. Do not rely on a reviewer to discover the set for itself — the spec root lists it, and you have already read the root.

A per-document reviewer:

> Read and follow `~/.claude/CLAUDE.md` and the repo-root `../../../CLAUDE.md` first. You are a document reviewer in a design-loop spec review. The skill's rules are in `<skill directory>`: read `AUTHORING.md`, `REVIEW.md` § What a document reviewer checks and § Finding format, and `<kind rules file>` — the rules for your document's kind, one of `SPECIFICATION.md`, `ARCHITECTURE.md` or `TECHNICAL-DESIGN.md` in that same directory. You own `<document path>` and its decisions document `<decisions document path>`; review those two together, and nothing else. Review, do not edit, and do not touch GitHub. Report every finding in the format `REVIEW.md` § Finding format gives.

The whole-spec reviewer:

> Read and follow `~/.claude/CLAUDE.md` and the repo-root `../../../CLAUDE.md` first. You are the whole-spec reviewer in a design-loop spec review. The skill's rules are in `<skill directory>`: read `AUTHORING.md` and `REVIEW.md` § What the whole-spec reviewer checks and § Finding format. The spec is `<every document path in the set>`. The plan's goal is `<goal>`; its scale is `<epic or feature>`. Judge the set as a whole against that goal and scale. Review, do not edit, and do not touch GitHub. Report every finding in the format `REVIEW.md` § Finding format gives.

### What a document reviewer checks

Against `AUTHORING.md` and the rules for its kind:

- Does it meet its kind's completeness test?
- Is every statement at the document's level? Apply `AUTHORING.md` § The level test to anything that looks marginal, and say where misplaced content belongs.
- Does it carry any of its kind's anti-patterns?
- Does every reference earn its place, or is it provenance on a settled statement, corroboration, or a citation trailing a gloss of what it says (`AUTHORING.md` § References)?
- Does it break any rule in `AUTHORING.md`? Decisions entries are the usual site — check every entry against the field table in that file's § Decisions.
- Does every decision entry clear the bar in `AUTHORING.md` § Decisions, or is it a choice that should simply be stated in the document?
- Is it over ~400 lines, and if so does it have an honest seam to split on?

### What the whole-spec reviewer checks

- Do these documents together produce a design that meets the plan's goal, at the plan's scale?
- Are they internally consistent? A fact stated twice in different words is a finding even when both statements are true today.
- Does any requirement enumerate a set another document in the set fixes (`.claude/skills/design-loop/SPECIFICATION.md` § Anti-patterns, **Design restated as obligation**)?
- Is every document in the set reachable from the root — directly, or through a document that has split and indexes its own parts? Does every document reached belong to this spec?
- Is anything claimed by no document — a behaviour the goal implies that nothing states?

### Finding format

One entry per finding, most severe first: a failure of the kind's completeness test, then content at the wrong level, then a broken authoring rule, then conciseness. Repeated instances of one rule in one document are a single finding listing every instance. Each is:

- **Where** — document path and section.
- **What** — the defect, in one sentence, naming the rule or anti-pattern it breaks.
- **Fix** — the concrete change, not "consider revising".

A reviewer that finds nothing says so. It never pads the list to look thorough, and it never reports a matter of taste as a defect.

## Reviewing candidate tickets

Run before any `gh issue create` that adds tickets to a plan — `SKILL.md` § Invocation, "Write the plan" step 5, and § Close out the ticket step 3 in that same file. Write the candidates to a scratch file — title and question body, numbered — and dispatch **one** reviewer over the whole list. One reviewer, not one per ticket: overlap between candidates is only visible in the batch.

Dispatch it with this prompt, every `<placeholder>` filled and every path absolute (`~/.claude/CLAUDE.md` § Subagents):

> Read and follow `~/.claude/CLAUDE.md` and the repo-root `../../../CLAUDE.md` first. You are the candidate-ticket reviewer for a design-loop plan. The skill's rules are in `<skill directory>`: read `SKILL.md` § Scale, `AUTHORING.md`, and `REVIEW.md` § Reviewing candidate tickets for the four verdicts and the evidence each requires. Read the spec root at `<spec root path>` and every document it links. The plan's goal is `<goal>`; its scale is `<epic or feature>`. For each candidate in `<candidate file path>`, output its number, one verdict of **Keep**, **Drop**, **Merge** or **Re-aim**, and the evidence that verdict requires. Drop is the default: a candidate you cannot justify keeping is dropped. Review, do not edit, and do not touch GitHub.

Verdicts, one per candidate:

- **Keep** — name what in the spec is wrong or unwritable until it is answered.
- **Drop** — name the plausible split that survives without the answer. This is the default verdict; a candidate the reviewer cannot justify keeping is dropped.
- **Merge** — into which other candidate, and why they are one question.
- **Re-aim** — the candidate is a feature question standing in for an epic one. State the epic question behind it.

The session creates one ticket per Keep, per Re-aim, and per merged group — stating in the merged ticket which candidates it absorbs — and says which candidates it dropped and why.
