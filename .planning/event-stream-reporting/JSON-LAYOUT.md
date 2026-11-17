# JSON output layout

## Summary

This document fixes the layout of the consolidated JSON output that `SPECIFICATION.md` R-028 requires: how the object is encoded, the tree it holds, and every key of a suite, scope, test, message, throwable, location and run node, with what each means and when it is absent. Third-party readers build against it under the compatibility promise of § Compatibility. § Not yet fixed lists the parts of the object whose keys this document does not yet name, and the statements of this document that the first JSON feature settles against `SPECIFICATION.md`.

Terms are defined in `.docs/ubiquitous-language.md`. R-numbers are those of `SPECIFICATION.md`.

## Compatibility

The JSON output's format has no version of its own beside the ScalaTest version of this fork that its root records (R-084). Once the epic is complete, changes to the format follow ScalaTest's semantic versioning: a patch release never changes the format, a minor release only adds to it, and only a major release removes or changes anything. Until the epic is complete, any feature may change the format.

## Encoding

- The JSON output is one object: the root (R-096a).
- The object is JSON text as RFC 8259 defines it (https://www.rfc-editor.org/rfc/rfc8259), and the JSON file is encoded in UTF-8.
- An absent value omits its key; `null` is never written. An empty string is written `""`.
- Keys are written in the order this document lists them.
- `startedAt`, `endedAt` and a message's `at` are the supplying event's `timeStamp`, in epoch milliseconds. `duration` is in milliseconds.
- Strings escape `"`, `\` and control characters. An unpaired UTF-16 surrogate, which a Java string can contain and UTF-8 cannot encode, is written as a `\uXXXX` escape (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2).

## Tree

- The root is the consolidated test command (R-096a). The root's children are its modules, in ascending lexicographic order by name.
- A run in a forked JVM is a run node (§ Run nodes), which is a child of the run's module (R-096a). The run node holds the run's own state and contains the run's top-level suites.
- Any other run has no node and is written as part of its module (R-135). The top-level suites of a run that has no node are children of the run's module. The module records the state that a run node would record for that run (§ Run nodes).
- A suite scheduled to run that has no events is written once, as a child of its module and inside no run node, aborted with reason *not run* (R-095a; § Nodes).
- A module's children are its run nodes, the top-level suites of a run that has no node, and the suites scheduled to run that have no events. The suites among a module's children appear in ascending lexicographic order by name, as do the top-level suites in a run node.
- No module selector is a node.
- The JSON output writes every node of the node tree in full (R-028), except what the filtering characters on its `-f` argument omit (R-127c, R-127d).
- A suite, scope or test that ran more than once is one node for each time it ran (R-130e).
- No run node, suite, scope or test of one run contains an event or captured output of another run.
- A process started to execute tests that ended before any run existed in it is recorded on its module, with the output captured from the process's stdout and stderr (R-076). That output is written nowhere else, and on no run node.

## Nodes

This section gives the layout of a suite and of every suite, scope and test below it. § Run nodes gives a run node's.

Every event a run reports contributes its data to one suite, scope or test, or to the run's node, or to the run's module where the run has no node (§ Tree), each message as its own item (§ Messages). A node records its final state, taking each value from the event that supplies it; the JSON output records nothing per event. The JSON output writes into no node an event's ordinal, formatter, rerunner or payload, or an event's copy of its suite's name, ID or class name. The `SuiteStarting` and `SuiteCompleted` of a `DiscoverySuite` or a `DistributedTestRunnerSuite` contribute to no node. A `DistributedTestRunnerSuite`'s `SuiteAborted` contributes to the one test that suite ran, which is then aborted.

A suite's, scope's or test's keys, in order: `kind`, `name`, `label`, `suiteId`, `location`, `outcome`, `reason`, `message`, `throwable`, `startedAt`, `endedAt`, `duration`, `threadName`, `contents`, `output`.

- `kind` — `suite`, `scope` or `test`. Always present.
- `name` — the node's name, never a joined name. In `"A" when { "b" should { "c" in { … } } }`, scope `A` has name `A`, scope `b` has name `b` and test `c` has name `c`; ScalaTest's test name `A when b should c` is not stored. A suite's name is its suite name. In a suite mixing in a ScalaTest style trait (those of `org.scalatest.funsuite`, `flatspec`, `funspec`, `wordspec`, `freespec`, `propspec`, `featurespec` and `refspec`, async and fixture variants included), a scope's or test's name is the name it was registered with. Any other suite has no scopes, and a test's name in it is the test's entry in the suite's `testNames`, whatever `testText` the suite's own `runTest` reports.
- `label` — scopes and tests only: the node's label, without leading indentation, since the tree gives the nesting. In the example above, `A` has label `A`, `b` has `when b` and `c` has `- should c`; an `AnyFunSpec` test `t1` has `- t1`. A test that never started or never closed still has one. In a suite mixing in no style trait, a test's label is the one ScalaTest 3.2.20 gives that test's `TestIgnored`, `- ` followed by its name.
- `suiteId` — suites only, always present.
- `location` — § Locations. A scope's comes from its `ScopeOpened`. A test's comes from its `TestStarting`, else its closing event, skipping `SeeStackDepthException`. A suite's is its class name in `className`, plus the file its scopes' and tests' registrations share where they all share one, and never a `lineNumber`.
- `outcome` — in ScalaTest's spelling:
  - test: `succeeded`, `failed`, `canceled`, `ignored`, `pending` or `aborted`;
  - scope: `pending` or `aborted`;
  - suite: `aborted`.

  Absent where the node has none (R-098a, R-098b, R-098c). No node's outcome is rolled up from its children; readers of the output roll up outcomes themselves.
- `reason` — `notRun`, only on a node aborted with reason *not run* (R-095a, R-101).
- `message`, `throwable` — on a failed, canceled or aborted node: the `message` and `throwable` (§ Throwables) of its closing `TestFailed`, `TestCanceled` or `SuiteAborted`. A test that a `DistributedTestRunnerSuite`'s `SuiteAborted` aborted takes them from that event. A node aborted without a closing event of its own has neither: a scope or test open when its suite aborted (R-095b), a node that never started (R-095a), or a node its run left unfinished (R-095b).
- `startedAt` — the opening event's: `SuiteStarting`, `ScopeOpened` or `TestStarting`. Absent on a node that never started.
- `endedAt` — the closing event's. For a node aborted without a closing event of its own, that of the `SuiteAborted` or `RunAborted` that aborted it, and absent where there is none.
- `duration` — for a suite or test, its closing event's, absent where that event carries none, as `TestIgnored` never does. For a scope, and for a node aborted without a closing event of its own, `endedAt - startedAt`, absent unless both are present.
- `threadName` — suites and tests only: the opening event's, else the closing event's.
- `contents` — always present, `[]` when empty: the node's children and messages in one sequence. A node's children appear in declared order where it is known, and in ascending alphanumeric order by label where it is not; children with identical labels, such as the nodes of a suite, scope or test that ran more than once in one parent, appear in the order they started (R-105). Each message appears after every child of its node that the run had reached when the message was emitted, whether that child then ran at once, concurrently or later, and before every other child of that node. A node's messages keep the order in which they were emitted (R-043). A suite's children are the suites it nests or ran, and its scopes and tests; a scope's are its scopes and tests, and a test's the suites it ran. Each child or message states its own `kind`.
- `output` — the output captured against the node (§ Captured output), as `{ "stdout": …, "stderr": … }`. Absent where none was captured against it.

A suite scheduled to run that has no events (R-095a) has `kind` `suite`, `outcome` `aborted`, `reason` `notRun` and `contents` `[]`. For class `p.B`, the suite's `suiteId` and `location.className` are `p.B`. The suite has no times, `threadName`, `message` or `throwable`.

## Messages

A message is an `InfoProvided`, `AlertProvided`, `NoteProvided` or `MarkupProvided` event, emitted on its own or recorded in a test's closing event; which of the two is not kept. A message in `contents` writes `kind`, `message`, `at`, `location`, `throwable`, `threadName`:

- `kind` — `info`, `alert`, `note` or `markup`.
- `message` — the event's `message`; for `MarkupProvided`, its `text`.
- `at` — the event's `timeStamp`.
- `location` — § Locations; absent where the event carries none.
- `throwable` — § Throwables; absent where the event carries none.
- `threadName` — only where it differs from its node's `threadName`, or the node has none.

## Throwables

A throwable record's keys, in order:

- `id` — an integer, numbered depth-first from 1 within one record: one node's or message's `throwable`.
- `className`.
- `message` — `getMessage`, absent when `null`.
- for a `StackDepthException`, `TestFailedException` and `TestCanceledException` among them: `location` (§ Locations), giving the failed code's file and line number, each where defined; and no `stackTrace`.
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

## Captured output

- An `output` contains all the output captured against its node, not only test output, whatever its source and whether or not its stream is known (R-097a).
- Each piece of captured output is written on the narrowest suite, scope or test it was captured against (R-097b). Output captured against no suite, scope or test is written on its run's node, or on the run's module where the run has no node (R-135), or on the module or the root where it was captured against that module or the root (R-072, R-076, R-087).
- Each piece of captured output is written with its stream, stdout or stderr, where that is known.
- Each piece of output a process wrote is written at most once, even where it was captured both against a node and from the process's stdout or stderr.
- No `output` contains Mill's list of failed tests or ScalaTest's end-of-run summary.
- A run node's `output` does not state whether it is test output or worker output.

## Run nodes

A run node records one run's own state, including how the run ended (R-074a), and contains the run's top-level suites. Every run in a forked JVM has a run node (§ Tree), including a run with no suites and a run given no task. A run node has no `name`, `label`, `suiteId`, `location` or `threadName` key. A run node's keys, in order: `kind`, `testCount`, `configMap`, `discovery`, `outcome`, `reason`, `message`, `throwable`, `startedAt`, `endedAt`, `duration`, `summary`, `contents`, `output`. A run node also records the run's identifier, unique within its module (§ Not yet fixed).

No suite names its run. A suite in a run node belongs to that node's run. A top-level suite that has events and is a child of a module belongs to that module's run that has no node.

- `kind` — `run`.
- `testCount` — `RunStarting`'s expected test count.
- `configMap` — `RunStarting`'s config map, as an object of each value's `toString`.
- `discovery` — `{ "startedAt", "duration" }`, from `DiscoveryStarting`'s `timeStamp` and `DiscoveryCompleted`'s `duration`; absent unless the run reported them.
- `outcome` — `aborted` or `stopped`; absent where the run completed (R-098d).
- `reason` — `processFailed`, only on a run aborted with reason *process failed* (R-102).
- `message`, `throwable` — those of the run's `RunAborted`; absent on a run aborted without one.
- `startedAt` — `RunStarting`'s.
- `endedAt`, `duration` — those of the run's closing event: `RunCompleted`, `RunStopped` or `RunAborted`.
- `summary` — the closing event's summary, with ScalaTest's own counts: `testsSucceededCount`, `testsFailedCount`, `testsIgnoredCount`, `testsPendingCount`, `testsCanceledCount`, `suitesCompletedCount`, `suitesAbortedCount`, `scopesPendingCount`.
- `contents` — always present, `[]` when empty: the run's top-level suites and the messages attached to the run rather than to a suite, in one sequence. The suites appear in the order § Tree gives. The messages are placed among the suites, and ordered, as a suite's `contents` places and orders its messages (§ Nodes).
- `output` — the output captured against the run, in the form a suite's takes.

A key is absent where the run never reported the event that supplies it, as when the runner sends no run events or the run does not finish before sending them.

For a run that has no node, the run's module records the state this section gives a run node, and the module's children include the run's top-level suites (§ Tree).

## Not yet fixed

This document states what the following contain; it does not yet name their keys or fix their form:

- the root (R-096a): the ScalaTest version of this fork, which every run shares (R-084); each module selector, with the test modules it resolved to; the build tool's name and version and whether that version is supported, which under the consolidated test command are Mill, the running Mill version and whether it is a supported Mill release (R-093); its outcome and failure where the consolidated test command has an error (R-098f); the failure state and output of a runner that is not intended to produce test output (R-072); the worker output captured against it (R-087); and its modules;
- a module (R-096a): its name; its outcome and failure where the module has an error (R-098e); the output captured against it (R-076, R-087); the state of a run that has no node (R-135); and its children (§ Tree);
- a process that ended before any run existed, and its output (R-076);
- a run node's identifier;
- where a module's run nodes appear among the module's children, and the order of those run nodes;
- whether the `name` of a suite scheduled to run that has no events is its simple or its fully qualified class name (R-095a);
- within `output`, the form of the value under `stdout` and `stderr`, and where the JSON output writes output whose stream is not known.

The first JSON feature settles the following, where this document differs from `SPECIFICATION.md` or goes beyond it (`ROADMAP.md` § 2 Settles):

- whether a node that ran always has a `duration`: R-103 gives every node that ran a duration, falling back to the sum of its children's durations, and § Nodes and § Run nodes leave `duration` absent where no event supplies it;
- a module's outcome reasons, *not run* and *could not start*, under `--halt-on-failure`: R-098e gives a module an outcome only where the module has an error;
- how a module tells the outcome of a run that has no node from the module's own outcome (R-098d, R-098e);
- the semantic-versioning promise of § Compatibility: R-084 states only that the JSON output is versioned against this fork;
- the order of modules and of top-level suites: § Tree orders both by name, and R-105 orders nodes by declaration order where it is known;
- whether a suite that has no events, and that a run was to execute, is written inside that run's node (R-101, R-136): § Tree writes every such suite as a child of its module.
