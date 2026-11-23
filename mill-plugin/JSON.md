# The JSON output

`mill scalatest/test <module selector> -fJ <path>` writes the JSON output to the file at `<path>` ([README.md](README.md) § Running the test command). This document gives the output's format.

The format carries no compatibility promise across releases of this fork: any release may change it. It has no format version. The root's `scalaTestVersion` names the release that wrote it; read the output against that release's copy of this document.

## Encoding

- The file holds one JSON object, the root, as RFC 8259 defines it, encoded in UTF-8.
- A key is always present unless this document states when it is absent.
- A key whose value is absent is left out. `null` is never written. An empty string is written `""`.
- Keys are written in the order this document lists them.
- `startedAt`, `endedAt` and a message's `at` are in epoch milliseconds. `duration` is in milliseconds.
- Strings escape `"`, `\` and control characters. An unpaired UTF-16 surrogate, which UTF-8 cannot encode, is written as a `\uXXXX` escape.

## Tree

- The root holds one module.
- The module holds one run node for each of its workers (README.md § What it supports), including a worker given no test class, such as the one worker of a module with no test class where no class glob is given. Each worker writes its run's events to a file of its own, its test event log, which the test command reads.
- The module holds no run node for a worker that wrote no test event log, as where its JVM failed to start or exited before its run began. That worker's captured output is not written, and the test command fails. The root has a failure for that worker only in the cases § Root lists.
- The module's run nodes appear in ascending order of `number`, compared as numbers, whatever order the runs started and ended in.
- The run node holds the run's top-level suites. A suite holds its nested suites, scopes and tests. A scope holds its scopes and tests. A test holds the suites it ran.
- Every node states its `kind`.
- A suite, scope or test that ran more than once is one node for each time it ran.

Children of a suite, scope or test appear in this order:

1. Children with a declared place, in declared order.
   - A scope's or test's declared place is its index in the order its parent registered its scopes and tests, counting only those the run's filter keeps. It is numbered the same way each time the suite declares its tests. It is not a source line.
   - A nested suite's declared place is its index in its parent's `nestedSuites`. A suite's nested suites come before all its scopes and tests, or after them where the suite mixes in `TestsBeforeNestedSuites`.
   - Where a suite's declaration changes during the run, as when a suite mixing in `OneInstancePerTest` registers different tests in a later instance, a scope or test its latest declaration drops keeps the place it last had, and appears after the scope or test the latest declaration holds there. Several dropped at one place appear in the order they were first declared.
   - A suite with no declared place that shares a `suiteId` with a sibling that has one appears directly after the last such sibling. Several such suites appear in the order the run first reported them.
2. The remaining children, which are suites, in ascending order by name. Suites that share a name appear in the order they started, followed by those that never started, in the order the run first reported them.

A child that ran more than once while its parent ran once has its nodes in the order they started. The nodes of a child with a declared place are together. The nodes of a remaining suite are ordered as point 2 gives, so a suite of the same name that started between them separates them.

A run node's top-level suites appear in ascending order by name; suites that share a name appear in the order they started, followed by those that never started. Names are compared as Java's `String.compareTo` compares them.

No node's outcome is rolled up from its children. Roll up outcomes yourself.

## Root

Keys, in order:

- `kind` — `root`.
- `scalaTestVersion` — the release of this fork that wrote the output, such as `3.2.20`.
- `buildTool` — `{ "name", "version" }`: `Mill` and the version of Mill that ran the test command.
- `moduleSelectors` — the module selector as given, as `[{ "selector", "modules" }]`, where `modules` is the names of the test modules it resolved to.
- `outcome` — `aborted`, present only where `failures` is.
- `failures` — `[{ "message" }]`: the failures that are not a run's. Absent where there is none. Either:
  - failures from running the module's workers, in this order:
    1. for each fork group in order, laying out the group's files in its folder failing, naming the fork group and the module, as in `Laying out fork group 1 of core.test failed: …`. None of that group's workers starts;
    2. for each worker in the order it started, naming the worker, its fork group and the module:
       1. starting or running it failing, as in `Starting worker 0 of fork group 1 of core.test failed: …`;
       2. capturing its output failing, so its output went to Mill's console, as in `Capturing the output of worker 0 of fork group 1 of core.test failed: …`;
       3. reading its test event log failing, including where a line of it cannot be decoded (§ Run node), as in `Reading the event log of worker 0 of fork group 1 of core.test failed: …`;
    3. where class globs are given, the failure Mill's `testOnly` fails with, worded as in the later item where class globs are given and none of them matches a test class of the module. It is added only where:
       - the root holds no failure of laying out a fork group (item 1) or of starting or running a worker (item 2.1);
       - once every worker has ended, no run has reported a test as succeeded, failed, ignored, pending or cancelled, or a suite as aborted. Neither of these counts as such a report:
         - a test shown as ignored only because its suite's structure marks it ignored;
         - a suite or test shown as aborted only because it has no end event, such as one with reason *not run*;
  - or one failure laying out the module's workers. No worker starts, and the module has no run node and no `startedAt`, `endedAt` or `duration`. Either:
    - class globs are given and none of them matches a test class of the module, and the failure is the one Mill's `testOnly` fails with. Its first line is `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, and its second is `Run discoveredTestClasses to see available tests`;
    - two of the module's fork groups would share a `processFolder`, and the failure names the folder and the groups, as in `Laying out the workers of core.test failed: fork groups 0 and 2 share the processFolder samples.A1`;
    - or checking whether the filesystem ignores case failed, and the failure gives what the check threw, as in `Laying out the workers of core.test failed: checking whether the filesystem ignores case failed: …`. The check runs only where two or more fork groups hold a test class to run.
