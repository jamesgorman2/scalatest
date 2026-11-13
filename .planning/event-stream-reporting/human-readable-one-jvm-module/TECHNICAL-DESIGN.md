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
- The test command, reached as `mill scalatest/test` with nothing in the build referring to it, must split a module's own ScalaTest arguments as ScalaTest's JVM runner parses them, turn a worker's stdout and stderr byte chunks into lines, and read Mill's colour decision through `private[mill]` API.
- The human-readable writer must reuse `StringReporter`'s formatting without changing what `StringReporter` prints, though that formatting reads a live `Throwable` the decoded event stream cannot rebuild and returns lines already indented and coloured.
- Every artifact must move to group `com.edc` while the build's MiMa check still compares against upstream's.

## Critical code paths

### The test command's entry point

- The test command is the `test` command of a top-level `ExternalModule` object in a named package of the plugin. Beside it, `package object scalatest extends mill.api.ExternalModule.Alias(<that object>)` gives it the prefix `scalatest`, as Mill 1.3.1's `package object spotless extends ExternalModule.Alias(SpotlessModule)` does (`libs/javalib/src/mill/javalib/spotless/package.scala`).
- Mill's selector parser accepts a single-segment prefix before `/` (`core/api/src/mill/api/internal/ParseArgs.scala` `selector`). It resolves prefix `scalatest` by loading class `scalatest$`, then `scalatest.package$`, the package object, from the meta-build's class loader (`core/resolve/src/mill/resolve/Resolve.scala` `resolveRootModule`, at Mill tag `1.3.1`).
- That resolution was read from source, not run. An integration test runs `mill scalatest/test core.test` on a fixture build that adds the plugin by its header line.
- If the alias does not resolve, the test command is instead `run` on an `ExternalModule` object `Tests` in top-level package `scalatest`, invoked as `mill scalatest.Tests/run`, Mill's documented `foo.Bar/qux` form, and `SPECIFICATION.md` § Scope changes with it.

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
- When its stream ends, a splitter appends the line it still holds, if it holds any bytes. Mill's call returns only once both reader threads have finished (`../research/mill-worker-channels.md` § 2.3), so the sequence is complete before the test command adds the test classes the worker took.
- A worker writes a piped stdout and stderr in the platform's native encoding unless its `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`. The splitter decodes with the charset Mill's JVM names in its `native.encoding` system property, or `Charset.defaultCharset()` where that property is absent, and replaces malformed bytes with U+FFFD rather than dropping them.

### A message among its node's children

This serves `specification/layout.md` AC-034.

- Assembly records, with each message assigned to a suite or scope, which of that node's children had sent the first event of an execution under the message's own (§ A node's executions and its suite's latest structure) when the message arrived.
- The human-readable writer puts the message after the last of those children in declared order and before the next. A child declared before that one but not yet reached, as under concurrent tests, therefore also comes before the message. Messages placed between the same two children keep the order they arrived in.

### A node's executions and its suite's latest structure

This serves `specification/runs-and-test-structure-executions.md` AC-141 to AC-147.

- Assembly keeps each node by its ID with its events, grouped by execution number, apart from its suite's structure. A further `structure` event (§ The test event log on the JVM § The format) replaces the scopes and tests of its root suite.
  - A node the new structure drops keeps the parent, declared position and label it last had. The writers show it, after any node the latest structure holds at that position, only while it or a node below it has an event.
- An event gives only its own node's execution number. Assembly puts a scope's or test's execution under the execution of its parent that was open when the child execution's first event arrived, else under the parent's latest to have started.
  - It reads nothing else from the order of scope events: under `RandomTestOrder`, a distributor or `AsyncEngine`, a scope can close before its tests start.
  - Where two runs of one suite overlap, a test's execution can therefore sit under the other run's scope execution.
- A suite's executions are not laid out apart. The writer lists each child's executions, whichever of the suite's they sit under, together under the suite's one name line.

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
- The writer passes a label's text without the formatter's leading indentation, indents each line by its depth in the tree (`specification/layout.md` AC-009 to AC-011), and composes multi-colour lines (AC-026, AC-027). It adds the suffixes, lines and marks `../SPECIFICATION.md` R-106 adds to 3.2.19's text.
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
  - the events `Framework` relays from a sub-process sbt forks, and the `RunAborted` it sends when it can no longer read them;
  - the run events of the reruns that `Runner`'s `-g` graphical reporter starts through `RunnerJFrame`, `SuiteRerunner` and `TestRerunner`.
