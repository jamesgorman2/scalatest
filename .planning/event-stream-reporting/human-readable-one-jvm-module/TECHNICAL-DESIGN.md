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
- The human-readable writer must reuse `StringReporter`'s formatting without changing what `StringReporter` prints, though that formatting reads a live `Throwable` the decoded event stream cannot rebuild and returns lines already indented and coloured.
- Every artifact must move to group `com.edc` while MiMa checks each one upstream also publishes against upstream's. Upstream's build sets the check on few of those projects and names the right artifact on two.

## Critical code paths

### The test command's entry point

- The test command is the `test` command of a top-level `ExternalModule` object in a named package of the plugin. Beside it, `package object scalatest extends mill.api.ExternalModule.Alias(<that object>)` gives it the prefix `scalatest`, as Mill 1.3.1's `package object spotless extends ExternalModule.Alias(SpotlessModule)` does (`libs/javalib/src/mill/javalib/spotless/package.scala`).
- Mill's selector parser accepts a single-segment prefix before `/` (`core/api/src/mill/api/internal/ParseArgs.scala` `selector`). It resolves prefix `scalatest` by loading class `scalatest$`, then `scalatest.package$`, the package object, from the meta-build's class loader (`core/resolve/src/mill/resolve/Resolve.scala` `resolveRootModule`, at Mill tag `1.3.1`).
- That resolution was read from source, not run. An integration test runs `mill scalatest/test core.test` on a fixture build that adds the plugin by its header line.

### The run's PID and process start time

The `runStarted` event's start time must equal `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM exactly, not closely (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

- The emitter reads both from `ProcessHandle.current()` by reflection. The read is in core's JVM sources only; Scala.js and Native are out of scope.
- It looks each method up on the interfaces `java.lang.ProcessHandle` and `java.lang.ProcessHandle.Info`, never on the handle's runtime class, whose methods are not accessible (`research/jvm-pid-and-start-time.md` § 2).
- `RuntimeMXBean.getStartTime()` is not a fallback: it records when the JVM finished initialising and never matched (`research/jvm-pid-and-start-time.md` § 3).
- A runner given the runner argument fails at construction where the JVM has no `ProcessHandle` or `startInstant()` is empty (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "The end of a run's event stream").
  - Mill 1.x supports user-defined modules down to Java 11 only, and its test worker's entry point is Java 11 bytecode in Mill 1.1.10 and 1.3.1 (`research/jvm-pid-and-start-time.md` § 1). No worker under the test command runs without `ProcessHandle`.

### A module's own ScalaTest arguments

The test command reads the module's arguments from Mill's `TestModule.testArgsDefault`, where `testForked` takes them, and passes the worker those `../SPECIFICATION.md` R-125 allows.

- A flag cannot be told from a value by its leading `-`: in `-z -o`, `-o` is a test-name substring. The split therefore needs the arities of `org.scalatest.tools.ArgsParser.parseArgs`, but `ArgsParser` is `private[tools]`, only sorts arguments, and throws on one it does not recognise.
- Core's argument splitter (`../ARCHITECTURE.md` § Modules § ScalaTest core) does not throw. It returns an argument whose flag `parseArgs` does not recognise ungrouped, under no flag. The test command fails on the first such argument and names it.
- The splitter keeps its own table of each flag's arity. A test runs `parseArgs` and the splitter over every flag `parseArgs` recognises, each followed by values that look like flags, and requires the same split.

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

This serves `specification/runs-and-test-structure-executions.md` AC-141 to AC-150.

- Assembly keeps each node by its ID with its events, grouped by execution number, apart from its suite's structure. A further `structure` event (§ The test event log on the JVM § The format) replaces the scopes and tests of its root suite.
  - A node the new structure drops keeps the parent, declared position and label it last had, and sorts after any node the latest structure holds at that position.
- Assembly orders a suite's children in three groups:
  - its scopes and tests, by the `position` its latest `structure` gives them;
  - the nested suites its first `structure` gave it, by `position`, before or after the scopes and tests as that structure had them;
  - then each nested suite a later `structure` event added, in the order those events arrived.
- A scope's or test's event gives its parent's execution number with its own (§ The test event log on the JVM § Executions). Assembly puts the node's execution under that execution of its parent.
  - It reads nothing from the order of scope events: under `RandomTestOrder`, a distributor or `AsyncEngine`, a scope can close before its tests start.

### The human-readable writer's reuse of `StringReporter`'s formatting

The `org.scalatest.tools.StringReporter` companion builds each line from a live `Throwable` (`getClass.getName`, `isInstanceOf[StackDepth]`, `isInstanceOf[PropertyCheckFailedException]`, `getCause`, `getStackTrace`), and returns whole lines, indented and in one colour.

- Its line-building functions take a throwable view in place of a `Throwable`:
  - the error lines (`stringsToPrintOnError`, its nested `getStackTrace`, and `withPossibleLineNumber`);
  - `Reporter.messageOrThrowablesDetailMessage` (`object Reporter` in `org/scalatest/Reporter.scala`, which `stringsToPrintOnError` calls for a blank message);
  - the text of info, alert, note and markup messages.

  The functions stay where they are, in objects already `private[scalatest]`. The no-error lines (`stringToPrintWhenNoError`) take no throwable and keep their parameters.