- `startedAt`, `endedAt`, `duration` — measured by the test command, from just before it lays out the module's workers until every run has ended. For a module that does not set `testBatchFrameworkTasks`, laying out its workers includes discovering its tests.
- `contents` — an array holding the module.
- `output` — output captured from a process that is not a run (§ Captured output). The test command captures none, so this key is absent.

## Module

Keys, in order:

- `kind` — `module`.
- `name` — the module's Mill path, such as `core.test`.
- `startedAt`, `endedAt`, `duration` — measured by the test command, from just before the module's first worker starts until its last worker has ended and that worker's test event log is read.
- `contents` — an array holding the module's run nodes, or `[]` where it has none (§ Tree).

## Run node

Keys, in order:

- `kind` — `run`.
- `number` — the run's number within its module, from 1, in ascending order of `forkGroup`, then of the worker's index within its fork group: the `<i>` of the worker's folder `worker-<i>`, or 0 where the fork group has one worker.
- `forkGroup` — the 0-based position of the run's fork group in the module's `testForkGrouping`, counting groups that have no worker because they hold no test class or, where class globs are given, none that a class glob matches. A module that does not set `testForkGrouping` has its runs in fork group `0`. Where no class glob is given, a module that holds no test class has one run, in fork group `0`. A fork group's runs are:
  - for a module that does not set `testBatchFrameworkTasks`, one for each worker Mill starts for the group (README.md § What it supports);
  - for a module that sets `testBatchFrameworkTasks`, at most one, whatever the module's `testParallelism`.
- `testCount` — the expected test count of the run's `RunStarting`. Absent where that count is 0, as it is under Mill.
- `configMap` — the run's config map, as an object of each value's `toString`, with keys in ascending order.
- `discovery` — `{ "startedAt", "duration" }`, from `DiscoveryStarting`'s time and `DiscoveryCompleted`'s duration. Absent unless the run reported both events. `duration` is absent where `DiscoveryCompleted` carries none.
- `outcome` — `aborted` or `stopped`, from the run's end as its test event log records it. Absent where the run completed, or where the log does not record the run's end.
- `message`, `throwable` — the `message` and `throwable` (§ Throwables) of the run's `RunAborted`. Absent where the run did not abort. `throwable` is also absent where `RunAborted` carries none.
- `startedAt` — `RunStarting`'s time.
- `endedAt`, `duration` — those of the run's `RunCompleted`, `RunStopped` or `RunAborted`. `duration` is absent where that event carries none.
- `summary` — that event's summary: `testsSucceededCount`, `testsFailedCount`, `testsIgnoredCount`, `testsPendingCount`, `testsCanceledCount`, `suitesCompletedCount`, `suitesAbortedCount`, `scopesPendingCount`. Absent where that event carries none.
- `contents` — the run's top-level suites (§ Tree), `[]` when empty.
- `output` — the messages sent outside any suite (§ Messages) and the output captured from the run's process (§ Captured output), in one sequence, in the order the test command received them. Lines captured before the run's test event log appeared come first. Absent where there is none.

A key is absent where the run never reported the event that supplies it.

A run's events can stop before the run's end is recorded:

- the worker's JVM dies; or
- the test command reaches a line of the run's test event log that it cannot decode. It reads no further, and the root has a failure for that worker naming the line and why it cannot be decoded (§ Root).

Either way, the run node has no `outcome`, though the run did not complete, and the suites, scopes and tests the run left open have no `outcome` either. The test command fails. Such a run node has no `endedAt` unless the log recorded the run's `RunCompleted`, `RunStopped` or `RunAborted`; every run node without `endedAt` is such a run.

## Suite, scope and test

Keys, in order:

