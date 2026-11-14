# Consolidated human-readable output for one JVM module: the consolidation library

Technical design of how assembly places messages and executions in the test structure, and how the human-readable writer reuses `StringReporter`'s formatting. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Critical code paths

### A message among its node's children

This serves `../specification/layout-messages.md` AC-034 and AC-160.

- For each message assigned to a suite, scope or test, assembly records which of that node's children, when the message arrived, had sent the first event of an execution under the message's own execution (§ A node's executions and its suite's latest structure).
- The human-readable writer places the message from that record.

### A node's executions and its suite's latest structure

This serves `../specification/runs-and-test-structure-executions.md` AC-141 to AC-143, AC-148, AC-149, AC-159 and AC-161, `../specification/runs-and-test-structure-structures.md` AC-144 to AC-147 and AC-150, and `../specification/runs-and-test-structure-nodes.md` AC-163.

- Assembly keeps each node by its ID with its events, grouped by execution number, apart from its suite's structure. A further `structure` event (`test-event-log.md` § The test event log on the JVM § The format) replaces the scopes and tests of its root suite.
  - A suite or scope takes its parent from its node path, and its name from the `structure` holding it. A test takes its parent scope, name and label from the latest `structure` holding it, so a further `structure` can move it without changing its ID.
  - A node the new structure drops keeps the parent, declared position and label it last had, and sorts after any node the latest structure holds at that position. This covers sibling suites sharing a `suiteId` that started out of declared order: the first to start takes the index 0 node, and its further `structure` replaces that node's tests where they differ.
  - A node a `structure` holds and no event names never started, as where the run's process died first.
- Assembly orders a suite's children in three groups:
  - its scopes and tests, by the `position` its latest `structure` gives them;
  - the nested suites its first `structure` gave it, by `position`, before or after the scopes and tests as that structure had them;
  - then each nested suite a later `structure` event added, in the order those events arrived.

  A test's children are the suites it ran, in the order their `structure` events arrived. Under a suite or a test, a suite a later `structure` adds beside siblings sharing its `suiteId` sits directly after the last of them.
- Each event gives its execution path (`test-event-log.md` § The test event log on the JVM § Executions). Assembly reads it against the latest `structure` when the event arrived, which gives the scopes above each test on the path, and puts the node's execution under the execution the path names for the node's parent, the scope, suite or test directly above it.
  - It reads nothing from the order of scope events: under `RandomTestOrder`, a distributor or `AsyncEngine`, a scope can close before its tests start.
  - A node shows each execution that its own events name or that the execution paths of the events below it name.
  - A nested suite's execution sits under the execution of its parent that the paths of its events, and of the events below it, name, so a suite a test runs in each of its executions shows under each.
  - These sit under the parent's latest execution:
    - a nested suite with no execution;
    - a scope or test with no execution;
    - an execution whose path names no execution of the node's latest parent, as where a further `structure` moved a test to another scope after it ran.

### The human-readable writer's reuse of `StringReporter`'s formatting

In ScalaTest 3.2.20 the `org.scalatest.tools.StringReporter` companion builds each line from a live `Throwable` (`getClass.getName`, `isInstanceOf[StackDepth]`, `isInstanceOf[PropertyCheckFailedException]`, `getCause`, `getStackTrace`), and returns whole lines, indented and in one colour. The decoded event stream cannot rebuild a live `Throwable`.

- These line-building functions take a throwable view, `org.scalatest.tools.ThrowableView`, in place of a `Throwable`:
  - `stringsToPrintOnError`, which builds the lines of a failed or canceled test, an aborted suite or run, and an info, alert or note message;
  - `stackTraceLines`, which builds a throwable's stack trace lines and those of its causes;
  - `withPossibleLineNumber`;
  - `Reporter.messageOrThrowablesDetailMessage` (`object Reporter` in `org/scalatest/Reporter.scala`, which `stringsToPrintOnError` calls for a blank message).

  The functions stay in the objects that hold them in 3.2.20, which are `private[scalatest]`. 3.2.20 nests `stackTraceLines` in `stringsToPrintOnError` as `getStackTrace`. The functions that format no throwable keep their parameters: `stringToPrintWhenNoError` for the no-error lines and `stringToPrintWhenMarkup` for a markup message's text.
