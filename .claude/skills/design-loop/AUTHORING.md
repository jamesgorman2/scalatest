# Authoring spec documents

The rules every document in a spec is held to, whatever its kind.

The test the whole document must pass: **can a reader implement this, and know whether the implementation is what was intended?**

## Say it once

No repetition, within a document or across the spec. Two copies of a fact become two contradictory sources. External authorities are the exception (§ References).

## Language

Clear, actionable, concise.

- Anything that can be summarised or deleted without costing legibility or actionability: cut it.
- Any self-coined jargon: convert it to plain English.

Judge this at the document's scale and in the eyes of its consumers. An epic document is broad and is consumed by features, not implemented directly. A feature document is consumed by tasks. Never write for the reviewer.

Define a new domain term inline at first use. A term that outlives the plan goes to `.docs/ubiquitous-language.md` via the "domain-modeling" skill.

## Length

Past **~400 lines**, reconsider the document. A reader stops holding it at once, and a session reading three such documents has spent a fifth of its context before it starts work.

Split along existing section boundaries into documents of the same kind, each named for its topic. **The document that split becomes their root**: it keeps its `## Summary`, gains a section indexing every part — linked, one line each on what it holds — and hands the rest of its content to the parts. A split specification stays the spec root and lists its own parts alongside the rest of the set.

Every document in a spec is linked from another document in that spec, so every one is reachable from the root — a document nothing links to is a document the next session won't read.

Never split mid-section, and never split a document that is merely repetitive — cut the repetition first and re-measure. A document with no honest seam — one long list, one table — stays whole; where it is well past the mark, its `## Summary` carries one line saying why it does not split.

## References

A reference earns its place only where the reader must open the source to act on the sentence, or where the sentence holds only because the source says so.

Comment on, extend or contradict a source as needed; never simply restate it.

- **Within a document** — by section or item ID.
- **Another document in the spec, or in the repo** — by path and section: `.docs/design/03-styles/colour.md` § The palette. Never gloss what it says (§ Say it once).
- **An external document** — by URL and anchor, plus a quote or paraphrase, so the reader does not have to follow the link. This is the one reference that carries a restatement.

  Good: "Card data never reaches this service — PCI-DSS v4.0.1 requirement 6.4.3 (https://docs-prv.pcisecuritystandards.org/PCI%20DSS/Standard/PCI-DSS-v4_0_1.pdf#page=143) puts every script running on the payment page in scope, which forces payment capture into the hosted iframe."

  Bad: "Card data never reaches this service (see PCI-DSS v4.0.1, https://docs-prv.pcisecuritystandards.org/PCI%20DSS/Standard/PCI-DSS-v4_0_1.pdf)."

- **Code** — by package and version for an external library (`@figma/plugin-typings@1.128.0`), by path for internal non-namespaced code (`packages/api/src/session.ts`), by fully namespaced symbol, or by a description of the block ("the second loop"). **Never by line number.**

Three references never earn their place, and each is deleted on sight:

- **Provenance** — the ticket, session or prior document a settled statement came from. The ticket that settled something belongs in **Settled by** on the decisions entry, not in the passage it settled (§ Decisions).
- **Corroboration** — a citation hung on a claim nobody disputes.
- **The restated citation** — a path or item ID followed by a gloss of what it says: "`SPECIFICATION.md` R-112 — every change records who made it — is a services-layer obligation". Keep the citation and the claim this document is making; cut the gloss.

## Decisions

A document's decisions live beside it in `<NAME>-DECISIONS.md` — `SPECIFICATION-DECISIONS.md` beside `SPECIFICATION.md`, `ARCHITECTURE-DECISIONS.md` beside `ARCHITECTURE.md`. This is where a resolved ticket's answer lands when it clears the bar below. Entries accrue as contested choices settle; a choice that does not clear the bar is just part of the document.

An answer earns an entry only when **both** hold:

- **Consequence** — reversing it later would be costly, or it constrains other decisions or documents. A choice that is local and cheap to change does not qualify.
- **Non-obviousness** — a competent reader could not reconstruct the reasoning from the specification and the alternatives. A choice whose reason is self-evident from the surrounding document does not qualify; state it plainly there instead.

These are not decisions, and each is cut on sight:

- names, labels, ordering, formatting;
- how the spec document itself is written or organised — prose versus IDs, section layout, where a file lives — a choice about the artefact, not the system;
- a choice a cited authority or a stated requirement already forces: record the constraint in the document, not a decision beside it;
- a tie-break between options with no material difference.

*When a decision file is split, move it and all the new files to `decisions/`*

Reasoning is what a reader needs least often and what costs most to hold, so it sits one link away rather than at the foot of the document it justifies. A document a reader can hold at once is worth more than reasoning they did not ask for.

