# Consolidated human-readable output for one JVM module: technical design

Its decisions are in [TECHNICAL-DESIGN-DECISIONS.md](TECHNICAL-DESIGN-DECISIONS.md).

## Summary

- The emitter, in a scalatest-core that compiles on JDK 8 without `java.lang.ProcessHandle`, must give the Mill plugin the PID and start time Mill's JVM reads from `ProcessHandle`, each label before any test runs, and each test's messages in the order the test called them; no event carries the labels or that order.
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

Item 1's start time must equal `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM exactly, not closely (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

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

- Assembly records, with each message attached to a suite or scope, which of that node's children had sent their first event when the message arrived.
- The human-readable writer puts the message after the last of those children in declared order and before the next. A child declared before that one but not yet reached, as under concurrent tests, therefore also comes before the message. Messages placed between the same two children keep the order they arrived in.

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
- `org.scalatest.tools.Runner.run`, and the runner `org.scalatest.tools.Framework.runner` constructs, each write item 1 once they have parsed the runner argument, before `RunStarting` (`specification/test-event-log.md` AC-094).
- The emitter writes item 1 to a file of another name in the same folder, moves that file to the event log's name with `java.nio.file.Files.move` and `ATOMIC_MOVE`, then opens it to append every later item (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
  - Event log reading never reads the other name, so a worker that dies before the move holds no event log file.
  - In the same folder, the move is one rename within one file system.
  - Item 1 precedes every other item, so nothing is appended between the move and the reopening.
- The emitter's one write lock (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin) is taken by:
  - the run's own thread;
  - the dispatcher's thread;
  - test threads, including for messages a test's informers hand straight to the emitter;
  - the `-W` slowpoke timer's thread.