- The view is `private[scalatest]`, in `org.scalatest.tools`. A wrapper supplies it for a live `Throwable`, and core's decoded throwable supplies it too. It holds what the decoded throwable holds (§ The test event log on the JVM § The format), except its frames' fields and its suppressed throwables. For a `StackDepth` it also holds the failed code's stack depth, which only the live wrapper fills.
- The functions return text without indentation or colour. An event's first line is the text its caller passes. The stack-trace function returns each throwable of the cause chain as its first line, `Cause: ` included for a cause, with its frame lines, `...` included.
- `StringReporter` adds the indentation it adds now, the formatter's indentation level or else one level, to every line after the first, and colours each line with its event's colour.
  - Its output stays byte-identical, and so does that of `Framework`'s `SbtLogInfoReporter` and the Scala.js and Native `TaskRunner`, which call `fragmentsForEvent` (`../SPECIFICATION.md` § Scope § Out of scope, R-003).
- The writer passes a label's text without the formatter's leading indentation, indents each line by its depth in the tree (`specification/layout.md` AC-009 to AC-011), and composes multi-colour lines (AC-026, AC-027). It adds the suffixes, lines and marks `../SPECIFICATION.md` R-106 adds to 3.2.20's text.
- The writer calls the stack-trace function with `S` and `F` off (`../SPECIFICATION.md` R-106). The decoded throwable therefore leaves the failed code's stack depth absent: only `StringReporter`'s `S` path reads it, and `s` will never need it (`../SPECIFICATION.md` R-128).
- A characterisation test proves `StringReporter` byte-identical. It is written and passes before the change, and passes unchanged after it.
  - It compares the fragments, text and colour, that `fragmentsForEvent` and `summaryFragments` return for every event kind they format, formatted and with `U`, against text recorded from the code before the change. It turns each of `D`, `S`, `F` and `V` on and off, and for the end-of-run summary, which formats a throwable only in its reminders, each of `I`, `T`, `G` and `K`.
  - Its throwables are a non-`StackDepth` throwable of more than ten frames with a non-`StackDepth` cause, a `StackDepth` exception with a cause, and a `PropertyCheckFailedException`. Their frames are fixed with `setStackTrace`, and each `StackDepth` exception's `source.Position` is fixed. One failure carries analysis lines.
  - The existing `StringReporterSuite`, `StringReporterAlertSpec` and `StringReporterSummarySpec` stay. They print no non-`StackDepth` frames, no `Cause:` line and no `PropertyCheckFailedException`, so they cannot prove the change alone.

## Stdout

- The test command writes human-readable output through `Task.log.streams.out`, whose lines reach stdout whole, with Mill's prefix (`../research/emitter-plugin-spike.md` § 8.1, § 8.2).
- It reads Mill's colour decision from `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`). `Task.log.prompt` is `private[mill]`, so the class that reads it is in a package inside `mill` (`../research/mill-runner-progress-without-trait.md` § 5.1).
  - The value was `false` under ConPTY and MinTTY (`../research/mill-runner-progress-without-trait.md` § 5.2), so there human-readable output is uncoloured unless Mill is given `--color true`.
- The discovery runner puts nothing on the console, so the test command need not silence it (`../SPECIFICATION.md` R-072, R-044), provided discovery is given no reporter argument, as R-125 withholds the module's.
  - `org.scalatest.tools.Framework` builds `SbtLogInfoReporter` only in a task's `execute`, which discovery never calls, and `Framework.runner` drops the standard-out reporter configuration from those its runner dispatches to.
  - `ScalaTestRunner.done` returns ScalaTest's end-of-run summary rather than printing it. Mill's `TestRunnerUtils.getTestTasks0` (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` at Mill tag `1.3.1`) ends with `runner.done()` and discards that summary; only Mill's run path prints it.

## The test event log on the JVM

- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it must accept the runner argument.
- Core's runner argument definition (`../ARCHITECTURE.md` § Modules § ScalaTest core) is one `private[scalatest]` definition in `org.scalatest.tools`, shared by `ArgsParser` and the library's **Build the runner argument** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library). The event log file is in the worker's `processFolder`. The test command adds the argument that operation returns, unread, to the worker's arguments.
- `org.scalatest.tools.Runner.run`, and the runner `org.scalatest.tools.Framework.runner` constructs, each write the `runStarted` event once they have parsed the runner argument, before `RunStarting` (`specification/test-event-log-structure.md` AC-094).
- The emitter writes the `runStarted` event to a file of another name in the same folder, moves that file to the event log's name with `java.nio.file.Files.move` and `ATOMIC_MOVE`, then opens it to append every later event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
  - Event log reading never reads the other name, so a worker that dies before the move holds no event log file.
  - In the same folder, the move is one rename within one file system.
  - The `runStarted` event precedes every other event, so nothing is appended between the move and the reopening.
- The emitter's one write lock (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin) is taken by:
  - the run's own thread;
  - the dispatcher's thread;
  - test threads, including for messages a test's informers hand straight to the emitter;
  - the `-W` slowpoke timer's thread.
- The places that hand off an event sent outside every structure wrapper (`../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter):
  - `org.scalatest.tools.Framework`'s runner: `RunStarting` in its construction, and `RunCompleted` in `done`.
  - `org.scalatest.DispatchReporter`'s slowpoke timer under `-W`: its `AlertProvided`, which has `nameInfo = None` (`specification/test-event-log-structure.md` AC-121). The timer also passes that same event to the `SuiteSortingReporter` that `Framework` registers with `registerSlowpokeReporter`. From there it reaches at most a task's `SbtLogInfoReporter`, through no wrapper, and is not handed off again.
  - `org.scalatest.tools.Runner.doRunRunRunDaDoRunRun` and its catches: `RunStarting`, `RunCompleted`, `RunStopped`, `RunAborted`, `DiscoveryStarting` and `DiscoveryCompleted`.
  - `org.scalatest.tools.SuiteRunner` running each `DiscoverySuite` that `Runner.genDiscoSuites` builds: that suite's `SuiteStarting`, its `SuiteCompleted`, and the `SuiteAborted` it sends in its `Failure(ue)` branch and its `NotAllowedException` and `RuntimeException` catches.
