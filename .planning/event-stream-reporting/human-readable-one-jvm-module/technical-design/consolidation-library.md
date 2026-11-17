# Consolidated human-readable output for one JVM module: the consolidation library

Technical design of how assembly places messages and executions in the test structure, how the human-readable writer shows the root and a run and ends with the summary line, and how it reuses `StringReporter`'s formatting. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Critical code paths

### A message among its node's children

This serves `../specification/layout-messages.md` AC-034 and AC-160.

- For each message assigned to a suite, scope or test, assembly records which of that node's children, when the message arrived, had sent the first event of an execution under the message's own execution (§ A node's executions and its suite's latest structure).
- The human-readable writer places the message from that record.

### A node's executions and its suite's latest structure

This serves `../specification/runs-and-test-structure-executions.md` AC-141 to AC-143, AC-148, AC-149, AC-159 and AC-161, `../specification/runs-and-test-structure-structures.md` AC-144 to AC-146, AC-150 and AC-177 to AC-179, and `../specification/runs-and-test-structure-nodes.md` AC-065, AC-066, AC-070, AC-163, AC-198 and AC-199.

- Assembly keeps each node by its ID with its events, grouped by execution number, apart from its suite's structure. A further `structure` event (`test-event-log.md` § The test event log on the JVM § The format) gives the scopes and tests of its root suite the parent, declared position and label they have from then on. The suite's scopes and tests are those of all its `structure` events together (`../../SPECIFICATION.md` R-130b).
  - Each node takes its parent, name and label as `../../ARCHITECTURE.md` § Modules § Consolidation library, Assembly, gives them, so a further `structure` can move a test to another scope without changing the test's ID. A test takes its ignored mark as it takes its label, from the latest `structure` that contains the test.
  - A node that a further `structure` drops stays in the tree, whether or not it started. It keeps the parent, declared position and label it last had, and sorts after any node the latest structure places at that position.
  - Sibling suites sharing a `suiteId` can start out of declared order. The first to start takes the index 0 node, and its further `structure` gives that node the tests it runs where they differ from the tests the node was declared with.
  - A node that a `structure` contains and that no event names never started, as where the run's process died first.
- Assembly leaves each attributed node (`../SPECIFICATION.md` § Acceptance criteria) out of the assembled tree, so the writer prints no line for the node, and neither a collapsed subtree's count line (`../specification/layout-lines.md` AC-016) nor the summary line (§ The summary line) counts it.
  - The key is a test's `suiteId`, that of the nearest suite above the test, and its `testName`. Assembly keeps, for each run, the set of keys of the tests that have an execution. The set belongs to one run, so one event log, and a test that ran in another run attributes nothing.
  - Assembly decides attribution once the run's event stream has ended, since any later event can add a key. A test with no execution is attributed where the set holds the test's key. Within one suite node a test name is one node, so the set holds the key of a test with no execution only where a test of another suite node ran.
  - A scope or suite is attributed bottom-up: the scope or suite has not started, has at least one test below it, and every test below it is attributed. A suite or scope that keeps one test not attributed stays whole, and its outcome is *not run*.
- Assembly orders the children shown under one execution of a node by `position` where they have one, and otherwise by name and then by start (`../../SPECIFICATION.md` R-105):
  - A suite's scopes and tests come by the `position` its latest `structure` gives them.
  - The nested suites a suite's first `structure` gave it come by `position`, before or after the scopes and tests as that structure had them.
  - A suite that a later `structure` adds has no `position`. Where it shares a `suiteId` with a sibling that has a `position`, it is a further run of a declared suite, and sits directly after the last sibling sharing that `suiteId`.
  - Every other suite with no `position` comes after those children: under a suite, a suite that a later `structure` adds and that shares no positioned sibling's `suiteId`; under a test, each suite the test ran; under a run, each top-level suite.
    - Assembly sorts the executions of these suites, not the suites: by the suite's name, compared with `String.compareTo`, then by the order in which the executions started. Two executions of one suite are therefore apart where an execution of another suite of the same name started between them.
    - An execution starts when the first event whose execution path names it arrives. Assembly numbers each execution of a run as it starts, so start order is one total order within the run.
    - A suite with no execution sorts after the executions of the suites sharing its name, in the order the event log's `structure` events first contained those suites.
