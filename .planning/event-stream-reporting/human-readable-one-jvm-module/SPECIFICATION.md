# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest end-to-end path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the test command's stdout.

## Plan

[Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54)

## Spec documents

- [specification/layout.md](specification/layout.md) — acceptance criteria for the human-readable output's layout: its tree, where each line sits, each line's text and colour, messages, collapsed subtrees, and Mill's prefix.
- [specification/runs-and-test-structure.md](specification/runs-and-test-structure.md) — acceptance criteria for which suites, scopes, tests, failures and captured output the human-readable output shows, how often and in what order, and a run's block.
- [specification/test-command.md](specification/test-command.md) — acceptance criteria for the test command's arguments, how it runs the module, its result, what else reaches the console, and Mill's own test tasks and BSP server.
- [specification/test-event-log.md](specification/test-event-log.md) — acceptance criteria for what a run writes to its test event log on the JVM, and for the ScalaTest behaviour that stays unchanged.
- [specification/compatibility-and-library.md](specification/compatibility-and-library.md) — acceptance criteria for compatibility with ScalaTest 3.2.20, and the consolidation library without Mill.
- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this specification.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this specification are the epic's.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture: the modules this feature starts, their parts (§ Modules), and the boundaries between them, including the test event log's events and how it carries them (§ APIs § The emitter and the Mill plugin).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.
- [../decisions/event-log-contract.md](../decisions/event-log-contract.md) — the epic's decisions on what a test event log carries and who may read it.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the feature's non-obvious technical choices, including the test event log's encoding.
- [research/jvm-pid-and-start-time.md](research/jvm-pid-and-start-time.md) — which JDKs a Mill 1.x test worker can run on, and which readings of a process's PID and start time match `ProcessHandle`'s.
- [research/mill-plugin-conventions.md](research/mill-plugin-conventions.md) — how Mill 1.x plugins are named, loaded and tested under testkit, and how the Mill internals the plugin drives differ between Mill 1.1.10 and 1.3.1.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- A ScalaTest runner argument on the JVM that turns the emitter on, so the run writes its test event log to a file.
- The consolidation library, producing human-readable output, including each run's block, and giving its caller the consolidation state.
- The Mill plugin's test command, invoked as `mill scalatest/test <module selector> [arguments]`, such as `mill scalatest/test core.test -oH`. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to its stdout.
- A build adds the Mill plugin as the entry `com.edc::scalatest-mill::<version>` of its `build.mill` header's `mvnDeps`, `<version>` being the plugin's release. A header without `mvnDeps` gains one line: `//| mvnDeps: ["com.edc::scalatest-mill::<version>"]`.
- The test command's output arguments `-oH` and a bare `-o`.
- The test command fails before any test starts, with no consolidated output, when given any other output argument, such as `-oP` or an `-f`, or module selectors other than one that resolves to one JVM test module.
- The test command supports Mill 1.3.1 only.
- A module that does not test with this fork, such as one testing with MUnit, uTest or upstream ScalaTest 3.2.20, fails the test command.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes, or deferred where that section is § Deferred.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters, and `-f` for human-readable output (§ 16).
- A module that presets a test command's arguments (§ 17).
- A module that sets `testBatchFrameworkTasks`, whatever its worker count, and a module for which Mill's `testForked` starts more than one worker, as it can with test parallelism on and more than one test class, and does with several fork groups (§ 3).
- A run given no task (§ 3).
- Class globs and framework arguments on the test command, the module's JUnit XML report, the paths Mill's test tasks put in their task folders, and the environment variables Mill's launcher sets from its own command line (§ 4).
- Running the tests of more than one module selector, or of a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (§ 5).
- Failing a module that does not test with this fork before any test starts, naming it, with no consolidated output (§ 5).
- A module that tests with a release of this fork other than the plugin's (§ 5).
- An unsupported Mill release (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- The emitter, consolidated output and the test command on Scala.js and Native (§ 11–15).
- Adding the plugin through `mvnDeps` on `mill-build/build.mill`'s `MillBuildRootModule`.
- Writing these to the test event log: `Runner`'s `-A` rerun alerts, the events of the reruns that `Runner`'s `-g` graphical reporter starts, and the events `Framework` relays from a process sbt forks.
- Showing a test's several executions as one line (`../ROADMAP.md` § Deferred).

## Epic requirements met

This feature meets the requirements of the first bullet for any number of modules and workers. It meets the requirements of every other bullet for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, this feature meets only the part the qualifier names.

- R-001, R-002, R-051, R-003 (on Mill 1.3.1, apart from test runs an IDE starts through Mill's BSP server), R-004, R-060 (on Mill 1.3.1).
- R-063, R-011 and R-074, for human-readable output.
- R-096, R-130, R-097, R-098, R-099, R-100 (runs that finish), R-104, R-105, R-106 (with no formatting or filtering character), R-131, R-133, R-134, R-132 (the name line), R-107 (runs that finish) and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout contains human-readable output); R-053 (second bullet: the working directory, and the environment apart from the variables Mill's launcher sets from its own command line), R-125; R-092, R-072.
- R-020 (human-readable output), R-027 (human-readable output), R-078 (human-readable output, runs that finish), R-079 (human-readable output, runs that finish), R-082 (first two bullets; aborted and stopped from the third), R-101, R-021 (human-readable output, runs that finish), R-012 (output captured against the run, in human-readable output), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046, in these parts:
  - Where a run reports a failure, the test command fails.
  - Where a run does not finish, or a process ends before any run exists in it, this feature covers only that the test command fails.
  - Where a run ends aborted or stopped, this feature covers only that the consolidation state is failed.
  - The test command's failure lists no test.
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.

Of the runtimes and Scala versions `../SPECIFICATION.md` § Scope covers, this feature covers the JVM, in test modules on Scala 3.3 or later. On Scala.js and Native, this feature covers only R-001. On those runtimes, this fork takes no runner argument that turns the emitter on.

## Acceptance criteria

The criteria are in the `specification/` documents listed in § Spec documents and their parts. Each Feature states in its own Background, description or steps the module, run or fixture its scenarios need. The definitions below give a reader the terms and notation the criteria share:

- The module is a JVM test module at module path `core.test`, whose ScalaTest dependency is `com.edc::scalatest` at the release under test. Its build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill::<release>` of the `build.mill` header's `mvnDeps`, without changing the module's definition. A criterion's test command is invoked as `mill scalatest/test <module selector> [arguments]`, and runs the module when given `core.test`. Every JVM test module of a criterion's build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset. Some criteria instead feed a run to the consolidation library directly, without Mill; the run's test run label is then `core.test`.
- *The runner argument* is the ScalaTest runner argument that turns the emitter on. A run given the runner argument outside Mill writes its test event log to a file the argument names.
- *An event log holding* events is, in a `Given`, a run fed directly to the consolidation library as those events, in that order, in the form this fork reads them back from a test event log. In a `Then`, it is the events this fork reads back from the run's test event log. A criterion states an event log as its events, not as the bytes of its format. The events are those a test event log carries: the events this fork adds, `runStarted`, `structure` and `runEnded`, and ScalaTest's events. A criterion writes them one per line:
  - `run started`: the `runStarted` event.
  - `structure`: a `structure` event, with the suite's tree beneath it, one node to a line, each indented 2 spaces under its parent and in declared order: `suite "<name>", class "<class name>"`, `scope "<name>", label "<label>"` or `test "<name>", label "<label>"`. A suite may add `, id "<suite ID>"`; without it, its suite ID is its class name. A test may add `, test name "<test name>"`, its ScalaTest test name. Without it, in a `Given`, its test name is its scopes' names and its own, joined by single spaces; in a `Then`, the line does not fix it. Each node's node path, parent and declared position follow from the tree.
    - `structure under suite "<name>"` contains the tree of a suite that the named suite nests or runs. The named suite is the parent of the block's suite. `structure under test "<name>"` contains the tree of a suite that the named test ran. The named test is the parent of the block's suite. Where no earlier block contains the block's suite, that suite sits directly after the last sibling that shares its suite ID, or, where no sibling shares its suite ID, after its parent's existing children.
    - A later block whose suite line has the suite ID and parent of a suite an earlier block contains is a *further block*: a further `structure` event for a suite that an earlier block contains. Where the further block's suite line adds `#<n>` after the name, the further block's suite is the suite that `#<n>` names, as `#<n>` names an event's node. Where the further block's suite line has no `#<n>`, the further block's suite is the first suite that an earlier block contains with that suite ID and parent. A suite line whose `#<n>` names no suite an earlier block contains adds a new suite. A further block for a suite whose parent is a suite is headed `structure under suite "<parent>"`. A further block for a suite whose parent is a test is headed `structure under test "<parent>"`. A further block contains only the suite's scopes and tests, and the suite's nested suites keep the structure and place they have. A further block gives each scope and test it contains its parent, declared position and label from then on.
    - In a `Given`, a test in a further block is the same node, with the same node path, as the test of the same test name that an earlier block contains. In a `Given`, a scope in a further block is the same node, with the same node path, as the scope of the same name that an earlier block contains at the same place among the scopes of that name under the same parent.
  - `<node>: <event>`: a ScalaTest event, as a test event log carries it, assigned to the node, named by kind and name as `test "is empty"`, or to `run` where it is assigned to the run. A node is named as the latest `structure` before the event names it.
    - Where nodes of one kind share a name, each adds `#<n>`, counting them in the order the event log's structures first contain them: `scope "A Stack" #2`.
    - `<node> execution <n>: <event>` marks an event of the node's `<n>`th execution, counting the node's executions in the order they started: `test "flaky" execution 2: TestStarting`. Without it, the event is of the node's first execution. An execution is *open* from its first event until its node's closing event in it: a test's `TestSucceeded`, `TestFailed`, `TestCanceled`, `TestPending` or `TestIgnored`, a scope's `ScopeClosed` or `ScopePending`, or a suite's `SuiteCompleted` or `SuiteAborted`.
    - `, in <ancestor> execution <m>` after the node marks the execution of that ancestor that the event's execution ran in, one for each ancestor the criterion fixes: `test "helps", in test "first" execution 1: TestStarting`. An ancestor is a suite, scope or test above the node, named as an event's node is, `#<n>` included; a test's scopes are those the latest `structure` before the event puts it in. Several markers are written from the top-level suite down. In a `Given`, an event's execution ran in the following execution of each ancestor the event does not mark: the ancestor's latest execution that is open at that point of the event log; where none is open, the ancestor's latest execution; where the ancestor has no execution yet, its first. In a `Then`, an event does not fix the execution of an ancestor it does not mark. An execution is also open from the first event that marks it.
    - The event is its `org.scalatest.events` class's name, followed in parentheses by the fields the criterion fixes, each as `<field>: <value>`.
    - `analysis` is a list of strings: `analysis: ["Stack(size: 1 -> 0)"]`.
    - A `location` is the `org.scalatest.events.Location` written as the Scala expression that builds it: `LineInFile(7, "FailSuite.scala", None)`, `TopOfClass("samples.FailSuite")`, `SeeStackDepthException`.
    - A throwable is written as its class name, then each of these it has, in this order:
      - its message, as a string, or `null` for a `null` one;
      - `at <file name>:<line number>`, marking it a `StackDepth` whose failed code is at that file and line. Where the criterion fixes the file's pathname, the pathname takes the file name's place, and its last segment is the file name. `?` in place of the file name or the line number marks that value undefined;
      - `property check`, marking it a `PropertyCheckFailedException`;
      - `frames [...]`, its frames in order, each the string `StackTraceElement.toString` gives it, from which the frame's fields follow;
      - `suppressing [...]`, its suppressed throwables in order, each written as a throwable;
      - `caused by` and its cause, written as a throwable, so that `A caused by B caused by C` is one chain.

      `TestFailed(throwable: java.lang.IllegalStateException "outer" frames ["samples.ThrowSuite.f(ThrowSuite.scala:9)"] suppressing [java.lang.RuntimeException "closing"] caused by java.io.IOException "inner")`.
  - `run ended <how>`: the `runEnded` event, where `<how>` is `completed`, `aborted` or `stopped`.

  In an event, a string is written in double quotes, with Java's escapes `\n`, `\r`, `\t`, `\"`, `\\` and `\uXXXX`, which can write a lone surrogate. A list is written in square brackets, its elements separated by `, `, and a number as a decimal literal.

  In a `Given`, a field an event does not write takes a default:
  - a ScalaTest event's `message` is its throwable's message, or `""` where it has no throwable or that message is `null`;
  - a throwable's message is `null`, and a `StackDepth`'s file pathname is absent;
  - every other optional field, such as `throwable`, `location` or `duration`, is absent, and `analysis`, frames and suppressed throwables are empty;
  - any other field, such as a timestamp, a thread name or the `runStarted` event's PID, may have any value.

  A `Given` leaves out each run event, `RunStarting`, `RunCompleted`, `RunStopped`, `RunAborted`, `DiscoveryStarting` or `DiscoveryCompleted`, that its criterion does not exercise.

  In a `Then`, an event fixes only the fields and throwable parts it writes, and `null` fixes a `null` message. An event log *is* the events given, and no others. It *holds* events *in this order* when it contains each, in that order, with any others between them.
- A node has one *execution* for each execution number that an event assigned to the node, or an event marking the node as an ancestor, carries for the node. A scope or test has *started* once the scope or test has an execution. A suite has started once the suite, or a node below it, has an execution.
- In a `Given`, *the run's captured output* is the lines fed to the library with a run fed directly to it, as the output captured from the run's process, each line tagged with its stream. A criterion gives the run's captured output as a table with the columns `stream`, which is `stdout` or `stderr`, and `line`, in the order the lines are fed. Where a criterion does not say when a line is fed among the event log's events, the criterion must pass wherever the line is fed.
- *The console* is what the `mill` command writes to its stdout and stderr. A line of it is *outside the human-readable output* when it is not a line of the human-readable output.
- *Mill's prefix* is the prefix Mill puts on every line the test command's own task writes to stdout, such as `1] `. A line is given without Mill's prefix. A line *sits under* another when it follows it, indented 2 spaces more, with no line between them indented as little as the other. *The lines for* a node are its own lines and every line under them.
- Text and colours the criteria do not fix are those ScalaTest 3.2.20 prints. Colour is on.
- "With every stack frame left out" drops each line under a throwable's first line or `Cause:` line.
- *The compatibility fixture* is a set of suites, at least one of each of these styles that the build for the fixture's runtime ships:
  - `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite` and `FixtureAsyncFunSuite`;
  - `AnyPropSpec` and `FixtureAnyPropSpec`;
  - `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec` and `PathAnyFunSpec`;
  - `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec` and `PathAnyFreeSpec`;
  - `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec` and `FixtureAsyncWordSpec`;
  - `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec` and `FixtureAsyncFlatSpec`;
  - `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec` and `FixtureAsyncFeatureSpec`;
  - `RefSpec`.

  Between them, the suites contain:
  - nested suites;
  - scopes, one of them pending;
  - tests that succeed, fail, are ignored, are pending and are cancelled;
  - `info`, `note`, `alert` and `markup` messages from suites, scopes and tests, and all four from a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test;
  - a suite whose `beforeAll` throws;
  - on the JVM, a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, with a scope;
  - a test that writes to stdout and to stderr.