The decisions document is created with the first decision, and linked from the document it belongs to the moment it exists. Its opening line names its subject document and what it holds. New entries go at the top of the file, so it reads newest first. It is held to every rule here, splitting past ~400 lines like any other (§ Length). When its subject document splits, it stays with the root and is not split alongside it; split it only on its own length.

Each entry is a `##` heading, holding these fields in this order and closed with a `---` rule:

````markdown
## The board's drag layer is the widget library's own.

**Alternatives**

- `@dnd-kit/core`, the obvious kanban answer, has published nothing since `6.3.1` on 2024-12-05 despite continuing commits.
- `@hello-pangea/dnd`, `react-beautiful-dnd`'s maintained successor, must reorder synchronously in its drop callback, landing a card before the services have accepted the move.

**Reasoning** WCAG 2.2 success criterion 2.5.7 Dragging Movements (https://www.w3.org/TR/WCAG22/#dragging-movements) is Level AA, so a keyboard alternative to every card drag is required by `SPECIFICATION.md` § Surfaces R-028 rather than optional, and React Aria ships one wired into the collection components the board is already drawn from.

**Costs** The API is shaped for Adobe's Spectrum, and one vendor sits beneath the entire widget surface.

**Settled by** [Pick the React widget library](https://github.com/jamesgorman2/hayate/issues/3).

**See** `prior-art/widget-library.md` § The drag layer.

---
````

| Field | Count | Length |
|---|---|---|
| the heading | 1 | one sentence stating the decision |
| **Alternatives** | 1..n | one short sentence each, naming the alternative and what disqualified it |
| **Reasoning** | 1 | one to four sentences on why the decision holds |
| **Costs** | 1 | one to two sentences on what the decision gives up |
| **Settled by** | 1..n | the ticket that settled it |
| **See** | 0..n | supporting evidence, by path and section |

A field carrying more than one value is a bulleted list under its label; a field carrying one sits inline after it.

The labels are a spine, so a reader skimming them alone still gets a sentence: *decision* — alternatives — reasoning. Keep the rejection with the alternative it disqualifies. A single reasoning block carrying two candidates' rejections and the positive case is the jumble this format exists to prevent.

**Alternatives** is never empty. A decision is a choice that could have gone another way, so an entry with none is either not a decision — cut it, and let the document state the thing plainly — or an alternative left unwritten, which is the failure the record exists to prevent.

**Costs** is never empty either. A decision that gives up nothing chose between options that were equivalent or strictly worse, so the choice was obvious — state it in the document and record no entry.

A heading stating more than one decision is more than one entry. Split it, and give each half its own alternatives and reasoning. Two candidates rejected on unrelated grounds are not that case: they are one decision with two alternatives.

A decision is never "open". If you are uncertain, it is a ticket on the plan.

**A decision that is overturned is replaced, not annotated.** Rewrite the entry as the decision that now stands, reasoned from where the spec is today, and delete what it replaced — including any entry that existed only to overturn an earlier one. An entry arguing with its own earlier version makes every reader reconstruct the current answer, and git holds the history for whoever wants it.

**No `## Open questions` section.** No document in a spec holds one (`SKILL.md` § Questions and tickets).

A question you could answer by thinking harder is not a ticket — answer it now. A question another document in the spec already answers is not a ticket — reference that document now.

## Empty sections

Remove them. Do not describe a section as having no content, or justify why it is empty.

## Tables and diagrams

Prefer lists to tables unless the data is wide. Prefer lists to diagrams unless the relationships are complex.

Add a diagram only when more than one relationship or flow must be held at once. Mermaid by default, so it renders in the repo and diffs as text; use another form only where Mermaid cannot carry the relationship, and say which. The diagram shows what exists and how it connects; the prose says why. Caption each with its claim ("billing never talks to auth directly"). Prose and diagram must never contradict — a contradiction is a review finding.

## The level test

Two axes run through a spec. **Scale** is scope: epic or feature. **Level** is which document a statement belongs in: specification, architecture or technical design. This test settles level.

Test whether content sits at the right level by asking: **if this were wrong, what would break, and who would catch it?**

- Nothing breaks → no decision was made ("the system should be scalable"). Cut it.
- A cross-cutting integration failure or a missed quality target, caught late and across several features → the architecture.
- A behaviour a feature's own acceptance tests would catch → that feature's specification.
- A behaviour no single feature owns, that the epic must hold across all of them → the epic specification.
- A wrong build of a behaviour the specification already fixes, caught in review because no black-box test could assert it → the technical design.

Content whose failure mode does not match its document's level is misclassified. Move it.

## Keeping documents current

A document is always work in progress. Edits, including deletions, are not recorded in the document — git holds that history.

When a plan's findings show a document outside the plan's spec is wrong, state the correct design plainly, add one line at the end of the section that depends on it naming the other document and what it gets wrong, and raise a ticket to correct that document. Never contort a design around a statement you have shown to be false, and never argue with it beyond that one line.
