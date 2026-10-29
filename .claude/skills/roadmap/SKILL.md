---
name: roadmap
description: Creating, updating and reading a project or epic's roadmap, starting or completing work on an epic or feature, or promoting a feature to an epic. Not for designing or executing an epic or feature (design-loop, build-loop).
---

A roadmap file contains upcoming and in-progress features. It is used to avoid flooding GitHub issues with work that has not been fully committed to. As features are completed, the work required may change. This reduces the level of commitment to future work while still recording the intended work. 

## File structure

- `.planning/`
  - `ROADMAP.md` - project wide roadmap containing upcoming features and epics
  - `<epic>/` 
    - `ROADMAP.md` - roadmap for a single epic, containing upcoming features

## `ROADMAP.md` Structure

**Template**

```markdown
# Roadmap {- <epic>}

## Summary

<sections>

## Deferred

- <deferred item list>
```

### Summary


### Sections

The main body of the roadmap is a list of epics and features in the approximate intended order of implementation.

**Top level section**

```markdown

## <N>. <epic/feature title>

<brief>

Enables: <...>

Settles: <...>

Traps: <...>
```

**Nested sections**

```markdown
## <N>. <theme title>

<summary>

### <N>.<M> <epic/feature title>

<...>
```

Large roadmaps may use nested sections to organise work into thematic groups.

#### Brief

The brief is a short, 1-2 paragraph, description of the intent of the feature. It is not a specification or design document. It may use lists instead of prose. This is particularly useful when enumerating the intended behaviours of feature in an epic where the high level behaviour is already known.

Any additional *already known* information that is known should be included in addition to the main 1-2 paragraph brief. Include it as a reference if it is already recorded.

#### Optional sections

An epic roadmap adds sections that state how the [SKILL.md](../../../../sync-engine/.claude/skills/design-loop/SKILL.md)features address the epic. Each is a simple list referencing, *not repeating* the source section(s) only. Only include necessary sections.

- **Enables**: specification and/or architecture sections implemented by this feature 
- **Settles**: key behaviours left open in the specification and/or architecture this feature settles
- **Traps**: risks and gotchas identified during the epic's `design-loop` likely to impact the feature

### Deferred

A list of possible features that were deliberately excluded and are not recorded elsewhere. 1-2 sentences max each.

## Starting an epic or feature

When an epic or feature is started:
- create an issue with the tag `epic` or `feature`
  - its body is the brief text from its section in ROADMAP.md
  - if the feature is in an epic, create it as a sub-issue of the epic
- delete the epic or feature's section from the `ROADMAP.md`
- create its design issue as a sub-issue, per `design-loop`
- create the epic or feature directory
  - `.planning/<feature>/` - a feature not in an epic
  - `.planning/<epic>/` - an epic
  - `.planning/<epic>/<feature>/` - a feature in an epic

## Promoting a feature to an epic

If a piece of work originally tagged as a feature is determined to be too large, it may be promoted to an epic.

Replace the `feature` tag with `epic`. If planning or design artefacts have been created, follow the rules here, and in `design-loop` and `build-loop` to create a roadmap, redistribute specifications, etc. 

## Completing an epic or feature

### Epic

When all features in an epic are complete or abandoned, close the `epic` issue.

Before deleting the epic's roadmap, review the deferred items and for each, choose whether to discard or promote it to the project roadmap's deferred items. 

### Feature

When the `build-loop` issue is close, close the parent `feature` issue.

## Creating features for an epic

After an epic has been specified in a `design-loop`, it should be broken into features for implementation.

A feature is the smallest releasable unit of customer value. The customer is whoever consumes what the project provides - for a web application this is the end user; for a library this is the library consumer.

When building a greenfield project, the work may not be releasable until a large body of work has been completed. Substitute 'releasable' with 'verifiable'. Is it sufficiently coherent that some domain expert can validate its behaviour? Is it sufficiently small that this is not an onerous task?

When you have broken the epic in to features, record them in the epic's `ROADMAP.md`.

Use a subagent to review the features and ensure that all requirements and architecture are covered by at least one feature.

