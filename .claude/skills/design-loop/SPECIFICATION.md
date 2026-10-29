# The specification

The specification states the external behaviours the epic or feature must support. It is the **root** of the spec: every other document in the set hangs off it.

Behaviour that already exists is defined by the code implementing it and the tests proving it.

Read `AUTHORING.md` first; the rules there apply to this document too.

## Scale

**Epic scale.**

An **epic** specification states the behaviours that must hold once the epic is complete, in only enough detail to split it into features and write each feature's own specification. The rest belongs to the feature specifications.

**Readability decides the form.** Point form, plain english, one requirement per bullet.

**Feature scale.**

A **feature** specification states behaviour precisely enough to derive its acceptance tests. Gherkin is the default form (§ Acceptance criteria).

## Level

**Every statement that constrains what gets built is checkable from outside this scope** — something a black-box test could assert without reading the source. Most take the form actor, trigger or input, observable result; an invariant or an externally imposed constraint instead states the condition and what would violate it. Context and rationale constrain nothing and are exempt.

- **At level.** A behaviour this scope supports that can be acceptance tested.
- **Too low.** Behaviour a task decides while implementing: a message's exact wording, an interaction's step order where nothing depends on it, an edge no acceptance test would assert.
- **Sideways** (the architecture, the technical design). *How* the behaviour is supported.
- **Too high.** Behaviour that cannot be acceptance tested — one that would need live A/B testing to judge.

**The architecture or technical design follows from the specification, never the reverse.** A requirement naming a set the domain model, architecture or technical design fixes — which levels carry a status, which entities are addressable, what a kind holds — has to pass one test: could a reader who has not seen those documents derive it from the product's purpose? If not, it is the design read back, and it cannot be false independently of the document it copies. State the behaviour that makes the design necessary, not the design.

## Completeness test

- Is there enough content to produce a set of features (epic) or tasks (feature)?
- Does it accurately describe the intended behaviours?
- Is it internally consistent?
- Does it accurately describe the bounds of desired behaviour?
- Can the behaviours be written as acceptance tests?

## Sections

### Mandatory

1. **`## Summary`** — one to four sentences on the problem being addressed.
2. **`## Plan`** — a link to the `design-loop:plan` issue this spec is written under. What is still open lives there.
3. **`## Spec documents`** — the rest of the set: every document this one links directly, with one line each on what it holds. A document that has itself split indexes its own parts, so the index nests rather than flattening (`AUTHORING.md` § Length). Omit only where this document is the whole set.
4. **`## Scope`** — what this epic or feature creates or changes.
   - **`### Out of scope`** — scope that could plausibly satisfy the goal and is deliberately not being implemented. When such scope exists.

### Behavioural sections

At least one is required. These are suggestions, not a checklist — choose what fits, or use a different section. Meeting the completeness test is what matters. Each says when to include it.

- **`## Acceptance criteria`** — behaviours stated as scenarios (§ Acceptance criteria). Feature scale only.
- **`## Constraints`** — external constraints that mandate specific behaviour. One to four sentences each. When an external authority mandates behaviour.

Behaviours may be grouped into sub-sections by topic:

- **`### Validation`** — where input is accepted and its rejection is observable.
- **`### Input and state`** — where an input or state produces behaviour.
- **`### Error states`** — where a failure is observable to a caller or a user.
- **`### Empty and loading states`** — where a UI surface is added or altered. An unstated empty state ships as a blank screen and an unstated loading state as a frozen one, so assume every UI surface `## Scope` names needs both. Drop one only where the surface cannot reach that state, and then say nothing about it.

**A requirement appears exactly once**, and carries a stable local ID wherever something else references or tests it. IDs are `AC-nnn` for acceptance criteria and `R-nnn` for every other requirement, numbered within the document and never reused.

`Must`, `will` and `shall` are equivalent; prefer `must` or `will`. `May` makes the behaviour optional — use it only where optionality is intended.

### Acceptance criteria

**Feature scale.**

Gherkin is the default: Feature, Background, Rule, Scenario, Scenario Outline, GIVEN, WHEN, THEN, AND. A single-interaction criterion is one GIVEN, one WHEN and one THEN as indented continuation lines under the ID, `AND` continuing the step above.