- Not handed off (`SPECIFICATION.md` § Scope § Out of scope):
  - the `AlertProvided`s that `Runner.readMemoryFiles` sends for `-A`;
  - the events `Framework` relays from a sub-process sbt forks;
  - the run events of the reruns that `Runner`'s `-g` graphical reporter starts through `RunnerJFrame`, `SuiteRerunner` and `TestRerunner`.
- Event log reading polls each file every 10 ms, the only interval measured (`../research/jvm-file-channel.md` § 2).
- The handle **Decode** returns (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is a public opaque type in the library over core's decoded event, whose classes are `private[scalatest]`.
- A suite's structure takes each scope's and test's label from the style's registration tree, before any of them runs. The structure precedes the suite's first event (`specification/test-event-log-structure.md` AC-096), and a test that never starts (AC-097) or never closes (`TestStarting` carries `MotionToSuppress`) has no event giving its label. It builds each as `Engine.runTestImpl` and `runTestsInBranch` do, in `Engine` and `AsyncEngine`, and drops the leading indentation:
  - a test's from `Suite.getIndentedTextForTest` over its text with its parent's child prefix, with the `includeIcon` its style's `runTest` passes, or `true` for a test the filter ignores, as `TestIgnored`'s formatter has it. That flag is `false` only in `AnyFeatureSpecLike` and `FixtureAnyFeatureSpecLike` (`specification/test-event-log-structure.md` AC-103);
  - a scope's from `getIndentedTextForInfo` over its text with its parent's child prefix, with no icon.
- A suite with no registration tree, one that overrides `testNames` and `runTest` itself, yields a test node for each name of `testNames` the filter keeps, in `testNames`'s order, and no scope. Each takes its name from `testNames`, never from an event's `testText`: `Suite.runTests` sends a test's name as `testText` only in the `TestIgnored` of a test the filter ignores, and otherwise the suite's own `runTest` may send any `testText`, or no event at all. Each is labelled as `Suite.runTests` labels a test it ignores, by `getEscapedIndentedTextForTest(testName, 1, true)` without the leading indentation.
- `Framework`, `Runner` and `DiscoverySuite` run a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` as a `ConfigMapWrapperSuite`, which has no registration tree and forwards the suite's name, ID, test names, tags and nested suites to its `private lazy val wrappedSuite`, the wrapped class constructed with `Map.empty`. The suite's first structure is built from `wrappedSuite`, reached through a `private[scalatest]` accessor on `ConfigMapWrapperSuite` (`specification/test-event-log-structure.md` AC-130). The instance that runs then gives its own (§ Further `structure` events).
  - `wrappedSuite` is built with `Constructor.newInstance`, so a throwing constructor reaches its first reader as an `InvocationTargetException`. In `org.scalatest.tools.Framework`'s `ScalaTestTask.execute`, that reader is the first `suite.suiteId`, outside any `try`, before `SuiteStarting` (`specification/test-event-log-structure.md` AC-132). Under `Runner` it is the `expectedTestCount` sum, inside `doRunRunRunDaDoRunRun`'s `try`, whose catch sends `RunAborted` (`specification/test-event-log-structure.md` AC-133).
  - With the emitter on, where the suite is a `ConfigMapWrapperSuite`, `execute` reads `suite.suiteId` once, before `registerReporter` and `runSuite`, in a `try`. Its catch builds a `DeferredAbortedSuite(suiteClass.getName, suiteClass.getName, e)`, hands the emitter that suite's structure and the `SuiteStarting` and `SuiteAborted` `runSuite` would send for it, assigned to its node and passed to no reporter, then rethrows `e`.
- Each suite's class name is `Suite.getSuiteClassName`'s, which for a `DeferredAbortedSuite` is the class whose construction failed. A `ConfigMapWrapperSuite`'s is instead its wrapped class's (`specification/test-event-log-structure.md` AC-104, AC-130). `SuiteAborted.suiteClassName` is never read: `Framework`'s catch around `suite.run` sets it to the suite's name.

### Suite instances and their nodes

An event's node is found from the suite instance that sent it, never by reading `args.reporter` (`TECHNICAL-DESIGN-DECISIONS.md` "An event's node is found from the suite instance that sent it…"). `ParallelTestExecution.runTests`, `AsyncEngine.runTestsImpl`, `RandomTestOrder.run`, `StopOnFailure.run` and `org.scalatest.tools.Utils.wrapReporterIfNecessary` each replace `args.reporter` before a test runs, as can a user's `run`, `runTests` or `runTest` override (`specification/test-event-log-events.md` AC-105, `specification/test-event-log-structure.md` AC-128).

- The emitter keeps a node table for each suite node, read and written under its write lock. It holds:
  - the suite's node ID, `suiteId` and class name;
  - its tests' IDs by test name, which `SuperEngine.registerTest` keeps unique within a suite;
  - its scopes' IDs by path: from the suite down, each scope's text with its place among the scopes of that text under the same parent, since `SuperEngine.registerNestedBranch` accepts sibling scopes of one text;
  - its nested suites' tables by position in `nestedSuites`, each with the instance it was read from.
- A `structure` event for a suite whose table exists takes each test's and scope's ID from the table where the table holds it, and mints the rest. A test therefore keeps its ID under another parent, as where a wrapper suite's flat tests are a delegate instance's scoped ones.
- The emitter keeps an identity map from suite instance to its registrations. A registration names a node table, or none while the instance is *unplaced*: running, with its suite node not yet known.
- Each site below registers an instance against a table, and removes the registration when the instance's run throws or its `Status` completes. Removal waits for the `Status` because under `AsyncEngine` and a distributor a suite's tests can run after its `run` returns:
  - the site that installs a top-level wrapper registers the top-level suite, against the table its structure mints;
  - `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` register each nested suite they run, against the table at its position (§ Wrappers);
  - `OneInstancePerTest.runTest`, `ParallelTestExecution.runTest` and `RandomTestOrder.runTest` register the instance they create with `newInstance`, against their own instance's table, naming the execution of the run that called them (§ Executions). `ParallelTestExecution.runTest` removes it when the distributor's `Status` completes. `RandomTestOrder.runTest` removes it when its own `Status` completes, after `RandomTestOrder.run` has run the deferred instance;
  - `ConfigMapWrapperSuite.run` constructs the wrapped class from `args.configMap` and runs it, as in 3.2.20. It registers that instance against the `ConfigMapWrapperSuite`'s own table: the instance is that suite.
  - Where the instance at one of these sites is itself unplaced (below), it has no table. A per-test instance it creates shares the creator's registration and is placed with it. A nested suite it runs is registered unplaced, and is placed below the outer suite once that is placed.
- An instance none of those sites registered registers itself, unplaced, at the first site of its run that looks it up: `RandomTestOrder.run`, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl`, `PathEngine.runPathTestsImpl`, `Suite.run` or `TestsBeforeNestedSuites.run`, else `runTestsImpl` or `Suite.runTests`. That site removes the registration as above. Such an instance is:
  - one user code creates and runs: a test body's `new HelperSuite().run(None, args)`, the instance a custom `@WrapWith` wrapper runs, or one a `run` override builds;
  - a nested suite run by a `runNestedSuites` other than those two, such as a user's override, `Doc.runNestedSuites` or `DocSpecLike.runNestedSuites`, or run at a position its outer suite's table lacks.
- An unplaced instance gets its wrappers as a placed one does (§ Wrappers). The emitter holds the events they hand off, in the order handed off, and writes none of them yet.
  - The site that registers the instance puts its suite-level wrapper in the `Args` it passes on, whichever site that is. `runImpl` and `runPathTestsImpl` also put that wrapper over the reporter their suite-level informers report to. `ParallelTestExecution.runTests` and `AsyncEngine.runTestsImpl` build their sorters after `runImpl`, find the instance registered, and put its wrapper over their own sorter as for any instance.
  - The registration keeps the instance's `suiteId` and `suiteName` from the first read 3.2.20 makes of each in that run: in a send helper of `object Suite`, in `Suite.run` or for the filter. The emitter reads neither itself.
  - The first held event to reach a wrapper of a placed instance (§ Wrappers) places the unplaced one, under the write lock, below that wrapper's suite node `S`, once its registration holds its `suiteId`. That `suiteId` is:
    - `S`'s own: the instance is `S`, and takes `S`'s table;
    - that of a nested suite of `S`: the instance is that nested suite. Where several share the `suiteId`, it is the one read from this instance, else the first in declared order that has no `SuiteCompleted` or `SuiteAborted`;
    - any other: the emitter mints a nested suite node directly under `S`, after `S`'s other children, with the registration's `suiteId` and `suiteName` and the instance's `Suite.getSuiteClassName`. Several such nodes sit in the order minted. A later run of an instance with that `suiteId` below `S` then meets the case above.
  - The emitter then writes the instance's `structure`, where its suite node is new or its tree differs from the table (§ Further `structure` events), and its held events in the order handed off. It writes each later one as it is handed off (`specification/test-event-log-executions.md` AC-135 to AC-138, AC-155).
  - A held event that first reaches a wrapper of another unplaced instance, as a nested suite's reaches its outer suite's, waits for that instance. Its own instance is then placed below that instance's suite node.
  - Events still held when the registration is removed are dropped: none reached a wrapper of a placed instance.

### An event's node

- Each engine site that sends an event passes the event's tree node and executions (§ Executions) to the send helper of `object Suite` it calls (`reportTestStarting`, `reportScopeOpened`, `reportInfoProvided` and the rest, which already take `theSuite`). With the emitter on, the helper records these with the instance, against the event's identity in the emitter's state, before it reports the event. This record is the event's *origin*. The event is unchanged (`../SPECIFICATION.md` R-002), and the emitter drops the origin once it writes the event.
  - The tree node is the test's name for a test's event, and the `DescriptionBranch` that `runTestsInBranch` holds for a scope's event or for a message registered in that scope. A suite-level message has none.
  - A scope's node is its branch's path in the table. Nothing reads the order in which scopes open and close.
- A wrapper names its registration's table when it hands an event off. The emitter assigns the event:
  - with an origin, to the origin's tree node in the table of the origin's instance. Where that instance has several registrations, as one instance nested twice and run by a distributor, it uses the wrapper's table;
  - without one, as for an event user code builds or the `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` a `runNestedSuites` sends, from the event's own data at or below the wrapper's suite. Its `suiteId`, or a message's `nameInfo`'s, names the suite, and its test name names the test in that suite's table. Sibling nested suites sharing a `suiteId` are told apart as an unplaced instance's are (§ Suite instances and their nodes).
- An event without an origin whose data names a suite or test no table holds is still assigned to its own node, and a further `structure` precedes the event:
  - a `suiteId` no suite at or below the wrapper's has is given a minted nested suite node, as an unplaced instance's is, with the `suiteName` and `suiteClassName` the event carries;
  - a test name its suite's table lacks is given a test node directly under the suite, named and labelled as a test of a suite with no registration tree is.
- A message with no origin whose `nameInfo` is `None` is assigned to the wrapper's own node, since nothing identifies another (`../SPECIFICATION.md` R-097; `specification/test-event-log-events.md` AC-107, AC-151).
- A `SuiteAborted` whose `suiteClassName` is `DistributedTestRunnerSuite`'s is assigned to the node of the wrapper that hands it off, the test that suite ran (`specification/test-event-log-structure.md` AC-126).

### Further `structure` events

- A further `structure` is built without calling an overridable member of the suite. `testNames`, `tags`, `nestedSuites` and `expectedTestCount` are user code that can throw, and inside `run` 3.2.20 calls them only where it needs them (`../SPECIFICATION.md` R-002).
  - For a suite with a registration tree it is built from `atomic` alone:
    - its nodes and labels are built as the first structure's are;
    - it is filtered by `args.filter` over `tagsMap`, with the class's tag annotations applied as `Suite.autoTagClassAnnotations` applies them but to the tree's own test names. A suite whose `tags` override keeps a test that this filtering drops gains the test by the last case of the next list;
    - it takes the table's `suiteId` and class name. A suite therefore keeps the class name its first `structure` gave, whichever instance runs.
  - For a suite with none, `Suite.runTests` builds it from the names `filter(theTestNames, tags, suiteId)` returns to its own loop.
  - It holds the suite's scopes and tests and no nested suite, which `atomic` does not hold. Each nested suite keeps the structure and place it has (§ The format).
- It is written, before the first event that needs it:
  - by the first engine site a run of a placed instance reaches, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl` or `PathEngine.runPathTestsImpl`, else `runTestsImpl`, where the instance's tree after filtering differs from the table in any node, parent, position or label. It therefore precedes every scope and test event of that instance. This is the site for the instance `ConfigMapWrapperSuite.run` runs (`specification/test-event-log-structure.md` AC-131), an instance `newInstance` creates and a nested suite's instance where `nestedSuites` returns new instances on each call;
  - by `Suite.runTests`, likewise, for a placed instance with no registration tree;
  - when an unplaced instance is placed, where its suite node is new or its tree differs from the table, from the tree or names the sites above read at its run and kept in its registration. A nested suite placed by its `SuiteStarting`, before its own run reads its tree, gets a `structure` holding the suite alone, and the first case then adds its scopes and tests;
  - by an engine site about to send an event for a test or scope of its tree that the table lacks, such as a test the filter drops that a `runTests` override runs. It adds that node and the scopes around it to the suite's latest structure.

### Wrappers

- `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` install each nested suite's wrapper before its `SuiteStarting`. In the sequential branch, `callExecuteOnSuite` sends the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` through the outer suite's reporter and builds the nested suite's `Args` only after `SuiteStarting`. The wrapper takes that reporter's place, both for those events and in the `Args`. In the `-P` branch of `Suite.runNestedSuites`, the wrapper goes in the `Args` given to the distributor, whose `SuiteRunner` sends those events.
  - Where `thisSuite` is a `DiscoverySuite`, each nested suite's wrapper is a top-level wrapper (`specification/test-event-log-structure.md` AC-099).
  - Otherwise the site looks up `thisSuite`, and each nested suite it runs takes the table at its position in `nestedSuites`. Matching by position keeps structure and run in agreement where `nestedSuites` returns new instances on each call.
- `Engine.runTestsImpl`, `AsyncEngine.runTestsImpl` and `Suite.runTests` look up `theSuite` or `this`, and install the test's structure wrapper, which names that registration and the test's name, in the `Args` they pass to `runTest`, not inside `runTestImpl`. `ParallelTestExecution.runTest` builds both the `Args` it gives the distributor and the `DistributedTestRunnerSuite` running that test from those `Args`. When that `DistributedTestRunnerSuite` throws, the distributor's `SuiteRunner` sends its `SuiteAborted` through those `Args`' reporter, so the event reaches the test's wrapper first (`specification/test-event-log-structure.md` AC-126). With the wrapper inside `runTestImpl`, the event would reach first the suite's wrapper that `ParallelTestExecution.runTests` puts over its `TestSortingReporter` (below).
  - Given a test name and a test sorter, `ParallelTestExecution.run`, `RandomTestOrder.run` and `AsyncEngine.runImpl` replace the reporter with a `TestSpecificReporter`. Each looks up `this`, and wraps the `TestSpecificReporter` in that test's wrapper, so the test-specific instance's events pass through it (`specification/test-event-log-structure.md` AC-100, AC-129). `AsyncEngine.runImpl` does so only in an async suite that mixes in neither `ParallelTestExecution` nor `RandomTestOrder`, for the instances `OneInstancePerTest.runTest` creates.
- `ParallelTestExecution.runTests`, `RandomTestOrder.run` and `AsyncEngine.runTestsImpl` each look up `this` or `theSuite` and put a wrapper for the suite over the `TestSortingReporter` they create. The sorter holds back the suite's own events, such as `ScopeOpened`, `ScopeClosed` and a scope's messages, until the tests before them complete. Each test's wrapper sits ahead of the sorter and hands the test's events off at once. Through the sorter alone, a scope's message would reach the emitter after children declared after it had started, and be placed after them (§ Critical code paths § A message among its node's children).
- A site whose lookup finds several registrations installs no wrapper. The instance's events are then handed off by the wrapper its registering site installed, which names the table (§ An event's node).
- An event reaches every wrapper between its sender and `DispatchReporter`: its test's, its suite's and each enclosing suite's, interleaved with other reporters such as `TestSortingReporter`, `CatchReporter`, `StopOnFailureReporter` and a user's. Only the innermost wrapper it reaches hands it to the emitter.
  - The emitter keeps an identity set of the events wrappers have handed off (`TECHNICAL-DESIGN-DECISIONS.md` "A wrapper tells an event another wrapper has handed to the emitter…"). Under the emitter's write lock, a wrapper hands off only an event not in the set, and adds it.
  - A wrapper that receives an event already in the set still names its registration to the emitter, under the write lock. Where the event is held, that places its unplaced instance (§ Suite instances and their nodes).
  - The top-level suite's wrapper, the last wrapper any event of its suite reaches, removes the event from the set instead of adding it.