- Event log reading polls each file every 10 ms, the only interval measured (`../research/jvm-file-channel.md` § 2).
- The handle **Decode** returns (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is a public opaque type in the library over core's decoded event, whose classes are `private[scalatest]`.
- A suite's structure takes each scope's and test's label from the style's registration tree, before any of them runs. The structure precedes the suite's first event (`specification/test-event-log-structure.md` AC-096), and a test that never starts (AC-097) or never closes (`TestStarting` carries `MotionToSuppress`) has no event giving its label. It builds each as `Engine.runTestImpl` and `runTestsInBranch` do, in `Engine` and `AsyncEngine`, and drops the leading indentation:
  - a test's from `Suite.getIndentedTextForTest` over its text with its parent's child prefix, with the `includeIcon` its style's `runTest` passes, or `true` for a test the filter ignores, as `TestIgnored`'s formatter has it. That flag is `false` only in `AnyFeatureSpecLike`, `FixtureAnyFeatureSpecLike` and the `FeatureSpecLike` and `FixtureFeatureSpecLike` that `project/GenSafeStyles.scala` generates from them (AC-103);
  - a scope's from `getIndentedTextForInfo` over its text with its parent's child prefix, with no icon.
- A suite with no registration tree, one that overrides `testNames` and `runTest` itself, yields a test node for each name of `testNames` the filter keeps, in `testNames`'s order, and no scope. Each takes its name from `testNames`, never from an event's `testText`: `Suite.runTests` sends a test's name as `testText` only in the `TestIgnored` of a test the filter ignores, and otherwise the suite's own `runTest` may send any `testText`, or no event at all. Each is labelled as `Suite.runTests` labels a test it ignores, by `getEscapedIndentedTextForTest(testName, 1, true)` without the leading indentation.
- `Framework`, `Runner` and `DiscoverySuite` run a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` as a `ConfigMapWrapperSuite`, which has no registration tree and forwards the suite's name, ID, test names, tags and nested suites to its `private lazy val wrappedSuite`, the wrapped class constructed with `Map.empty`. The suite's first structure is built from `wrappedSuite`, reached through a `private[scalatest]` accessor on `ConfigMapWrapperSuite` (AC-130). The instance that runs then gives its own (§ Further `structure` events).
  - `wrappedSuite` is built with `Constructor.newInstance`, so a throwing constructor reaches its first reader as an `InvocationTargetException`. In `org.scalatest.tools.Framework`'s `ScalaTestTask.execute`, that reader is the first `suite.suiteId`, outside any `try`, before `SuiteStarting` (AC-132). Under `Runner` it is the `expectedTestCount` sum, inside `doRunRunRunDaDoRunRun`'s `try`, whose catch sends `RunAborted` (AC-133).
  - With the emitter on, where the suite is a `ConfigMapWrapperSuite`, `execute` reads `suite.suiteId` once, before `registerReporter` and `runSuite`, in a `try`. Its catch builds a `DeferredAbortedSuite(suiteClass.getName, suiteClass.getName, e)`, hands the emitter that suite's structure and the `SuiteStarting` and `SuiteAborted` `runSuite` would send for it, assigned to its node and passed to no reporter, then rethrows `e`.
- Each suite's class name is `Suite.getSuiteClassName`'s, which for a `DeferredAbortedSuite` is the class whose construction failed. A `ConfigMapWrapperSuite`'s is instead its wrapped class's (AC-104, AC-130). `SuiteAborted.suiteClassName` is never read: `Framework`'s catch around `suite.run` sets it to the suite's name.

### Suite instances and their nodes

An event's node is found from the suite instance that sent it, never by reading `args.reporter` (`TECHNICAL-DESIGN-DECISIONS.md` "An event's node is found from the suite instance that sent it…"). `ParallelTestExecution.runTests`, `AsyncEngine.runTestsImpl`, `RandomTestOrder.run`, `StopOnFailure.run` and `org.scalatest.tools.Utils.wrapReporterIfNecessary` each replace `args.reporter` before a test runs, as can a user's `run`, `runTests` or `runTest` override (`specification/test-event-log-events.md` AC-105, `specification/test-event-log-structure.md` AC-128).

- The emitter keeps a node table for each suite node, read and written under its write lock. It holds:
  - the suite's node ID and `suiteId`;
  - its tests' IDs by test name, which `SuperEngine.registerTest` keeps unique within a suite;
  - its scopes' IDs by path: from the suite down, each scope's text with its place among the scopes of that text under the same parent, since `SuperEngine.registerNestedBranch` accepts sibling scopes of one text;
  - its nested suites' tables by position in `nestedSuites`, each with the instance it was read from.
- A `structure` event for a suite whose table exists takes each test's and scope's ID from the table where the table holds it, and mints the rest. A test therefore keeps its ID under another parent, as where a wrapper suite's flat tests are a delegate instance's scoped ones.
- The emitter keeps an identity map from suite instance to its registrations. A registration names a node table, or none while the instance is *unplaced*: running, with its suite node not yet known.
- Each site below registers an instance against a table, and removes the registration when the instance's run throws or its `Status` completes. Removal waits for the `Status` because under `AsyncEngine` and a distributor a suite's tests can run after its `run` returns:
  - the site that installs a top-level wrapper registers the top-level suite, against the table its structure mints;
  - `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` register each nested suite they run, against the table at its position (§ Wrappers);
  - `OneInstancePerTest.runTest`, `ParallelTestExecution.runTest` and `RandomTestOrder.runTest` register the instance they create with `newInstance`, against their own instance's table. `ParallelTestExecution.runTest` removes it when the distributor's `Status` completes. `RandomTestOrder.runTest` removes it when its own `Status` completes, after `RandomTestOrder.run` has run the deferred instance;
  - `ConfigMapWrapperSuite.run` constructs the wrapped class from `args.configMap` and runs it, as in 3.2.19. It registers that instance against the `ConfigMapWrapperSuite`'s own table: the instance is that suite.
- An instance none of those sites registered registers itself, unplaced, at the first site of its run that looks it up: `RandomTestOrder.run`, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl`, `PathEngine.runPathTestsImpl`, `Suite.run` or `TestsBeforeNestedSuites.run`, else `runTestsImpl` or `Suite.runTests`. That site removes the registration as above. Such an instance is:
  - one user code creates and runs: a test body's `new HelperSuite().run(None, args)`, the instance a custom `@WrapWith` wrapper runs, or one a `run` override builds;
  - a nested suite run by a `runNestedSuites` other than those two, such as a user's override, `Doc.runNestedSuites` or `DocSpecLike.runNestedSuites`, or run at a position its outer suite's table lacks.
- An unplaced instance gets its wrappers as a placed one does (§ Wrappers). The emitter holds the events they hand off, in the order handed off, and writes none of them yet (`TECHNICAL-DESIGN-DECISIONS.md` "The emitter holds the events of a suite instance core did not register…").
  - `runImpl` and `runPathTestsImpl` put the instance's suite-level wrapper over the reporter their suite-level informers report to, and in the `Args` they pass on. `RandomTestOrder.run` builds its `TestSortingReporter` before `runImpl`, so that wrapper is ahead of it. `ParallelTestExecution.runTests` and `AsyncEngine.runTestsImpl` build theirs after `runImpl`, find the instance registered, and put its wrapper over their own sorter as for any instance.
  - The first held event to reach a wrapper of a placed instance places the unplaced one, under the write lock, below that wrapper's suite node `S`. It is placed by the `suiteId` its held events carry, once one carries it:
    - `S`'s own `suiteId`: the instance is `S`, and takes `S`'s table;
    - that of a nested suite of `S`: the instance is that nested suite. Where several share the `suiteId`, it is the one read from this instance, else the first in declared order that has no `SuiteCompleted` or `SuiteAborted`;
    - any other: the emitter mints a nested suite node directly under `S`, after `S`'s other children, with the `suiteName`, `suiteId` and `suiteClassName` the held events carry. A later run of an instance with that `suiteId` below `S` then meets the case above.
  - The emitter then writes the instance's `structure`, where its suite node is new or its tree differs from the table (§ Further `structure` events), and its held events in the order handed off. It writes each later one as it is handed off (`specification/test-event-log-executions.md` AC-135 to AC-138).
  - A held event that first reaches a wrapper of another unplaced instance waits for that instance, and its own is then placed below that instance's suite node.
  - Events still held when the registration is removed are dropped: none reached a wrapper of a placed instance.

### An event's node

- Each engine site that sends an event passes the send helper of `object Suite` it calls (`reportTestStarting`, `reportScopeOpened`, `reportInfoProvided` and the rest, which already take `theSuite`) the event's tree node and execution (§ Executions). With the emitter on, the helper records these with the instance, against the event's identity in the emitter's state, before it reports the event. This record is the event's *origin*. The event is unchanged (`../SPECIFICATION.md` R-002), and the emitter drops the origin once it writes the event.
  - The tree node is the test's name for a test's event, and the `DescriptionBranch` that `runTestsInBranch` holds for a scope's event or for a message registered in that scope. A suite-level message has none.
  - A scope's node is its branch's path in the table. Nothing reads the order in which scopes open and close.
- A wrapper names its registration's table when it hands an event off. The emitter assigns the event:
  - with an origin, to the origin's tree node in the table of the origin's instance. Where that instance has several registrations, as one instance nested twice and run by a distributor, it uses the wrapper's table;
  - without one, as for an event user code builds or the `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` a `runNestedSuites` sends, from the event's own data at or below the wrapper's suite: its `suiteId`, or a message's `nameInfo`'s, names the suite, and its test name the test in that suite's table. Sibling nested suites sharing a `suiteId` are told apart as an unplaced instance's are (§ Suite instances and their nodes).
- An event without an origin whose data names a suite or test no table holds is still assigned to its own node. A `suiteId` no suite at or below the wrapper's has is given a minted nested suite node, as an unplaced instance's is. A test name its suite's table lacks is given a test node directly under the suite, named and labelled as a test of a suite with no registration tree is. A further `structure` precedes the event.
- A message with no origin whose `nameInfo` is `None` is assigned to the wrapper's own node, since nothing identifies another (`../SPECIFICATION.md` R-097; `specification/test-event-log-events.md` AC-107).
- A `SuiteAborted` whose `suiteClassName` is `DistributedTestRunnerSuite`'s is assigned to the node of the wrapper that hands it off, the test that suite ran (`specification/test-event-log-structure.md` AC-126).

### Further `structure` events

- A further `structure` is built without calling an overridable member of the suite. `testNames`, `tags`, `nestedSuites` and `expectedTestCount` are user code that can throw, and inside `run` 3.2.19 calls them only where it needs them (`../SPECIFICATION.md` R-002).
  - For a suite with a registration tree it is built from `atomic` alone: nodes and labels as the first structure's are, filtered by `args.filter` over `tagsMap`, with the class's tag annotations applied as `Suite.autoTagClassAnnotations` applies them but to the tree's own test names, and with the table's `suiteId`. A suite whose `tags` override keeps a test that this filtering drops gains the test by the last case below.
  - For a suite with none, `Suite.runTests` builds it from the names `filter(theTestNames, tags, suiteId)` returns to its own loop.
  - It holds the suite's scopes and tests and no nested suite, which `atomic` does not hold. Each nested suite keeps the structure it has.
- It is written, before the first event that needs it:
  - by the first engine site a run of a placed instance reaches, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl` or `PathEngine.runPathTestsImpl`, else `runTestsImpl`, where the instance's tree after filtering differs from the table in any node, parent, position or label. This is the site for the instance `ConfigMapWrapperSuite.run` runs (`specification/test-event-log-structure.md` AC-131), an instance `newInstance` creates and a nested suite's instance where `nestedSuites` returns new instances on each call;
  - by `Suite.runTests`, likewise, for a placed instance with no registration tree;
  - when an unplaced instance is placed, where its suite node is new or its tree differs from the table, from the tree or names the sites above read at its run and kept in its registration. A nested suite placed by its `SuiteStarting`, before its own run reads its tree, gets a `structure` holding the suite alone, and the first case then adds its scopes and tests;
  - by an engine site about to send an event for a test or scope of its tree that the table lacks, such as a test the filter drops that a `runTests` override runs. It adds that node and the scopes around it to the suite's latest structure.

### Wrappers

- `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` install each nested suite's wrapper before its `SuiteStarting`. In the sequential branch, `callExecuteOnSuite` sends the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` through the outer suite's reporter and builds the nested suite's `Args` only after `SuiteStarting`. The wrapper takes that reporter's place, both for those events and in the `Args`. In the `-P` branch of `Suite.runNestedSuites`, the wrapper goes in the `Args` given to the distributor, whose `SuiteRunner` sends those events.
  - Where `thisSuite` is a `DiscoverySuite`, each nested suite's wrapper is a top-level wrapper (AC-099).
  - Otherwise the site looks up `thisSuite`, and each nested suite it runs takes the table at its position in `nestedSuites`. Matching by position keeps structure and run in agreement where `nestedSuites` returns new instances on each call.
- `Engine.runTestsImpl`, `AsyncEngine.runTestsImpl` and `Suite.runTests` look up `theSuite` or `this`, and install the test's structure wrapper, which names that registration and the test's name, in the `Args` they pass to `runTest`, not inside `runTestImpl`. `ParallelTestExecution.runTest` builds both the `Args` it gives the distributor and the `DistributedTestRunnerSuite` running that test from those `Args`. When that `DistributedTestRunnerSuite` throws, the distributor's `SuiteRunner` sends its `SuiteAborted` through those `Args`' reporter, so the event reaches the test's wrapper first (`specification/test-event-log-structure.md` AC-126). With the wrapper inside `runTestImpl`, the event would reach first the suite's wrapper that `ParallelTestExecution.runTests` puts over its `TestSortingReporter` (below).
  - Given a test name and a test sorter, `ParallelTestExecution.run`, `RandomTestOrder.run` and `AsyncEngine.runImpl` replace the reporter with a `TestSpecificReporter`. Each looks up `this`, and wraps the `TestSpecificReporter` in that test's wrapper, so the test-specific instance's events pass through it (AC-100, AC-129). `AsyncEngine.runImpl` does so only in an async suite that mixes in neither `ParallelTestExecution` nor `RandomTestOrder`, for the instances `OneInstancePerTest.runTest` creates.
- `ParallelTestExecution.runTests`, `RandomTestOrder.run` and `AsyncEngine.runTestsImpl` each look up `this` or `theSuite` and put a wrapper for the suite over the `TestSortingReporter` they create. The sorter holds back the suite's own events, such as `ScopeOpened`, `ScopeClosed` and a scope's messages, until the tests before them complete. Each test's wrapper sits ahead of the sorter and hands the test's events off at once. Through the sorter alone, a scope's message would reach the emitter after children declared after it had started, and be placed after them (§ Critical code paths § A message among its node's children).
- A site whose lookup finds several registrations installs no wrapper. The instance's events are then handed off by the wrapper its registering site installed, which names the table (§ An event's node).
- An event reaches every wrapper between its sender and `DispatchReporter`: its test's, its suite's and each enclosing suite's, interleaved with other reporters such as `TestSortingReporter`, `CatchReporter`, `StopOnFailureReporter` and a user's. Only the innermost wrapper it reaches hands it to the emitter (`../ARCHITECTURE-DECISIONS.md` "Only the innermost structure wrapper an event reaches hands it to the emitter…").
  - The emitter keeps an identity set of the events wrappers have handed off. Under the emitter's write lock, a wrapper hands off only an event not in the set, and adds it.
  - The top-level suite's wrapper, the last wrapper any event of its suite reaches, removes the event from the set instead of adding it.
