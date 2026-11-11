# JSON output layout

## Summary

This document fixes the layout of the consolidated JSON output that `SPECIFICATION.md` R-028 and R-080 require: how the object is encoded, and every key of a suite, scope, test, message, throwable, location and run record, with what each means and when it is absent. Each node and run record holds its final state, folded from its events, never the events themselves (`SPECIFICATION.md` R-108). Third-party readers build against it under the compatibility promise of `SPECIFICATION.md` R-084. § Not yet fixed lists the parts of the object whose keys this document does not yet name.

Terms are defined in `.docs/ubiquitous-language.md`. R-numbers are those of `SPECIFICATION.md`.

## Encoding

- The object is JSON text as RFC 8259 defines it (https://www.rfc-editor.org/rfc/rfc8259), and the JSON file is encoded in UTF-8.
- An absent value omits its key; `null` is never written. An empty string is written `""`.
- Keys are written in the order this document lists them.
- `startedAt`, `endedAt` and a message's `at` are the supplying event's `timeStamp`, in epoch milliseconds. `duration` is in milliseconds.
- Strings escape `"`, `\` and control characters. An unpaired UTF-16 surrogate, which a Java string can hold and UTF-8 cannot encode, is written as a `\uXXXX` escape (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2).
## Nodes

This section gives the layout of a suite and of every nested suite, scope and test below it. § Run records gives a run's.

Every event a run reports contributes its data to one node or to its run's record, each message as its own item (§ Messages). No node holds an ordinal, formatter, rerunner or payload, or an event's copy of its suite's name, ID or class name (R-108); nor does a run record. Neither a `DiscoverySuite` nor a `DistributedTestRunnerSuite` is a node (R-096), so their `SuiteStarting` and `SuiteCompleted` contribute to none; a `DistributedTestRunnerSuite`'s `SuiteAborted` contributes to the one test it ran, which is then aborted.

A node's keys, in order: `kind`, `name`, `label`, `suiteId`, `location`, `outcome`, `reason`, `message`, `throwable`, `startedAt`, `endedAt`, `duration`, `threadName`, `contents`, `output`.

- `kind` — `suite`, `scope` or `test`. Always present.
- `name` — the node's name, never a joined name. In `"A" when { "b" should { "c" in { … } } }`, scope `A` has name `A`, scope `b` has name `b` and test `c` has name `c`; ScalaTest's test name `A when b should c` is not stored. A suite's name is its suite name. A scope or test holds its name where its suite uses a ScalaTest style trait (those of `org.scalatest.funsuite`, `flatspec`, `funspec`, `wordspec`, `freespec`, `propspec`, `featurespec` and `refspec`, async and fixture variants included) and passes its tests the reporter it was given. In any other suite, `name` holds the event's text in its place: `ScopeOpened`'s `message`, or the test's `testText`.
- `label` — scopes and tests only: the node's label, without leading indentation, since the tree gives the nesting. In the example above, `A` has label `A`, `b` has `when b` and `c` has `- should c`; an `AnyFunSpec` test `t1` has `- t1`. In a suite that supplies names (see `name`), a test that never closed still has one. In any other suite, it is the `formattedText` of the node's `ScopeOpened` or closing event, and absent where neither carries one.
- `suiteId` — suites only, always present.
- `location` — § Locations. A scope's comes from its `ScopeOpened`. A test's comes from its `TestStarting`, else its closing event, skipping `SeeStackDepthException`. A suite's is its class name in `className`, plus the file its scopes' and tests' registrations share where they all share one, and never a `lineNumber`.
- `outcome` — in ScalaTest's spelling:
  - test: `succeeded`, `failed`, `canceled`, `ignored`, `pending` or `aborted`;
  - scope: `pending` or `aborted`;
  - suite: `aborted`.

  Absent where the node has none (R-098).
- `reason` — `notRun`, only on a node aborted with reason *not run* (R-095, R-100, R-101).
- `message`, `throwable` — on a failed, canceled or aborted node: the `message` and `throwable` (§ Throwables) of its closing `TestFailed`, `TestCanceled` or `SuiteAborted`. A test that a `DistributedTestRunnerSuite`'s `SuiteAborted` aborted takes them from that event. A node aborted without a closing event of its own has neither: a scope or test open when its suite aborted (R-099), a node that never started (R-100), or a node its run left unfinished (R-064).
- `startedAt` — the opening event's: `SuiteStarting`, `ScopeOpened` or `TestStarting`. Absent on a node that never started.
- `endedAt` — the closing event's. For a node aborted without a closing event of its own, that of the `SuiteAborted` or `RunAborted` that aborted it, and absent where there is none.
- `duration` — for a suite or test, its closing event's, absent where that event carries none, as `TestIgnored` never does. For a scope, and for a node aborted without a closing event of its own, `endedAt - startedAt`, absent unless both are present. A node that never ran has none (R-103).
- `threadName` — suites and tests only: the opening event's, else the closing event's.
- `contents` — always present, `[]` when empty: the node's children and messages in the one sequence R-109 gives, children in the order R-105 gives. Each child or message states its own `kind`.
- `output` — the output captured against the node (R-012, R-122), as `{ "stdout": …, "stderr": … }`. Absent where none was captured against it.

A not-run test class (R-095) is a node with `kind` `suite`, `outcome` `aborted`, `reason` `notRun` and `contents` `[]`. For class `p.B`, its `suiteId` and `location.className` are `p.B`. It has no times, `threadName`, `message` or `throwable`.

## Messages

A message is an `InfoProvided`, `AlertProvided`, `NoteProvided` or `MarkupProvided` event, emitted on its own or recorded in a test's closing event; which of the two is not kept. A message in `contents` writes `kind`, `message`, `at`, `location`, `throwable`, `threadName`:

- `kind` — `info`, `alert`, `note` or `markup`.
- `message` — the event's `message`; for `MarkupProvided`, its `text`.
- `at` — the event's `timeStamp`.
- `location` — § Locations; absent where the event carries none.
- `throwable` — § Throwables; absent where the event carries none.
- `threadName` — only where it differs from its node's `threadName`, or the node has none.

## Throwables

A throwable record holds, in order:

- `id` — an integer, numbered depth-first from 1 within one record: one node's, run record's or message's `throwable`.
- `className`.
- `message` — `getMessage`, absent when `null`.
- for a `StackDepthException`, `TestFailedException` and `TestCanceledException` among them: `location` (§ Locations), holding the failed code's file and line number, each where defined; and no `stackTrace`.
- for any other throwable: `stackTrace`, every frame as `{ "className", "methodName", "fileName", "lineNumber", "isNative" }`, with `fileName` absent when unknown, `lineNumber` absent when negative, and `isNative` present only when `true`.
- `cause` — recorded the same way, by its own type; absent when there is none.
- `suppressed` — an array, in order, each recorded the same way; absent when empty.

A throwable already recorded within the same record is written `{ "ref": <id> }`, which ends any cycle. "Already recorded" means the same object, never `equals`, which a throwable class may override.

## Locations

A `location` is one object whose keys, each present only when known, are `className`, `methodId`, then `filePath` or `fileName`, then `lineNumber`. `filePath` is written where ScalaTest has the path, `fileName` only where it has not; never both. `filePath` is written as recorded, usually absolute; readers relativise it.

ScalaTest's `Location`s fold into it:

- `LineInFile` — `filePath` or `fileName`, and `lineNumber`;
- `TopOfClass` — `className`;
- `TopOfMethod` — `className` and `methodId`;
- `SeeStackDepthException` — no `location` on the node or message; the throwable's `location` carries it.

## Run records

A run record (R-080) holds one run's own state. It has no `name`, `label`, `suiteId`, `location` or `threadName`, and holds none of the run's suites, which sit under the run's test run label (R-078). Its keys, in order: `kind`, `testCount`, `configMap`, `discovery`, `outcome`, `reason`, `message`, `throwable`, `startedAt`, `endedAt`, `duration`, `summary`, `contents`, `output`. It also holds the run's identifier (§ Not yet fixed).

- `kind` — `run`.
- `testCount` — `RunStarting`'s expected test count.
- `configMap` — `RunStarting`'s config map, as an object of each value's `toString`.
- `discovery` — `{ "startedAt", "duration" }`, from `DiscoveryStarting`'s `timeStamp` and `DiscoveryCompleted`'s `duration`; absent unless the run reported them.
- `outcome` — `aborted` or `stopped`; absent where the run completed.
- `reason` — `processFailed`, only on a run aborted with reason *process failed* (R-102).
- `message`, `throwable` — those of the run's `RunAborted`; absent on a run aborted without one.
- `startedAt` — `RunStarting`'s.
- `endedAt`, `duration` — those of the run's closing event: `RunCompleted`, `RunStopped` or `RunAborted`.
- `summary` — the closing event's summary, with ScalaTest's own counts: `testsSucceededCount`, `testsFailedCount`, `testsIgnoredCount`, `testsPendingCount`, `testsCanceledCount`, `suitesCompletedCount`, `suitesAbortedCount`, `scopesPendingCount`.
- `contents` — always present, `[]` when empty: the messages attached to the run rather than to a suite (R-097), in the order they were emitted.
- `output` — the output captured against the run (R-012, R-042, R-122), in the form a node's takes.

A key whose supplying event the run never reported is absent, as on a run given no task (R-071) or a run that does not finish.

## Not yet fixed

`SPECIFICATION.md` states what the following hold; this document does not yet name their keys or fix their form:

- the consolidation root (R-078, R-081): its ScalaTest version, module selectors with the test modules each resolved to, the build tool's name, version and support status, and its test run labels;
- a test run label (R-025, R-058, R-078, R-080, R-083, R-095, R-114): its name, its outcome, reason (*not run* or *could not start*) and failure where its module's tests did not run, its run records, and its top-level suites and not-run test classes;
- a process that ended before any run existed, and its output (R-076);
- a run record's identifier, and the key by which a suite names its run record (R-080);
- whether a not-run test class's `name` is its simple or its fully qualified class name (R-095);
- within `output`, the form of the value under `stdout` and `stderr`, and where output whose stream is not known is held (R-012, R-122).