- The view is `private[scalatest]`. `ThrowableView.of` supplies it for a live `Throwable`, and core's decoded throwable is one. It holds:
  - the class name, and the message where the throwable has one;
  - whether the throwable is a `PropertyCheckFailedException`;
  - each frame's `StackTraceElement.toString` text;
  - the cause;
  - for a `StackDepth`, the failed code's file name and its pathname, each joined to the line number as `StackDepth` joins them, and the failed code's stack depth, which only the live throwable's view fills.
- The functions return lines without indentation:
  - `stringsToPrintOnError` returns an event's first line, built from the formatter's text as its caller passes it, then the lines under it.
  - `stackTraceLines` returns, for each throwable of the cause chain, its class name line, `Cause: ` included for a cause, then its frame lines, `...` included.
  - `stackTraceLines` walks the cause chain in a loop, so a chain of any length is formatted whatever the thread's stack size. 3.2.20 recurses over the chain, and overflows the stack on a chain that returns to itself.
  - `stackTraceLines` stops at a throwable the chain has already shown, since a decoded throwable can be its own cause. Two views of one live `Throwable` are equal for that check.
  - `stringsToPrintOnError` throws a `NullPointerException` for a blank message and a throwable without a message, as it does in 3.2.20.
- `StringReporter`'s fragment functions add the indentation 3.2.20 adds, the formatter's indentation level or else one level, to every line after the first, and colour each line with its event's colour.
  - Its output stays byte-identical, and so does that of `Framework`'s `SbtLogInfoReporter` and the Scala.js and Native `TaskRunner`, which call `fragmentsForEvent` (`../../SPECIFICATION.md` § Scope § Out of scope, R-003).
- The writer passes a label's text without the formatter's leading indentation, indents each line by its depth in the tree (`../specification/layout-tree.md` AC-009 to AC-011), and composes multi-colour lines (`../specification/layout-lines.md` AC-026, AC-027). It adds the suffixes, lines and marks `../../SPECIFICATION.md` R-106 adds to 3.2.20's text.
- The writer calls `stackTraceLines` with `S` and `F` off (`../../SPECIFICATION.md` R-106). The decoded throwable therefore leaves the failed code's stack depth absent: only `StringReporter`'s `S` path reads it, and `s` will never need it (`../../SPECIFICATION.md` R-128).
- A characterisation test, `StringReporterRecordedTextSpec`, proves `StringReporter` byte-identical. It was written and passed before the functions took the view, and passes unchanged after.
  - It compares the fragments, text and colour, that `fragmentsForEvent` and `summaryFragments` return for every event kind they format, formatted and with `U`, against text recorded from the code before the change, in the test resource `StringReporterRecordedText.txt`.
  - For each event it turns `D`, `S`, `F` and `V` all off, each on alone, and all on.
  - For the end-of-run summary, which formats a throwable only in its reminders, it turns:
    - `I`, `T`, `G` and `K` all off, each on alone, and all on;
    - each of `T`, `G` and `K` on with `I`;
    - `D` and `V` on with reminders.
  - Its throwables' frames are fixed with `setStackTrace`, and each `StackDepth` exception's `source.Position` or stack depth is fixed. Two failures carry analysis lines. The throwables include:
    - a non-`StackDepth` throwable of more than ten frames with a non-`StackDepth` cause;
    - a `StackDepth` exception of more than ten frames whose cause is a `StackDepth` exception with a non-`StackDepth` cause;
    - a `PropertyCheckFailedException` with a cause.
  - The recorded text holds a line feed where the run-aborted text holds the platform's line separator, so one recording serves every platform.
  - The existing `StringReporterSuite`, `StringReporterAlertSpec` and `StringReporterSummarySpec` stay. They print no non-`StackDepth` frames, no `Cause:` line and no `PropertyCheckFailedException`, so they cannot prove the change alone.