- `wrapReporterIfNecessary` adds a `CatchReporter` over any reporter that is not one, so it adds one over a wrapper put where 3.2.20 passes a `CatchReporter` on. That layer forwards every event. A wrapper catches whatever its own hand-off throws and then passes the event on, so the layer never prints `Resources.reporterThrew` for the emitter, and no reporter misses an event (`../SPECIFICATION.md` R-002).

### Executions

- Core mints an execution as an object in the emitter's state. The emitter gives it a node's next number, from 1, when it first writes an event naming it for that node, as the node's own or as its parent's.
- `Engine.runTestImpl` and `AsyncEngine.runTestImpl` mint one on each call, for the test's `TestStarting`, its closing event and its messages. A `runTest` override that calls `super.runTest` twice therefore gives its test two (`specification/test-event-log-executions.md` AC-140).
- `traverseSubNodes`, `runTestsImpl` and `Suite.runTests` mint one for each `TestIgnored` they send.
- The first site a run of an instance reaches mints one for the run and keeps it in the instance's registration. That site is `runImpl` or `runPathTestsImpl`, else `runTestsImpl`; for a suite with no registration tree it is `Suite.run`, else `Suite.runTests`.
  - Every scope whose events `runTestsInBranch` sends in that run takes it, each numbering it among its own, and so does the suite, for the run's suite-level messages. Where the suite's `SuiteStarting` opened an execution that no run has taken, the run takes that one.
  - The run of a per-test instance mints none: it uses the execution its registration names (§ Suite instances and their nodes).
