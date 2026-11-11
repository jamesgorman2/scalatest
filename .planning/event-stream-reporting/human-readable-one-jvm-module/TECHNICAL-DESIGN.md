# Consolidated human-readable output for one JVM module: technical design

## Summary

The emitter lives in scalatest-core, which must compile on JDK 8, where `java.lang.ProcessHandle` does not exist (`research/jvm-pid-and-start-time.md` § Short answer). Yet on the JVM the emitter must give the Mill plugin a PID and start time matching those Mill's JVM reads from `ProcessHandle`. The test command must also split a module's own ScalaTest arguments as ScalaTest's parser does, which that parser cannot do for it. It can read Mill's colour decision only through `private[mill]` API.

## Critical code paths

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
- The split is core's argument splitter (`../ARCHITECTURE.md` § Modules § ScalaTest core), a `private[scalatest]` unit in package `org.scalatest.tools` beside `ArgsParser`. It returns the arguments grouped by flag with their values. Rather than throwing on an argument whose flag `parseArgs` does not recognise, it returns that argument as a value. It shares `parseArgs`'s arities, or is tested against `parseArgs` over every flag.
- The plugin reaches the splitter through the consolidation library, which sits in package `org.scalatest`.
- The test command keeps the groups whose flag R-125 allows. An unrecognised argument fails the test command before any test starts (`SPECIFICATION.md` § Scope).

### Mill's `Running Test Class` lines

At `testLogLevel` `Debug`, Mill's default, the worker writes `Running Test Class <name>` to stderr for each class it runs (`libs/javalib/src/mill/javalib/TestModule.scala` `testLogLevel` and `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `runClaimedTestClass`, at Mill tag `1.3.1`). `../SPECIFICATION.md` R-129 keeps the line out of captured output.

- The test command passes the worker the higher of `Info` and the module's own `testLogLevel`, so a module's `Warn` or `Error` is kept. Mill then never writes the line. No captured line is matched by text, so a test that writes the same line keeps it.
- Raising the level loses nothing else: ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which R-041 already silences.
- R-053 does not require the worker to run at the module's own log level.
- Cost: under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

## Stdout

- The test command writes human-readable output through `Task.log.streams.out`, whose lines reach stdout whole, with Mill's prefix (`../research/emitter-plugin-spike.md` § 8.1, § 8.2).
- Colour is decided in Mill's JVM, not by ScalaTest in the worker (`../decisions/outputs.md` "Human-readable output on stdout is coloured where Mill colours its own console output.").
- The test command reads Mill's decision from `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`) and gives it to **Start** as the colour of human-readable output on stdout (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library).
  - The member is `private[mill]`. The read belongs with the plugin's other Mill-internal API use, in classes the test command alone loads (`../ARCHITECTURE.md` § Modules § Mill plugin), and those classes must be in a package inside `mill` to compile (`../research/mill-runner-progress-without-trait.md` § 5.1).
  - The value was `false` under ConPTY and MinTTY (`../research/mill-runner-progress-without-trait.md` § 5.2), so there human-readable output is uncoloured unless Mill is given `--color true`.

## The test event log on the JVM

- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it must accept the runner argument beside the module's arguments that `../SPECIFICATION.md` R-125 passes. The argument carries the event log file's absolute path.
- Event log reading polls the file. 10 ms is the only interval tested (`../research/jvm-file-channel.md` § 2, § 8).

## Testing

Under Mill, a JVM `Framework` run ends completed unless its process dies. The aborted and stopped parts of `../SPECIFICATION.md` R-082, and R-101, are therefore tested by feeding event streams to the consolidation library directly. So is the interleaving of a run's captured stdout and stderr lines, since a test cannot fix the order in which the plugin reads two streams.