- Each event gives its execution path (`test-event-log.md` § The test event log on the JVM § Executions). Assembly reads it against the latest `structure` when the event arrived, which gives the scopes above each test on the path, and puts the node's execution under the execution the path names for the node's parent, the scope, suite or test directly above it.
  - It reads nothing from the order of scope events: under `RandomTestOrder`, a distributor or `AsyncEngine`, a scope can close before its tests start.
  - A node shows each execution that its own events name or that the execution paths of the events below it name.
  - Each execution of a suite is shown apart, with the nodes that ran in it (`../specification/runs-and-test-structure-executions.md` AC-143, AC-161). A message of the suite's parent counts each execution of the suite as a child of its own (§ A message among its node's children).
  - A nested suite's execution sits under the parent execution that the execution paths of the suite's events, and of the events below the suite, name. A suite that a test runs in each of the test's executions therefore shows under each of them.
  - These sit under the parent's latest execution:
    - a nested suite with no execution;
    - a scope or test with no execution;
    - an execution whose path names no execution of the node's latest parent, as where a further `structure` moved a test to another scope after it ran.

### The root and a run as nodes

This serves `../specification/layout-tree.md` AC-002, `../specification/layout-lines.md` AC-045 and AC-046, and `../specification/runs-and-test-structure-runs.md` AC-035 to AC-038, AC-054, AC-172 and AC-173.

- The writer walks one tree: the root, each module, each run of the module, then the run's top-level suites. The root and a run each print a line only where `../../SPECIFICATION.md` R-082 shows them, which the writer decides at **End**, from everything the library was given.
  - A line's depth is the number of its node's ancestors that print a line. A run that prints no line therefore leaves its suites directly under its module's line, and a root that prints no line leaves each module's line unindented.
  - A run's top-level suites are ordered within their run (§ A node's executions and its suite's latest structure), never merged with those of another run of the module.
  - A run's line is `Run <n>:`. `<n>` is the run's place, from 1, among its module's runs in the order `addRun` was called for them, taken under the consolidation's one lock. A run keeps its number whether or not it prints a line, so a line's text does not depend on which other runs print one. The caller calls `addRun` as it starts each run. This feature's module has one run, so its line is always `Run 1:`.
  - The root's line is the constant `Consolidated tests:`. `Consolidation.start` takes no name for the root, so the library writes the same line for every caller (`../../SPECIFICATION.md` R-113).
  - The writer gives both lines the colour of a module's line.