- The places that hand off an event sent outside every structure wrapper (`../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter):
  - `org.scalatest.tools.Framework`'s runner: `RunStarting` in its construction, and `RunCompleted` in `done`.
  - `org.scalatest.DispatchReporter`'s slowpoke timer under `-W`: its `AlertProvided`, which has `nameInfo = None` (`specification/test-event-log.md` AC-121). The timer also passes that same event to the `SuiteSortingReporter` that `Framework` registers with `registerSlowpokeReporter`. From there it reaches at most a task's `SbtLogInfoReporter`, through no wrapper, and is not handed off again.
  - `org.scalatest.tools.Runner.doRunRunRunDaDoRunRun` and its catches: `RunStarting`, `RunCompleted`, `RunStopped`, `RunAborted`, `DiscoveryStarting` and `DiscoveryCompleted`.
  - `org.scalatest.tools.SuiteRunner` running each `DiscoverySuite` that `Runner.genDiscoSuites` builds: that suite's `SuiteStarting`, its `SuiteCompleted`, and the `SuiteAborted` it sends in its `Failure(ue)` branch and its `NotAllowedException` and `RuntimeException` catches.
    - Where `thisSuite` is a `DiscoverySuite`, `Suite.runNestedSuites` installs each nested suite's top-level wrapper (AC-099) before that suite's `SuiteStarting`, in both branches. In the sequential branch, `callExecuteOnSuite` sends the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` through the `DiscoverySuite`'s reporter, and builds the nested suite's `Args` only after `SuiteStarting`, so the wrapper takes that reporter's place for those events and in the `Args`. In the `-P` branch, the wrapper goes in the `Args` given to the distributor, whose `SuiteRunner` sends those events.
- Not handed off (`SPECIFICATION.md` § Scope § Out of scope):
  - the `AlertProvided`s that `Runner.readMemoryFiles` sends for `-A`;
  - the events `Framework` relays from a sub-process sbt forks, and the `RunAborted` it sends when it can no longer read them;
  - the run events of the reruns that `Runner`'s `-g` graphical reporter starts through `RunnerJFrame`, `SuiteRerunner` and `TestRerunner`.
- Event log reading polls each file every 10 ms, the only interval measured (`../research/jvm-file-channel.md` § 2).
- The handle **Decode** returns (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is a public opaque type in the library over core's decoded item, whose classes are `private[scalatest]`.
- A suite's structure takes each scope's and test's label from the style's registration tree, before any of them runs. The structure precedes the suite's first event (`specification/test-event-log.md` AC-096), and a test that never starts (AC-097) or never closes (`TestStarting` carries `MotionToSuppress`) has no event giving its label. It builds each as `Engine.runTestImpl` and `runTestsInBranch` do, in `Engine` and `AsyncEngine`, and drops the leading indentation:
  - a test's from `Suite.getIndentedTextForTest` over its text with its parent's child prefix, with the `includeIcon` its style's `runTest` passes, or `true` for a test the filter ignores, as `TestIgnored`'s formatter has it. That flag is `false` only in `AnyFeatureSpecLike`, `FixtureAnyFeatureSpecLike` and the `FeatureSpecLike` and `FixtureFeatureSpecLike` that `project/GenSafeStyles.scala` generates from them (AC-103);
  - a scope's from `getIndentedTextForInfo` over its text with its parent's child prefix, with no icon.
- A suite with no registration tree, one that overrides `testNames` and `runTest` itself, yields a test node for each name of `testNames` the filter keeps, in `testNames`'s order, and no scope. Each takes its name from `testNames`, never from an event's `testText`: `Suite.runTests` sends a test's name as `testText` only in the `TestIgnored` of a test the filter ignores, and otherwise the suite's own `runTest` may send any `testText`, or no event at all. Each is labelled as `Suite.runTests` labels a test it ignores, by `getEscapedIndentedTextForTest(testName, 1, true)` without the leading indentation.
- Each suite's class name is `Suite.getSuiteClassName`'s, which for a `DeferredAbortedSuite` is the class whose construction failed (AC-104). `SuiteAborted.suiteClassName` is never read: `Framework`'s catch around `suite.run` sets it to the suite's name.
- A test's structure wrapper is installed in the `Args` its suite passes to `runTest`, where `Engine`, `AsyncEngine` and `Suite.runTests` call it, not inside `runTestImpl`. `ParallelTestExecution.runTest` builds both the `Args` it gives the distributor and the `DistributedTestRunnerSuite` running that test from those `Args`. When that `DistributedTestRunnerSuite` throws, the `SuiteAborted` the distributor's `SuiteRunner` sends therefore reaches the test's wrapper first (`specification/test-event-log.md` AC-126). Below `runTest` it would reach only the `TestSortingReporter` that `ParallelTestExecution.runTests` puts over the suite's wrapper, which passes a `SuiteAborted` straight to that wrapper.
  - Given a test name, `ParallelTestExecution.run` replaces the reporter it is given, which is that test's wrapper, with a `TestSpecificReporter`. It puts that test's wrapper back over the `TestSpecificReporter`, so the test-specific instance's events also pass through it (AC-100).
- A test's `info` and `markup` reach reporters only in its closing event's `recordedEvents`, after its `note`s and `alert`s and with ordinals taken at the close (`Engine.runTestImpl`, `AsyncEngine.runTestImpl`). Neither the order events arrive in nor their ordinals give the order the test called them (`specification/test-event-log.md` AC-105). With the emitter on, the test's informer and documenter therefore also hand each message to the emitter when called, and the emitter leaves the closing event's recorded events out of the event log. With the emitter off they behave as in 3.2.19.
  - They reach the emitter through the test's structure wrapper: `args.reporter` in `runTestImpl`, or the `reporter` of the `WrapperCatchReporter` that holds it. The wrapper tags the message with the test's node and hands it to the emitter only, never to the reporter it wraps (`../SPECIFICATION.md` R-002).
  - The hand-off takes no ordinal from `tracker`, so every event reporters receive keeps its 3.2.19 ordinal (`specification/compatibility-and-library.md` AC-056). The event log carries no ordinal, since no output shows or uses one.
  - The message's timestamp is the time of the call (AC-105).
- The messages of a path-style test, one of `PathAnyFunSpec` or `PathAnyFreeSpec`, never pass through that hand-off. `PathEngine.handleTest` runs the test's body while its suite is constructed, before the suite's `structure` and `SuiteStarting`. It gives the messages to four recorders, `PathMessageRecordingInformer`, `PathMessageRecordingNotifier`, `PathMessageRecordingAlerter` and `PathMessageRecordingDocumenter` in `org/scalatest/ConcurrentInformer.scala`. Each keeps its own queue, with no order across the four and no call time. Only the informer's queue reaches `TestLeaf.recordedMessages`, and `Engine.runTestImpl`, replaying the stored outcome, adds only the informer's events to the closing event's `recordedEvents`.
  - The four recorders of one test also append each call to one shared call sequence. An entry holds what its recorder stores of the call, the recorder's event function and the time of the call. `registerTest` takes, and `TestLeaf` holds, that sequence beside the informer.
  - With the emitter on, `Engine.runTestImpl` replaying a path-style test hands the sequence's messages, in order, to the test's structure wrapper after `TestStarting` and before the closing event, and the wrapper treats them as it treats messages handed off at call time. Each message is built by its entry's event function, given a fresh `Tracker` in place of `tracker` so that it takes no ordinal from the run's. Its `timeStamp` is then set to the entry's call time.
  - The closing event's `recordedEvents` are built from `tracker` as in 3.2.19, emitter on or off, so the test's notes, alerts and markup still reach no reporter (`specification/compatibility-and-library.md` AC-056).

### The format

Each item is a JSON object (`TECHNICAL-DESIGN-DECISIONS.md` "Each item of a run's test event log is a JSON object."), encoded as `../JSON-LAYOUT.md` § Encoding gives, except that keys are written in the order this section gives. Escaping control characters in strings keeps each item to one line.

- A lone surrogate's `\uXXXX` escape decodes back to the same `char`.
- Integers are JSON numbers, read as `Long`, never through `Double`.
- An empty array omits its key, as an absent value does.

Each item's first key is `item`, naming its kind. The items are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, and their keys, in order, are:

1. `item` `runStarted`; `pid`; `startTime`, the text `Instant.toString` gives, read back with `Instant.parse`, so it equals `ProcessHandle`'s exactly.
2. `item` `structure`; `nodes`, an array of the suite's nodes in depth-first pre-order, each node's children in declared order. A node's keys are `id`, `kind` (`suite`, `scope` or `test`), `parent` (its parent's `id`), `position` (its declared position, from 0), `name`, `label`, `suiteId` and `className`, each present only on the nodes item 2 gives it. A node's `id` is an integer.
3. `item` `event`; `node`, the `id` of the node the event is tagged with, absent where it is tagged as the run's; `event`, its `org.scalatest.events` class's simple name; then the event's fields.
4. `item` `runEnded`; `outcome`: `completed`, `aborted` or `stopped`.

An event's fields:

- A decoded class holds every field of its `org.scalatest.events` class except:
  - `ordinal`, `formatter`, `rerunner` and `payload`;
  - `recordedEvents` (§ The test event log on the JVM);
  - the event's copy of its node's names and IDs: `suiteName`, `suiteId`, `suiteClassName`, `testName`, `testText`, `nameInfo`, and a `ScopeOpened`'s, `ScopeClosed`'s or `ScopePending`'s `message`, since names, IDs, class names and labels come from item 2 through the node the event is tagged with;
  - `DiscoveryStarting`'s `configMap`, since the run record's comes from `RunStarting`.
- Its keys are the field names of its decoded class, in that class's declaration order. Each decoded class names and orders the fields it holds as its `org.scalatest.events` class does.
- A field whose `org.scalatest.events` type is not an `Option` is required, except that an empty sequence omits its key and decodes as empty.
- A `String` is a JSON string, an `Int` or `Long` a number and a `Boolean` `true` or `false`. A sequence is an array, and `None` omits its key.
- A case class, such as a `Summary`, is an object of its fields under the same rules. A member of a sealed family, such as a `Location`, is that object with first key `kind` holding its class's simple name: `{"kind":"SeeStackDepthException"}`.
- A `ConfigMap` is an object of each value's `toString`, its keys in ascending `String.compareTo` order.
- A throwable is an object with these keys, in order:
  - `id`, numbered from 1 in the order the item writes its throwables. A throwable the item has already written, the same object and never merely an `equals` one, is `{"ref": <id>}`. Identity is per item, so a torn item breaks no other.
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

The criteria whose `Given` is an event log holding items (`SPECIFICATION.md` § Acceptance criteria) are tested without Mill and without the codec (`TECHNICAL-DESIGN-DECISIONS.md` "The consolidation library's tests build each run with a test-only builder…"). Those tests build each run with a test-only builder in the library's tests:

- A test gives the run as one ordered list: the criterion's items, in the notation of `SPECIFICATION.md` § Acceptance criteria, with the run's captured lines at the places they are fed.
- The builder derives only each node's ID, its parent's ID and its declared position, from the `structure` items, and resolves each node an item names to its ID. It emits every item and captured line in the order given, in the form core's decoder yields, so a test can place a structure item, an event or a captured line anywhere the criterion puts it.
- It adds the test classes the run's process took after the event stream ends, as the test command does.

The emitter's and the decoder's criteria are those of `specification/test-event-log.md` that state items. Among them, AC-109 writes lone surrogates, and AC-100 and AC-121 write items from concurrent threads. Each side of the codec is tested against the format on its own (`../ARCHITECTURE-DECISIONS.md` "Each side of core's codec is tested on its own…"), so each such criterion has two tests. Both build the criterion's items with the same builder from its item list, and share expected text in the format, written by hand in the test: a whole line for each item the criterion states, with any value where the criterion fixes none.

- The emitter's test runs the criterion's suites with the runner argument, on every Scala version core builds for on the JVM. It compares the lines the run writes with the expected text, only on the keys holding what the criterion fixes and under the *is* and *holds in order* rules for items (below). It compares node IDs through the structures that mint them. It then decodes the lines with core's codec and compares the decoded items with the built ones.
- The decoder's test feeds core's decoder the expected text and compares the items it yields with the built ones.

One further decoder test, tied to no criterion, feeds the decoder text whose last line is torn, from which it yields no item (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

Each comparison of items:

- Each item is compared only on what the criterion fixes: a `structure` on its whole tree, an event on its node, its class and the fields the criterion names.
- Where the criterion says the event log *is* the items, the decoded items must be exactly those built. Where it says the log *holds* them *in this order*, the built items must be a subsequence of the decoded ones.

A drift between builder and decoder in what a criterion fixes then fails those tests, as well as the criteria run under Mill.

Each test of human-readable output compares the whole output, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour.

### Comparison with ScalaTest 3.2.19

This serves `specification/compatibility-and-library.md` AC-056 and AC-057, `specification/test-event-log.md` AC-116, and `specification/runs-and-test-structure.md` AC-073.

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
