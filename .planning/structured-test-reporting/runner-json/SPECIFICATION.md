# Structured JSON from `Runner`

Scale: feature.

## Summary

ScalaTest 3.2.19's `J` reporters write one JSON line per event, and no event names its node's parent, so a tool reading that output cannot recover the nesting once suites or tests run in parallel. Under `Runner`, this feature makes every `J` reporter write one JSON object per run holding the run's whole test structure, while every other reporter receives exactly what 3.2.19 sends.

## Plan

[Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18)

## Spec documents

- [ACCEPTANCE-CRITERIA.md](ACCEPTANCE-CRITERIA.md) — the acceptance criteria, as Gherkin scenarios and tables.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the object's layout: every key of a node, message, throwable and location, which event supplies each, and how a node's name and label and a suite's file reach the writer.
- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — why the object holds final state, how nodes are named and labelled, why a `StackDepthException` has no stack trace, why a `DistributedTestRunnerSuite`'s abort aborts its test, and which suites a halted run lists and how.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic specification this feature is written against; its § Test structure and § JSON output fix what the object holds.
- [../SPECIFICATION-DECISIONS.md](../SPECIFICATION-DECISIONS.md) — the epic's decisions this feature carries: filter characters drop only data, and the format has no version of its own.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic architecture this feature builds under `Runner`.

Terms are those of `.docs/ubiquitous-language.md`.

## Scope

Under `org.scalatest.tools.Runner` on the JVM, on every Scala version in `../SPECIFICATION.md` § Scope, every `J` reporter writes the object of § The JSON object in place of 3.2.19's one line per event. No other reporter changes.

- R-012 Under the JVM, Scala.js and Scala Native `Framework`, every reporter receives what 3.2.19 sends (§ Constraints), and each `J` reporter writes what it writes in 3.2.19.

A run here is one call of `Runner.main` or `Runner.run`, including those IntelliJ's forked mode and `ScalaTestAntTask` make, or one run that the GUI reporter (`-g`) starts with its Run button.

`Runner`'s arguments select the execution mode:

- *sequential* — no `-P` argument; top-level suites run one after another on the calling thread.
- *concurrent* — `-P`, `-P<n>` or `-PC`; top-level suites and nested suites run on a thread pool, as do the tests of a suite mixing in `ParallelTestExecution`.
- *concurrent with suite sorting* — `-PS`, `-PS<n>`, `-PSC` or `-PCS`; as concurrent, with events ordered by suite through `SuiteSortingReporter`.

Within every mode, suites may be asynchronous or mix in `ParallelTestExecution`, `RandomTestOrder`, `SequentialNestedSuiteExecution` or `StepwiseNestedSuiteExecution`, and `-W` may turn on slowpoke alerts.

A run may select suites with:

- `-s`, alone or with `-z`, `-t` or `-i`;
- `-s` with a glob;
- `-A`, `-j`, `-b`, `-m` or `-w`;
- `-z` or `-t` without `-s`;
- none of these, which discovers every suite on the `-R` runpath, narrowed by `-q`.

It may filter by tag with `-n` and `-l`.

A *message* is an `InfoProvided`, `AlertProvided`, `NoteProvided` or `MarkupProvided` event, emitted on its own or recorded in a test's closing event.

### Out of scope

- The human-readable block and progress output.
- Test output in the JSON.
- `RunAborted` in place of `RunCompleted` when a run finishes with nodes unfinished, the *process failed* reason, the `-fJ` marker and shutdown hook, and *not run* nodes other than those of `ACCEPTANCE-CRITERIA.md` AC-022, AC-025 and AC-026.
- Which top-level suites appear when `Runner` aborts a run before `RunStarting` for any cause but a suite's constructor throwing, such as an `-s` class that cannot be loaded or is not a suite, a JUnit or TestNG wrapper that fails to load, or an `expectedTestCount` that throws.
- The object under the JVM `Framework`, sbt's forked mode, Scala.js and Scala Native.
- The `org.scalatest.tools.Runner.forever` system property, whose `-P` loop never ends a run.

## Constraints