- `kind` — `suite`, `scope` or `test`.
- `name` — the node's own name, never a joined name. In `"A" when { "b" should { "c" in { … } } }`, the names are `A`, `b` and `c`. A suite's name is its suite name. In a suite mixing in a ScalaTest style trait (one in `org.scalatest.funsuite`, `flatspec`, `funspec`, `wordspec`, `freespec`, `propspec`, `featurespec` or `refspec`, async and fixture variants included), a scope's or test's name is the name it was registered with. In any other suite, a test's name is its entry in the suite's `testNames`.
- `label` — scopes and tests only: the line the human-readable output shows for the node, without indentation. In the example above, `A`, `when b` and `- should c`. In a suite mixing in no style trait, a test's label is `- ` followed by its name.
- `suiteId` — suites only.
- `location` — § Locations. A scope's comes from its `ScopeOpened`. A test's comes from its `TestStarting`, else from the event that closed it. A scope's or test's is absent where none of those events gives a location other than `SeeStackDepthException`. A suite's holds its class name in `className`, plus `filePath` or `fileName` where every location of its own scopes and tests names the same file; it never holds a `lineNumber`.
- `outcome` — a test's is `succeeded`, `failed`, `canceled`, `ignored`, `pending` or `aborted`; a scope's `pending` or `aborted`; a suite's `aborted`. Absent where the node has none, as on a suite that completed, or on a node left open by a run whose end its test event log does not record (§ Run node).
- `reason` — `notRun`, on a node that never started, other than an ignored test: no event reports it or any node below it. Examples are a suite scheduled but never started, and a scope or test its suite or run never reached. Its `outcome` is `aborted`.
- `message`, `throwable` — the `message` and `throwable` (§ Throwables) of the `TestFailed`, `TestCanceled` or `SuiteAborted` that closed the node. Absent where no such event closed the node, as on an aborted node with no closing event of its own. `throwable` is also absent where that event carries none.
- `startedAt` — the time of the `SuiteStarting`, `ScopeOpened` or `TestStarting`. Absent where the node has none, as on a node that never started or an ignored test.
- `endedAt` — the time of the event that closed the node. For an aborted node without such an event of its own, the time of the `SuiteAborted` of the nearest suite above it that aborted, else of the run's `RunAborted`. Absent where there is none of these, as on a node with reason `notRun`, or on a node left open by a run that stopped or whose end its test event log does not record.
- `duration` — a suite's or test's comes from the event that closed it, and is absent where that event carries none, as on an ignored test. A scope's, and that of a node without such an event of its own, is `endedAt` less `startedAt`, absent unless both are present.
- `threadName` — suites and tests only: the thread of the event that opened the node, else of the event that closed it. Absent where the node has neither.
- `contents` — the node's children (§ Tree), `[]` when empty.
- `output` — the node's messages (§ Messages), in the order they were sent. Absent where there are none.

## Messages

A message is an item of an `output`, from an `info`, `alert`, `note` or `markup` call. Keys, in order:

- `kind` — `info`, `alert`, `note` or `markup`.
- `message` — the text.
- `at` — the time it was sent.
- `location` — § Locations. Absent where the message carries none.
- `throwable` — § Throwables. Absent where the message carries none, and always on a `markup`.
- `threadName` — the thread that sent it. Present only where it differs from its node's `threadName`, or the node has none, as on a run node.

## Captured output

A piece of captured output is an item of an `output`: a line captured from a process's stdout or stderr. Keys, in order:

- `kind` — `stdio`.
- `stream` — `stdout` or `stderr`.
- `text` — the line as written, with its line terminator if it has one.

Joined in order, the `text` of one stream's items in an `output` is what was captured from that stream. Where starting or running the run's worker failed (§ Root), its output can lack an unterminated last line of each stream, and anything its process wrote after the module's last worker ended. Where capturing that worker's output failed (§ Root), its output holds none of the worker's stdout or stderr. No `output` holds Mill's list of failed tests or ScalaTest's end-of-run summary.

## Throwables

A throwable is one object. Keys, in order:

- `id` — an integer, numbered depth-first from 1 within one `throwable`, a cause before suppressed throwables.
- `className`.
- `message` — `getMessage`. Absent where it is `null`.
- `location` — for a throwable mixing in `org.scalatest.exceptions.StackDepth`, such as `TestFailedException` or `TestCanceledException`: the failed code's file and line (§ Locations), each where known. Absent for any other throwable, and where neither is known.
- `stackTrace` — for a throwable not mixing in `StackDepth`: every frame, as `{ "className", "methodName", "fileName", "lineNumber", "isNative" }`. `fileName` is absent where unknown, `lineNumber` where negative, and `isNative` unless it is `true`. Absent for a throwable mixing in `StackDepth`.
- `cause` — the cause, written the same way. Absent where there is none.
- `suppressed` — the suppressed throwables, in order, each written the same way. Absent where there are none.

A throwable already written within the same `throwable` is written `{ "ref": <id> }`, which ends any cycle. "Already written" means the same object, not an equal one.

## Locations

A location is one object. Keys, in order, each present only where known:

- `className`.
- `methodId`.
- `filePath` or `fileName` — never both. `filePath` is the source file's path as recorded, usually absolute. `fileName` is the file's name, written only where the path is not recorded, as where the code was compiled without the environment variable `SCALACTIC_FILL_FILE_PATHNAMES` set to `yes`.
- `lineNumber`.

ScalaTest's locations become:

- `LineInFile` — `filePath` or `fileName`, and `lineNumber`.
- `TopOfClass` — `className`.
- `TopOfMethod` — `className` and `methodId`.
- `SeeStackDepthException` — no `location`. The node's or message's `throwable` carries it.
