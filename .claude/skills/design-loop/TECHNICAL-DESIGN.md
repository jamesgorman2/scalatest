# The technical design

The technical design covers the new or changed, non-obvious technical choices for a single feature. It pairs with the specification at **feature** scale — where the feature has such choices. A feature whose build is the standard approach for this stack has no technical design.

Read `AUTHORING.md` first; the rules there apply to this document too.

## Level

- **At level.** How this feature is built: the units inside it and their boundaries, the paths carrying risk, the data it changes, what it integrates with, and how it is verified.
- **Too low.** Obvious implementation detail: file layout, exhaustive call bodies, any path carrying no design risk. Show code only where a specific language or platform risk is being addressed.
- **Sideways** (the specification). What the feature does: acceptance criteria, states, errors.
- **Too high.** A structural decision that escapes this feature.

## Completeness test

- Does it accurately describe the design at the technical design level?
- Is it internally consistent?
- Is it constrained to the bounds of the specification?
- Can it be implemented by one or more tasks?

## Sections

### Mandatory

1. **`## Summary`** — one to four sentences on the technical problem being addressed.

### Design sections

At least one is required. These are suggestions, not a checklist — choose what fits, or use a different section. Meeting the completeness test is what matters.

- **`## Components`** — the key modules, classes and functions, and the broad logic flows between them. When the feature has internal structure worth naming.
- **`## Critical code paths`** — key business logic or algorithms. Prose plus pseudocode by default; a sequence diagram or a state table where the risk is in the interleaving rather than the steps. When a path is load-bearing, non-obvious, or carries correctness or integration risk.
- **`## APIs`** — the *external* APIs the feature must deliver. When it delivers one.
- **`## Data and persistence`** — critical changes to schema, or interactions with the persistence layer. When the feature changes stored data or how it is reached.
- **`## Migration`** — required migrations or back-fills of persistent data. When existing data must change shape.
- **`## External libraries`** — new external libraries the implementation should use, what they do, and the risks they carry. When the feature adds one.
- **`## Testing`** — new or non-obvious testing, or test harnesses, that the feature requires. How the testing is built, not the values being tested. When it is not the standard approach for this stack.

## Anti-patterns

The defects whose smell is not obvious from the rule they break.

- **Restating the specification** — behavioural claims standing where their realisation belongs. Fix: reference the specification, keeping only how it is built.
- **Restating the obvious** — the standard approach for this stack, spelled out because a section wanted content. Fix: say nothing.
- **Over-design** — a unit, code path, schema delta or integration tracing back to no behaviour, quality requirement or named integration. Fix: for each unit, name the specification item it serves; a unit with no answer comes out.
- **Premature task breakdown** — an imperative task list, an effort estimate or a build order. Fix: stop at the units, their responsibilities and their dependencies.
- **Written to be transcribed** — something to copy, rather than a concept to implement. Fix: cut to the decisions and their drivers, or to an algorithm shape.
- **A boundary shaped only by the existing code** — an awkward unit boundary justified by nothing but "that is how the current code works". Fix: design the long-term fix, or record the existing shape as a stated constraint with its cost.
- **Builds on a statement it has shown to be wrong** — the design contorts to satisfy an epic-level statement its own findings falsify, or names the defect and proceeds against it anyway. Fix: record the correct design and raise a ticket to change the epic document.