- An origin for a scope's or test's event names the node's own execution and its parent's, which is the run's, since the suite and every scope take the run's. The emitter writes both numbers (§ The format), so a test's execution sits under the scope execution it ran in where runs of one suite overlap.
  - The later sites of a run, `runTestsImpl`, `runTestImpl` and `Suite.runTests`, find the run's execution in their instance's registration. `runTests` and `runTest` are overridable and pass only `Args`, which carries no execution.
  - Where one instance has two runs in flight, those sites take the execution minted last. For a suite with a registration tree 3.2.20 already treats this as an error: the `runImpl` or `runPathTestsImpl` whose informers the other run replaced throws `ConcurrentModificationException` when its run ends.
- An execution reaches the emitter in its events' origins, and in the messages a test's informers hand over (§ A test's messages).
- A test's execution closes with its closing event, a scope's with `ScopeClosed` or `ScopePending`, and a suite's with `SuiteCompleted` or `SuiteAborted`. A `TestIgnored` opens and closes its own.
- For an event with no origin, the emitter writes (`specification/test-event-log-events.md` AC-134, AC-152, AC-153; `specification/test-event-log-executions.md` AC-156):
  - the execution of its node that opened last and has not closed;
  - where none is open, a new one for an opening event (`SuiteStarting`, `TestStarting`, `ScopeOpened` or `TestIgnored`), and for any other event the node's latest, or a first one where it has none;
  - as its parent's, the parent's execution open when the event is handed off, else the parent's latest.

### A test's messages

- A test's `info` and `markup` reach reporters only in its closing event's `recordedEvents`, after its `note`s and `alert`s and with ordinals taken at the close (`Engine.runTestImpl`, `AsyncEngine.runTestImpl`). Neither the order events arrive in nor their ordinals give the order the test called them (`specification/test-event-log-events.md` AC-105). With the emitter on, the test's informer and documenter therefore also hand each message to the emitter when called. With the emitter off they behave as in 3.2.20.
  - The informer and documenter hand each message to the emitter with the execution `runTestImpl` minted, never to a reporter (`../SPECIFICATION.md` R-002). The emitter assigns it to the node that execution's `TestStarting` was assigned to, and holds it until that event is handed off.
  - Before reporting the closing event, `runTestImpl` adds it, under the write lock, to an identity set the emitter keeps. The emitter leaves the recorded events of an event in that set out of the event log, and removes the event from the set once it writes it.
  - The hand-off takes no ordinal from `tracker`, so every event reporters receive keeps its 3.2.20 ordinal (`specification/compatibility-and-library.md` AC-056). The event log carries no ordinal, since no output shows or uses one.
  - The message's timestamp is the time of the call (`specification/test-event-log-events.md` AC-105).
- The messages of a path-style test, one of `PathAnyFunSpec` or `PathAnyFreeSpec`, never pass through that hand-off. `PathEngine.handleTest` runs the test's body while its suite is constructed, before the suite's `structure` and `SuiteStarting`. It gives the messages to four recorders, `PathMessageRecordingInformer`, `PathMessageRecordingNotifier`, `PathMessageRecordingAlerter` and `PathMessageRecordingDocumenter` in `org/scalatest/ConcurrentInformer.scala`. Each keeps its own queue, with no order across the four and no call time. Only the informer's queue reaches `TestLeaf.recordedMessages`, and `Engine.runTestImpl`, replaying the stored outcome, adds only the informer's events to the closing event's `recordedEvents`.
  - The four recorders of one test also append each call to one shared call sequence. An entry holds what its recorder stores of the call, the recorder's event function and the time of the call. `registerTest` takes, and `TestLeaf` holds, that sequence beside the informer.
  - With the emitter on, `Engine.runTestImpl` replaying a path-style test hands the sequence's messages, in order, to the emitter after `TestStarting` and before the closing event, as the informer's are handed over at call time. Each message is built by its entry's event function, given a fresh `Tracker` in place of `tracker` so that it takes no ordinal from the run's. Its `timeStamp` is then set to the entry's call time.
  - The closing event's `recordedEvents` are built from `tracker` as in 3.2.20, emitter on or off, so the test's notes, alerts and markup still reach no reporter (`specification/compatibility-and-library.md` AC-056).

### Runs that pass no run-entry site

- `TestsBeforeNestedSuites.run` calls neither `super.run` nor its style's `runImpl`. It looks its instance up itself (§ Suite instances and their nodes), and `runTestsImpl` is its first engine site. In 3.2.20 such a suite has no suite-level informers, so it sends no suite-level message.
- A user's `run` that calls no `super.run`:
  - where it calls `runTests` or `runNestedSuites`, those sites look the instance up;
  - where it sends events itself, they have no origin and pass no wrapper of the instance. The innermost wrapper they reach assigns each from its own data (§ An event's node).
- `Doc.runNestedSuites` and `DocSpecLike.runNestedSuites` run each included suite with their own `Args` and send no `SuiteStarting` or `SuiteCompleted` for it. The suite registers itself unplaced and is placed by its `suiteId`.
- A helper suite run from `beforeAll()`, which is given no `Args`, reports to whatever reporter the user gives it. Given the suite's captured `args.reporter`, it is placed below that suite. Given any other, its events reach no wrapper of a placed instance and are dropped (§ Suite instances and their nodes).

### The format

Each event is a JSON object (`TECHNICAL-DESIGN-DECISIONS.md` "Each event of a run's test event log is a JSON object."), encoded as `../JSON-LAYOUT.md` § Encoding gives, except that keys are written in the order this section gives. Escaping control characters in strings keeps each event to one line.

- A lone surrogate's `\uXXXX` escape decodes back to the same `char`.
- Integers are JSON numbers, read as `Long`, never through `Double`.
- An empty array omits its key.

Each event's first key is `item`, naming its kind. The events are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, and the keys of each, in order, are:

- The `runStarted` event: `item` `runStarted`; `pid`; `startTime`, the text `Instant.toString` gives, read back with `Instant.parse`, so it equals `ProcessHandle`'s exactly.
  - Core's codec takes and yields `startTime` as that text, since core also builds for Scala.js, which has no `java.time`. The emitter's JVM sources format it and the library parses it.
- A `structure` event: `item` `structure`; `nodes`, an array of the suite's nodes in depth-first pre-order, each node's children in declared order. A node's keys are `id`, `kind` (`suite`, `scope` or `test`), `parent` (its parent's `id`), `position` (its declared position, from 0), `name`, `label`, `suiteId` and `className`, each present only on the nodes `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin gives it. A node's `id` is an integer.
  - The first node is the event's root suite. A top-level suite has no `parent` or `position`. A nested suite that is the root has `parent`, and the `position` an earlier event gave it; one new to the event log has none (§ Critical code paths § A node's executions and its suite's latest structure).
  - A further `structure` event has the same form. Its root is a suite an earlier event holds, or a nested suite new to the event log under a suite an earlier event holds. It holds the root's scopes and tests whole, their `position`s from 0 among themselves, and none of its nested suites. A node an earlier event holds has the same `id`, and a suite the same `className`.
- A ScalaTest event:
  - `item` `event`;
  - `node`, the `id` of the node the event is assigned to, absent where it is assigned to the run;
  - `execution`, the number of that node's execution the event belongs to, from 1, present exactly where `node` is;
  - `parentExecution`, the number of the execution of the node's parent that this one ran in, present exactly where the node is a scope or test;
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
- holds a value of another JSON type than this section gives its key, `null` included;
- holds an integer that does not fit its field's `Int` or `Long`, or one below the least this section gives its key: 0 for a `position`, 1 for an `execution`, a `parentExecution` and a throwable's `id`;
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
- A dependency of a Scala 3 project on a ScalaTest+ artifact excludes upstream's groups. Its upstream ScalaTest would otherwise sit on the classpath beside the fork's, which no longer evicts it.
- The consolidation library is a JVM-only project of `project/DottyBuild.scala`, depending on its Scala 3 scalatest-core project. `scalatestDotty` aggregates it, so the sbt build publishes it with core, before the plugin's Mill build resolves both.
- A task of the library's project checks `specification/compatibility-and-library.md` AC-062. It publishes the library and the projects it depends on with `publishLocal`, resolves the published library transitively from the local Ivy repository and Maven Central alone, and fails on any dependency with group `com.lihaoyi` and a name beginning `mill-`.
- The plugin's Mill build publishes `scalatest-mill` under `com.edc` with `publishLocal`.

## Testing

### Runs the criteria describe

The criteria whose `Given` is an event log holding events (`SPECIFICATION.md` § Acceptance criteria) are tested without Mill and without the codec (`TECHNICAL-DESIGN-DECISIONS.md` "The consolidation library's tests build each run with a test-only builder…"). Those tests build each run with a test-only builder in the library's tests:

- A test gives the run as one ordered list: the criterion's events, in the notation of `SPECIFICATION.md` § Acceptance criteria, with the run's captured lines at the places they are fed.
- The builder derives only each node's ID, its parent's ID and its declared position, from the `structure` events, and resolves each node an event names to its ID. It emits every event and captured line in the order given, in the form core's decoder yields, so a test can place a `structure` event, a ScalaTest event or a captured line anywhere the criterion puts it.
- It gives each event the execution number, and each scope's or test's event the parent's execution number, that the notation's `execution <n>` and `parent execution <m>` rules give it.
- A `structure` block for a suite an earlier block holds becomes a further `structure` event. The builder keeps the ID of each node that `SPECIFICATION.md` § Acceptance criteria makes the node of an earlier block, and mints the rest.
- A suite a `structure under suite` block adds gets its parent's ID and no position (§ The test event log on the JVM § The format).

The emitter's and the decoder's criteria are those that state events, in the parts of `specification/test-event-log.md`. Among them, `specification/test-event-log-events.md` AC-109 writes lone surrogates, and `specification/test-event-log-structure.md` AC-100 and AC-121 write events from concurrent threads. Each side of the codec is tested against the format on its own (`../decisions/architecture-test-event-log.md` "Each side of core's codec is tested on its own…"), so each such criterion has two tests. Both build the criterion's events with the same builder from its event list, and share expected text in the format, written by hand in the test: a whole line for each event the criterion states, with any value where the criterion fixes none.

- The emitter's test runs the criterion's suites with the runner argument. It compares the lines the run writes with the expected text, only on the keys holding what the criterion fixes and under the *is* and *holds in order* rules for events (below). It compares node IDs through the structures that mint them. It then decodes the lines with core's codec and compares the decoded events with the built ones.
- The decoder's test feeds core's decoder the expected text and compares the events it yields with the built ones.

One further decoder test, tied to no criterion, feeds the decoder text whose last line is torn, from which it yields no event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

Each comparison of events:

- Each event is compared only on what the criterion fixes: a `structure` event on its whole tree, a ScalaTest event on its node, its class and the fields the criterion names.
- Where the criterion says the event log *is* the events, the decoded events must be exactly those built. Where it says the log *holds* them *in this order*, the built events must be a subsequence of the decoded ones.

A drift between builder and decoder in what a criterion fixes then fails those tests, as well as the criteria run under Mill.

Each test of human-readable output compares the whole output, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour.

### Comparison with ScalaTest 3.2.20

This serves `specification/compatibility-and-library.md` AC-056 and AC-057, `specification/test-event-log-events.md` AC-114 and AC-116, and `specification/runs-and-test-structure-nodes.md` AC-073.

- AC-056 and AC-057 run in a test project of `project/DottyBuild.scala`.
  - The compatibility fixture and the recorders (the `-C` recording reporter, and a test-interface event handler and loggers that record what they receive) are the sources of an sbt configuration of the project's own that extends neither `Compile` nor `Test` and whose one library dependency is `org.scalatest:scalatest` 3.2.20. They compile there once, so both runs load the same compiled classes. `../SPECIFICATION.md` R-004 lets the fork run them.
  - 3.2.20 is on no classpath that also holds the fork: the two share packages and class names, so on one classpath each class loads from whichever jar comes first. The test itself, in `Test`, is given three classpaths: that configuration's compiled classes, its resolved classpath (3.2.20 and its dependencies), and the runtime classpath of the fork's `scalatestDotty` and `scalacticDotty` projects.
- The test forks one JVM for each run, on the fixture's compiled classes plus either 3.2.20's resolved classpath or the fork's, as IntelliJ's forked mode and a build tool's test worker each start a JVM of their own. No JVM-wide state, such as `System.out` or a system property, then passes from one run to the other.
- In each JVM the recorders write each event to a file as one line, holding every field but timestamps, durations and thread names, and of each throwable only what `../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x compares. The test compares the two runs' files line by line.
- A test of AC-114 runs in forked-JVM pairs set up as AC-056's, launched through `Runner.run` and through `Framework`, with the emitter off and on.
  - Each pair runs once for each of several reporter argument lists, which between them turn on every character `ReporterConfigParam` gives `-o`, `-e` and `-f`.
  - The test reads each JVM's stdout and stderr from the forked process, each `-f` file from a directory of that JVM's own, and, under `Framework`, the lines the recording loggers receive.
  - Before comparing, it replaces each run of stack-frame lines, and each `stackTraces` and `depth` value in a `J` reporter's JSON, with one placeholder, and blanks durations, timestamps and thread names.
- AC-116 runs in a Scala.js and a Native test project of `project/DottyBuild.scala`. The fixture and the recorders compile once against 3.2.20's artefacts for the platform, and the test links them twice, once with 3.2.20's and once with the fork's. There the recorders print each event to stdout as one line, in the form the JVM's recorders write it, and print each line the loggers receive, normalised as for AC-114. The test compares the two runs' lines.
- AC-073 runs among the plugin's integration tests under Mill's testkit. Its fixture build also holds a module over the same suites that tests with 3.2.20 and passes `-f` in its own ScalaTest arguments, configured as `SPECIFICATION.md` § Acceptance criteria requires of every JVM test module of a criterion's build; Mill's `testForked` on that module writes the file the criterion compares with.

### MiMa

`specification/compatibility-and-library.md` AC-058 runs the MiMa check on every project § Publishing covers. The check fails for a covered project that resolves no previous artifact.

The check runs with no problem filter, since a filter would let it pass without the compatibility it proves:

- The `mimaBinaryIssueFilters` upstream's build sets on covered projects are removed. They excuse upstream's own changes since 3.2.19, and against 3.2.20 they could only hide a break the fork makes.
- Those it sets on Scala 2 projects stay as upstream wrote them (§ Publishing). The fork runs no check on those projects.
- Review rejects any filter this feature adds.
