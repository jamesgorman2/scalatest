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
- [specification/test-event-log.md](specification/test-event-log.md) — acceptance criteria for what a run's test event log holds on the JVM, and for the ScalaTest behaviour that stays unchanged.
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
- The consolidation library, producing human-readable output, including each run's block (R-082), and giving its caller the consolidation state (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**).
- The Mill plugin's test command, invoked as `mill scalatest/test <module selector> [arguments]`, such as `mill scalatest/test core.test -oH`. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to its stdout.
- A build adds the Mill plugin (`../ARCHITECTURE.md` § Modules § Mill plugin) as the entry `com.edc::scalatest-mill::<version>` of its `build.mill` header's `mvnDeps`, `<version>` being the plugin's release. A header without `mvnDeps` gains one line: `//| mvnDeps: ["com.edc::scalatest-mill::<version>"]`.
- The test command's output arguments `-oH` and a bare `-o` (R-048).
- The test command fails before any test starts, with no consolidated output, when given any other output argument, such as `-oP` or an `-f`, or module selectors other than one that resolves to one JVM test module. This narrows what R-048, R-049 and R-055 allow.
- The test command supports Mill 1.3.1 only.
- A module that does not test with this fork, such as one testing with MUnit, uTest or upstream ScalaTest 3.2.20, fails the test command.

### Out of scope

Each is left to the feature of `../ROADMAP.md` it names, where it names one, or deferred where it names § Deferred.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters (R-127, R-128), and `-f` for human-readable output (R-049) (§ 16).
- A module that presets a test command's arguments (R-126) (§ 17).
- A module that sets `testBatchFrameworkTasks`, whatever its worker count, and a module for which Mill's `testForked` starts more than one worker, as it can with test parallelism on and more than one test class, and does with several fork groups (§ 3).
- A run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, the module's JUnit XML report, the paths Mill's test tasks put in their task folders (R-110), and the launcher's environment variables (R-111) (§ 4).
- Running the tests of more than one module selector, or of a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet) (§ 5).
- Failing a module that does not test with this fork before any test starts, naming it, with no consolidated output (R-059) (§ 5).
- A module that tests with a release of this fork other than the plugin's (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- The emitter, consolidated output and the test command on Scala.js and Native (§ 11–15).
- Adding the plugin through `mvnDeps` on `mill-build/build.mill`'s `MillBuildRootModule`.
- Writing these to the test event log: `Runner`'s `-A` rerun alerts, the events of the reruns that `Runner`'s `-g` graphical reporter starts, and the events `Framework` relays from a process sbt forks.
- Showing a test's several executions as one line (`../ROADMAP.md` § Deferred).

## Epic requirements met

The first bullet's requirements hold in full. Each other holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-004.
- R-060.
- R-010, R-063, R-011, R-074.
- R-096, R-130, R-097, R-098, R-099, R-100 (runs that finish), R-104, R-105, R-106 (with no formatting or filtering character), R-131, R-133, R-134, R-132 (the name line), R-107 (runs that finish) and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout holds human-readable output); R-053 (second bullet, except where R-110 and R-111 override it), R-125; R-092, R-072.
- R-020 (human-readable output), R-027 (human-readable output), R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-101, R-021 (human-readable output, runs that finish), R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046 (first and second bullets; for a run that does not finish and a process that ends before any run exists in it, only that the test command fails; for a run that ends aborted or stopped, only that the consolidation state is failed).
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.
- `../SPECIFICATION.md` § Scope: the JVM, in test modules on Scala 3.3 or later.

## Acceptance criteria

The criteria are in the `specification/` documents listed in § Spec documents and their parts. In them:

- The module is a JVM test module at module path `core.test`, whose ScalaTest dependency is `com.edc::scalatest` at the release under test, in a build that adds the Mill plugin at that release by its header line (§ Scope) without changing the module's definition (R-092). A criterion's test command is § Scope's invocation, and runs the module when given `core.test`. Every JVM test module of a criterion's build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset. Some criteria instead feed a run to the consolidation library directly, without Mill; the run's test run label is then `core.test`.
- *The runner argument* is the ScalaTest runner argument that turns the emitter on (§ Scope). A run given it outside Mill writes its test event log to a file it names.
- *An event log holding* events is, in a `Given`, a run fed directly to the consolidation library as core's decoder would yield those events, in that order. In a `Then`, it is what core's codec decodes from the run's test event log. A criterion states an event log as its events, not as the bytes of its format. The events are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, one per line: the consolidator events, `runStarted`, `structure` and `runEnded`, and the ScalaTest events:
  - `run started`: the `runStarted` event.
  - `structure`: a `structure` event, with the suite's tree beneath it, one node to a line, each indented 2 spaces under its parent and in declared order: `suite "<name>", class "<class name>"`, `scope "<name>", label "<label>"` or `test "<name>", label "<label>"`. A suite may add `, id "<suite ID>"`; without it, its suite ID is its class name. A test may add `, test name "<test name>"`, its ScalaTest test name. Without it, in a `Given`, its test name is its scopes' names and its own, joined by single spaces; in a `Then`, the line does not fix it. Each node's ID, parent and declared position follow from the tree.
    - `structure under suite "<name>"` holds the tree of a suite nested in the named suite, which is its parent, and `structure under test "<name>"` the tree of a suite the named test ran. Where no earlier block holds that suite, it sits after its parent's existing children.
    - A later block is a further `structure` event for a suite where an earlier block holds a suite with the same suite ID under the same parent, at the same place among the siblings sharing that ID. A nested suite's further block is headed `structure under suite "<parent>"`. A further block holds the suite's scopes and tests only: its nested suites keep the structure and place they have. It gives each scope and test its parent, declared position and label from then on.
    - In a `Given`, a node of a further block is the node of an earlier block, with the same node ID, where it is a test of the same test name, or a scope of the same name at the same place among the scopes of that name under the same parent.
  - `<node>: <event>`: a ScalaTest event, a member of the decoded event family (`../ARCHITECTURE.md` § Modules § ScalaTest core, Codec) assigned to the node, named by kind and name as `test "is empty"`, or to `run` where it is assigned to the run. A node is named as the latest `structure` before the event names it.
    - Where nodes of one kind share a name, each adds `#<n>`, counting them in the order the event log's structures first hold them: `scope "A Stack" #2`.
    - `<node> execution <n>: <event>` marks an event of the node's `<n>`th execution, counting the node's executions in the order they started: `test "flaky" execution 2: TestStarting`. Without it, the event is of the node's first execution. An execution is *open* from its first event until the event that ends it.
    - `, in <ancestor> execution <m>` after the node marks the execution of that ancestor that the event's execution ran in, one for each ancestor the criterion fixes: `test "helps", in test "first" execution 1: TestStarting`. An ancestor is a suite, scope or test above the node; a test's scopes are those the latest `structure` before the event puts it in. For each ancestor not marked, in a `Given`, that is the ancestor's latest execution open at that point of the event log, else its latest, else its first; in a `Then`, the event does not fix it. An execution is also open from the first event that marks it.
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
  - any other field, such as a timestamp, a thread name or the `runStarted` event's PID, may hold any value.

  A `Given` leaves out each run event (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, item 3) its criterion does not exercise.

  In a `Then`, an event fixes only the fields and throwable parts it writes, and `null` fixes a `null` message. An event log *is* the events given, and no others. It *holds* events *in this order* when it holds each, in that order, with any others between them.
- A node has an *execution* for each execution number the events assigned to it, or marking it as an ancestor, carry for it. A scope or test has *started* once it has one. A suite has started once it, or a node below it, has one.
- *The run's captured output* in a `Given` is the lines fed with a run fed directly to the library, as the output captured from its process, each tagged with its stream. It is given as a table of `stream`, `stdout` or `stderr`, and `line`, in the order the lines are fed. Where the criterion does not say when a line is fed among the event log's events, the criterion holds wherever it is fed.
- *The console* is what the `mill` command writes to its stdout and stderr. A line of it is *outside the human-readable output* when it is not a line of the human-readable output.
- A line is given without Mill's prefix (`specification/layout.md` AC-044). A line *sits under* another when it follows it, indented 2 spaces more, with no line between them indented as little as the other. *The lines for* a node are its own lines and every line under them.
- Text and colours the criteria do not fix are ScalaTest 3.2.20's (R-106). Colour is on (R-124).
- "With every stack frame left out" drops each line under a throwable's first line or `Cause:` line.
- *The compatibility fixture* is a set of suites, at least one of each style `specification/test-event-log-structure.md` AC-103 names that the build for the fixture's runtime ships, holding between them:
  - nested suites;
  - scopes, one of them pending;
  - tests that succeed, fail, are ignored, are pending and are cancelled;
  - `info`, `note`, `alert` and `markup` messages from suites, scopes and tests, and all four from a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test;
  - a suite whose `beforeAll` throws;
  - on the JVM, a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, with a scope;
  - a test that writes to stdout and to stderr.
