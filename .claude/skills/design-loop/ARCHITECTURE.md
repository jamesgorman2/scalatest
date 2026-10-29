# The architecture

The architecture is a map for building working software. It presents the overall shape of a system, and it pairs with the specification at **epic** scale — where the epic changes that shape. An epic that changes no boundary, module or store has no architecture.

A feature never has one (`SKILL.md` § What a feature is).

Read `AUTHORING.md` first; the rules there apply to this document too.

## Level

- **At level.** The sentence is about what the product does, what constrains it from outside, or the entities, modules, boundaries, flows and stores that carry either.
- **Too low.** Detail one engineer reverses without changing the shape: attribute lists, method signatures, queries, UI trees, library configuration.
- **Sideways** (the specification, the technical design). What the product must do, and how any one feature is built.
- **Too high.** It decides nothing: an adjective that forces no shape, a category standing where a named part belongs.

## Completeness test

- Is there enough content to produce one or more technical designs?
- Does it accurately describe the design at the architecture level?
- Is it internally consistent?
- Does it accurately describe the bounds, and the critical components, structure and interactions?

## Sections

### Mandatory

1. **`## Summary`** — one to four sentences on the architecture problem being addressed.

### Structural sections

At least one is required. These are suggestions, not a checklist — choose what fits, or use a different section. Meeting the completeness test is what matters. Each says when to include it.

- **`## Boundaries ruled out`** — boundaries a competent reader would genuinely read either way, one line each stating why it is out. Boundaries only: scope ruled out of the epic belongs in `SPECIFICATION.md` § Scope § Out of scope. When such a boundary exists.
- **`## Compliance`** — obligations an external authority imposes, one line each naming the obligation and the authority. One entry per obligation, at the granularity where a single revision of the authority could change it — not one per clause, and not one blanket entry per authority. When such an authority exists.
- **`## External systems`** — systems this product must talk to and does not own: what each is used for, and what forces the integration. A system chosen freely belongs in `## Third-party services`, or in the decisions document where a reader would otherwise re-propose what it beat. When at least one exists.
- **`## Performance`** — the numbers the shape has to hit, each with what it is measured on ("sync completes within 30 s of reconnect"). When a number forces the shape; an adjective never does (§ Anti-patterns).
- **`## Domain model`** — the objects the purpose requires and no others: name plus a one-line responsibility, attributes only where load-bearing. When the scope has domain objects worth naming.
- **`## Modules`** — a diagram where more than one relationship must be held at once (`AUTHORING.md` § Tables and diagrams), otherwise the list alone. Each module: name, one-line responsibility, and the entities it owns. Where the system has more than one deployable unit, each is a module and may be composed of modules. Inherited parts appear as external boxes — referenced, never re-drawn. When the scope has structure worth naming.
- **`## APIs`** — the boundaries between modules and to the outside, as operations, never method signatures. When a feature author must build against a boundary.
- **`## Data flow`** — a flow that crosses a module boundary and carries the critical path or the main failure path ("checkout to billing to fulfilment; payment-declined is the failure path"). When such a flow exists and no other section already states it.
- **`## Persistence`** — where data rests: which module or store owns each entity, and the shape of that store where it forces anything (one store, per-module stores, event log). When data outlives a single run.
- **`## Libraries`** — only those that constrain the shape: frameworks, not utilities ("Room constrains the shape; okio does not"). State conditions of use where there are any: a mandated version, API, or restricted subset. When a library constrains the shape.
- **`## Third-party services`** — bought or hosted components, including SaaS, the design must fit around, each with its conditions of use ("Stripe for payments, Payment Intents API only"). When such a component shapes the design.

## Anti-patterns

The defects whose smell is not obvious from the rule they break.

- **Component dump** — every box enumerated, with no principle for what earned its place. Fix: for each box, name what breaks without it; a box with no answer comes out.
- **Technology inventory** — a stack list standing in for structure. Fix: keep only what constrains the shape, in `## Libraries` or `## Third-party services`; structure lives in `## Modules`.
- **Quality adjectives** — "fast", "secure", "scalable" force nothing. Fix: a number in `## Performance`, or cut it.
- **Decision ceremony** — a decisions entry hung on every choice. The map is the decision; only a choice a reader would otherwise re-propose earns one. Fix: cut the entry and let the document state the choice plainly (`AUTHORING.md` § Decisions).
- **Defensive writing** — a statement shaped so its author cannot be blamed: a hedge on a decision, a caveat restating that the future is uncertain, a note explaining why a section is empty, a risk parked where a decision belongs. Fix: state the shape and let the next pass revise it.
- **Wall of text** — prose where a short list or a diagram would carry it. Fix: replace it with the list or diagram (`AUTHORING.md` § Tables and diagrams).
