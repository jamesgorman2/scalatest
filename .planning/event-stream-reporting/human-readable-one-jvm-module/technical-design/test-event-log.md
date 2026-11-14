# Consolidated human-readable output for one JVM module: the test event log

Technical design of how the emitter reads the run's PID and start time, which nodes and executions it gives each event, and the event log's format. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Critical code paths

### The run's PID and process start time

The `runStarted` event's start time must equal `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM exactly, not closely (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

- The emitter reads both from `ProcessHandle.current()` by reflection, in `org.scalatest.tools.ProcessIdentity`. The read is in core's JVM sources only; Scala.js and Native are out of scope.
- It looks each method up on the interfaces `java.lang.ProcessHandle` and `java.lang.ProcessHandle.Info`, never on the handle's runtime class, whose methods are not accessible (`../research/jvm-pid-and-start-time.md` § 2).
- `RuntimeMXBean.getStartTime()` is not a fallback: it records when the JVM finished initialising and never matched (`../research/jvm-pid-and-start-time.md` § 3).
- A runner given the runner argument fails at construction, with an `IllegalStateException` and before it creates any file, where the JVM has no `ProcessHandle` or `startInstant()` is empty (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "The end of a run's event stream").
  - Mill 1.x supports user-defined modules down to Java 11 only, and its test worker's entry point is Java 11 bytecode in Mill 1.1.10 and 1.3.1 (`../research/jvm-pid-and-start-time.md` § 1). No worker under the test command runs without `ProcessHandle`.

## The test event log on the JVM

- The runner argument is two arguments: the flag `--test-event-log`, then the event log file's path. No ScalaTest 3.2.20 flag begins `--`.
- Core's runner argument definition (`../../ARCHITECTURE.md` § Modules § ScalaTest core) is `org.scalatest.tools.TestEventLogArgument`, one `private[scalatest]` definition shared by `ArgsParser` and the library's **Build the runner argument** (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library), which is `org.scalatest.consolidation.RunnerArgument.forEventLogFile`. The event log file is in the worker's `processFolder`. The test command adds the argument that operation returns, unread, to the worker's arguments.
- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it reads the runner argument:
  - `ArgsParser.parseArgsWithTestEventLog`, which `Runner.run` and `Framework.runner` call, returns the file the argument names beside the other arguments. It rejects the flag with no argument after it, and the runner argument given twice.
  - `ArgsParser.parseArgs`, which every other caller still calls, rejects the flag with 3.2.20's message for an argument it does not recognise.
  - `ArgsParser.checkArgsForValidity`, which only `Runner.run` calls, accepts the runner argument.
