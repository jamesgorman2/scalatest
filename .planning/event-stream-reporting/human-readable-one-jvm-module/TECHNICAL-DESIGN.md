# Consolidated human-readable output for one JVM module: technical design

Its decisions are in [TECHNICAL-DESIGN-DECISIONS.md](TECHNICAL-DESIGN-DECISIONS.md).

## Summary

- The emitter, in a scalatest-core that compiles on JDK 8 without `java.lang.ProcessHandle`, must give the Mill plugin:
  - the PID and start time Mill's JVM reads from `ProcessHandle`;
  - each label before any test runs, though no event carries the labels;
  - each test's events assigned to its node, though core and user code replace the reporter a test is given;
  - the scopes and tests of an instance that runs, though the instance first read may register others and user code runs instances core never sees;
  - each execution of a node told from its others, though no event says which run of its suite or call of `runTest` sent it;
  - each test's messages in the order the test called them, though no event carries that order.
- The test command, reached as `mill scalatest/test` with nothing in the build referring to it, must:
  - split a module's own ScalaTest arguments as ScalaTest's JVM runner parses them;
  - turn a worker's stdout and stderr byte chunks into lines;
  - read Mill's colour decision through `private[mill]` API;
  - take its result from library state that another thread changes, not from what Mill's test-runner subprocess call returns.
- The human-readable writer must reuse `StringReporter`'s formatting without changing what `StringReporter` prints, though in ScalaTest 3.2.20 that formatting reads a live `Throwable` the decoded event stream cannot rebuild and returns lines already indented and coloured.
- Every artifact must move to group `com.edc` while MiMa checks each one upstream also publishes against upstream's. Upstream's build sets the check on few of those projects and names the right artifact on two.

## Critical code paths

### The test command's entry point

- The test command is the `test` command of the top-level `ExternalModule` object `org.scalatest.mill.TestCommandModule`. Beside it, package `scalatest`'s package object, `object package extends mill.api.ExternalModule.Alias(TestCommandModule)`, gives it the prefix `scalatest`, as Mill 1.3.1's `package object spotless extends ExternalModule.Alias(SpotlessModule)` does (`libs/javalib/src/mill/javalib/spotless/package.scala`).
  - The package object names Mill's and the plugin's types from `_root_`. Any import in its file causes a cyclic reference, since the package object's members are in scope where the file's imports resolve.
- Mill's selector parser accepts a single-segment prefix before `/` (`core/api/src/mill/api/internal/ParseArgs.scala` `selector`). It resolves prefix `scalatest` by loading class `scalatest$`, then `scalatest.package$`, the package object, from the meta-build's class loader (`core/resolve/src/mill/resolve/Resolve.scala` `resolveRootModule`, at Mill tag `1.3.1`). The plugin's integration tests run `mill scalatest/test core.test` on fixture builds that add the plugin by their header line.
- The command takes its arguments as a `mainargs.Leftover[String]`, so that an argument beginning `-` reaches it rather than Mill's argument parser.

### The run's PID and process start time

The `runStarted` event's start time must equal `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM exactly, not closely (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

- The emitter reads both from `ProcessHandle.current()` by reflection, in `org.scalatest.tools.ProcessIdentity`. The read is in core's JVM sources only; Scala.js and Native are out of scope.
- It looks each method up on the interfaces `java.lang.ProcessHandle` and `java.lang.ProcessHandle.Info`, never on the handle's runtime class, whose methods are not accessible (`research/jvm-pid-and-start-time.md` § 2).
- `RuntimeMXBean.getStartTime()` is not a fallback: it records when the JVM finished initialising and never matched (`research/jvm-pid-and-start-time.md` § 3).
- A runner given the runner argument fails at construction, with an `IllegalStateException` and before it creates any file, where the JVM has no `ProcessHandle` or `startInstant()` is empty (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "The end of a run's event stream").
  - Mill 1.x supports user-defined modules down to Java 11 only, and its test worker's entry point is Java 11 bytecode in Mill 1.1.10 and 1.3.1 (`research/jvm-pid-and-start-time.md` § 1). No worker under the test command runs without `ProcessHandle`.

### A module's own ScalaTest arguments

The test command reads the module's arguments from Mill's `TestModule.testArgsDefault`, where `testForked` takes them, and passes the worker those `../SPECIFICATION.md` R-125 allows. As in `testForked`, discovery receives the same arguments, without the runner argument.

- A flag cannot be told from a value by its leading `-`: in `-z -o`, `-o` is a test-name substring. The split therefore needs the arities of `org.scalatest.tools.ArgsParser.parseArgs`, but `ArgsParser` is `private[tools]`, only sorts arguments, and throws on one it does not recognise.
- Core's argument splitter (`../ARCHITECTURE.md` § Modules § ScalaTest core) is `org.scalatest.tools.ArgumentSplitter`, in core's Scala 3 JVM sources only. It returns the arguments in the order given, each as one of two kinds:
  - an argument whose flag `parseArgs` recognises, with its values: the flag without what is written onto it (`-o` for `-oD`), then the argument as written and its values;
  - an argument whose flag `parseArgs` does not recognise, ungrouped, under no flag. The test command fails on the first such argument and names it. A null argument is an unrecognised argument where a flag is read; where a value is read, it is kept as the flag's value.
- The splitter recognises the runner argument's flag, which `parseArgs` reads only through `parseArgsWithTestEventLog` (§ The test event log on the JVM).
- The splitter keeps its own table of each flag's arity. The splitter never throws:
  - a flag short of its values keeps those it has, as `parseArgs` does for every flag but two;
  - where `parseArgs` throws, the splitter does as follows:
    - `-W` short of its values, or with values that are not integers, keeps the next two arguments, or those that remain;
    - `--test-event-log` with no file keeps the flag alone.
- Tests keep the table in step with `parseArgs`:
  - One runs `parseArgs` and the splitter over every flag `parseArgs` recognises, the runner argument included, each with its values and followed by another flag, and requires the same split. The values look like flags, except those of `-k` and `-K`, which `parseArgs` reads only where they do not begin with `-`, and those of `-W`, which are negative integers.
  - The other gives both every printable ASCII character as a flag, written alone and with a character written onto it, and requires that the splitter recognise exactly those `parseArgs` recognises, and no flag the first test leaves out.
- The library's **Split a module's ScalaTest arguments** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is `org.scalatest.consolidation.ScalaTestArgument.split`, which returns the splitter's result in the library's public type `ScalaTestArgument`.

### Mill's `Running Test Class` lines

At `testLogLevel` `Debug`, Mill's default, the worker writes `Running Test Class <name>` to stderr for each class it runs (`libs/javalib/src/mill/javalib/TestModule.scala` `testLogLevel` and `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `runClaimedTestClass`, at Mill tag `1.3.1`). `../SPECIFICATION.md` R-129 keeps the line out of captured output.

The test command passes the worker the higher of `Info` and the module's own `testLogLevel`, so a module's `Warn` or `Error` is kept. Mill then never writes the line. No captured line is matched by text, so a test that writes the same line keeps it.

### Capturing a JVM worker's stdout and stderr

The worker's stdout and stderr become the tagged lines **Add a run** takes (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library).

- The test command makes Mill's test-runner subprocess call with `os.Inherit.out` and `os.Inherit.err` each set to an `os.ProcessOutput.ReadBytes`. os-lib then reads each pipe on its own thread, in chunks that ignore line ends (`../research/mill-worker-channels.md` § 1.2, § 2.3; `../research/mill-parallel-worker-capture.md` § 4).
- Each stream has its own line splitter, fed only by that stream's reader thread. It holds bytes up to a `\n` byte, drops one `\r` directly before it, decodes the held bytes, and appends the line, tagged with its stream, to the run's one sequence under a lock.
  - Splitting before decoding never cuts a character in an ASCII-compatible charset whose multi-byte sequences never hold byte `0x0A`. That covers every encoding `native.encoding` names, among them UTF-8, the single-byte charsets, MS932, GBK, MS949 and Big5.
  - A lone `\r` stays in the line's text.
  - Lines of the two streams enter the sequence in the order their splitters completed them. `../SPECIFICATION.md` R-043 asks no more.
- When its stream ends, a splitter appends the line it still holds, if it holds any bytes. Mill's call returns only once both reader threads have finished (`../research/mill-worker-channels.md` § 2.3), so the sequence is complete when the call returns.
- Mill's worker prints to stdout what its runner's `done` returns (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `handleRunnerDone`, at Mill tag `1.3.1`). Given the runner argument, `org.scalatest.tools.Framework`'s `done` returns an empty string in place of ScalaTest's end-of-run summary. Mill prints nothing for an empty string, so the summary is never captured (`../SPECIFICATION.md` R-045).
- A worker writes a piped stdout and stderr in the platform's native encoding unless its `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`. The splitter decodes with the charset Mill's JVM names in its `native.encoding` system property, or `Charset.defaultCharset()` where that property is absent, and replaces malformed bytes with U+FFFD rather than dropping them.

### The test command's result

This serves `specification/test-command.md` AC-084 to AC-087 and AC-157.

- The library keeps the consolidation state (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**) readable from any thread without a lock: event log reading adds events on its own thread while the test command's thread reads the state.
  - Each operation that makes the state failed does so before it returns, so a read made after that operation returns sees the failure.
  - The events that report a failure (`../SPECIFICATION.md` R-046) are `TestFailed`, `TestCanceled` and `SuiteAborted`. A `RunAborted` or `RunStopped` is not among them (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "What the library takes").