- A run keeps its messages and the lines captured from its process in one sequence, in the order its `RunFeed` was given them (`../../SPECIFICATION.md` R-043).
  - The caller feeds events from event log reading's thread and captured lines from each stream's reader thread (`test-command.md` § Critical code paths § Capturing a JVM worker's stdout and stderr). Every `RunFeed` operation runs under the consolidation's one lock, so the order in which the feed was given them is one total order.
  - The writer prints that sequence under the run's line after the lines for the run's suites, then the lines for how the run ended.
- The root keeps the captured lines and the failures the caller attaches to it (`test-event-log.md` § The test event log on the JVM, `addRootOutput` and `addRootFailure`), each in the order given. No event stream feeds the root: a runner that is not a run writes no event log (`../../SPECIFICATION.md` R-072).
  - Under the root's line the writer prints the lines for each module, then the root's captured lines, each formatted as a run's captured line is, then the root's failures. The root's children, output and ending take the order a run's take.
  - Where the root has a failure, the writer prints one line `*** CONSOLIDATED TESTS ABORTED ***`, then each failure's message one level under that line, in the order the failures were attached. The line and the messages take the colour of `*** RUN ABORTED ***` and its message lines.

### The summary line

This serves `../specification/layout-lines.md` AC-171, AC-174 to AC-176 and AC-194 to AC-197, and `../specification/test-command.md` AC-076.

- Assembly gives a test with no execution the outcome ignored where the test is marked ignored (§ A node's executions and its suite's latest structure), and *not run* otherwise. A test with an execution takes that execution's outcome from its events alone, so the mark never overrides an event. The ignored outcome is one outcome whichever source gave it: the writer prints the same line for it, and it fails nothing.
  - An ignored test has no execution, so a subtree that contains one still collapses (`../specification/layout-tree.md` § Collapsed subtrees). The subtree's count line counts only the hidden nodes whose outcome is *not run*, so it leaves the ignored test out, as the tests expected count does. A scope or suite that has not started is still *not run* where every test below it is marked ignored.
  - Attribution is decided first: an attributed test is left out of the tree whether or not it is marked ignored.
- At **End** the writer writes the summary line after the tree's last line, unindented, to the sink that takes every other line of the human-readable output. It therefore carries Mill's prefix on stdout, and a test command given a bare `-o`, whose sink discards every line, writes none.
- One function gives the summary's counts, text and colours from the library's runs. It requires each run's event stream to have ended, because it counts over the assembled tree and assembly decides attribution only then (§ A node's executions and its suite's latest structure). Where `../../ROADMAP.md` § 9 redefines the line's format, it changes this one function.
  - The function counts over the assembled tree, the one the writer prints, not by tallying events, so no count can disagree with the lines above the summary line. It visits each suite and test once for each execution assembly gives the node, and once where the node has none, and reads each count from that execution's outcome, or from the node's outcome where it has no execution (`../specification/layout-lines.md` § The summary line). A subtree the writer collapses is still counted.
  - The ignored count is the visits whose outcome is ignored: an execution that closed with `TestIgnored`, or a test with no execution that is marked ignored. The tests expected count is every other visit of a test, so the function never subtracts, and an ignored test of a suite that did not start is counted from the mark alone.
  - The function returns the line as fragments, each a text and its colour, as `StringReporter` returns a line, so a writer with colour off prints the same text. Each part named in `../specification/layout-lines.md` § The summary line is one fragment, in the colour the writer gives a line of that kind, and the text between the parts is cyan, the colour `StringReporter.summaryFragments` gives 3.2.20's `Suites:` and `Tests:` lines. No fragment's colour depends on a count or on the consolidation state.
  - Progress output (`../../ROADMAP.md` § 9) counts while event streams are open, so it cannot call this function as events arrive. To keep the summary line equal to the last progress output (`../../SPECIFICATION.md` R-141), § 9 must:
    - split the counts events give as they arrive from the changes a run's end makes: an attributed suite or test leaves the counts, and a suite still open when the run ends is aborted;
    - show that, once every event stream has ended, its counts equal this function's.

### The human-readable writer's reuse of `StringReporter`'s formatting

In ScalaTest 3.2.20 the `org.scalatest.tools.StringReporter` companion builds each line from a live `Throwable` (`getClass.getName`, `isInstanceOf[StackDepth]`, `isInstanceOf[PropertyCheckFailedException]`, `getCause`, `getStackTrace`), and returns whole lines, indented and in one colour. The decoded event stream cannot rebuild a live `Throwable`.

- These line-building functions take a throwable view, `org.scalatest.tools.ThrowableView`, in place of a `Throwable`:
  - `stringsToPrintOnError`, which builds the lines of a failed or canceled test, an aborted suite or run, and an info, alert or note message;
  - `stackTraceLines`, which builds a throwable's stack trace lines and those of its causes;
  - `withPossibleLineNumber`;
  - `Reporter.messageOrThrowablesDetailMessage` (`object Reporter` in `org/scalatest/Reporter.scala`, which `stringsToPrintOnError` calls for a blank message).

  The functions stay in the objects that define them in 3.2.20, which are `private[scalatest]`. 3.2.20 nests `stackTraceLines` in `stringsToPrintOnError` as `getStackTrace`. The functions that format no throwable keep their parameters: `stringToPrintWhenNoError` for the no-error lines and `stringToPrintWhenMarkup` for a markup message's text.
- The view is `private[scalatest]`. `ThrowableView.of` supplies it for a live `Throwable`, and core's decoded throwable is one. It exposes:
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
  - `StringReporter`'s output stays byte-identical, and so does that of `Framework`'s `SbtLogInfoReporter` and the Scala.js and Native `TaskRunner`, which call `fragmentsForEvent` (`../../SPECIFICATION.md` § Scope § Out of scope, R-003).
- The writer passes a label's text without the formatter's leading indentation, indents each line by its depth in the tree (`../specification/layout-tree.md` AC-009 to AC-011), and composes multi-colour lines (`../specification/layout-lines.md` AC-026, AC-027). It adds the suffixes, lines and marks that `../specification/layout-lines.md` and `../specification/layout-messages.md` add to 3.2.20's text.
- The writer calls `stackTraceLines` with `S` and `F` off. The decoded throwable therefore leaves the failed code's stack depth absent: only `StringReporter`'s `S` path reads it, and no formatting character of the human-readable output turns `S` on (`../../SPECIFICATION.md` R-106f, R-106g).
- A characterisation test, `StringReporterRecordedTextSpec`, proves `StringReporter` byte-identical. It passes before the functions take the view, and passes unchanged after.
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
  - The recorded text has a line feed where the run-aborted text has the platform's line separator, so one recording serves every platform.
  - The existing `StringReporterSuite`, `StringReporterAlertSpec` and `StringReporterSummarySpec` stay. They print no non-`StackDepth` frames, no `Cause:` line and no `PropertyCheckFailedException`, so they cannot prove the change alone.
