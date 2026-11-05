# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## When a suite's constructor halts a `Runner` run, no suite that only an unbound `-z` or `-t` would select is listed.

**Alternatives** Listing such suites means constructing every suite on the runpath to find the tests `-z` or `-t` name, running constructors that 3.2.19 never runs once the run has halted, any of which may itself throw.

**Reasoning** `Runner` finds the suites an unbound `-z` or `-t` selects only by constructing each suite on the runpath, a search that a run halted at a named suite's constructor never reaches. Every other selection argument names its suites, or selects them from the runpath's suite names, without constructing them.

**Costs** The halted run's object omits suites that would have run, falling short of `../SPECIFICATION.md` § Test structure's rule that every top-level suite the run was to execute still appears.

**Settled by** the review of [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## Under `Runner`, a suite whose constructor throws is aborted in the test structure only; existing reporters still receive 3.2.19's `RunAborted` and no `SuiteAborted`.

**Alternatives**

- Sending existing reporters a `SuiteAborted` for the suite before the `RunAborted` adds an event 3.2.19 never sends, so every `-C` reporter, IntelliJ's among them, would see a different sequence (`../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x).
- Deferring the failure, as the `Framework` and `DiscoverySuite` do with a `DeferredAbortedSuite`, and running the remaining suites turns 3.2.19's `RunAborted` into `RunCompleted`, a larger departure from the same constraint.
- Showing only the aborted run node loses which suite threw and which suites never ran, against `../SPECIFICATION.md` § Test structure.

**Reasoning** `../SPECIFICATION.md` § Test structure requires the suite that threw to be reported as aborted, but its § Constraints § Compatibility with ScalaTest 3.2.x fixes the events reporters receive, so the abort can be reported only where no 3.2.19 contract exists: the test structure. The run node stays aborted with the `RunAborted` every reporter received, so the test structure never contradicts the event stream, only adds to it.

**Costs** The test structure holds a suite node, and *not run* siblings, for which no reporter received an event, so a reader cannot rebuild that suite's `SuiteAborted` from the event stream, and the same failure reads differently under `Runner` and the `Framework`.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---