- The test command reads the state once, after **End** returns.
- Mill's test-runner subprocess call returns a `Result[(String, Seq[TestResult])]` (`../research/mill-worker-channels.md` § 2.3), which the test command leaves unread, a `Result.Failure` included.
  - A worker that dies makes the call return a `Result.Failure`, not throw (`../research/mill-worker-channels.md` § 3.1, "A dead worker"). The captured line sequence is then complete (§ Capturing a JVM worker's stdout and stderr), and the test command adds the worker's run, or its process where the `processFolder` holds no event log file (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "On the JVM, on Scala.js under `NodeJs` and on Native").
  - The call throws where Mill's `CREATE_NEW` write of `testargs` is refused, before it starts the worker (`../research/mill-test-worker-args.md` § 2.3), so that throw leaves no worker, no captured line and no event log. The write is not refused in a `processFolder` the test command has cleared and starts one worker in (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "On the JVM, on Scala.js under `NodeJs` and on Native").
  - `TestModuleUtil.runTests()` also throws when a worker future failed (`../research/mill-worker-channels.md` § 5), but the test command drives the test queue itself (`../ARCHITECTURE.md` § Data flow) and never calls it.
  - A user interrupt does not reach this path: the command's thread does not regain control (`../research/mill-command-lifetime.md` § 5; `../SPECIFICATION.md` R-069, R-116).
- AC-087 needs no check of the module's framework or release.
  - MUnit and uTest have no emitter. 3.2.20's `ArgsParser`, which its `Framework.runner` calls, throws on the runner argument (§ The test event log on the JVM).
  - The worker's `processFolder` therefore holds no event log file when Mill's call returns, so the test command adds the worker as a process that ended before any run existed in it. That makes the consolidation state failed (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**).

### A message among its node's children

This serves `specification/layout.md` AC-034.

- Assembly records, with each message assigned to a suite or scope, which of that node's children had sent the first event of an execution under the message's own execution (§ A node's executions and its suite's latest structure) when the message arrived.
- The human-readable writer places the message from that record.

### A node's executions and its suite's latest structure

This serves `specification/runs-and-test-structure-executions.md` AC-141 to AC-150 and AC-159.

- Assembly keeps each node by its ID with its events, grouped by execution number, apart from its suite's structure. A further `structure` event (§ The test event log on the JVM § The format) replaces the scopes and tests of its root suite.
  - A suite or scope takes its parent from its node path, and its name from the `structure` holding it. A test takes its parent scope, name and label from the latest `structure` holding it, so a further `structure` can move it without changing its ID.
  - A node the new structure drops keeps the parent, declared position and label it last had, and sorts after any node the latest structure holds at that position. This covers sibling suites sharing a `suiteId` that started out of declared order: the first to start takes the index 0 node, and its further `structure` replaces that node's tests where they differ.
  - A suite whose events name a node path no `structure` holds is at a place the run did not declare (§ The test event log on the JVM § Suite instances and their nodes). Assembly adds it as a nested suite of the parent its path names, directly after the last sibling with its `suiteId`, and gives it the scopes, tests, names and labels of the latest `structure` of the declared sibling with that `suiteId`, each under the new suite's node path. A borrowed test no event names never started.
  - A node a `structure` holds and no event names never started, as where the run's process died first.
- Assembly orders a suite's children in three groups:
  - its scopes and tests, by the `position` its latest `structure` gives them;
  - the nested suites its first `structure` gave it, by `position`, before or after the scopes and tests as that structure had them;
  - then each nested suite a later `structure` event added, in the order those events arrived.

  A test's children are the suites it ran, in the order their `structure` events arrived.
- Each event gives its execution path (§ The test event log on the JVM § Executions). Assembly puts the node's execution under the execution its path names for the node's parent, the scope, suite or test directly above it.
  - It reads nothing from the order of scope events: under `RandomTestOrder`, a distributor or `AsyncEngine`, a scope can close before its tests start.
  - A node shows each execution that its own events name or that the execution paths of the events below it name.
  - A nested suite's execution sits under the execution of its parent that the paths of its events, and of the events below it, name, so a suite a test runs in each of its executions shows under each.
  - These sit under the parent's latest execution:
    - a nested suite with no execution;
    - a scope or test with no execution;
    - an execution of a scope or test that no event of its own names.

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
  - Its output stays byte-identical, and so does that of `Framework`'s `SbtLogInfoReporter` and the Scala.js and Native `TaskRunner`, which call `fragmentsForEvent` (`../SPECIFICATION.md` § Scope § Out of scope, R-003).
- The writer passes a label's text without the formatter's leading indentation, indents each line by its depth in the tree (`specification/layout.md` AC-009 to AC-011), and composes multi-colour lines (AC-026, AC-027). It adds the suffixes, lines and marks `../SPECIFICATION.md` R-106 adds to 3.2.20's text.
- The writer calls `stackTraceLines` with `S` and `F` off (`../SPECIFICATION.md` R-106). The decoded throwable therefore leaves the failed code's stack depth absent: only `StringReporter`'s `S` path reads it, and `s` will never need it (`../SPECIFICATION.md` R-128).
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

## Stdout

- The test command writes human-readable output through `Task.log.streams.out`, whose lines reach stdout whole, with Mill's prefix (`../research/emitter-plugin-spike.md` § 8.1, § 8.2).
- It reads Mill's colour decision from `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`). `Task.log.prompt` is `private[mill]`, so the class that reads it is in a package inside `mill` (`../research/mill-runner-progress-without-trait.md` § 5.1).
  - The value was `false` under ConPTY and MinTTY (`../research/mill-runner-progress-without-trait.md` § 5.2), so there human-readable output is uncoloured unless Mill is given `--color true`.
- The discovery runner puts nothing on the console, so the test command need not silence it (`../SPECIFICATION.md` R-072, R-044), provided discovery is given no reporter argument, as R-125 withholds the module's.
  - `org.scalatest.tools.Framework` builds `SbtLogInfoReporter` only in a task's `execute`, which discovery never calls, and `Framework.runner` drops the standard-out reporter configuration from those its runner dispatches to.
  - `ScalaTestRunner.done` returns ScalaTest's end-of-run summary rather than printing it. Mill's `TestRunnerUtils.getTestTasks0` (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` at Mill tag `1.3.1`) ends with `runner.done()` and discards that summary; only Mill's run path prints it, and given the runner argument there is none to print (§ Critical code paths § Capturing a JVM worker's stdout and stderr).

## The test event log on the JVM

- The runner argument is two arguments: the flag `--test-event-log`, then the event log file's path. No ScalaTest 3.2.20 flag begins `--`.
- Core's runner argument definition (`../ARCHITECTURE.md` § Modules § ScalaTest core) is `org.scalatest.tools.TestEventLogArgument`, one `private[scalatest]` definition shared by `ArgsParser` and the library's **Build the runner argument** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library), which is `org.scalatest.consolidation.RunnerArgument.forEventLogFile`. The event log file is in the worker's `processFolder`. The test command adds the argument that operation returns, unread, to the worker's arguments.
- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it reads the runner argument:
  - `ArgsParser.parseArgsWithTestEventLog`, which `Runner.run` and `Framework.runner` call, returns the file the argument names beside the other arguments. It rejects the flag with no argument after it, and the runner argument given twice.
  - `ArgsParser.parseArgs`, which every other caller still calls, rejects the flag with 3.2.20's message for an argument it does not recognise.
  - `ArgsParser.checkArgsForValidity`, which only `Runner.run` calls, accepts the runner argument.
- `Runner.run` rejects the runner argument given with the graphic reporter `-g`, whose runs `RunnerJFrame` starts. Given the runner argument and no reporter argument, it configures no reporter, where 3.2.20 configures the graphic reporter.
- `Framework.runner` given the runner argument and no reporter argument builds no `SbtLogInfoReporter`. A sub-process runner, one given remote arguments, ignores the runner argument: sbt gives it the main runner's arguments, and the event log file is the main runner's.
- `org.scalatest.tools.Runner.run`, and the runner `org.scalatest.tools.Framework.runner` constructs, each start the emitter, `org.scalatest.tools.TestEventLogEmitter`, once they have parsed and checked their arguments. Starting it writes the `runStarted` event, before `RunStarting` (`specification/test-event-log-structure.md` AC-094).
- The emitter writes the `runStarted` event to a file of another name in the same folder, the event log's name followed by `.tmp`. It moves that file to the event log's name with `java.nio.file.Files.move` and `ATOMIC_MOVE`, then opens it to append every later event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
  - Event log reading never reads the other name, so a worker that dies before the move holds no event log file.
  - In the same folder, the move is one rename within one file system.
  - The `runStarted` event precedes every other event, so nothing is appended between the move and the reopening.
  - It appends through a `java.io.FileOutputStream`, with one `write` call for each event. An interrupt of a thread writing to a `FileChannel` closes the channel for every thread, and a test's time limit can interrupt a test thread.
- The emitter writes nothing after the `runEnded` event, and closes the file once it has written it.
- An event that cannot be encoded or written ends the event log before that event and closes the file, so the run has no `runEnded` event. Turning the emitter on must not stop a reporter receiving an event, so a hand-off keeps what encoding or writing throws from its send site. Two kinds of throwable are treated differently:
  - An `InterruptedException` is kept from the send site too, but the hand-off leaves its thread interrupted. Encoding calls user code that can block, and a test's time limit can interrupt it there.
  - A `VirtualMachineError` other than a `StackOverflowError`, or a `ThreadDeath`, is thrown to the send site once the file is closed. A `StackOverflowError` is kept from the send site: a throwable or config map value that recurses without end is one more event that cannot be encoded.
- A run that ends with no closing run event has no `runEnded` event in its event log. The emitter's file is closed:
  - by `Runner.run`, when its run without `-g` returns or throws;
  - by `Framework.runner`, when the construction of its runner throws, as where a custom reporter class cannot be loaded. The exception reaches the build tool, and the event log holds the `runStarted` event alone;
  - by the `Framework` runner's `done`, when it throws before it sends `RunCompleted`, as where its wait for a sub-process's events is interrupted. A runner whose `done` the build tool never calls keeps its file open.
- A send site hands its event off by calling a function, an `Event => Unit`, before it reports the event. A run without the runner argument is given a function that does nothing. The function reaches each site as follows:
  - `Runner.doRunRunRunDaDoRunRun` and `Framework`'s runner take it as a parameter.
  - `SuiteRunner` takes it as a constructor parameter. `Runner` supplies it for a `DiscoverySuite`, through `ConcurrentDistributor` under `-P`.
  - `DispatchReporter` takes it through `registerSlowpokeAlertHandOff`, as it takes `Framework`'s `SuiteSortingReporter` through `registerSlowpokeReporter`.
- The emitter's one write lock (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin) is taken by:
  - the run's own thread;
  - the dispatcher's thread;
  - test threads, including for messages a test's informers hand straight to the emitter;
  - the `-W` slowpoke timer's thread.
- The emitter encodes an event before it takes the write lock. Encoding calls `getMessage`, `getStackTrace`, `getCause` and `getSuppressed` on the run's throwables and `toString` on its config map's values. Any of these can block or be slow, and none may hold up another thread's hand-off. The emitter calls no user code under the lock. There it only:
  - checks that the event log is still open;
  - registers a suite instance: finds its place in the tables and starts its run's execution (§ Suite instances and their nodes);
  - tells whether it already has an event, and adds or removes it (§ Wrappers);
  - finds the node of an event without an origin and numbers the executions an event names;
  - writes the event's line, and those of the messages a test's closing event records or a test has handed off;
  - compares a suite's scopes and tests with its latest structure and, where they differ or no `structure` holds the instance, encodes and writes a `structure`. Its nodes hold only names and labels, so encoding it calls no user code (§ Further `structure` events);
  - after a closing run event, writes the `runEnded` event and closes the file;
  - closes the file where an event could not be encoded or written;
  - when a top-level suite's run completes, moves the events of that run still in its set to the suite's wrapper (§ Wrappers);
  - on closing the file, drops every event it keeps.
- The places that hand off an event sent outside every structure wrapper (`../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter):
  - `org.scalatest.tools.Framework`'s runner: `RunStarting` in its construction, and `RunCompleted` in `done`.
  - `org.scalatest.DispatchReporter`'s slowpoke timer under `-W`: its `AlertProvided`, which has `nameInfo = None` (`specification/test-event-log-structure.md` AC-121). The timer also passes that same event to the `SuiteSortingReporter` that `Framework` registers with `registerSlowpokeReporter`. From there it reaches at most a task's `SbtLogInfoReporter`, through no wrapper, and is not handed off again.
  - `org.scalatest.tools.Runner.doRunRunRunDaDoRunRun` and its catches: `RunStarting`, `RunCompleted`, `RunStopped`, `RunAborted`, `DiscoveryStarting` and `DiscoveryCompleted`.
  - `org.scalatest.tools.Runner.withClassLoaderAndDispatchReporter`'s catch, for the run `Runner.run` starts without `-g`: the `RunAborted` it gives the pass-fail reporter. It catches what `ReporterFactory.getDispatchReporter` throws, as where a custom reporter class cannot be loaded, and what `doRunRunRunDaDoRunRun` throws outside its own `try`, as where a `-A` file cannot be read. `Runner.run` passes it the hand-off through an overload. `RunnerJFrame` calls the 3.2.20 signature, which hands nothing off.
  - `org.scalatest.tools.SuiteRunner` running each `DiscoverySuite` that `Runner.genDiscoSuites` builds: that suite's `SuiteStarting`, its `SuiteCompleted`, and the `SuiteAborted` it sends in its `Failure(ue)` branch and its `NotAllowedException` and `RuntimeException` catches.
- Not handed off (`SPECIFICATION.md` § Scope § Out of scope):
  - the `AlertProvided`s that `Runner.readMemoryFiles` sends for `-A`;
  - the events `Framework` relays from a sub-process sbt forks;
  - the run events of the reruns that `Runner`'s `-g` graphical reporter starts through `RunnerJFrame`, `SuiteRerunner` and `TestRerunner`.
- The event log file is `scalatest-event-log.jsonl` in the worker's `processFolder`, a name Mill does not use there.
- The test command's folder for a module is `modules/<module path>`, beside the command's `test.dest`: `out/org.scalatest.mill.TestCommandModule/modules/core.test` for `core.test`. Mill clears `test.dest` but not its siblings, so the test command clears the module's folder itself before it runs discovery. The module's one worker uses that folder as its `processFolder`.
- Event log reading polls each file every 10 ms, the only interval measured (`../research/jvm-file-channel.md` § 2).
- On Windows, opening the event log file just after the emitter moves it into place can fail for a moment with a sharing violation, which Java throws as a `FileNotFoundException`. Event log reading retries the open at its poll interval, up to 500 times, before it fails the test command.
- The handle **Decode** returns (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is `org.scalatest.consolidation.RunEvent`, a public opaque type in the library over core's decoded event, whose classes are `private[scalatest]`. **Decode** is `RunEvent.decode`. It also rejects a `runStarted` event whose start time `Instant.parse` cannot parse.
- **Start** is `org.scalatest.consolidation.Consolidation.start`. The `Consolidation` it returns has the other operations:
  - **Add a run** is `addRun`. It takes the run's `TestRunLabel` and returns a `RunFeed`. The caller adds the run's events and captured lines to the feed, then ends the feed's event stream.
  - **Add a process that ended before any run existed in it** is `addProcessEndedBeforeAnyRun`.
  - **End** is `end`.
  - **Read the consolidation state** is `state`.
- A suite's structure takes each scope's and test's label from the style's registration tree, before any of them runs. The structure precedes the suite's first event (`specification/test-event-log-structure.md` AC-096), and a test that never starts (AC-097) or never closes (`TestStarting` carries `MotionToSuppress`) has no event giving its label. `SuperEngine.registeredNodes` and `AsyncSuperEngine.registeredNodes` build each label as `runTestImpl` and `runTestsInBranch` do, at indentation level 0, where the formatter adds no indentation:
  - a test's from `Suite.getIndentedTextForTest` over its text with its parent's child prefix, with the `includeIcon` its style's `runTest` passes, or `true` for a test the filter ignores, as `TestIgnored`'s formatter has it. That flag is `false` only in `AnyFeatureSpecLike` and `FixtureAnyFeatureSpecLike` (`specification/test-event-log-structure.md` AC-103);
  - a scope's from `getIndentedTextForInfo` over its text with its parent's child prefix, with no icon.
- A scope's or test's name is the text its style registers it with (`../JSON-LAYOUT.md` § Nodes, `name`), except where the style's registration adds words to what the suite's source gives. There the name is what the source gives:
  - a FlatSpec test registered through a verb is named without the `should`, `must` or `can` its registration puts before its text. One registered through `registerTest`, `registerIgnoredTest`, `registerAsyncTest` or `registerIgnoredAsyncTest` is named by its whole text, a leading verb included;
  - a WordSpec scope registered through `which` or `that` is named without that word, and without the after word's text where one follows it, which its registration puts after its text;
  - a FeatureSpec scope's name is its text without what `Resources.feature` puts before it, and a FeatureSpec test's without what `Resources.scenario` puts before it.
- The registered text alone does not say how a FlatSpec test or a WordSpec scope was registered, so those registrations record the name on the node they add to the registration tree. `registeredNodes` gives a recorded name before one derived from the text. Each style's names come as follows:
  - each FlatSpec helper that registers a verb's test, such as `registerTestToRun`, calls the engine's `nameLastRegisteredTest` as the registration returns. Every caller of those helpers passes a verb, a space and the test's text;
  - each WordSpec `which` and `that` registers through `registerBranchNamed`, which calls the engine's `nameCurrentScope` at the start of the scope's registration function, where the scope is the engine's current branch. `which` and `that` are inline in Scala 3, so a suite compiled against 3.2.20's WordSpec artifact holds 3.2.20's expansion, which passes the inline accessor of `registerBranch` the text already joined. Run on the fork's WordSpec artifact, such a suite's `which` and `that` scopes are named by that joined text, such as `u which`;
  - a WordSpec scope opened by `it` or `they` is registered by `registerShorthandBranch` with the text of the top-level scope before it. In its registration function it calls `nameCurrentScope` with the name that scope recorded, where it recorded one, so both scopes have one name. After a scope of a suite compiled against 3.2.20's WordSpec artifact, which records none, it is named by the joined text as that scope is;
  - a FeatureSpec name is derived from the text, since every FeatureSpec registration, `registerTest` included, puts `Resources.feature` or `Resources.scenario` before it.
- Each style trait that keeps a registration tree gives its scopes and tests, so named and labelled, by overriding `Suite.scopesAndTestsOfRegistrationTree`, a `private[scalatest]` member that is `None` in `Suite`. A style artifact of 3.2.20 run on the fork's core has no override, so its suites are read as suites with no registration tree.
- `RefSpecLike`, `PathAnyFunSpecLike` and `PathAnyFreeSpecLike` register on first use, not at construction, and their overrides run that registration. Reading a structure (§ Wrappers) therefore runs it for each such suite the structure holds, nested suites included, where nothing has run it yet:
  - for a RefSpec, the reflection over its scope objects and test methods, which runs each scope object's initialiser;
  - for a path suite, the construction of one further instance for each test after the first, which runs each of those tests' bodies. The first test's body ran as the suite was constructed.
- ScalaTest 3.2.20 runs that registration at the first call of one of the suite's registering members:
  - for a RefSpec, `expectedTestCount`, `testNames`, `tags`, `run`, `runTests` or `runTest`;
  - for a path suite, `expectedTestCount`, `testNames`, `tags`, `run`, `runTest` or `testDataFor`. Its `runTests` registers nothing: it throws `UnsupportedOperationException`.
- Reading a structure comes before that first call only for a nested suite:
  - a top-level suite is registered before its wrapper is installed. `Framework`'s `runSuite` and `SuiteRunner.run` each call `Suite.formatterForSuiteStarting`, which reads `testNames`, before they call the function that installs the wrapper. Under `Runner.run` the `expectedTestCount` sum before `RunStarting` has already registered every suite it reaches;
  - a nested suite that sum has not reached is registered in 3.2.20 as its outer suite's `runNestedSuites` builds its `SuiteStarting`, again through `formatterForSuiteStarting`. That is every nested suite under `Framework`, and under `Runner.run` one nested in a suite whose `expectedTestCount` does not ask it. With the emitter on, it is registered as the top-level suite's wrapper is installed, before that suite's `SuiteStarting` and not after it. Reporters receive the same events, since a path test's events replay its stored outcome and duration. What the registration itself does, such as the lines it prints, comes that much earlier.
- Each of those suites registers once: it marks itself registered before it registers, so a registration that throws leaves a suite that registers nothing more, and only the first call throws. In 3.2.20 a nested suite's first call is inside its outer suite's `run`, which therefore throws, and the outer suite is reported with `SuiteAborted`. With the emitter on, the structure read would take that throw, and the run would then complete both suites, the nested one holding part of its tests. The throw is therefore kept for the call 3.2.20 would have made first:
  - each override runs the registration through the engine's `registeredForStructure`, which keeps what the registration throws as well as throwing it;
  - `RefSpecLike.ensureScopesAndTestsRegistered` and `PathEngine.ensureTestResultsRegistered`, which each of those registering members calls, begin by calling the engine's `throwFailureOfRegistrationForStructure`, which throws a kept throwable once. Reporters therefore receive what 3.2.20 gives them. The throwable is the one the structure read's registration threw, so its stack trace is that read's;
  - the structure read fails, which ends the event log (§ Wrappers).
- A structure read from a suite with a registration tree holds:
  - every scope of the tree, since `runTestsInBranch` sends `ScopeOpened` for a scope whose tests the filter all drops;
  - each test for which `filter(testName, suite.tags, suite.suiteId)` does not say to drop it, as `runTestsInBranch` asks for each test.
- A structure holds a suite's nested suites, each read from `nestedSuites` with the same filter, unless the filter excludes nested suites. Among a suite's children they come before its scopes and tests, as `Suite.run` runs them, and after them for a suite mixing in `TestsBeforeNestedSuites`.
- A suite with no registration tree, one that overrides `testNames` and `runTest` itself, yields a test node for each name of `testNames` the filter keeps, in `testNames`'s order, and no scope. Each takes its name from `testNames`, never from an event's `testText`: `Suite.runTests` sends a test's name as `testText` only in the `TestIgnored` of a test the filter ignores, and otherwise the suite's own `runTest` may send any `testText`, or no event at all. Each is labelled as `Suite.runTests` labels a test it ignores, by `getEscapedIndentedTextForTest(testName, 1, true)` without the leading indentation.
- `Framework`, `Runner` and `DiscoverySuite` run a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` as a `ConfigMapWrapperSuite`, which has no registration tree and forwards the suite's name, ID, test names, tags and nested suites to its `private lazy val wrappedSuite`, the wrapped class constructed with `Map.empty`. The suite's first structure is built from `wrappedSuite`, reached through a `private[scalatest]` accessor on `ConfigMapWrapperSuite` (`specification/test-event-log-structure.md` AC-130). The instance that runs then gives its own (§ Further `structure` events).
  - `wrappedSuite` is built with `Constructor.newInstance`, so a throwing constructor reaches its first reader as an `InvocationTargetException`. In `org.scalatest.tools.Framework`'s `ScalaTestTask.execute`, that reader is the first `suite.suiteId`, outside any `try`, before `SuiteStarting` (`specification/test-event-log-structure.md` AC-132). Under `Runner` it is the `expectedTestCount` sum, inside `doRunRunRunDaDoRunRun`'s `try`, whose catch sends `RunAborted` (`specification/test-event-log-structure.md` AC-133).
  - Where the suite is a `ConfigMapWrapperSuite`, `execute` forces the construction of `wrappedSuite` through the `private[scalatest]` accessor, before `registerReporter` and `runSuite`, in a `try`. It calls no member a suite can override. Each later read 3.2.20 makes finds `wrappedSuite` already built, so a run without the runner argument throws what 3.2.20 throws, before any event. With the emitter on, the catch:
    1. builds a `DeferredAbortedSuite(suiteClass.getName, suiteClass.getName, e)`;
    2. hands the emitter that suite's structure, and the `SuiteStarting` and `SuiteAborted` `runSuite` would send for it, assigned to its node and passed to no reporter. Those two events take their ordinals from a `Tracker` of their own, so the run's reporters receive every later event with its 3.2.20 ordinal;
    3. rethrows `e`.
- Each suite's class name is `Suite.getSuiteClassName`'s, which for a `DeferredAbortedSuite` is the class whose construction failed. A `ConfigMapWrapperSuite`'s is instead its wrapped class's (`specification/test-event-log-structure.md` AC-104, AC-130). `SuiteAborted.suiteClassName` is never read: `Framework`'s catch around `suite.run` sets it to the suite's name.

### Suite instances and their nodes

An event's node comes from the invocation context its send site runs in, never from `args.reporter` (`../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter; `../decisions/architecture-test-structure.md` "A node's ID is its node path…"). `ParallelTestExecution.runTests`, `AsyncEngine.runTestsImpl`, `RandomTestOrder.run`, `StopOnFailure.run` and `org.scalatest.tools.Utils.wrapReporterIfNecessary` each replace `args.reporter` before a test runs, as can a user's `run`, `runTests` or `runTest` override (`specification/test-event-log-events.md` AC-105, `specification/test-event-log-structure.md` AC-128).

- The invocation context is a thread-local holding the run's emitter, a node path and an execution of that node. An execution's context also counts, by `suiteId`, the suites started in it, which gives each suite its index among its siblings sharing its `suiteId`.
- It is set, and restored after, only while the run's emitter is on:
  - by each site that installs a top-level suite's wrapper (§ Wrappers), to the run's own context, around the suite's run. Under a run without the runner argument the site clears it instead, so a `Runner` started inside a test without the runner argument registers nothing (`../SPECIFICATION.md` R-001);
  - by the site that registers a suite instance (below), to the instance's run execution, around the rest of its run;
  - by `Engine.runTestImpl` and `AsyncEngine.runTestImpl`, to the test's node and the execution they mint for it, around the test body;
  - by `Runner`'s `ConcurrentDistributor`, which takes the context where a suite is handed to it and sets it around the `SuiteRunner` that runs the suite on the pool's thread;
  - by `SerialExecutionContext`, which takes the context in `execute` and sets it around the task.
- A suite instance is *registered* by the first site of its run that finds a context set: `RandomTestOrder.run`, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl`, `PathEngine.runPathTestsImpl`, `Suite.run` or `TestsBeforeNestedSuites.run`, else `runTestsImpl` or `Suite.runTests`. A top-level suite is registered by the site that installs its wrapper, and a nested suite by the `runNestedSuites` that sends its `SuiteStarting` (§ Wrappers); each runs the instance in a context naming that registration, which the instance's run takes. The registering site keeps the registration in the context it sets around the rest of the run.
  - A later site of the same run, such as `runTestsImpl` after `runImpl`, finds the registration in the context and registers nothing.
  - An instance whose run finds no context set is not registered. Its sites record no origin and install no wrapper, so its events reach the wrappers of the enclosing suite or test through `args.reporter` and are assigned by their own data (§ An event's node).
- A registration takes the instance's `suiteId` and `suiteName` from the first read ScalaTest 3.2.20 makes of each in the run, and reads neither itself (`TECHNICAL-DESIGN-DECISIONS.md` "A registration takes a suite's `suiteId` from the first read 3.2.20 makes of it…"). The `suiteId` is read:
  - by `Framework`'s `runSuite` or `SuiteRunner.run`, for a top-level suite, as they build its `SuiteStarting`;
  - by `Suite.runNestedSuites` or `StepwiseNestedSuiteExecution.runNestedSuites`, as they build a nested suite's `SuiteStarting`;
  - in `Suite.runTests`, for the filter;
  - in a send helper of `object Suite`, as it builds the event: the first event whose origin the instance records.

  Each comes before the instance's first scope or test event, so the `structure` holding the node still precedes them. Where an event is about to be handed off before any of these reads, the emitter reads `suiteId` itself. Until then the registration's node path is unknown: a send site builds its origin against the registration, and the emitter resolves the path when the event is handed off.
- Once the `suiteId` is known, the registration fixes its node path:
  - where the context is a suite's run outside its tests and the `suiteId` is that suite's, the instance is that suite: it takes the suite's node path, and its run is the suite's run execution, or the execution the suite's `SuiteStarting` opened where the suite has no registered run (`../decisions/architecture-test-structure.md` "An instance a suite runs outside its tests that has the suite's `suiteId` is that suite's node…"). This covers the instance `OneInstancePerTest.runTest`, `ParallelTestExecution.runTest` and `RandomTestOrder.runTest` create with `newInstance`, the class `ConfigMapWrapperSuite.run` constructs from `args.configMap`, and the delegate a custom `@WrapWith` wrapper runs (`specification/test-event-log-executions.md` AC-135), with no site of their own;
  - otherwise its node path is the context's node path followed by its `suiteId` and the count of suites with that `suiteId` already started in the context's execution, which it then increments, and it starts the run's execution (§ Executions).
- `DiscoverySuite` and `DistributedTestRunnerSuite` reach no site that registers, so neither is a node (`../SPECIFICATION.md` R-096): the suites a `DiscoverySuite` runs start in the run's context, and the instance a `DistributedTestRunnerSuite` runs in the context `ParallelTestExecution.runTest` handed to the distributor, its suite's run outside its tests.
- The emitter keeps a node table for each suite node, by node path, read and written under its write lock. It holds:
  - the suite's name, `suiteId` and class name, and the parent and position its first `structure` gave it;
  - the scopes and tests of its latest `structure`, against which a further `structure` is compared (§ Further `structure` events);
  - its tests by test name, which `SuperEngine.registerTest` keeps unique within a suite, and its scopes by path: from the suite down, each scope's registered text with its index among the scopes of that text under the same parent, since `SuperEngine.registerNestedBranch` accepts sibling scopes of one text. The text is the one the scope is registered with, which each engine's `registeredNodes` gives beside the scope's name;
  - the parent of each scope and test;
  - the executions of the suite and of each of its scopes and tests (§ Executions).

  A table holds no suite instance.
- As it fixes an instance's node path, the registration looks the path up in the tables:
  - a `structure` holds the node: the first engine site of the run compares the instance's tree with the table (§ Further `structure` events);
  - a `structure` holds a suite with the instance's `suiteId` under the same parent, at a lower index: the instance started at a place no `structure` declared, and gets no `structure`. Assembly gives it the declared sibling's shape (§ Critical code paths § A node's executions and its suite's latest structure);
  - otherwise, as for a suite a test runs, the emitter writes a `structure` before any of the instance's events. It holds the instance's suite node, with its parent, and its scopes and tests, read as a further `structure` is (§ Further `structure` events), and none of its nested suites: each gets its own as it starts.
- Under this rule:
  - a nested suite that `Suite.runNestedSuites`, `StepwiseNestedSuiteExecution.runNestedSuites`, a user's `runNestedSuites` override, `Doc.runNestedSuites` or `DocSpecLike.runNestedSuites` runs starts in its outer suite's context, and is the nested suite at its place in the outer suite's `structure`;
  - a suite a test body runs, such as `new HelperSuite().run(None, args)`, starts in the test's context, and is a child of that test;
  - a suite a `run` override builds with another `suiteId` starts in the suite's context, and is a nested suite of it (`specification/test-event-log-executions.md` AC-136, AC-137, AC-155).

### An event's node

- Each engine site that sends an event for a registered instance passes an `org.scalatest.EventOrigin` to the send helper of `object Suite` it calls (`reportTestStarting`, `reportScopeOpened`, `reportInfoProvided` and the rest, which already take `theSuite`). The site gets the `EventOrigin` from the registration in its context; it holds the event's node and executions (§ Executions). Each helper takes it through an overload beside its 3.2.20 signature, which passes `EventOrigin.none`. With the emitter on, the helper hands the event to the emitter with its origin, then reports it (`../decisions/architecture-test-structure.md` "An event core sends for a registered instance is handed to the emitter where it is sent…"). The event is unchanged (`../SPECIFICATION.md` R-002).
  - The node is the test's for a test's event: the suite's node path and the test's name. For a scope's event, or for a message registered in that scope, it is the scope's path, which the site builds from the `DescriptionBranch` that `runTestsInBranch` holds. A suite-level message's is the suite's.
  - Nothing reads the order in which scopes open and close.
- An event without an origin, as one user code builds, a top-level suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted`, or an event of an instance that is not registered, is handed off by the innermost wrapper it reaches, with that wrapper's node. The emitter assigns it from the event's own data at or below the wrapper's node, else anywhere in the wrapper's top-level suite. Its `suiteId`, or a message's `nameInfo`'s, names the suite, and its test name names the test in that suite's table. Among suites sharing that `suiteId`, it is the wrapper's own suite where that is one, else the first in node-path order that has no `SuiteCompleted` or `SuiteAborted`.
- An event without an origin whose data names a suite or test no table holds is still assigned to its own node, and a further `structure` precedes the event:
  - a `suiteId` no suite at or below the wrapper's node has is given a nested suite node directly under the wrapper's node, with the `suiteName` and `suiteClassName` the event carries;
  - a test name its suite's table lacks is given a test node directly under the suite, named and labelled as a test of a suite with no registration tree is.
- An event of a test that the suite's latest structure drops, but that an earlier structure held, keeps that test's node ID. Assembly keeps the dropped node (§ Critical code paths § A node's executions and its suite's latest structure). Where the running instance's tree holds the test, the emitter first adds it back to the latest structure (§ Further `structure` events).
- A message with no origin whose `nameInfo` is `None` is assigned to the wrapper's own node, since nothing identifies another (`../SPECIFICATION.md` R-097; `specification/test-event-log-events.md` AC-107, AC-151).
- A `SuiteAborted` whose `suiteClassName` is `DistributedTestRunnerSuite`'s is assigned to the node of the wrapper that hands it off, the test that suite ran (`specification/test-event-log-structure.md` AC-126).
- The emitter writes each message a test's closing event carries in its `recordedEvents` as an event of its own, before the closing event and assigned as it is: to the same node, in the same executions. Reporters receive those messages only inside the closing event (`specification/test-event-log-executions.md` AC-140). The emitter skips this for a closing event whose origin says its messages were handed off, since the test's informer and documenter have already handed them over (§ A test's messages).
- The emitter encodes an event, and the messages it records, before it takes the write lock, as it encodes a run's event (§ The test event log on the JVM). Under the lock it finds the node of an event without an origin, numbers the event's executions and writes its lines.

### Further `structure` events

- A further `structure` is built without calling an overridable member of the suite. `testNames`, `tags`, `nestedSuites` and `expectedTestCount` are user code that can throw, and inside `run` 3.2.20 calls them only where it needs them (`../SPECIFICATION.md` R-002).
  - For a suite with a registration tree it is built from `atomic` alone:
    - its nodes and labels are built as the first structure's are;
    - each test an earlier structure of the suite filtered keeps that result: dropped, ignored or neither. The node table keeps each test's result, because the first structure is filtered by the suite's own `tags`, as `runTestsInBranch` filters each test, and a further structure cannot call `tags`;
    - each other test is filtered by `args.filter` over `tagsMap`, with the class's tag annotations applied as `Suite.autoTagClassAnnotations` applies them but to the tree's own test names. A suite whose `tags` override keeps a test that this filtering drops gains the test by the last case of the next list. Where its `tags` override drops a test this filtering keeps, the further structure holds a test node no event names. That is accepted: only a test no earlier tree of the suite held reaches this filtering, and detecting the case would mean calling `tags`;
    - it takes the table's `suiteId` and class name, where the table exists. A suite therefore keeps the class name its first `structure` gave, whichever instance runs.
  - For a suite with none, `Suite.runTests` builds it from the names `filter(theTestNames, tags, suiteId)` returns to its own loop.
  - It holds the suite's scopes and tests and no nested suite, which `atomic` does not hold. Each nested suite keeps the structure and place it has (§ The format).
- It is written, before the first event that needs it:
  - by the first engine site a run of a registered instance reaches, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl` or `PathEngine.runPathTestsImpl`, else `runTestsImpl`, where the instance's tree after filtering differs from the table in any node, parent, position or label. It therefore precedes every scope and test event of that instance. This is the site for the instance `ConfigMapWrapperSuite.run` runs (`specification/test-event-log-structure.md` AC-131), an instance `newInstance` creates, a nested suite's instance where `nestedSuites` returns new instances on each call, and one of several sibling suites sharing a `suiteId` that started out of declared order;
  - by `Suite.runTests`, likewise, for a registered instance with no registration tree;
  - by a registration that fixes its node path at a place no `structure` holds, where a `structure` is due (§ Suite instances and their nodes);
  - by an engine site about to send an event for a test or scope of its tree that the table lacks, such as a test the filter drops that a `runTests` override runs. It adds that node and the scopes around it to the suite's latest structure.
- A registered instance at a place no `structure` declared, beside a declared sibling with its `suiteId`, gets none of these.

### Wrappers

- A site that installs a top-level suite's wrapper calls a function, a `(Suite, Filter, Reporter) => Reporter`, with the suite, the filter the suite is run with and the reporter 3.2.20 gives the suite. It sends the suite's `SuiteStarting` to the reporter the function returns, and runs the suite with it. A site calls the function only for a suite that is to start, after any check of `stopper.stopRequested` that keeps the suite from starting, so a suite that never starts has no structure (`../ARCHITECTURE.md` § Data flow, "A top-level suite the run never started sent nothing"). A run without the runner argument is given a function that returns the reporter it is passed. The function reaches each site as follows:
  - `Framework`'s `runSuite` takes it as a parameter, through its task, and calls it once it has built the suite's filter. `runSuite` checks no stopper.
  - `SuiteRunner` takes it as a constructor parameter, and calls it in `run` after its check of `stopper.stopRequested`. `Runner.doRunRunRunDaDoRunRun` takes the function as a parameter and gives it to the `SuiteRunner` of each suite it runs, directly or under `-P` through `ConcurrentDistributor.apply`. For a `DiscoverySuite` it gives the function that returns the reporter it is passed.
  - A `DiscoverySuite` takes it as a constructor parameter (below).
- With the emitter on, the function is `TestEventLogEmitter.wrapperOfTopLevelSuite`. It reads the suite's structure, outside the write lock, since reading calls the suite's `suiteName`, `suiteId`, `testNames`, `tags` and `nestedSuites` and those of its nested suites. It then fills the tables of the suite and its nested suites, writes the `structure` event and returns the wrapper.
  - A structure that cannot be read ends the event log before it and closes the file, as an event that cannot be encoded does (§ The test event log on the JVM). The function then returns the reporter it was passed, and keeps what reading threw from its caller as a hand-off does.
- `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` register each nested suite as they build its `SuiteStarting`, which fixes its node path, and install its wrapper, naming that path, before sending it. They send the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` with its origin, so each is handed off at its send site and lands on the node the instance's own events name, under `-P` too. In the sequential branch, `callExecuteOnSuite` sends the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` through the outer suite's reporter and builds the nested suite's `Args` only after `SuiteStarting`. The wrapper takes that reporter's place, both for those events and in the `Args`. In the `-P` branch of `Suite.runNestedSuites`, the wrapper goes in the `Args` given to the distributor, whose `SuiteRunner` sends those events.
  - Where `thisSuite` is a `DiscoverySuite`, each nested suite's wrapper is a top-level wrapper (`specification/test-event-log-structure.md` AC-099). `DiscoverySuite` overrides two `private[scalatest]` members of `Suite` with the function its constructor takes:
    - In the sequential branch, `callExecuteOnSuite` asks `thisSuite` for the nested suite's reporter through `Suite.reporterOfNestedSuite`, which in `Suite` returns the reporter it is passed. `Suite.runNestedSuites` calls `callExecuteOnSuite` only where no stop is requested.
    - In the `-P` branch, `Suite.runNestedSuites` hands each nested suite to the distributor through `Suite.distributeNestedSuite`, which in `Suite` calls the distributor. `DiscoverySuite`'s override gives `Runner`'s `ConcurrentDistributor` the function, and the nested suite's `SuiteRunner` calls it after its stop check, on the pool's thread. The distributor starts each suite as it is handed it, so one nested suite can be running before the structure of the next is read and written.
- `Engine.runTestsImpl`, `AsyncEngine.runTestsImpl` and `Suite.runTests` install the test's structure wrapper, which names the test's node, in the `Args` they pass to `runTest`, not inside `runTestImpl`. `ParallelTestExecution.runTest` builds both the `Args` it gives the distributor and the `DistributedTestRunnerSuite` running that test from those `Args`. When that `DistributedTestRunnerSuite` throws, the distributor's `SuiteRunner` sends its `SuiteAborted` through those `Args`' reporter, so the event reaches the test's wrapper first (`specification/test-event-log-structure.md` AC-126). With the wrapper inside `runTestImpl`, the event would reach first the suite's wrapper that `ParallelTestExecution.runTests` puts over its `TestSortingReporter` (below).
  - Given a test name and a test sorter, `ParallelTestExecution.run`, `RandomTestOrder.run` and `AsyncEngine.runImpl` replace the reporter with a `TestSpecificReporter`, and wrap it in that test's wrapper. `AsyncEngine.runImpl` does so only in an async suite that mixes in neither `ParallelTestExecution` nor `RandomTestOrder`, and only where its caller passes a test sorter. `OneInstancePerTest` passes none: its `runTests` sets `runTestInNewInstance` before `AsyncEngine.runTestsImpl` runs, so `AsyncEngine.runTestsImpl` creates no `TestSortingReporter` for the instance that creates the per-test instances.
- `ParallelTestExecution.runTests`, `RandomTestOrder.run` and `AsyncEngine.runTestsImpl` each put a wrapper for the suite over the `TestSortingReporter` they create.
- A site of an instance that is not registered installs no wrapper.
- An event reaches every wrapper between its sender and `DispatchReporter`: its test's, its suite's and each enclosing suite's, interleaved with other reporters such as `TestSortingReporter`, `CatchReporter`, `StopOnFailureReporter` and a user's. An event with an origin was handed off at its send site, and no wrapper hands it off. Of an event without one, only the innermost wrapper it reaches hands it off.
  - The emitter keeps an identity set of the events it has been handed, by a send helper or a wrapper (`TECHNICAL-DESIGN-DECISIONS.md` "A wrapper tells an event the emitter already has…"). Under the emitter's write lock, a wrapper hands off only an event not in the set, and adds it.
  - The top-level suite's wrapper, the last wrapper any event of its suite reaches, removes the event from the set instead of adding it.
  - Once the top-level suite's run completes, the emitter moves the events of that suite's run still in its set into an identity set on the top-level suite's wrapper, read and written under the write lock, so the set is dropped with the wrapper. An event in it that still reaches the wrapper is removed and not written again, as an alert in flight from a thread the run started, or one a reporter between the wrappers held back. The wrapper hands off each other event it then receives, assigned by its own data.
- `wrapReporterIfNecessary` adds a `CatchReporter` over any reporter that is not one, so it adds one over a wrapper put where 3.2.20 passes a `CatchReporter` on. That layer forwards every event. A wrapper catches whatever its own hand-off throws and then passes the event on, so the layer never prints `Resources.reporterThrew` for the emitter, and no reporter misses an event (`../SPECIFICATION.md` R-002).

### Executions

- Core mints an execution as an object in the emitter's state. The emitter gives it a node's next number, from 1, when it first writes an event whose execution path names it for that node.
- An event's *execution path* is, for each node from the top-level suite down to its own node, the number of that node's execution the event's execution ran in. The nodes are the places of its node path and, for a test, the scopes between its suite and it in the latest `structure` holding the test, so the path is not a projection of the node path. It grows with the depth of the node (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, item 3).
- `Engine.runTestImpl` and `AsyncEngine.runTestImpl` mint one on each call, for the test's `TestStarting`, its closing event and its messages. A `runTest` override that calls `super.runTest` twice therefore gives its test two (`specification/test-event-log-executions.md` AC-140).
- `traverseSubNodes`, `runTestsImpl` and `Suite.runTests` mint one for each `TestIgnored` they send.
- The registration starts an execution for its run, and keeps it in the registration. A nested suite's `SuiteStarting` opens it, and the run takes it.
  - The suite takes it, for the run's suite-level messages. `runTestsInBranch` mints an execution of a scope each time it opens the scope, so a scope that a run opens twice has two executions within one suite execution. Where the suite's `SuiteStarting` opened an execution that no run has taken, the run takes that one.
  - The run of an instance that is its runner's node (§ Suite instances and their nodes) starts none: it uses its runner's.
  - A path-style suite (`PathAnyFunSpec` or `PathAnyFreeSpec`) runs each test's body in an instance of its own as the suite is constructed, but those instances send no event. The instance a site runs replays each test's stored outcome in `Engine.runTestImpl`, so all the suite's events are that instance's and take its registration. As for any suite with a registration tree, each test's and scope's events are assigned to their own nodes, in a new execution of the test and of each scope.
- An origin names the event's execution path: its own node's execution, that of each scope above it, and that of each suite and test above those, which the registration keeps from the context its run started in. A scope can have several executions within one suite execution, as where a `runTests` override runs a branch twice, and a test's path names the one it ran in. The emitter writes both numbers (§ The format), so a test's execution sits under the scope execution it ran in where runs of one suite overlap.
  - The later sites of a run, `runTestsImpl`, `runTestImpl` and `Suite.runTests`, find the run's execution in the registration of their context. `runTests` and `runTest` are overridable and pass only `Args`, which carries no execution.
- A test's execution closes with its closing event, a scope's with `ScopeClosed` or `ScopePending`, and a suite's with `SuiteCompleted` or `SuiteAborted`. A `TestIgnored` opens and closes its own.
- For an event with no origin, the emitter writes (`specification/test-event-log-events.md` AC-134, AC-152, AC-153; `specification/test-event-log-executions.md` AC-156):
  - the execution of its node that opened last and has not closed;
  - where none is open, a new one for an opening event (`SuiteStarting`, `TestStarting`, `ScopeOpened` or `TestIgnored`), and for any other event the node's latest, or a first one where it has none;
  - for each node above it on its node path, from the wrapper's own node and the execution the wrapper names, the ancestor's execution open when the event is handed off, else the ancestor's latest.

### A test's messages

- A test's `info` and `markup` reach reporters only in its closing event's `recordedEvents`, after its `note`s and `alert`s and with ordinals taken at the close (`Engine.runTestImpl`, `AsyncEngine.runTestImpl`). Neither the order events arrive in nor their ordinals give the order the test called them (`specification/test-event-log-events.md` AC-105). With the emitter on, the test's informer and documenter therefore also hand each message to the emitter when called. With the emitter off they behave as in 3.2.20.
  - The informer and documenter hand each message to the emitter with the execution `runTestImpl` minted, never to a reporter (`../SPECIFICATION.md` R-002). The emitter assigns it to the test, in that execution.
  - The test's `TestStarting`, `note`s and `alert`s are handed off at their send sites, so the emitter writes a test execution's messages in call order among them.
  - The `MessageRecorder` records a message and hands it off under its own lock. Reading its `recordedEvents` for the closing event stops every later hand-off under that lock. A message the closing event records has therefore been handed off before that event is reported. A message the test calls after it, from another thread, is never handed off: it is lost, as it is to reporters in 3.2.20.
    - The hand-off is split in two. The prepare step, `EventOrigin.preparedHandOffOfMessage`, runs before the recorder lock is taken: it builds the message's event, which calls the suite's own `suiteName` and `suiteId`, and encodes it. Only the commit step it returns runs under the recorder lock: it hands the encoded event to the emitter, which writes it under the write lock. With the emitter off the prepare step builds nothing.
  - `runTestImpl` reports the closing event with the origin `EventOrigin.ofClosingEventWithMessagesHandedOff` returns. The emitter leaves the recorded events of such an event out of the event log.
  - The hand-off takes no ordinal from `tracker`, so every event reporters receive keeps its 3.2.20 ordinal (`specification/compatibility-and-library.md` AC-056). The event log carries no ordinal, since no output shows or uses one.
  - The message's timestamp is the time of the call (`specification/test-event-log-events.md` AC-105).
- The messages of a path-style test, one of `PathAnyFunSpec` or `PathAnyFreeSpec`, never pass through that hand-off. `PathEngine.handleTest` runs the test's body while its suite is constructed, before the suite's `structure` and `SuiteStarting`. It gives the messages to four recorders, `PathMessageRecordingInformer`, `PathMessageRecordingNotifier`, `PathMessageRecordingAlerter` and `PathMessageRecordingDocumenter` in `org/scalatest/ConcurrentInformer.scala`. Each keeps its own queue, with no order across the four and no call time. Only the informer's queue reaches `TestLeaf.recordedMessages`, and `Engine.runTestImpl`, replaying the stored outcome, adds only the informer's events to the closing event's `recordedEvents`.
  - The four recorders of one test also append each call to one shared call sequence, `PathMessageCalls`. An entry holds what its recorder stores of the call, the recorder's event function and the time of the call. The informer holds the sequence, so it reaches `TestLeaf.recordedMessages` with the informer, and neither `registerTest` nor `TestLeaf` changes.
  - With the emitter on, `Engine.runTestImpl` replaying a path-style test hands the sequence's messages, in order, to the emitter after `TestStarting` and before the closing event, as the informer's are handed over at call time. Each message is built by its entry's event function, given a fresh `Tracker` in place of `tracker` so that it takes no ordinal from the run's. Its `timeStamp` is then set to the entry's call time.
  - The closing event's `recordedEvents` are built from `tracker` as in 3.2.20, emitter on or off, so the test's notes, alerts and markup still reach no reporter (`specification/compatibility-and-library.md` AC-056).

### Runs that pass no run-entry site

- `TestsBeforeNestedSuites.run` calls neither `super.run` nor its style's `runImpl`. It registers its instance itself (§ Suite instances and their nodes). In 3.2.20 such a suite has no suite-level informers, so it sends no suite-level message.
- A user's `run` that calls no `super.run`:
  - where it calls `runTests` or `runNestedSuites`, those sites register the instance;
  - where it sends events itself, they have no origin. The innermost wrapper they reach assigns each from its own data (§ An event's node).
- `Doc.runNestedSuites` and `DocSpecLike.runNestedSuites` run each included suite with their own `Args` and send no `SuiteStarting` or `SuiteCompleted` for it. The suite starts in the outer suite's context, as any nested suite does.
- A helper suite run from `beforeAll()` starts in its suite's context, so it is a nested suite of that suite, and its events are handed off at their send sites whatever reporter the user gives it.

### The format

Each event is a JSON object (`TECHNICAL-DESIGN-DECISIONS.md` "Each event of a run's test event log is a JSON object."), encoded as `../JSON-LAYOUT.md` § Encoding gives, except that keys are written in the order this section gives. Escaping control characters in strings keeps each event to one line.

- A lone surrogate's `\uXXXX` escape decodes back to the same `char`.
- Integers are JSON numbers, read as `Long`, never through `Double`.
- An empty array omits its key.

Each event's first key is `item`, naming its kind. The events are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, and the keys of each, in order, are:

- The `runStarted` event: `item` `runStarted`; `pid`; `startTime`, the text `Instant.toString` gives, read back with `Instant.parse`, so it equals `ProcessHandle`'s exactly.
  - Core's codec takes and yields `startTime` as that text, since core also builds for Scala.js, which has no `java.time`. The emitter's JVM sources format it and the library parses it.
- A `structure` event: `item` `structure`; `nodes`, an array of the suite's nodes in depth-first pre-order, each node's children in declared order. A node's keys are `id`, `kind` (`suite`, `scope` or `test`), `parent` (its parent's `id`), `position` (its declared position, from 0), `name`, `label`, `suiteId` and `className`, each present only on the nodes `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin gives it. A node's `id` is its node path, an array of its places from its top-level suite down: a suite's `{"suiteId": …, "index": …}`, a scope's `{"scope": …, "index": …}`, its registered text and its index among the scopes of that text under its parent, and a test's `{"test": …}`, its test name, directly after its suite's place. A suite's `parent` is its path less its last place, a scope's likewise, and a test's the `id` of the scope or suite the `structure` puts it in.
  - The first node is the event's root suite. A top-level suite has no `parent` or `position`. A nested suite that is the root has `parent`, the suite or test it sits under, and the `position` an earlier event gave it; one new to the event log has none (§ Critical code paths § A node's executions and its suite's latest structure).
  - A further `structure` event has the same form. Its root is a suite an earlier event holds, or a suite new to the event log under a suite or test an earlier event holds. It holds the root's scopes and tests whole, their `position`s from 0 among themselves, and none of its nested suites. A suite an earlier event holds keeps its `className`.
- A ScalaTest event:
  - `item` `event`;
  - `node`, the node path of the node the event is assigned to, in the form of a node's `id`, absent where it is assigned to the run;
  - `executions`, the event's execution path: an array of execution numbers, from 1, one for each node from the top-level suite down to the event's node, a test's scopes included, present exactly where `node` is;
  - `event`, its `org.scalatest.events` class's simple name;
  - then the event's fields.
- The `runEnded` event: `item` `runEnded`; `outcome`: `completed`, `aborted` or `stopped`.

A ScalaTest event's fields:

- A decoded class holds every field of its `org.scalatest.events` class except:
  - `ordinal`, `formatter`, `rerunner` and `payload`;
  - `recordedEvents` (§ A test's messages);
  - the event's copy of its node's names and IDs: `suiteName`, `suiteId`, `suiteClassName`, `testName`, `testText`, `nameInfo`, and a `ScopeOpened`'s, `ScopeClosed`'s or `ScopePending`'s `message`, since names, IDs, class names and labels come from the `structure` event through the node the event is assigned to;
  - `DiscoveryStarting`'s `configMap`, since the run record's comes from `RunStarting`.
- Its keys are the field names of its decoded class, in that class's declaration order. Each decoded class names and orders the fields it holds as its `org.scalatest.events` class does.
- A field whose `org.scalatest.events` type is not an `Option` is required, except a sequence, whose omitted key decodes as empty.
- A `String` is a JSON string, an `Int` or `Long` a number and a `Boolean` `true` or `false`. A sequence is an array, and `None` omits its key.
- A case class, such as a `Summary`, is an object of its fields under the same rules. A member of a sealed family, such as a `Location`, is that object with first key `kind` holding its class's simple name: `{"kind":"SeeStackDepthException"}`.
- A `ConfigMap` is an object of each value's `toString`, its keys in ascending `String.compareTo` order.
- A throwable is an object with these keys, in order:
  - `id`, numbered from 1 in the order the event writes its throwables. A throwable the event has already written, the same object and never merely an `equals` one, is `{"ref": <id>}`. Identity is per event, so a torn event breaks no other.
  - `className`; `message`, absent where `getMessage` is `null`.
  - `stackDepth`, only on a `StackDepth`: an object of `fileName`, `filePathname` and `lineNumber`, the failed code's, each present where defined.
  - `propertyCheck`, `true`, only on a `PropertyCheckFailedException`, whose message already holds its line number.
  - `stackTrace`, its frames, a `StackDepth`'s included, each a frame with the keys `../JSON-LAYOUT.md` § Throwables gives a frame, each absent where that section says, followed by `text`, its `StackTraceElement.toString`, which on JDK 9 and later can hold a module and a class loader name.
  - `cause`; `suppressed`, in order. Core's codec writes and reads nested values without recursion, so a chain of causes of any length is written and read whole, whatever the thread's stack size.

A line cannot be decoded where it:

- is not one JSON object, or repeats a key within an object;
- holds a key this section does not give its object;
- lacks a required key;
- holds an `item`, `event`, `kind` or `outcome` value this section does not give;
- holds a value whose JSON type is not the one this section gives its key, `null` included;
- holds an integer that does not fit its field's `Int` or `Long`, or one below the least this section gives its key: 0 for a `position` and an `index`, 1 for an element of `executions` and a throwable's `id`;
- holds a `ref` to an `id` the event has not yet written, or two throwables with one `id`.

## Publishing

The group change of `../ARCHITECTURE.md` § Modules reaches both builds. Every artifact is published at one version, the fork's release, and the plugin depends on core and the library at its own version.

- The fork's release is the sbt build's `releaseVersion`, `3.2.20`, so the JVM's core is `com.edc:scalatest-core_3:3.2.20`.
- The fork builds and publishes only the Scala 3 projects of `project/DottyBuild.scala` and the Java-only `scalatestCompatible` of `project/scalatest.scala`, which `scalatestCoreDotty` and `scalatestAppDotty` depend on. Each has `organization` `com.edc`, those upstream publishes under `org.scalatest` and `org.scalactic` alike.
- The build's Scala 2 projects stay as upstream wrote them, group included, so that a merge from upstream's `3.2.x-new` branch applies to them unchanged. The fork neither builds nor publishes them, and nothing keeps them compiling. `scalatestCompatible` is the one project of `project/scalatest.scala` the fork changes.
  - The build definition must still compile with the upgraded sbt plugins, so a Scala 2 project's setting changes where it no longer compiles. The change is the smallest that compiles and keeps the setting's behaviour. The one case is `scalatestTestJS` of `project/JsBuild.scala`: Scala.js 1.20.2 rejects `withStrictFloats`, so the call is removed. Strict floats are that version's default.
- Every Scala 3 project compiles with the Scala, Scala.js and Scala Native versions `../ARCHITECTURE.md` § Modules § ScalaTest core gives. Upstream's 3.2.20 build sets:
  - Scala 3.1.3 for the JVM and Scala.js (`dottyVersion`'s default);
  - Scala 3.3.3 for Native (`nativeDottySettings`);
  - Scala.js 1.8.0 (`scalaJSVersion`'s default, in both `project/plugins.sbt` and `project/BuildCommons.scala`);
  - Scala Native 0.5.10 (`scalaNativeVersion`'s default, in `project/plugins.sbt`), which has no Scala 3.3.8 library.

  The feature changes those defaults, so the build needs no `SCALAJS_VERSION` or `SCALANATIVE_VERSION` environment variable.
- `publishLocal` of a Scala 3 project must work on Windows, for the dependency check below and for the plugin's Mill build. Upstream's doc settings pass `-doc-source-url`, which Scaladoc 3 does not have: it reads the URL as a source path, and Windows rejects that path. The Scala 3 projects that take those settings drop the option and its URL.
- MiMa compares with upstream's 3.2.20 (`../SPECIFICATION.md` R-004), and `previousReleaseVersion` is `3.2.20`.
  - `project/plugins.sbt` upgrades `sbt-mima-plugin` from upstream's 0.7.0 to 1.1.4. Under the build's sbt 1.11.0, which is also upstream's, 0.7.0's `mimaReportBinaryIssues` fails on every project, Java-only `scalatestCompatible` included, with `NoSuchMethodError: 'scala.tools.nsc.settings.AbsSettings$AbsSetting scala.tools.nsc.Settings.verbose()'`.
  - The check covers every project the fork publishes whose artifact upstream 3.2.20 also publishes, on all three runtimes: every published Scala 3 project but the consolidation library, and `scalatestCompatible`.
  - Each covered project's `mimaPreviousArtifacts` names upstream's group, `org.scalatest` or `org.scalactic`, the project's `moduleName`, its platform cross-version and `3.2.20`, and its `mimaCurrentClassfiles` is the jar the project packages. `scalatest-compatible` has no Scala suffix.
  - Upstream's build sets both keys on eight Scala 3 projects and on `scalatestCompatible`, and names the right artifact only on `scalatestAppDotty` and `scalatestAppDottyJS`.
    - The other six Scala 3 projects build the artifact's name and the jar's from `name`, which is the project ID where a project sets only `moduleName`. Those on Scala.js and Native use `%%`, which leaves out the platform suffix.
    - `scalatestCompatible` uses `%%`, which adds a Scala suffix.
    - The feature sets both keys on every covered project.
  - MiMa passes where a class loses an inline accessor, a public method whose name holds `$`. Scala 3.3 names the accessor of a private member of a non-final class `<class>$$inline$<member>`, where Scala 3.1 named it `inline$<member>`. Upstream built 3.2.20 for the JVM and Scala.js with Scala 3.1, so code compiled against either calls the accessor by the Scala 3.1 name.
    - 3.2.20's JVM and Scala.js artifacts hold 34 such accessors, all named `inline$applyImpl`, in the 22 nested classes of the `FunSpec`, `FunSuite`, `PropSpec` and `FeatureSpec` style traits whose inline `apply` reaches a private `applyImpl`. On the JVM and Scala.js each of those classes therefore declares each `inline$applyImpl` itself, forwarding to `applyImpl`.
    - Upstream built 3.2.20 for Native with Scala 3.3, so its Native artifacts hold only the Scala 3.3 name. The Native sources leave the forwarders out, between `// SKIP-SCALATESTNATIVE-START` and `// SKIP-SCALATESTNATIVE-END`, so the fork's Native artifacts add no method 3.2.20's lack.
    - § Testing § MiMa gives the check that guards the accessors.
- A dependency of a Scala 3 project on a ScalaTest+ artifact excludes upstream's groups. Its upstream ScalaTest would otherwise sit on the classpath beside the fork's, which no longer evicts it.
- The consolidation library is a JVM-only project of `project/DottyBuild.scala`, depending on its Scala 3 scalatest-core project. `scalatestDotty` aggregates it, so the sbt build publishes it with core, before the plugin's Mill build resolves both.
- A task of the library's project checks `specification/compatibility-and-library.md` AC-062. It publishes the library and the projects it depends on with `publishLocal`, resolves the published library transitively from the local Ivy repository and Maven Central alone, and fails on any dependency with group `com.lihaoyi` and a name beginning `mill-`.
- The plugin's Mill build is `mill-plugin/build.mill`, pinned to Mill 1.3.1 by `mill-plugin/.mill-version` and run through the `mill` and `mill.bat` bootstrap scripts beside it.
  - Module `plugin` compiles with Mill 1.3.1's Scala, 3.9.0, against `mill-libs` 1.3.1 in `provided` scope. `plugin.publishLocal` publishes `com.edc:scalatest-mill_mill1_3` at the fork's release to the local Ivy repository.
  - Its dependencies on `scalatest-core_3` and `scalatest-consolidation_3` take a single colon before the version, since `::` would append the plugin's `_mill1` platform suffix.
  - `plugin.integration` uses `mill.testkit.IntegrationTester` to run a Mill 1.3.1 launcher, built from `mill-runner-launcher_3`, on each fixture build under `plugin/integration/resources/`.
  - The tests' `COURSIER_REPOSITORIES` lists the plugin's `publishLocalTestRepo` folder, then `ivy2Local|central`. A fixture therefore resolves the plugin as this build last compiled it, and the library and the fork from the local Ivy repository.
  - On Windows the launcher is copied to a `.bat` file, because Windows starts the extensionless script Mill's `launcher` writes only as a batch file. `CreateProcess` also rejects `assembly`'s jar, which has a script prepended.
- These commands, in order, publish what the integration tests resolve and then run them:
  1. At the repository root, `sbt.bat scalatestCompatible/publishLocal scalacticDotty/publishLocal scalatestDotty/publishLocal` (`sbt` off Windows). `scalatestDotty` aggregates core, the style artifacts and the consolidation library but neither `scalactic` nor `scalatest-compatible`, which are published first.
  2. In `mill-plugin/`, `./mill --no-daemon plugin.test.testForked + plugin.integration.testForked` (`mill.bat` on Windows). `plugin.test` holds the plugin's unit tests. The integration tests compile and publish the plugin to their test repository themselves.

  `./mill --no-daemon plugin.publishLocal` then publishes the plugin for builds outside the tests.

## Testing

### Runs the criteria describe

The criteria whose `Given` is an event log holding events (`SPECIFICATION.md` § Acceptance criteria) are tested without Mill and without the codec (`TECHNICAL-DESIGN-DECISIONS.md` "The consolidation library's tests build each run with a test-only builder…"). Those tests build each run with a test-only builder, `org.scalatest.events.TestRunBuilder`. It is in the build's common test project, `dotty/common-test`, which is not published. Core's test project depends on it. The library's project does not: it adds the common test project's classpath to its own unmanaged test classpath, so the library's published POM and `ivy.xml` do not name that project.

The builder takes its input and builds the run as follows:

- A test gives the run as one ordered list: the criterion's events, in the notation of `SPECIFICATION.md` § Acceptance criteria, with the run's captured lines at the places they are fed.
- The builder derives only each node's ID, its node path, its parent's ID and its declared position, from the `structure` events, and resolves each node an event names to its ID. It emits every event and captured line in the order given, in the form core's decoder yields, so a test can place a `structure` event, a ScalaTest event or a captured line anywhere the criterion puts it.
- It gives each event the execution path that the notation's `execution <n>` and `in <ancestor> execution <m>` rules give it.
- A `structure` block for a suite an earlier block holds becomes a further `structure` event. Each node of it that `SPECIFICATION.md` § Acceptance criteria makes the node of an earlier block has that node's path, and so its ID.
- A suite a `structure under suite` block adds gets its parent's ID and no position (§ The test event log on the JVM § The format).

The emitter's and the decoder's criteria are those that state events, in the parts of `specification/test-event-log.md`. Among them, `specification/test-event-log-events.md` AC-109 writes lone surrogates, and `specification/test-event-log-structure.md` AC-100 and AC-121 write events from concurrent threads. Each side of the codec is tested against the format on its own (`../decisions/architecture-test-event-log.md` "Each side of core's codec is tested on its own…"), so each such criterion has two tests. Both build the criterion's events with the same builder from its event list, and share expected text in the format, written by hand in the test: a whole line for each event the criterion states, with any value where the criterion fixes none.

- The emitter's test runs the criterion's suites with the runner argument. It compares the lines the run writes with the expected text, only on the keys holding what the criterion fixes and under the *is* and *holds in order* rules for events (below). It compares node IDs through the structures that hold them. It then decodes the lines with core's codec and compares the decoded events with the built ones.
  - The test also runs the suites without the runner argument, and compares what a `-C` recording reporter receives in each run with one expected list (`../SPECIFICATION.md` R-002). The tests of `specification/test-event-log-events.md` AC-112 and `specification/test-event-log-structure.md` AC-121 do not.
  - Where a criterion has `Framework` run a suite that nests others, the test's task selects each nested suite with a `NestedSuiteSelector` beside its `SuiteSelector`. Given a suite by name alone, `Framework` runs none of the tests of the suites it nests.
  - Where a criterion has `Runner.run` discover its suites, the suites are discoverable samples of package `samples.discovered`, whose class files the test copies to a runpath of its own. The build's own test run discovers and constructs these samples too, so a sample whose constructor must throw does so only on a thread of the thread group in which the test starts `Runner.run`. It goes by the group, not the thread's name, because `Runner.run` renames its thread.
  - `specification/test-event-log-events.md` AC-112 starts a JVM with the test JVM's classpath, in which `Framework` runs the suite, and reads that JVM's stdout and stderr from files.
- The decoder's test feeds core's decoder the expected text and compares the events it yields with the built ones.

One further decoder test, tied to no criterion, feeds the decoder text whose last line is torn, from which it yields no event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

Each comparison of events is made by `org.scalatest.events.EventLogComparison`, beside the builder:

- Each event is compared only on what the criterion fixes:
  - A `structure` event is compared on its whole tree.
  - A ScalaTest event is compared on its node, its execution number, each ancestor's execution number the criterion writes, its class, and the fields the criterion names.
  - A throwable is compared on the parts the criterion writes.
- Where the criterion says the event log *is* the events, the decoded events must be exactly those built. Where it says the log *holds* them *in this order*, the built events must be a subsequence of the decoded ones.

A drift between builder and decoder in what a criterion fixes then fails those tests, as well as the criteria run under Mill.

Each test of human-readable output compares the whole output, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour.

### Mill's console output

The plugin's integration tests assert that Mill's list of failed tests and ScalaTest's end-of-run summary do not reach the console. They cannot assert the same of Mill's runner progress: without a terminal Mill prints no progress prompt, even under `testForked` with `--ticker true`, so its absence proves nothing. Suppressing the progress is covered by construction (§ Stdout, the reading of `Task.log.prompt`) and by `../research/mill-runner-progress-without-trait.md`, which observed it under pseudo-terminals.

### Comparison with ScalaTest 3.2.20

This serves `specification/compatibility-and-library.md` AC-056 and AC-057, `specification/test-event-log-events.md` AC-113, AC-114, AC-116 and AC-117, and `specification/runs-and-test-structure-nodes.md` AC-073.

- AC-056 and AC-057 run in a test project of `project/DottyBuild.scala`.
  - The compatibility fixture and the recorders (the `-C` recording reporter, and a test-interface event handler and loggers that record what they receive) are the sources of an sbt configuration of the project's own that extends neither `Compile` nor `Test` and whose one library dependency is `org.scalatest:scalatest` 3.2.20. They compile there once, so both runs load the same compiled classes. `../SPECIFICATION.md` R-004 lets the fork run them.
    - The suites every runtime can run are in `src/fixture/scala`. The suite annotated `@WrapWith`, the `RefSpec` suite and the recorders that write files or use the test interface are in `src/fixture/scala-jvm`.
    - 3.2.20 depends on the Scala 3.1 library, which code compiled by Scala 3.3 cannot link against, so the project overrides that dependency's version with its own Scala version.
    - ScalaTest leaves the test interface to the build tool. The configuration takes sbt's own test-interface jar as an unmanaged jar, and both runs load it.
  - 3.2.20 is on no classpath that also holds the fork: the two share packages and class names, so on one classpath each class loads from whichever jar comes first. A generated resource gives the test itself, in `Test`, the test-interface jar and three classpaths: that configuration's compiled classes, its resolved classpath (3.2.20 and its dependencies), and the runtime classpath of the fork's `scalatestDotty` and `scalacticDotty` projects.
- The test forks one JVM for each run, as IntelliJ's forked mode and a build tool's test worker each start a JVM of their own. Each JVM's classpath is the fixture's compiled classes, the test-interface jar, and either 3.2.20's resolved classpath or the fork's. No JVM-wide state, such as `System.out` or a system property, then passes from one run to the other.
- In each JVM the recorders write each event to a file as one line, holding every field but timestamps, durations and thread names, and of each throwable only what `../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x compares. The test compares the two runs' files line by line.
- The harness that forks a pair of runs takes the arguments only the fork's run is given, so a test with the emitter on passes the runner argument there.
- A test of AC-113 forks the same pair of JVMs.
  - In each, a recorder writes one line for each class of `org.scalatest.events`: whether it is an event class, its declared fields with their erased types, and its constructors with their parameter types.
  - Scala 3.1 and 3.3 implement a lazy `val` with different fields. The recorder leaves out the two kinds of field only one of them adds, `OFFSET$<n>` and `<n>bitmap$<n>`. It writes the field that stores the value, `<name>$lzy<n>`, by name alone, without its modifiers and type. A lazy `val` or nested object the fork adds therefore still shows as an added field.
  - The test compares the lines of every class 3.2.20 has and of every event class.
- A test of AC-114 runs in forked-JVM pairs set up as AC-056's, launched through `Runner.run` and through `Framework`, with the emitter off and on.
  - Each pair runs once for each of several reporter argument lists, which between them turn on every character 3.2.20 accepts for `-o`, `-e` and `-f`: those of `ReporterConfigParam` except `A` and `B`, which `ArgsParser.parseConfigSet` rejects.
  - The test reads:
    - each JVM's stdout and stderr, from the forked process;
    - each `-f` file, from a directory of that JVM's own;
    - under `Framework`, the lines the recording loggers receive and, recorded apart from them, the summary the runner's `done` returns.
  - It checks that each run's reporters wrote to each place the launch sends them:
    - under `Runner.run`, stdout, stderr and the file;
    - under `Framework`, whose `-o` reporter writes to the loggers, the loggers, stderr and the file.
  - Before comparing, it replaces each run of stack-frame lines, and each `stackTraces` and `depth` value in a `J` reporter's JSON, with one placeholder, and blanks durations, timestamps and thread names.
  - The fixture's test writes one line to stdout and one to stderr. Under `Runner.run` the reporters write from the dispatch thread, so where those lines fall among a reporter's is not fixed. The test takes them out of each stream before comparing, and checks that each run wrote each once.
- A test of AC-117 forks the same pair of JVMs, launched through `Runner.run` and through `Framework`, without the runner argument and with a `-f` reporter.
  - It compares the names of the files each run leaves in its working directory, which is a directory of that JVM's own.
  - A `-C` reporter records, at each event it receives, the names of the live threads of thread group `main`. The JVM's own threads are in other groups. It normalises each name in two steps:
    - it removes what `Suite.run` adds to the name of the thread running a suite for the length of that run: `-running-<suite name>`, and the `-ScalaTest` before it. The reporter is called on the dispatcher's thread, so which suite's name it would find depends on scheduling. A thread the fork starts is still recorded, under the name it was started with;
    - it replaces each number with `N`.
  - The fork's run must leave no file and record no thread name that 3.2.20's does not.
- AC-116 runs in a Scala.js and a Native test project of `project/DottyBuild.scala`. The fixture and the recorders compile once against 3.2.20's artefacts for the platform, and the test links them twice, once with 3.2.20's and once with the fork's. There the recorders print each event to stdout as one line, in the form the JVM's recorders write it, and print each line the loggers receive, normalised as for AC-114. The test compares the two runs' lines.
- AC-073 runs among the plugin's integration tests under Mill's testkit. Its fixture build also holds a module over the same suites that tests with 3.2.20 and passes `-f` in its own ScalaTest arguments, configured as `SPECIFICATION.md` § Acceptance criteria requires of every JVM test module of a criterion's build; Mill's `testForked` on that module writes the file the criterion compares with.

### MiMa

`specification/compatibility-and-library.md` AC-058 runs the MiMa check on every project § Publishing covers. The check fails for a covered project that resolves no previous artifact.

The check runs with no problem filter, since a filter would let it pass with a break it would otherwise report:

- The `mimaBinaryIssueFilters` upstream's build sets on covered projects are removed. They excuse upstream's own changes since 3.2.19, and against 3.2.20 they could only hide a break the fork makes.
- Those it sets on Scala 2 projects stay as upstream wrote them (§ Publishing). The fork runs no check on those projects.
- Review rejects any filter this feature adds.

A passing check does not prove that code compiled against 3.2.20 links: MiMa does not report a lost inline accessor (§ Publishing). A test in the comparison's test project (§ Comparison with ScalaTest 3.2.20) covers that gap on the JVM:

- It reads the class files of the `org.scalatest` and `org.scalactic` packages on 3.2.20's resolved classpath and on the fork's runtime classpath. It fails where a class of 3.2.20 declares a method whose name holds `inline$` and the fork's class declares none of that name and erased descriptor.
- It covers every such accessor of every JVM artifact, whichever registrations the compatibility fixture calls.
- Nothing automated covers the Scala.js artifacts. Their forwarders are generated from the same source lines as the JVM's, and the AC-116 comparison links only the accessors its fixture calls.
- The Native artifacts need no such check while the fork builds them with Scala 3.3, as upstream built 3.2.20's.