A criterion states behaviour a user can observe from outside; how that behaviour is achieved (libraries, events, capture mechanisms) belongs in the technical design.

Behaviour that is a mapping rather than an interaction — input to output, state to outcome, old shape to new shape — goes in a table instead. The IDs stay either way.

```gherkin
Feature: bestPractices.bestPractice.update
  Rename a best practice, rewrite its body, move it to another practice document, or reorder it.

  Background:
    Given the actor has read best practice "One assertion per test" under "Unit tests" and holds its EntityID

  Rule: Only the named properties change
    Scenario Outline: AC-001 One property is updated
      When the actor updates the best practice with <property> set to a new value
      Then the output is the best practice with <property> changed and the other unchanged
      Examples:
        | property |
        | name     |
        | body     |
      
  Rule: A best practice may move only to another practice document
    Scenario: AC-001 Moved via parent
      Given practice document "Code review" exists
      When the actor updates the best practice with parent set to "Code review"
      Then the output’s parent is "Code review"
      And a subsequent read of "Code review" lists it last and a read of "Unit tests" does not list it

    Scenario: AC-001 Moved and positioned in one call
      Given "Code review" holds best practices P and Q in that order
      When the actor updates the best practice with parent "Code review" and before set to Q
      Then a subsequent read of "Code review" lists P, it, Q
  
```

**Epic scale.**

State the behaviour in plain english and let the feature specification derive the criteria. The prefixed EARS forms (`WHEN <trigger>`, `IF <condition>, THEN`, `WHILE <state>`, `WHERE <feature is included>`) are available where a condition has to be stated, but they do not survive repetition: a handful reads, a section of them does not. Where a group shares a condition, state it once above them. One requirement per statement. Never argue the form on the page.

## Anti-patterns

The defects whose smell is not obvious from the rule they break.

- **Indeterminate set** — an item quantifies over a set whose membership or variation is nowhere stated: "each command SHALL …" where the reader cannot list the commands, or where some behave differently. Fix: enumerate the set in `## Scope` where this document owns it; otherwise reference the document that fixes it. Never copy the members into the requirement — that is the next entry.
- **Design restated as obligation** — a requirement whose content is a fact another document already fixes (the entity set, which entities carry a dependency, what a kind holds), rendered as a `shall`. It cannot be false independently of the document it copies, so no test distinguishes the two and they drift apart. Several such requirements are usually one behaviour repeated once per member of the set. Fix: state that behaviour once, generally, and reference the document that fixes the set (§ Level).
- **Contingent requirements** — requirements that only hold under a particular architecture, technical design, or (for epics) feature set. These are at the wrong level or the wrong scale. Fix: delete duplicates, move to an architecture or technical design document, set aside as possible feature requirements to be assessed later.
- **Inherited ambiguity** — the goal or the epic is ambiguous and the specification silently picks a reading it was never given. Fix: state the chosen reading in the decisions document, or raise the competing readings as a ticket.
- **Contorted around existing code** — behaviour bent to avoid changing a contract this plan's findings show to be wrong, the only driver being that today's code works that way. Fix: state the behaviour the system needs and treat changing the code as work to be scheduled.
- **Design in the specification** — a constraining statement no black-box test could assert. Fix: keep the behaviour it stood in for; route the mechanism to the architecture or the technical design.
- **Technical design** — an entry enumerating a type, schema or field shape rather than stating an obligation. Fix: write it to the architecture or the technical design.
- **Mishandled resolved question** — closing a ticket without writing its answer into the section it constrains, or writing the answer in and leaving the ticket open. Fix: write the answer in, then close.
- **Too much detail** — if removing a statement would not change what gets built, cut it.
- **Premature precision at epic scale** — exact field values or exhaustive error lists in an epic specification, pinning down edges no epic-wide behaviour depends on. Fix: state the broad behaviour, and leave the edge to the feature.
- **EARS as house style** — prefixed `WHEN`/`IF`/`WHILE`/`WHERE` statements carried past the point the section still reads, or a requirement carrying a sentence defending its own form. Fix: lift a shared condition above the group; return the rest to plain english, unless the plain english could be misinterpreted.
- **Justification and pontification** — saying why there is a requirement, some cool stuff about what a requirement might mean. Fix: delete it.
