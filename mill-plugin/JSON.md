# Consolidated JSON output

The consolidated JSON output is a UTF-8 encoded file containing a single RFC 8259 JSON object. The JSON schema is in [consolidated.schema.json](./consolidated.schema.json).

The file holds the consolidated JSON output of one consolidation, such as the file a test command writes for `-fJ <path>`. Its one object is the [Root](#root). A key is present unless its entry says when it is absent. An absent value is left out, never written as `null`. Epoch milliseconds are an integer count of milliseconds since the Unix epoch; a duration is an integer count of milliseconds.

## Root

The consolidation as a whole, holding its modules.

- `kind` — string, always `root`.
- `scalaTestVersion` — string: the version of the release of this fork that wrote the output, such as `3.2.20`. The format may change between releases, so read the output against that release's documentation.
- `buildTool` — [Build tool](#build-tool) object: the build tool that started the runs. Absent where the consolidation library's caller gives no build tool.
- `moduleSelectors` — array of [Module selector](#module-selector) objects, one for each module selector the test command was given. Empty where none was given.
- `outcome` — string, always `aborted`. Present exactly when `failures` is. No outcome is rolled up from the modules.
- `failures` — array of one or more [Failure](#failure) objects: the failures attached to the root, which belong to no run, such as a worker failing to start. Present exactly when `outcome` is; absent where no failure is attached.
- `startedAt` — integer, epoch milliseconds: the root's start time, which the consolidation library's caller gives. Absent where the caller gives none.
- `endedAt` — integer, epoch milliseconds: the root's end time, which the consolidation library's caller gives. Absent where the caller gives none.
- `duration` — integer, milliseconds: `endedAt` less `startedAt`. Present exactly when both `startedAt` and `endedAt` are.
- `contents` — array of [Module](#module) objects: the root's modules. Empty where there are none.
- `output` — array of one or more [Captured output](#captured-output) items: the output captured against the root rather than against any module or run, in the order it was written. Absent where there is none.

## Build tool

The build tool that started the runs, and its running version.

- `name` — string: the build tool's name, `Mill` under the test command.
- `version` — string: the running version of the build tool, such as `1.3.1`.

## Module selector

One module selector as the build tool was given it, with the test modules it resolved to.

- `selector` — string: the module selector as given, such as `__.test`.
- `modules` — array of strings, possibly empty: the module names of the test modules the module selector resolved to.

## Failure

A failure attached to the root: one that belongs to no run, such as laying out a fork group or starting a worker failing.

- `message` — string: the failure's text, such as `Starting worker 0 of fork group 1 of core.test failed: …`.

## Module

A build tool's unit of build whose tests produced the runs it holds.

- `kind` — string, always `module`.
- `name` — string: the module name, such as `core.test` for a Mill module.
- `startedAt` — integer, epoch milliseconds: the module's start time, which the consolidation library's caller gives. Absent where the caller gives none.
- `endedAt` — integer, epoch milliseconds: the module's end time, which the consolidation library's caller gives. Absent where the caller gives none.
- `duration` — integer, milliseconds: `endedAt` less `startedAt`. Present exactly when both `startedAt` and `endedAt` are.
- `contents` — array of [Run node](#run-node) objects, one for each of the module's runs. Empty where it has none.

## Run node

One run's own state, including how the run ended, and its top-level suites. Besides the absences each entry states, a key is absent where the run never reported the event that supplies it.

- `kind` — string, always `run`.
- `number` — integer, at least 1: the run's number, unique within its module and counted from 1. Runs given a worker are numbered by fork group, then by the worker's index within its fork group.
- `forkGroup` — integer, at least 0: the 0-based position of the run's fork group among the fork groups the module's `testForkGrouping` gives. Absent where the consolidation library's caller gives the run no fork group.
- `testCount` — integer: the expected test count of the run's `RunStarting`. Absent where that count is 0, as it is under Mill, or where the run reported no `RunStarting`.
- `configMap` — [Config map](#config-map) object: the config map of the run's `RunStarting`. Absent where the run reported no `RunStarting`.
- `discovery` — [Discovery](#discovery) object: the run's discovery of its suites. Absent unless the run reported both `DiscoveryStarting` and `DiscoveryCompleted`.
- `outcome` — string, `aborted` or `stopped`: the run's outcome where it aborted or stopped. Absent where the run completed, or where its event stream does not record how it ended.
- `message` — string: the message of the run's `RunAborted`. Absent where the run reported no `RunAborted`.
- `throwable` — [Throwable](#throwable) record: the throwable of the run's `RunAborted`. Absent where the run reported no `RunAborted`, or where that event carries none. Present only where `message` is.
- `startedAt` — integer, epoch milliseconds: the time of the run's `RunStarting`.
- `endedAt` — integer, epoch milliseconds: the time of the run's `RunCompleted`, `RunStopped` or `RunAborted`. Absent on a run that does not finish.
- `duration` — integer, milliseconds: the duration that the run's `RunCompleted`, `RunStopped` or `RunAborted` carries. Absent where that event carries none. Present only where `endedAt` is.
- `summary` — [Summary](#summary) object: the summary that the run's `RunCompleted`, `RunStopped` or `RunAborted` carries. Absent where that event carries none. Present only where `endedAt` is.
- `contents` — array of [Test node](#test-node) objects: the run's top-level suites. Empty where it has none.
- `output` — array of one or more items, each a [Message](#message) or a [Captured output](#captured-output) item, told apart by `kind`: the messages sent outside any suite and the output captured against the run, in one sequence in the order they were received. Absent where there is none.

## Config map

A run's config map: one key for each of the config map's entries, its value a string, the entry's value's `toString`. Its keys are the run's own, not the format's.

## Discovery

A run's discovery of its suites, from its `DiscoveryStarting` and `DiscoveryCompleted`.

- `startedAt` — integer, epoch milliseconds: the time of the run's `DiscoveryStarting`.
- `duration` — integer, milliseconds: the duration that the run's `DiscoveryCompleted` carries. Absent where it carries none.

## Summary

ScalaTest's own counts for a run, as its closing event's summary gives them. Every key is an integer.

- `testsSucceededCount` — the number of tests that succeeded.
- `testsFailedCount` — the number of tests that failed.
- `testsIgnoredCount` — the number of tests that were ignored.
- `testsPendingCount` — the number of tests that were pending.
- `testsCanceledCount` — the number of tests that were canceled.
- `suitesCompletedCount` — the number of suites that completed.
- `suitesAbortedCount` — the number of suites that aborted.
- `scopesPendingCount` — the number of scopes that were pending.

## Test node

A suite, scope or test: one execution of it, so a node that ran more than once is one node for each time it ran. No node's outcome is rolled up from its children.

- `kind` — string: `suite`, `scope` or `test`.
- `name` — string: the node's name, a suite's suite name, or a scope's or test's own text without that of the scopes around it. It is never ScalaTest's joined test name.
- `label` — string: a scope's or test's label, such as `when b` or `- should c`, without its indentation. Present on every scope and test; absent on a suite.
- `suiteId` — string: a suite's suite ID. Present on every suite; absent on a scope or test.
- `location` — [Location](#location) object: for a suite, its class name and the source file its own scopes and tests share; for a scope or test, the location its opening or closing event gives. Present on every suite; absent on a scope or test whose events give none.
- `outcome` — string, in ScalaTest's spelling: `succeeded`, `failed`, `canceled`, `ignored`, `pending` or `aborted`. A test's may be any of these, a scope's `pending` or `aborted`, and a suite's `aborted`. Absent where the node has none, as on a suite that completed or a node its run left open.
- `reason` — string, always `notRun`: why the node aborted, on a node that never started, where no event reports it or any node below it. Present only where `outcome` is `aborted`; absent otherwise.
- `message` — string: the message of the `TestFailed`, `TestCanceled` or `SuiteAborted` that closed the node. Absent where no such event closed it.
- `throwable` — [Throwable](#throwable) record: the throwable of the `TestFailed`, `TestCanceled` or `SuiteAborted` that closed the node. Absent where no such event closed it, or where that event carries none. Present only where `message` is.
- `startedAt` — integer, epoch milliseconds: the time of the node's `SuiteStarting`, `ScopeOpened` or `TestStarting`. Absent on a node that never started.
- `endedAt` — integer, epoch milliseconds: the time of the event that closed the node or, for an aborted node with no closing event of its own, of the `SuiteAborted` or `RunAborted` that aborted it. Absent where there is none of these.
- `duration` — integer, milliseconds: for a suite or test, the duration its closing event carries; for a scope, or a node with no closing event of its own, `endedAt` less `startedAt`. Absent where neither is known.
- `threadName` — string: the thread of the event that opened the node, else of the event that closed it. Never present on a scope; absent on a suite or test with neither event.
- `contents` — array of [Test node](#test-node) objects: the node's children, a suite's nested suites, scopes and tests, a scope's scopes and tests, or the suites a test ran. Empty where it has none.
- `output` — array of one or more [Message](#message) objects: the node's messages, in the order they were sent. Absent where there are none.

## Message

One `info`, `alert`, `note` or `markup` call's `InfoProvided`, `AlertProvided`, `NoteProvided` or `MarkupProvided`, as an item of an `output`.

- `kind` — string: `info`, `alert`, `note` or `markup`.
- `message` — string: the message's text; for a `markup`, the `MarkupProvided`'s `text`.
- `at` — integer, epoch milliseconds: the time the message was sent.
- `location` — [Location](#location) object: the location the message carries. Absent where it carries none.
- `throwable` — [Throwable](#throwable) record: the throwable the message carries. Absent where it carries none, and always on a `markup`.
- `threadName` — string: the thread that sent the message. Present only where it differs from its node's `threadName`, or the node has none, as on a run node.

## Captured output

One line captured from a process's stdout or stderr, as an item of an `output`. Joined in order, the `text` of one stream's items in an `output` is what was captured from that stream.

- `kind` — string, always `stdio`.
- `stream` — string: the stream the line was captured from, `stdout` or `stderr`.
- `text` — string: the line as the process wrote it, with its line terminator where it has one.

## Throwable

A throwable record: a throwable with its cause and suppressed throwables, each recorded in full the first time it appears within the record. `location` and `stackTrace` are never both present.

- `id` — integer, at least 1: the throwable's number, from 1, unique within one record. A `ref` within the same record names it.
- `className` — string: the throwable's fully qualified class name.
- `message` — string: the throwable's `getMessage`. Absent where it is `null`.
- `location` — [Failed code location](#failed-code-location) object: for a throwable mixing in `org.scalatest.exceptions.StackDepth`, such as `TestFailedException`, the failed code's file and line, each where known. Absent for any other throwable, and where neither is known.
- `stackTrace` — array of [Stack frame](#stack-frame) objects, innermost call first: for a throwable not mixing in `StackDepth`, every frame of its stack trace. Absent for a throwable mixing in `StackDepth`.
- `cause` — a throwable record or a throwable reference: the throwable's cause, recorded the same way, or a reference where it already appears in the record. Absent where there is none.
- `suppressed` — array of one or more items, each a throwable record or a throwable reference: the throwable's suppressed throwables, each recorded the same way, or a reference where it already appears in the record. Absent where there are none.

A throwable reference, `{ "ref": <id> }`, stands for a throwable already recorded within the same record, and ends any cycle. Only the same object counts, never an equal one.

- `ref` — integer, at least 1: the `id` of the throwable's record.

## Stack frame

One frame of a throwable's stack trace.

- `className` — string: the fully qualified name of the class holding the frame's method.
- `methodName` — string: the name of the frame's method.
- `fileName` — string: the name of the frame's source file. Absent where it is unknown.
- `lineNumber` — integer, at least 0: the frame's line number. Absent where it is unknown, which Java gives as a negative number.
- `isNative` — boolean, always `true`: present only where the frame's method is native.

## Location

Where a node or message is, folded from ScalaTest's `Location`: `TopOfClass` gives `className`, `TopOfMethod` gives `className` and `methodId`, and `LineInFile` gives `filePath` or `fileName` and `lineNumber`. It holds at least one key, and each key is present only where known.

- `className` — string: the fully qualified name of the class.
- `methodId` — string: the method's ID, as `TopOfMethod` gives it, such as `def adds(): Unit`. Present only where `className` is.
- `filePath` — string: the source file's path as recorded, usually absolute. Written where ScalaTest has the path. Never present with `fileName`.
- `fileName` — string: the source file's name, written only where ScalaTest has no path, as where the code was compiled without `SCALACTIC_FILL_FILE_PATHNAMES` set to `yes`. Never present with `filePath`.
- `lineNumber` — integer: the line number in the source file.

## Failed code location

The failed code's file and line, as a throwable mixing in `org.scalatest.exceptions.StackDepth` gives them. It holds at least one key, and each key is present only where known.

- `filePath` — string: the source file's path as recorded, usually absolute. Written where ScalaTest has the path. Never present with `fileName`.
- `fileName` — string: the source file's name, written only where ScalaTest has no path, as where the code was compiled without `SCALACTIC_FILL_FILE_PATHNAMES` set to `yes`. Never present with `filePath`.
- `lineNumber` — integer: the line number of the failed code.