Under `Runner`, this feature holds `../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x and the **Forked** bullet of § Constraints § IntelliJ. `ACCEPTANCE-CRITERIA.md`, Rule *Existing reporters receive what 3.2.19 sends*, tests them.

In this specification, *3.2.19* means `org.scalatest:scalatest` 3.2.19 run with the same arguments over the same compiled suites. A reporter *receives what 3.2.19 sends* when it receives the events 3.2.19 sends it, with the same field values apart from timestamps, durations and thread names, in this order:

- in a sequential run, the same sequence;
- in a concurrent run, an order 3.2.19 could deliver them, with each suite's events in the order 3.2.19 delivers that suite's.

- R-001 The object is JSON text as RFC 8259 defines it (https://www.rfc-editor.org/rfc/rfc8259), and a `-fJ` file is encoded in UTF-8, which RFC 8259 § 8.1 requires of JSON "exchanged between systems that are not part of a closed ecosystem".

## The JSON object

- R-002 The object holds the run's test structure (`../SPECIFICATION.md` § Test structure), rooted at the run node. Every node states its kind — run, suite, scope or test.
- R-003 A node's children and messages form one sequence. Its children appear in declared order. Each message appears after every child of its node that the run had reached when the message was emitted, whether that child then ran at once, concurrently or later, and before every other child. Its messages keep the order in which they were emitted. This holds in every execution mode.
- R-004 Each node holds its final state, each value taken from the event attached to it that supplies the value, and nothing per event: no ordinal, formatter, rerunner or payload, and no event's copy of its suite's name, ID or class name. A node holds:
  - its kind, and its outcome where it has one;
  - where it is aborted with a reason, that reason, such as *not run* for a node the run was to execute that never started (`../SPECIFICATION.md` § Test structure);
  - where it failed, was canceled or was aborted, its closing event's message and throwable (R-006);
  - its start time, end time and duration, and, for a suite or test, its thread name;
  - for a suite, its name, suite ID and location; for a scope or test, its name, label and location (R-013), a location being the class, method, file and line that ScalaTest gives it, each where known;
  - for the run node, the values of R-007, its expected test count, its config map with each value as its `toString`, the start time and duration of discovery where the run sent discovery events, and its closing event's summary counts.
- R-005 Every event the run sends its reporters contributes its data to one node, each message as its own item. `RunStarting`, `RunCompleted`, `RunAborted`, `RunStopped`, `DiscoveryStarting` and `DiscoveryCompleted` contribute to the run node. The exceptions:
  - neither a `DiscoverySuite` nor a `DistributedTestRunnerSuite` is a node, so their `SuiteStarting` and `SuiteCompleted` contribute to none, and a `DistributedTestRunnerSuite`'s `SuiteAborted` contributes to the one test it ran (`ACCEPTANCE-CRITERIA.md` AC-052);
  - the `RunAborted` that a suite's throwing constructor causes also gives its message and throwable to the suite that threw (AC-022, AC-025, AC-026).
- R-006 A throwable is recorded with:
  - its class name;
  - its `getMessage`, absent when `null`;
  - for a `StackDepthException`, its failed code's file and line number, each where defined, and no stack trace;
  - for any other throwable, every frame of its stack trace;
  - its cause and its suppressed exceptions, in order, each recorded the same way by its own type, except that a throwable already recorded within the same node's or message's throwable is recorded as a reference to that record, so a cycle ends.
- R-007 The root records the ScalaTest version that `Runner -v` prints, and the reporter's event-filter characters.
- R-008 Configuration characters other than the event-filter characters do not change the object.
- R-013 A suite's name is its suite name. In a suite of ScalaTest's style traits — those of `org.scalatest.funsuite`, `flatspec`, `funspec`, `wordspec`, `freespec`, `propspec`, `featurespec` and `refspec`, async and fixture variants included — that passes its tests the reporter it was given, a scope or test holds its name and its label (`.docs/ubiquitous-language.md` **Name**, **Label**). In any other suite, a scope or test holds its event's text in place of its name, and its event's formatted text as its label, absent where no event carries one.
- R-009 On a `J` reporter, a node's kind, name, label, suite ID and location, its place in the test structure, and the values no event supplies — the root's version and filter characters, a reason, and the outcome aborted that the structure gives a node — are present whatever the characters. Every value an event supplies is present exactly when that event passes 3.2.19's `FilterReporter` for the reporter's event-filter characters, with each test's recorded events filtered as it filters them.
- R-010 The fork's documentation describes the format for third-party readers: every field, what it means, when it is empty or absent, the effect of each event-filter character, and the compatibility promise of `../SPECIFICATION.md` § JSON output.