- `Runner.run` rejects the runner argument given with the graphic reporter `-g`, whose runs `RunnerJFrame` starts. Given the runner argument and no reporter argument, it configures no reporter, where 3.2.20 configures the graphic reporter.
- `Framework.runner` given the runner argument and no reporter argument builds no `SbtLogInfoReporter`. A sub-process runner, one given remote arguments, ignores the runner argument: sbt gives it the main runner's arguments, and the event log file is the main runner's.
- `org.scalatest.tools.Runner.run`, and the runner `org.scalatest.tools.Framework.runner` constructs, each start the emitter, `org.scalatest.tools.TestEventLogEmitter`, once they have parsed and checked their arguments. Starting it writes the `runStarted` event, before `RunStarting` (`../specification/test-event-log-structure.md` AC-094).
- The emitter writes the `runStarted` event to a file of another name in the same folder, the event log's name followed by `.tmp`. It moves that file to the event log's name with `java.nio.file.Files.move` and `ATOMIC_MOVE`, then opens it to append every later event (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
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
- The emitter's one write lock (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin) is taken by:
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
  - compares a suite's scopes and tests with its latest structure and, where a further `structure` is due (§ Further `structure` events), encodes and writes it. Encoding it calls no user code, since its nodes hold only names and labels;
  - after a closing run event, writes the `runEnded` event and closes the file;
  - closes the file where an event could not be encoded or written;
  - when a top-level suite's run completes, moves the events of that run still in its set to the suite's wrapper (§ Wrappers);
  - on closing the file, drops every event it keeps.
- The places that hand off an event sent outside every structure wrapper (`../../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter):
  - `org.scalatest.tools.Framework`'s runner: `RunStarting` in its construction, and `RunCompleted` in `done`.
  - `org.scalatest.DispatchReporter`'s slowpoke timer under `-W`: its `AlertProvided`, which has `nameInfo = None` (`../specification/test-event-log-structure.md` AC-121). The timer also passes that same event to the `SuiteSortingReporter` that `Framework` registers with `registerSlowpokeReporter`. From there it reaches at most a task's `SbtLogInfoReporter`, through no wrapper, and is not handed off again.
  - `org.scalatest.tools.Runner.doRunRunRunDaDoRunRun` and its catches: `RunStarting`, `RunCompleted`, `RunStopped`, `RunAborted`, `DiscoveryStarting` and `DiscoveryCompleted`.
  - `org.scalatest.tools.Runner.withClassLoaderAndDispatchReporter`'s catch, for the run `Runner.run` starts without `-g`: the `RunAborted` it gives the pass-fail reporter. It catches what `ReporterFactory.getDispatchReporter` throws, as where a custom reporter class cannot be loaded, and what `doRunRunRunDaDoRunRun` throws outside its own `try`, as where a `-A` file cannot be read. `Runner.run` passes it the hand-off through an overload. `RunnerJFrame` calls the 3.2.20 signature, which hands nothing off.
  - `org.scalatest.tools.SuiteRunner` running each `DiscoverySuite` that `Runner.genDiscoSuites` builds: that suite's `SuiteStarting`, its `SuiteCompleted`, and the `SuiteAborted` it sends in its `Failure(ue)` branch and its `NotAllowedException` and `RuntimeException` catches.
- Not handed off (`../SPECIFICATION.md` § Scope § Out of scope):
  - the `AlertProvided`s that `Runner.readMemoryFiles` sends for `-A`;
  - the events `Framework` relays from a sub-process sbt forks;
  - the run events of the reruns that `Runner`'s `-g` graphical reporter starts through `RunnerJFrame`, `SuiteRerunner` and `TestRerunner`.
- The event log file is `scalatest-event-log.jsonl` in the worker's `processFolder`, a name Mill does not use there.
- The test command's folder for a module is `modules/<module path>`, beside the command's `test.dest`: `out/org.scalatest.mill.TestCommandModule/modules/core.test` for `core.test`. Mill clears `test.dest` but not its siblings, so the test command clears the module's folder itself before it runs discovery. The module's one worker uses that folder as its `processFolder`.
- Event log reading polls each file every 10 ms, the only interval measured (`../../research/jvm-file-channel.md` § 2).
- On Windows, opening the event log file just after the emitter moves it into place can fail for a moment with a sharing violation, which Java throws as a `FileNotFoundException`. Event log reading retries the open at its poll interval, up to 500 times, before it fails the test command.
- The handle **Decode** returns (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is `org.scalatest.consolidation.RunEvent`, a public opaque type in the library over core's decoded event, whose classes are `private[scalatest]`. **Decode** is `RunEvent.decode`. It also rejects a `runStarted` event whose start time `Instant.parse` cannot parse.
- **Start** is `org.scalatest.consolidation.Consolidation.start`. The `Consolidation` it returns has the other operations:
  - **Add a run** is `addRun`. It takes the run's `TestRunLabel` and returns a `RunFeed`. The caller adds the run's events and captured lines to the feed, then ends the feed's event stream.
  - **Add a process that ended before any run existed in it** is `addProcessEndedBeforeAnyRun`.
  - **End** is `end`.
  - **Read the consolidation state** is `state`.
- A suite's structure takes each scope's and test's label from the style's registration tree, before any of them runs. The structure precedes the suite's first event (`../specification/test-event-log-structure.md` AC-096), and a test that never starts (AC-097) or never closes (`TestStarting` carries `MotionToSuppress`) has no event giving its label. `SuperEngine.registeredNodes` and `AsyncSuperEngine.registeredNodes` build each label as `runTestImpl` and `runTestsInBranch` do, at indentation level 0, where the formatter adds no indentation:
  - a test's from `Suite.getIndentedTextForTest` over its text with its parent's child prefix, with the `includeIcon` its style's `runTest` passes, or `true` for a test the filter ignores, as `TestIgnored`'s formatter has it. That flag is `false` only in `AnyFeatureSpecLike` and `FixtureAnyFeatureSpecLike` (`../specification/test-event-log-structure.md` AC-103);
  - a scope's from `getIndentedTextForInfo` over its text with its parent's child prefix, with no icon.
- A scope's or test's name is the text its style registers it with (`../../JSON-LAYOUT.md` § Nodes, `name`), except where the style's registration adds words to what the suite's source gives. There the name is what the source gives:
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
- `Framework`, `Runner` and `DiscoverySuite` run a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` as a `ConfigMapWrapperSuite`, which has no registration tree and forwards the suite's name, ID, test names, tags and nested suites to its `private lazy val wrappedSuite`, the wrapped class constructed with `Map.empty`. The suite's first structure is built from `wrappedSuite`, reached through a `private[scalatest]` accessor on `ConfigMapWrapperSuite` (`../specification/test-event-log-structure.md` AC-130). The instance that runs then gives its own (§ Further `structure` events).
  - `wrappedSuite` is built with `Constructor.newInstance`, so a throwing constructor reaches its first reader as an `InvocationTargetException`. In `org.scalatest.tools.Framework`'s `ScalaTestTask.execute`, that reader is the first `suite.suiteId`, outside any `try`, before `SuiteStarting` (`../specification/test-event-log-structure.md` AC-132). Under `Runner` it is the `expectedTestCount` sum, inside `doRunRunRunDaDoRunRun`'s `try`, whose catch sends `RunAborted` (`../specification/test-event-log-structure.md` AC-133).
  - Where the suite is a `ConfigMapWrapperSuite`, `execute` forces the construction of `wrappedSuite` through the `private[scalatest]` accessor, before `registerReporter` and `runSuite`, in a `try`. It calls no member a suite can override. Each later read 3.2.20 makes finds `wrappedSuite` already built, so a run without the runner argument throws what 3.2.20 throws, before any event. With the emitter on, the catch:
    1. builds a `DeferredAbortedSuite(suiteClass.getName, suiteClass.getName, e)`;
    2. hands the emitter that suite's structure, and the `SuiteStarting` and `SuiteAborted` `runSuite` would send for it, assigned to its node and passed to no reporter. Those two events take their ordinals from a `Tracker` of their own, so the run's reporters receive every later event with its 3.2.20 ordinal;
    3. rethrows `e`.
- Each suite's class name is `Suite.getSuiteClassName`'s, which for a `DeferredAbortedSuite` is the class whose construction failed. A `ConfigMapWrapperSuite`'s is instead its wrapped class's (`../specification/test-event-log-structure.md` AC-104, AC-130). `SuiteAborted.suiteClassName` is never read: `Framework`'s catch around `suite.run` sets it to the suite's name.

### Suite instances and their nodes

An event's node comes from the invocation context its send site runs in, never from `args.reporter` (`../../ARCHITECTURE.md` § Modules § ScalaTest core, Emitter; `../../decisions/architecture-test-structure.md` "A node's ID is its node path…"). `ParallelTestExecution.runTests`, `AsyncEngine.runTestsImpl`, `RandomTestOrder.run`, `StopOnFailure.run` and `org.scalatest.tools.Utils.wrapReporterIfNecessary` each replace `args.reporter` before a test runs, as can a user's `run`, `runTests` or `runTest` override (`../specification/test-event-log-events.md` AC-105, `../specification/test-event-log-structure.md` AC-128).

- The invocation context is a thread-local holding the run's emitter, a node path and an execution of that node. An execution's context also counts, by `suiteId`, the suites started in it, which gives each suite its index among its siblings sharing its `suiteId`.
- Each site that installs a top-level suite's wrapper (§ Wrappers) sets it to the run's own context around the suite's run, and restores it after. Under a run without the runner argument the site clears it instead, so a `Runner` started inside a test without the runner argument registers nothing (`../../SPECIFICATION.md` R-001).
- Elsewhere it is set, and restored after, only while the run's emitter is on:
  - by the site that registers a suite instance (below), to the instance's run execution, around the rest of its run;
  - by `Engine.runTestImpl` and `AsyncEngine.runTestImpl`, to the test's node and the execution they mint for it, around the test body;
  - by `Runner`'s `ConcurrentDistributor`, which takes the context where a suite is handed to it and sets it around the `SuiteRunner` that runs the suite on the pool's thread;
  - by `SerialExecutionContext`, which takes the context in `execute` and sets it around the task.
- A suite instance is *registered* by the first site of its run that finds a context set: `RandomTestOrder.run`, `SuperEngine.runImpl`, `AsyncSuperEngine.runImpl`, `PathEngine.runPathTestsImpl`, `Suite.run` or `TestsBeforeNestedSuites.run`, else `runTestsImpl` or `Suite.runTests`. A top-level suite is registered by the site that installs its wrapper, and a nested suite by the `runNestedSuites` that sends its `SuiteStarting` or, under `-P`, hands it to the distributor (§ Wrappers). Each runs the instance in a context naming that registration, which the instance's run takes. The registering site keeps the registration in the context it sets around the rest of the run.
  - A later site of the same run, such as `runTestsImpl` after `runImpl`, finds the registration in the context and registers nothing.
  - An instance whose run finds no context set is not registered. Its sites record no origin and install no wrapper, so its events reach the wrappers of the enclosing suite or test through `args.reporter` and are assigned by their own data (§ An event's node).
- A registration reads neither the instance's `suiteId` nor its `suiteName` itself. It takes each from the first read ScalaTest 3.2.20 makes of it in the run: each core site that makes such a read passes the value to the registration in its context (`../TECHNICAL-DESIGN-DECISIONS.md` "A registration takes a suite's `suiteId` and `suiteName` from the first reads 3.2.20 makes of them…"). The `suiteName` is read first by a `SuiteStarting` a top-level site or `runNestedSuites` builds, by `Suite.run` or `TestsBeforeNestedSuites.run` as they rename the thread, or by a send helper as it builds an event. The `suiteId` is read:
  - for a top-level suite, by `Framework`'s task before it calls `runSuite`, or under `Runner` by the structure read of the suite's wrapper, which `SuiteRunner.run` makes before it builds the suite's `SuiteStarting` (§ Wrappers);
  - by `Suite.runNestedSuites` or `StepwiseNestedSuiteExecution.runNestedSuites`, as they build a nested suite's `SuiteStarting`, or under `-P` by the distributor's `SuiteRunner`, as it builds that `SuiteStarting` on the pool's thread;
  - in `Suite.runTests`, for the filter;
  - in a send helper of `object Suite`, as it builds the event: the first event whose origin the instance records.

  Each comes before the instance's first scope or test event, so the `structure` holding the node still precedes them (§ Further `structure` events). Where an event is about to be handed off before both are read, the emitter reads the missing one itself. Until the `suiteId` is known the registration's node path is unknown: a send site builds its origin against the registration, and the emitter resolves the path when the event is handed off.
- Once the `suiteId` is known, the registration fixes its node path:
  - where the context is a suite's run outside its tests and the `suiteId` is that suite's, the instance is that suite: it takes the suite's node path, and its run is the suite's run execution, or the execution the suite's `SuiteStarting` opened where the suite has no registered run (`../../decisions/architecture-test-structure.md` "An instance a suite runs outside its tests that has the suite's `suiteId` is that suite's node…"). This covers the instance `OneInstancePerTest.runTest`, `ParallelTestExecution.runTest` and `RandomTestOrder.runTest` create with `newInstance`, the class `ConfigMapWrapperSuite.run` constructs from `args.configMap`, and the delegate a custom `@WrapWith` wrapper runs (`../specification/test-event-log-executions.md` AC-135), with no site of their own;
  - otherwise its node path is the context's node path followed by its `suiteId` and the count of suites with that `suiteId` already started in the context's execution, which it then increments, and it starts the run's execution (§ Executions).
- `DiscoverySuite` and `DistributedTestRunnerSuite` reach no site that registers, so neither is a node (`../../SPECIFICATION.md` R-096): the suites a `DiscoverySuite` runs start in the run's context, and the instance a `DistributedTestRunnerSuite` runs in the context `ParallelTestExecution.runTest` handed to the distributor, its suite's run outside its tests.
- The emitter keeps a node table for each suite node, by node path, read and written under its write lock. It holds:
  - the suite's name, `suiteId` and class name, and the parent and position its first `structure` gave it;
  - the scopes and tests of its latest `structure`, against which a further `structure` is compared (§ Further `structure` events);
  - its tests by test name, which `SuperEngine.registerTest` keeps unique within a suite;
  - its scopes by path: from the suite down, each scope's registered text with its index among the scopes of that text under the same parent, since `SuperEngine.registerNestedBranch` accepts sibling scopes of one text. The text is the one the scope is registered with, which each engine's `registeredNodes` gives beside the scope's name;
  - the parent of each scope and test;
  - the executions of the suite and of each of its scopes and tests (§ Executions).

  A table holds no suite instance.
- As it fixes an instance's node path, the registration looks the path up in the tables:
  - a `structure` holds the node: the instance's tree is compared with the table (§ Further `structure` events);
  - otherwise, as for a suite a test runs or one that starts beside more siblings sharing its `suiteId` than its parent's `structure` declared, the emitter writes a `structure` for the instance (§ Further `structure` events). It holds the instance's suite node, with its parent, and its scopes and tests, and none of its nested suites: each gets its own as it starts.
- Under this rule:
  - a nested suite that `Suite.runNestedSuites`, `StepwiseNestedSuiteExecution.runNestedSuites`, a user's `runNestedSuites` override, `Doc.runNestedSuites` or `DocSpecLike.runNestedSuites` runs starts in its outer suite's context, and is the nested suite at its place in the outer suite's `structure`;
  - a suite a test body runs, such as `new HelperSuite().run(None, args)`, starts in the test's context, and is a child of that test;
  - a suite a `run` override builds with another `suiteId` starts in the suite's context, and is a nested suite of it (`../specification/test-event-log-executions.md` AC-136, AC-137, AC-155).

### An event's node

- Each engine site that sends an event for a registered instance passes an `org.scalatest.EventOrigin` to the send helper of `object Suite` it calls (`reportTestStarting`, `reportScopeOpened`, `reportInfoProvided` and the rest, which already take `theSuite`). The site gets the `EventOrigin` from the registration in its context; it holds the event's node and executions (§ Executions). Each helper takes it through an overload beside its 3.2.20 signature, which passes `EventOrigin.none`. With the emitter on, the helper hands the event to the emitter with its origin, then reports it (`../../decisions/architecture-test-structure.md` "An event core sends for a registered instance is handed to the emitter where it is sent…"). The event is unchanged (`../../SPECIFICATION.md` R-002).
  - The node is the test's for a test's event: the suite's node path and the test's name. For a scope's event, or for a message registered in that scope, it is the scope's path, which the site builds from the `DescriptionBranch` that `runTestsInBranch` holds. A suite-level message's is the suite's.
  - Nothing reads the order in which scopes open and close.
- An event without an origin is handed off by the innermost wrapper it reaches, with that wrapper's node. Such events are those user code builds, a top-level suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted`, and those of an instance that is not registered. The emitter assigns it from the event's own data at or below the wrapper's node. Its `suiteId`, or a message's `nameInfo`'s, names the suite, and its test name names the test in that suite's table. Among suites sharing that `suiteId`, it is the wrapper's own suite where that is one, else the first in node-path order that has no `SuiteCompleted` or `SuiteAborted`.
- An event without an origin whose data names a suite or test no table holds is still assigned to its own node, and a further `structure` precedes the event:
  - a `suiteId` no suite at or below the wrapper's node has is given a nested suite node directly under the wrapper's node, its node path the wrapper's followed by that `suiteId` at index 0, with the `suiteName` and `suiteClassName` the event carries;
  - a test name its suite's table lacks is given a test node directly under the suite, named and labelled as a test of a suite with no registration tree is.
- An event of a test that the suite's latest structure drops, but that an earlier structure held, keeps that test's node ID. Assembly keeps the dropped node (`consolidation-library.md` § Critical code paths § A node's executions and its suite's latest structure). Where the running instance's tree holds the test, the emitter first adds it back to the latest structure (§ Further `structure` events).
- A message with no origin whose `nameInfo` is `None` is assigned to the wrapper's own node, since nothing identifies another (`../../SPECIFICATION.md` R-097; `../specification/test-event-log-events.md` AC-107, AC-151).
- A `SuiteAborted` whose `suiteClassName` is `DistributedTestRunnerSuite`'s is assigned to the node of the wrapper that hands it off, the test that suite ran (`../specification/test-event-log-structure.md` AC-126).
- The emitter writes each message a test's closing event carries in its `recordedEvents` as an event of its own, before the closing event and assigned as it is: to the same node, in the same executions. Reporters receive those messages only inside the closing event (`../specification/test-event-log-executions.md` AC-140). The emitter skips this for a closing event whose origin says its messages were handed off, since the test's informer and documenter have already handed them over (§ A test's messages).
- The emitter encodes an event, and the messages it records, before it takes the write lock, as it encodes a run's event (§ The test event log on the JVM). Under the lock it finds the node of an event without an origin, numbers the event's executions and writes its lines.

### Further `structure` events

- A further `structure` is built without calling an overridable member of the suite. `testNames`, `tags`, `nestedSuites` and `expectedTestCount` are user code that can throw, and inside `run` 3.2.20 calls them only where it needs them (`../../SPECIFICATION.md` R-002).
  - For a suite with a registration tree it is built from `atomic` alone:
    - its nodes and labels are built as the first structure's are;
    - each test an earlier structure of the suite filtered keeps that result: dropped, ignored or neither. The node table keeps each test's result, because the first structure is filtered by the suite's own `tags`, as `runTestsInBranch` filters each test, and a further structure cannot call `tags`;
    - each other test is filtered by `args.filter` over `tagsMap`, with the class's tag annotations applied as `Suite.autoTagClassAnnotations` applies them but to the tree's own test names. A suite whose `tags` override keeps a test that this filtering drops gains the test by the last case of the next list. Where its `tags` override drops a test this filtering keeps, the further structure holds a test node no event names. That is accepted: only a test no earlier tree of the suite held reaches this filtering, and detecting the case would mean calling `tags`;
    - it takes the table's `suiteId` and class name, where the table exists. A suite therefore keeps the class name its first `structure` gave, whichever instance runs.
  - For a suite with none, `Suite.runTests` builds it from the names `filter(theTestNames, tags, suiteId)` returns to its own loop.
  - It holds the suite's scopes and tests and no nested suite, which `atomic` does not hold. Each nested suite keeps the structure and place it has (§ The format).
- It is written, before the first event that needs it:
  - for a registered instance, by the core site that first reads the last input it needs (§ Suite instances and their nodes): its `suiteId`, and its `suiteName` where no `structure` holds its node. Where a `structure` holds the node, it is written only where the instance's tree after filtering differs from the table in any node, parent, position or label. It therefore precedes every scope and test event of that instance. For a registered instance with no registration tree, it is built from the names `Suite.runTests`' filter returns, so `Suite.runTests` writes it. This covers:
    - the instance `ConfigMapWrapperSuite.run` runs (`../specification/test-event-log-structure.md` AC-131);
    - an instance `newInstance` creates;
    - a nested suite's instance where `nestedSuites` returns new instances on each call;
    - one of several sibling suites sharing a `suiteId` that started out of declared order;
    - a suite a test runs;
    - a suite that starts beside more siblings sharing its `suiteId` than were declared (`../specification/test-event-log-executions.md` AC-164);
  - by an engine site about to send an event for a test or scope of its tree that the table lacks, such as a test the filter drops that a `runTests` override runs. It adds that node and the scopes around it to the suite's latest structure.

### Wrappers

- A site that installs a top-level suite's wrapper calls a function, a `(Suite, Filter, Reporter) => Reporter`, with the suite, the filter the suite is run with and the reporter 3.2.20 gives the suite. It sends the suite's `SuiteStarting` to the reporter the function returns, and runs the suite with it. A site calls the function only for a suite that is to start, after any check of `stopper.stopRequested` that keeps the suite from starting, so a suite that never starts has no structure (`../../ARCHITECTURE.md` § Data flow, "A top-level suite the run never started sent nothing"). A run without the runner argument is given a function that returns the reporter it is passed. The function reaches each site as follows:
  - `Framework`'s `runSuite` takes it as a parameter, through its task, and calls it once it has built the suite's filter. `runSuite` checks no stopper.
  - `SuiteRunner` takes it as a constructor parameter, and calls it in `run` after its check of `stopper.stopRequested`. `Runner.doRunRunRunDaDoRunRun` takes the function as a parameter and gives it to the `SuiteRunner` of each suite it runs, directly or under `-P` through `ConcurrentDistributor.apply`. For a `DiscoverySuite` it gives the function that returns the reporter it is passed.
  - A `DiscoverySuite` takes it as a constructor parameter (below).
- With the emitter on, the function is `TestEventLogEmitter.wrapperOfTopLevelSuite`. It reads the suite's structure, outside the write lock, since reading calls the suite's `suiteName`, `suiteId`, `testNames`, `tags` and `nestedSuites` and those of its nested suites. It then fills the tables of the suite and its nested suites, writes the `structure` event and returns the wrapper.
  - A structure that cannot be read ends the event log before it and closes the file, as an event that cannot be encoded does (§ The test event log on the JVM). The function then returns the reporter it was passed, and keeps what reading threw from its caller as a hand-off does.
- `Suite.runNestedSuites` and `StepwiseNestedSuiteExecution.runNestedSuites` register each nested suite as they build its `SuiteStarting`, which fixes its node path, and install its wrapper, naming that path, before sending it. They send the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` with its origin, so each is handed off at its send site and lands on the node the instance's own events name, under `-P` too.
  - In the sequential branch, `callExecuteOnSuite` sends the nested suite's `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` through the outer suite's reporter and builds the nested suite's `Args` only after `SuiteStarting`. The wrapper takes that reporter's place, both for those events and in the `Args`.
  - In the `-P` branch of `Suite.runNestedSuites`, which reads no `suiteId`, it registers each nested suite as it hands it to the distributor, in a context naming that registration, and puts the wrapper, naming the registration, in the `Args` it gives the distributor. The distributor's `SuiteRunner` sends those events with the registration's origin, and its `SuiteStarting` makes the first read of the suite's `suiteId`, which fixes the node path. Nested suites sharing a `suiteId` therefore take their indexes in the order the pool starts them, which can differ from declared order (`consolidation-library.md` § Critical code paths § A node's executions and its suite's latest structure).
  - Where `thisSuite` is a `DiscoverySuite`, each nested suite is top-level: its wrapper is a top-level wrapper (`../specification/test-event-log-structure.md` AC-099), and its `SuiteStarting`, `SuiteCompleted` and `SuiteAborted` carry no origin, as `Framework`'s and `SuiteRunner`'s for a top-level suite do. `DiscoverySuite` overrides two `private[scalatest]` members of `Suite` with the function its constructor takes:
    - In the sequential branch, `callExecuteOnSuite` asks `thisSuite` for the nested suite's reporter through `Suite.reporterOfNestedSuite`, which in `Suite` returns the reporter it is passed. `Suite.runNestedSuites` calls `callExecuteOnSuite` only where no stop is requested.
    - In the `-P` branch, `Suite.runNestedSuites` hands each nested suite to the distributor through `Suite.distributeNestedSuite`, which in `Suite` calls the distributor. `DiscoverySuite`'s override gives `Runner`'s `ConcurrentDistributor` the function, and the nested suite's `SuiteRunner` calls it after its stop check, on the pool's thread. The distributor starts each suite as it is handed it, so one nested suite can be running before the structure of the next is read and written.
- `Engine.runTestsImpl`, `AsyncEngine.runTestsImpl` and `Suite.runTests` install the test's structure wrapper, which names the test's node, in the `Args` they pass to `runTest`, not inside `runTestImpl`. `ParallelTestExecution.runTest` builds both the `Args` it gives the distributor and the `DistributedTestRunnerSuite` running that test from those `Args`. When that `DistributedTestRunnerSuite` throws, the distributor's `SuiteRunner` sends its `SuiteAborted` through those `Args`' reporter, so the event reaches the test's wrapper first (`../specification/test-event-log-structure.md` AC-126). With the wrapper inside `runTestImpl`, the event would reach first the suite's wrapper that `ParallelTestExecution.runTests` puts over its `TestSortingReporter` (below).
  - Given a test name and a test sorter, `ParallelTestExecution.run`, `RandomTestOrder.run` and `AsyncEngine.runImpl` replace the reporter with a `TestSpecificReporter`, and wrap it in that test's wrapper. `AsyncEngine.runImpl` does so only in an async suite that mixes in neither `ParallelTestExecution` nor `RandomTestOrder`, and only where its caller passes a test sorter. `OneInstancePerTest` passes none: its `runTests` sets `runTestInNewInstance` before `AsyncEngine.runTestsImpl` runs, so `AsyncEngine.runTestsImpl` creates no `TestSortingReporter` for the instance that creates the per-test instances.
- `ParallelTestExecution.runTests`, `RandomTestOrder.run` and `AsyncEngine.runTestsImpl` each put a wrapper for the suite over the `TestSortingReporter` they create.
- A site of an instance that is not registered installs no wrapper.
- An event reaches every wrapper between its sender and `DispatchReporter`: its test's, its suite's and each enclosing suite's, interleaved with other reporters such as `TestSortingReporter`, `CatchReporter`, `StopOnFailureReporter` and a user's. An event with an origin was handed off at its send site, and no wrapper hands it off. Of an event without one, only the innermost wrapper it reaches hands it off.
  - The emitter keeps an identity set of the events it has been handed, by a send helper or a wrapper (`../TECHNICAL-DESIGN-DECISIONS.md` "A wrapper tells an event the emitter already has…"). Under the emitter's write lock, a wrapper hands off only an event not in the set, and adds it.
  - The top-level suite's wrapper, the last wrapper any event of its suite reaches, removes the event from the set instead of adding it.
  - Once the top-level suite's run completes, the emitter moves the events of that suite's run still in its set into an identity set on the top-level suite's wrapper, read and written under the write lock, so the set is dropped with the wrapper. An event in it that still reaches the wrapper is removed and not written again, as an alert in flight from a thread the run started, or one a reporter between the wrappers held back. The wrapper hands off each other event it then receives, assigned by its own data.
- `wrapReporterIfNecessary` adds a `CatchReporter` over any reporter that is not one, so it adds one over a wrapper put where 3.2.20 passes a `CatchReporter` on. That layer forwards every event. A wrapper catches whatever its own hand-off throws and then passes the event on, so the layer never prints `Resources.reporterThrew` for the emitter, and no reporter misses an event (`../../SPECIFICATION.md` R-002).

### Executions

- Core mints an execution as an object in the emitter's state. The emitter gives it a node's next number, from 1, when it first writes an event whose execution path names it for that node.
- An event's *execution path* is, for each node from the top-level suite down to its own node, the number of that node's execution the event's execution ran in. The nodes are the places of its node path and, for each test on it, the scopes between that test's suite and it in the latest `structure` holding the test, so the path cannot be derived from the node path alone. It grows with the depth of the node (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, item 3).
- `Engine.runTestImpl` and `AsyncEngine.runTestImpl` mint one on each call, for the test's `TestStarting`, its closing event and its messages. A `runTest` override that calls `super.runTest` twice therefore gives its test two (`../specification/test-event-log-executions.md` AC-140).
- `traverseSubNodes`, `runTestsImpl` and `Suite.runTests` mint one for each `TestIgnored` they send.
- The registration starts an execution for its run, and keeps it in the registration.
  - The suite takes it, for the run's suite-level messages. `runTestsInBranch` mints an execution of a scope each time it opens the scope, so a scope that a run opens twice has two executions within one suite execution. Where the suite's `SuiteStarting` opened an execution that no run has taken, the run takes that one.
  - The run of an instance that takes the node of the suite running it (§ Suite instances and their nodes) starts none: it uses that suite's.
  - A path-style suite (`PathAnyFunSpec` or `PathAnyFreeSpec`) runs each test's body in an instance of its own as the suite is constructed, but those instances send no event. The instance a site runs replays each test's stored outcome in `Engine.runTestImpl`, so all the suite's events are that instance's and take its registration. As for any suite with a registration tree, each test's and scope's events are assigned to their own nodes, in a new execution of the test and of each scope.
- An origin names the event's execution path: its own node's execution, that of each scope above it, and that of each suite and test above those, which the registration keeps from the context its run started in. A scope can have several executions within one suite execution, as where a `runTests` override runs a branch twice, and a test's path names the one it ran in. The emitter writes the execution path (§ The format), so a test's execution sits under the scope execution it ran in where runs of one suite overlap.
  - The later sites of a run, `runTestsImpl`, `runTestImpl` and `Suite.runTests`, find the run's execution in the registration of their context. `runTests` and `runTest` are overridable and pass only `Args`, which carries no execution.
- A test's execution closes with its closing event, a scope's with `ScopeClosed` or `ScopePending`, and a suite's with `SuiteCompleted` or `SuiteAborted`. A `TestIgnored` opens and closes its own.
- For an event with no origin, the emitter writes (`../specification/test-event-log-events.md` AC-134, AC-152, AC-153; `../specification/test-event-log-executions.md` AC-156):
  - the execution of its node that opened last and has not closed;
  - where none is open, a new one for an opening event (`SuiteStarting`, `TestStarting`, `ScopeOpened` or `TestIgnored`), and for any other event the node's latest, or a first one where it has none;
  - for each node above it on its execution path, the scopes above each test on it included, that node's execution open when the event is handed off, else its latest.

### A test's messages

- A test's `info` and `markup` reach reporters only in its closing event's `recordedEvents`, after its `note`s and `alert`s and with ordinals taken at the close (`Engine.runTestImpl`, `AsyncEngine.runTestImpl`). Neither the order events arrive in nor their ordinals give the order the test called them (`../specification/test-event-log-events.md` AC-105). With the emitter on, the test's informer and documenter therefore also hand each message to the emitter when called. With the emitter off they behave as in 3.2.20.
  - The informer and documenter hand each message to the emitter with the execution `runTestImpl` minted, never to a reporter (`../../SPECIFICATION.md` R-002). The emitter assigns it to the test, in that execution.
  - The test's `TestStarting`, `note`s and `alert`s are handed off at their send sites, so the emitter writes a test execution's messages in call order among them.
  - The `MessageRecorder` records a message and hands it off under its own lock. Reading its `recordedEvents` for the closing event stops every later hand-off under that lock. A message the closing event records has therefore been handed off before that event is reported. A message the test calls after it, from another thread, is never handed off: it is lost, as it is to reporters in 3.2.20.
    - The hand-off is split in two. The prepare step, `EventOrigin.preparedHandOffOfMessage`, runs before the recorder lock is taken: it builds the message's event, which calls the suite's own `suiteName` and `suiteId`, and encodes it. Only the commit step it returns runs under the recorder lock: it hands the encoded event to the emitter, which writes it under the write lock. With the emitter off the prepare step builds nothing.
  - `runTestImpl` reports the closing event with the origin `EventOrigin.ofClosingEventWithMessagesHandedOff` returns. The emitter leaves the recorded events of such an event out of the event log.
  - The hand-off takes no ordinal from `tracker`, so every event reporters receive keeps its 3.2.20 ordinal (`../specification/compatibility-and-library.md` AC-056). The event log carries no ordinal, since no output shows or uses one.
  - The message's timestamp is the time of the call (`../specification/test-event-log-events.md` AC-105).
- The messages of a path-style test, one of `PathAnyFunSpec` or `PathAnyFreeSpec`, never pass through that hand-off. `PathEngine.handleTest` runs the test's body while its suite is constructed, before the suite's `structure` and `SuiteStarting`. It gives the messages to four recorders, `PathMessageRecordingInformer`, `PathMessageRecordingNotifier`, `PathMessageRecordingAlerter` and `PathMessageRecordingDocumenter` in `org/scalatest/ConcurrentInformer.scala`. Each keeps its own queue, with no order across the four and no call time. Only the informer's queue reaches `TestLeaf.recordedMessages`, and `Engine.runTestImpl`, replaying the stored outcome, adds only the informer's events to the closing event's `recordedEvents`.
  - The four recorders of one test also append each call to one shared call sequence, `PathMessageCalls`. An entry holds what its recorder stores of the call, the recorder's event function and the time of the call. The informer holds the sequence, so it reaches `TestLeaf.recordedMessages` with the informer, and neither `registerTest` nor `TestLeaf` changes.
  - With the emitter on, `Engine.runTestImpl` replaying a path-style test hands the sequence's messages, in order, to the emitter after `TestStarting` and before the closing event, as the informer's are handed over at call time. Each message is built by its entry's event function, given a fresh `Tracker` in place of `tracker` so that it takes no ordinal from the run's. Its `timeStamp` is then set to the entry's call time.
  - The closing event's `recordedEvents` are built from `tracker` as in 3.2.20, emitter on or off, so the test's notes, alerts and markup still reach no reporter (`../specification/compatibility-and-library.md` AC-056).

### Runs that pass no run-entry site

- `TestsBeforeNestedSuites.run` calls neither `super.run` nor its style's `runImpl`. It registers its instance itself (§ Suite instances and their nodes). In 3.2.20 such a suite has no suite-level informers, so it sends no suite-level message.
- A user's `run` that calls no `super.run`:
  - where it calls `runTests` or `runNestedSuites`, those sites register the instance;
  - where it sends events itself, they have no origin. The innermost wrapper they reach assigns each from its own data (§ An event's node).
- `Doc.runNestedSuites` and `DocSpecLike.runNestedSuites` run each included suite with their own `Args` and send no `SuiteStarting` or `SuiteCompleted` for it. The suite starts in the outer suite's context, as any nested suite does.
- `BeforeAndAfterAll.run` calls `beforeAll()` before `super.run` reaches a site that registers. A helper suite run from `beforeAll()` therefore starts in its suite's context, as a nested suite of that suite, only where the suite was registered before its run began: a top-level suite, or one `runNestedSuites` runs (`../specification/test-event-log-executions.md` AC-166). Otherwise it starts in the context of the suite or test that ran the suite, as the suite's sibling. Either way its events are handed off at their send sites whatever reporter the user gives it.

### The format

Each event is a JSON object (`../TECHNICAL-DESIGN-DECISIONS.md` "Each event of a run's test event log is a JSON object."), encoded as `../../JSON-LAYOUT.md` § Encoding gives, except that keys are written in the order this section gives. Escaping control characters in strings keeps each event to one line.

- A lone surrogate's `\uXXXX` escape decodes back to the same `char`.
- Integers are JSON numbers, read as `Long`, never through `Double`.
- An empty array omits its key.

Each event's first key is `item`, naming its kind. The events are those of `../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, and the keys of each, in order, are:

- The `runStarted` event: `item` `runStarted`; `pid`; `startTime`, the text `Instant.toString` gives, read back with `Instant.parse`, so it equals `ProcessHandle`'s exactly.
  - Core's codec takes and yields `startTime` as that text, since core also builds for Scala.js, which has no `java.time`. The emitter's JVM sources format it and the library parses it.
- A `structure` event: `item` `structure`; `nodes`, an array of the suite's nodes in depth-first pre-order, each node's children in declared order. A node's keys are `id`, `kind` (`suite`, `scope` or `test`), `parent` (its parent's `id`), `position` (its declared position, from 0), `name`, `label`, `suiteId` and `className`, each present only on the nodes `../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin gives it. A node's `id` is its node path, an array of its places from its top-level suite down: a suite's `{"suiteId": …, "index": …}`, a scope's `{"scope": …, "index": …}`, its registered text and its index among the scopes of that text under its parent, and a test's `{"test": …}`, its test name, directly after its suite's place. A suite's `parent` is its path less its last place, a scope's likewise, and a test's the `id` of the scope or suite the `structure` puts it in.
  - The first node is the event's root suite. A top-level suite has no `parent` or `position`. A nested suite that is the root has `parent`, the suite or test it sits under, and the `position` an earlier event gave it; one new to the event log has none (`consolidation-library.md` § Critical code paths § A node's executions and its suite's latest structure).
  - A further `structure` event has the same form. Its root is a suite an earlier event holds, or a suite new to the event log under a suite or test an earlier event holds. It holds the root's scopes and tests whole, their `position`s from 0 among themselves, and none of its nested suites. A suite an earlier event holds keeps its `className`.
- A ScalaTest event:
  - `item` `event`;
  - `node`, the node path of the node the event is assigned to, in the form of a node's `id`, absent where it is assigned to the run;
  - `executions`, the event's execution path: an array of execution numbers, from 1, one for each node from the top-level suite down to the event's node, each test's scopes included, present exactly where `node` is;
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
  - `stackTrace`, its frames, a `StackDepth`'s included, each a frame with the keys `../../JSON-LAYOUT.md` § Throwables gives a frame, each absent where that section says, followed by `text`, its `StackTraceElement.toString`, which on JDK 9 and later can hold a module and a class loader name.
  - `cause`; `suppressed`, in order. Core's codec writes and reads nested values without recursion, so a chain of causes of any length is written and read whole, whatever the thread's stack size.

A line cannot be decoded where it:

- is not one JSON object, or repeats a key within an object;
- holds a key this section does not give its object;
- lacks a required key;
- holds an `item`, `event`, `kind` or `outcome` value this section does not give;
- holds a value whose JSON type is not the one this section gives its key, `null` included;
- holds an integer that does not fit its field's `Int` or `Long`, or one below the least this section gives its key: 0 for a `position` and an `index`, 1 for an element of `executions` and a throwable's `id`;
- holds a `ref` to an `id` the event has not yet written, or two throwables with one `id`.
