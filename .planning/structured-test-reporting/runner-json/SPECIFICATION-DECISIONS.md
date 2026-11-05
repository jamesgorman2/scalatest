# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A scope or test holds its name as written in the source and its label as formatted, in place of ScalaTest's joined test name.

**Alternatives**

- Storing the event's text, as `ScopeOpened` and `testText` carry it, keeps the verb-prefixed, lossy naming the fork exists to replace (`../SPECIFICATION.md` § Summary).
- Storing ScalaTest's joined test name beside them repeats what a reader rebuilds from the names and labels of the test's scopes and the test.

**Reasoning** A tool needs the text the user wrote, to match a node to its source, and the text ScalaTest prints, to render a node as 3.2.19 would; together they reconstruct any joined name, so neither is derived from the other and nothing is stored twice.

**Costs** No 3.2.19 event carries the source text, so `Engine` must supply it through the envelope; a suite not built on `Engine` falls back to the event's text, so a node's name means slightly different things by suite kind; and a reader wanting ScalaTest's test name must rebuild it.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## A `StackDepthException` is recorded with its failed-code location and no stack trace.

**Alternatives** Recording every frame of every throwable writes, for each failed or canceled test, ScalaTest's assertion machinery and the test body around the one line the exception's location already names.

**Reasoning** A `StackDepthException` exists to name the line of the failing code, which ScalaTest's own output reports in place of its trace; any other throwable has no such line, so its frames are the only record of where it came from, and its causes keep theirs. `../SPECIFICATION.md` § Constraints requires class, message, cause chain, suppressed exceptions and location to be preserved exactly, not frames.

**Costs** A reader cannot see how the test reached a failing assertion made inside a helper method, and the consolidator cannot print a `StackDepthException`'s trace.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## The object holds each node's final state, not the events attached to it.

**Alternatives** Writing each node's events with every field, as 3.2.19's JSON lines do, repeats each event's suite name, suite ID and suite class name, and carries ordinals, formatters and rerunners, which only restate the order the tree already gives or have no meaning outside the run that wrote them.

**Reasoning** Every reader of the format, the consolidator and third-party tools, needs a node's state and its place, and the tree gives the place; one record per node, with one key per value, is what the documentation of R-010 can describe and keep stable under semantic versioning.

**Costs** The object no longer holds every field of every event, and the `Event`s the consolidator rebuilds from it carry no ordinals, formatters or rerunners (`../ARCHITECTURE.md` § Multi-run consolidation executable).

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## When a suite's constructor halts a `Runner` run, no suite that only an unbound `-z` or `-t` would select is listed.

**Alternatives** Listing such suites means constructing every suite on the runpath to find the tests `-z` or `-t` name, running constructors that 3.2.19 never runs once the run has halted, any of which may itself throw.

**Reasoning** `Runner` finds the suites an unbound `-z` or `-t` selects only by constructing each suite on the runpath, a search that a run halted at a named suite's constructor never reaches. Every other selection argument names its suites, or selects them from the runpath's suite names, without constructing them.

**Costs** The halted run's object omits suites that would have run, falling short of `../SPECIFICATION.md` § Test structure's rule that every top-level suite the run was to execute still appears.

**Settled by** the review of [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## Under `-P`, a `SuiteAborted` from a `DistributedTestRunnerSuite` aborts the one test that suite ran, not the suite that holds the test.

**Alternatives**

- Attaching it to the holding suite aborts a suite that goes on to send `SuiteCompleted`, and leaves the test that failed with no outcome.
- Making the `DistributedTestRunnerSuite` a node puts into the test structure a suite the user never wrote, one per test.

**Reasoning** Under `ParallelTestExecution`, ScalaTest runs each test in its own `DistributedTestRunnerSuite`, so that suite's `SuiteAborted` reports a failure of that one test outside its body, as from a throwing `beforeEach`; the test is the node that failed.

**Costs** The test's outcome comes from a suite event, so it has no `TestStarting` data, and the run's summary counts it as an aborted suite while the test structure shows an aborted test.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

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