- `wrapReporterIfNecessary` adds a `CatchReporter` over any reporter that is not one, so it adds one over a wrapper put where 3.2.19 passes a `CatchReporter` on. That layer forwards every event. A wrapper catches whatever its own hand-off throws and then passes the event on, so the layer never prints `Resources.reporterThrew` for the emitter, and no reporter misses an event (`../SPECIFICATION.md` R-002).

### Executions

- Core mints an execution as an object in the emitter's state. The emitter gives it its node's next number, from 1, when it first writes an event of it, and writes the number on every event assigned to a suite, scope or test (§ The format).
- `Engine.runTestImpl` and `AsyncEngine.runTestImpl` mint one on each call, for the test's `TestStarting`, its closing event and its messages. A `runTest` override that calls `super.runTest` twice therefore gives its test two (`specification/test-event-log-executions.md` AC-140).
- `traverseSubNodes` and `runTestsImpl` mint one for each `TestIgnored` they send.
- The first engine site a run of an instance reaches, `runImpl` or `runPathTestsImpl`, else `runTestsImpl`, mints one for the run. Every scope whose events `runTestsInBranch` sends in that run takes it, each numbering it among its own, and so does the suite, for the run's suite-level messages. Where the suite's `SuiteStarting` opened an execution that no run has taken, the run takes that one.
- An execution reaches the emitter in its events' origins, and in the messages a test's informers hand over (§ A test's messages). `Args` carries none.
- For an event with no origin, the emitter writes the execution of its node that opened last and has not closed, else the node's latest, else a first one (`specification/test-event-log-events.md` AC-134). A test's execution closes with its closing event, a scope's with `ScopeClosed` or `ScopePending`, and a suite's with `SuiteCompleted` or `SuiteAborted`.

### A test's messages

- A test's `info` and `markup` reach reporters only in its closing event's `recordedEvents`, after its `note`s and `alert`s and with ordinals taken at the close (`Engine.runTestImpl`, `AsyncEngine.runTestImpl`). Neither the order events arrive in nor their ordinals give the order the test called them (`specification/test-event-log-events.md` AC-105). With the emitter on, the test's informer and documenter therefore also hand each message to the emitter when called. With the emitter off they behave as in 3.2.19.
  - The informer and documenter hand each message to the emitter with the execution `runTestImpl` minted, never to a reporter (`../SPECIFICATION.md` R-002). The emitter assigns it to the node that execution's `TestStarting` was assigned to, and holds it until that event is handed off.
  - Before reporting the closing event, `runTestImpl` adds it, under the write lock, to an identity set the emitter keeps. The emitter leaves the recorded events of an event in that set out of the event log, and removes the event from the set once it writes it.
  - The hand-off takes no ordinal from `tracker`, so every event reporters receive keeps its 3.2.19 ordinal (`specification/compatibility-and-library.md` AC-056). The event log carries no ordinal, since no output shows or uses one.
  - The message's timestamp is the time of the call (AC-105).
- The messages of a path-style test, one of `PathAnyFunSpec` or `PathAnyFreeSpec`, never pass through that hand-off. `PathEngine.handleTest` runs the test's body while its suite is constructed, before the suite's `structure` and `SuiteStarting`. It gives the messages to four recorders, `PathMessageRecordingInformer`, `PathMessageRecordingNotifier`, `PathMessageRecordingAlerter` and `PathMessageRecordingDocumenter` in `org/scalatest/ConcurrentInformer.scala`. Each keeps its own queue, with no order across the four and no call time. Only the informer's queue reaches `TestLeaf.recordedMessages`, and `Engine.runTestImpl`, replaying the stored outcome, adds only the informer's events to the closing event's `recordedEvents`.
  - The four recorders of one test also append each call to one shared call sequence. An entry holds what its recorder stores of the call, the recorder's event function and the time of the call. `registerTest` takes, and `TestLeaf` holds, that sequence beside the informer.
  - With the emitter on, `Engine.runTestImpl` replaying a path-style test hands the sequence's messages, in order, to the emitter after `TestStarting` and before the closing event, as the informer's are handed over at call time. Each message is built by its entry's event function, given a fresh `Tracker` in place of `tracker` so that it takes no ordinal from the run's. Its `timeStamp` is then set to the entry's call time.
  - The closing event's `recordedEvents` are built from `tracker` as in 3.2.19, emitter on or off, so the test's notes, alerts and markup still reach no reporter (`specification/compatibility-and-library.md` AC-056).

### Runs that pass no run-entry site

- `TestsBeforeNestedSuites.run` calls neither `super.run` nor its style's `runImpl`. It looks its instance up itself (§ Suite instances and their nodes), and `runTestsImpl` is its first engine site. In 3.2.19 such a suite has no suite-level informers, so it sends no suite-level message. The event log holds what any suite's holds.
- A user's `run` that calls no `super.run`:
  - where it calls `runTests` or `runNestedSuites`, those sites look the instance up, and the event log holds its events on its own nodes;
  - where it sends events itself, they have no origin and pass no wrapper of the instance. The innermost wrapper they reach assigns each from its own data (§ An event's node).
- `Doc.runNestedSuites` and `DocSpecLike.runNestedSuites` run each included suite with their own `Args` and send no `SuiteStarting` or `SuiteCompleted` for it. The suite registers itself unplaced and is placed by its `suiteId`. The event log holds its events on its own nodes, and no `SuiteStarting` or `SuiteCompleted` for it.
- A helper suite run from `beforeAll()`, which is given no `Args`, reports to whatever reporter the user gives it. Given the suite's captured `args.reporter`, it is placed below that suite. Given any other, its events reach no wrapper of a placed instance, and the event log holds none of them.

### The format

Each event is a JSON object (`TECHNICAL-DESIGN-DECISIONS.md` "Each event of a run's test event log is a JSON object."), encoded as `../JSON-LAYOUT.md` § Encoding gives, except that keys are written in the order this section gives. Escaping control characters in strings keeps each event to one line.

- A lone surrogate's `\uXXXX` escape decodes back to the same `char`.
- Integers are JSON numbers, read as `Long`, never through `Double`.
- An empty array omits its key, as an absent value does.

Each event's first key is `item`, naming its kind. The events are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, and the keys of each, in order, are:

- The `runStarted` event: `item` `runStarted`; `pid`; `startTime`, the text `Instant.toString` gives, read back with `Instant.parse`, so it equals `ProcessHandle`'s exactly.
- A `structure` event: `item` `structure`; `nodes`, an array of the suite's nodes in depth-first pre-order, each node's children in declared order. A node's keys are `id`, `kind` (`suite`, `scope` or `test`), `parent` (its parent's `id`), `position` (its declared position, from 0), `name`, `label`, `suiteId` and `className`, each present only on the nodes `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin gives it. A node's `id` is an integer.
  - The first node is the event's root suite. A top-level suite has no `parent` or `position`; a nested suite that is the root has both.
  - A further `structure` event has the same form. Its root is a suite an earlier event holds, or a nested suite new to the event log under a suite an earlier event holds. It holds the root's scopes and tests whole, and none of its nested suites. A node an earlier event holds has the same `id`.
- A ScalaTest event: `item` `event`; `node`, the `id` of the node the event is assigned to, absent where it is assigned to the run; `execution`, the number of that node's execution the event belongs to, from 1, present exactly where `node` is; `event`, its `org.scalatest.events` class's simple name; then the event's fields.
- The `runEnded` event: `item` `runEnded`; `outcome`: `completed`, `aborted` or `stopped`.

A ScalaTest event's fields:

- A decoded class holds every field of its `org.scalatest.events` class except:
  - `ordinal`, `formatter`, `rerunner` and `payload`;
  - `recordedEvents` (§ The test event log on the JVM);
  - the event's copy of its node's names and IDs: `suiteName`, `suiteId`, `suiteClassName`, `testName`, `testText`, `nameInfo`, and a `ScopeOpened`'s, `ScopeClosed`'s or `ScopePending`'s `message`, since names, IDs, class names and labels come from the `structure` event through the node the event is assigned to;
  - `DiscoveryStarting`'s `configMap`, since the run record's comes from `RunStarting`.
- Its keys are the field names of its decoded class, in that class's declaration order. Each decoded class names and orders the fields it holds as its `org.scalatest.events` class does.
- A field whose `org.scalatest.events` type is not an `Option` is required, except that an empty sequence omits its key and decodes as empty.
- A `String` is a JSON string, an `Int` or `Long` a number and a `Boolean` `true` or `false`. A sequence is an array, and `None` omits its key.
- A case class, such as a `Summary`, is an object of its fields under the same rules. A member of a sealed family, such as a `Location`, is that object with first key `kind` holding its class's simple name: `{"kind":"SeeStackDepthException"}`.
- A `ConfigMap` is an object of each value's `toString`, its keys in ascending `String.compareTo` order.
- A throwable is an object with these keys, in order:
  - `id`, numbered from 1 in the order the event writes its throwables. A throwable the event has already written, the same object and never merely an `equals` one, is `{"ref": <id>}`. Identity is per event, so a torn event breaks no other.
  - `className`; `message`, absent where `getMessage` is `null`.
  - `stackDepth`, only on a `StackDepth`: an object of `fileName`, `filePathname` and `lineNumber`, the failed code's, each present where defined.
  - `propertyCheck`, `true`, only on a `PropertyCheckFailedException`, whose message already holds its line number.
  - `stackTrace`, its frames, a `StackDepth`'s included, each a frame with the keys `../JSON-LAYOUT.md` § Throwables gives a frame, each absent where that section says, followed by `text`, its `StackTraceElement.toString`, which on JDK 9 and later can hold a module and a class loader name.
  - `cause`; `suppressed`, in order.

A line cannot be decoded where it is not one JSON object, holds a key this section does not give its object, lacks a required key, or holds an `item`, `event`, `kind` or `outcome` value this section does not give.

## Publishing

The group change of `../ARCHITECTURE.md` § Modules reaches both builds. Every artifact is published at one version, the fork's release, and the plugin depends on core and the library at its own version.

- The sbt build's `organization` becomes `com.edc` in every project it publishes, its `org.scalatest` and `org.scalactic` projects alike.
- Each `mimaPreviousArtifacts` under `project/` takes its group from `organization.value`, so it would then name a `com.edc` 3.2.19 that does not exist. Each names its project's upstream group, `org.scalatest` or `org.scalactic`, in place of `organization.value`.
- The consolidation library is a JVM-only project of `project/DottyBuild.scala`, depending on its Scala 3 scalatest-core project, so the sbt build publishes it with core, before the plugin's Mill build resolves both.
- The plugin's Mill build publishes `scalatest-mill` under `com.edc` with `publishLocal`.

## Testing

### Runs the criteria describe

The criteria whose `Given` is an event log holding events (`SPECIFICATION.md` § Acceptance criteria) are tested without Mill and without the codec (`TECHNICAL-DESIGN-DECISIONS.md` "The consolidation library's tests build each run with a test-only builder…"). Those tests build each run with a test-only builder in the library's tests:

- A test gives the run as one ordered list: the criterion's events, in the notation of `SPECIFICATION.md` § Acceptance criteria, with the run's captured lines at the places they are fed.
- The builder derives only each node's ID, its parent's ID and its declared position, from the `structure` events, and resolves each node an event names to its ID. It emits every event and captured line in the order given, in the form core's decoder yields, so a test can place a `structure` event, a ScalaTest event or a captured line anywhere the criterion puts it.
- `<node> execution <n>: <event>` gives the event execution number `<n>`, and an event without it execution 1.
- A `structure` block for a suite an earlier block holds becomes a further `structure` event. The builder keeps the ID of each node an earlier block holds, matching nodes as the emitter's node tables do (§ The test event log on the JVM § Suite instances and their nodes), and mints the rest. A nested suite's block names its parent, which the builder resolves to that suite's ID.
- It adds the test classes the run's process took after the event stream ends, as the test command does.

The emitter's and the decoder's criteria are those that state events, in the parts of `specification/test-event-log.md`. Among them, `specification/test-event-log-events.md` AC-109 writes lone surrogates, and `specification/test-event-log-structure.md` AC-100 and AC-121 write events from concurrent threads. Each side of the codec is tested against the format on its own (`../ARCHITECTURE-DECISIONS.md` "Each side of core's codec is tested on its own…"), so each such criterion has two tests. Both build the criterion's events with the same builder from its event list, and share expected text in the format, written by hand in the test: a whole line for each event the criterion states, with any value where the criterion fixes none.

- The emitter's test runs the criterion's suites with the runner argument, on every Scala version core builds for on the JVM. It compares the lines the run writes with the expected text, only on the keys holding what the criterion fixes and under the *is* and *holds in order* rules for events (below). It compares node IDs through the structures that mint them. It then decodes the lines with core's codec and compares the decoded events with the built ones.
- The decoder's test feeds core's decoder the expected text and compares the events it yields with the built ones.

One further decoder test, tied to no criterion, feeds the decoder text whose last line is torn, from which it yields no event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

Each comparison of events:

- Each event is compared only on what the criterion fixes: a `structure` event on its whole tree, a ScalaTest event on its node, its class and the fields the criterion names.
- Where the criterion says the event log *is* the events, the decoded events must be exactly those built. Where it says the log *holds* them *in this order*, the built events must be a subsequence of the decoded ones.

A drift between builder and decoder in what a criterion fixes then fails those tests, as well as the criteria run under Mill.

Each test of human-readable output compares the whole output, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour.

### Comparison with ScalaTest 3.2.19

This serves `specification/compatibility-and-library.md` AC-056 and AC-057, `specification/test-event-log-events.md` AC-116, and `specification/runs-and-test-structure-nodes.md` AC-073.

- AC-056 and AC-057 run in a test project of the fork's sbt build.
  - The compatibility fixture and the recorders (the `-C` recording reporter, and a test-interface event handler and loggers that record what they receive) are the sources of an sbt configuration of the project's own that extends neither `Compile` nor `Test` and whose one library dependency is `org.scalatest:scalatest` 3.2.19. They compile there once, so both runs load the same compiled classes. `../SPECIFICATION.md` R-004 lets the fork run them.
  - 3.2.19 is on no classpath that also holds the fork: the two share packages and class names, so on one classpath each class loads from whichever jar comes first. The test itself, in `Test`, is given three classpaths: that configuration's compiled classes, its resolved classpath (3.2.19 and its dependencies), and the runtime classpath of the fork's `scalatest` and `scalactic` projects (`project/scalatest.scala`) for the same Scala version.
  - For Scala 2.11, 2.12 and 2.13 the project is cross-built over `crossScalaVersions` (`project/BuildCommons.scala` `scalaVersionsSettings`). Scala 3 artefacts come only from `project/DottyBuild.scala`, so for Scala 3.3 a twin project there builds the same sources, its fork classpath from `scalatestDotty` and `scalacticDotty`.
- The test forks one JVM for each run, on the fixture's compiled classes plus either 3.2.19's resolved classpath or the fork's, as IntelliJ's forked mode and a build tool's test worker each start a JVM of their own. No JVM-wide state, such as `System.out` or a system property, then passes from one run to the other.
- In each JVM the recorders write each event to a file as one line, holding every field but timestamps, durations and thread names. The test compares the two runs' files line by line.
- AC-116 runs in a Scala.js and a Native test project of the fork's sbt build, for each Scala version. The fixture and the recorders compile once against 3.2.19's artefacts for the platform, and the test links them twice, once with 3.2.19's and once with the fork's. There the recorders print each event as one line to stdout, and the test compares the two runs' lines as above.
- AC-073 runs among the plugin's integration tests under Mill's testkit. Its fixture build also holds a module over the same suites that tests with 3.2.19 and passes `-f` in its own ScalaTest arguments, configured as `SPECIFICATION.md` § Acceptance criteria requires of every JVM test module of a criterion's build; Mill's `testForked` on that module writes the file the criterion compares with.

### MiMa

`specification/compatibility-and-library.md` AC-058 runs the build's MiMa check against upstream's groups (§ Publishing). Review rejects any MiMa problem filter this feature adds, since a filter would let the check pass without the compatibility it proves.
