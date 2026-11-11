# Consolidated human-readable output for one JVM module: technical design

## Summary

The emitter lives in scalatest-core, which must compile on JDK 8, where `java.lang.ProcessHandle` does not exist (`research/jvm-pid-and-start-time.md` § Short answer). Yet on the JVM the emitter must give the Mill plugin a PID and start time matching those Mill's JVM reads from `ProcessHandle`. The test command must also split a module's own ScalaTest arguments as ScalaTest's parser does, and can read Mill's colour decision only through `private[mill]` API.

## Critical code paths

### The run's PID and process start time

Item 1's start time must equal `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM exactly, not closely (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

- The emitter reads both from `ProcessHandle.current()` by reflection. The read is in core's JVM sources only; Scala.js and Native are out of scope.
- It looks each method up on the interfaces `java.lang.ProcessHandle` and `java.lang.ProcessHandle.Info`, never on the handle's runtime class, whose methods are not accessible (`research/jvm-pid-and-start-time.md` § 2).
- `RuntimeMXBean.getStartTime()` is not a fallback: it records when the JVM finished initialising and never matched (`research/jvm-pid-and-start-time.md` § 3).
- A runner given the runner argument fails at construction where the JVM has no `ProcessHandle` or `startInstant()` is empty. A run without a start time would be counted as exited at once (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "The end of a run's event stream").
  - Mill 1.x supports user-defined modules down to Java 11 only, and its test worker's entry point is Java 11 bytecode in Mill 1.1.10 and 1.3.1 (`research/jvm-pid-and-start-time.md` § 1). No worker under the test command runs without `ProcessHandle`.

### A module's own ScalaTest arguments

The test command reads the module's arguments from Mill's `TestModule.testArgsDefault`, where `testForked` takes them, and passes the worker those `../SPECIFICATION.md` R-125 allows. A flag cannot be told from a value by its leading `-`, so the split follows the arities of `org.scalatest.tools.ArgsParser.parseArgs`:

- `-D` and `-P`, and a reporter's configuration characters, are joined to their flag (`-Dkey=value`, `-P4`, `-PC`, `-oD`): match on the prefix.
- A flag that takes a value consumes the next argument whatever it is: in `-z -o`, `-o` is a test-name substring, passed with `-z`.
- `-W` takes two values; `-k` and `-K` take up to two; `-h <dir>` may be followed by `-Y <css>`, which is withheld with it.

## Colour on stdout

- Mill passes the framework nothing about colour, and its worker's logger always reports ANSI as supported, so ScalaTest's own colour choice in a worker cannot serve `../SPECIFICATION.md` R-124. The decision is made in Mill's JVM, where the human-readable output is written.
- Mill's colour decision reaches a task only as `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`), which is `private[mill]`. The read belongs with the plugin's other Mill-internal API use, in classes the test command alone loads (`../ARCHITECTURE.md` § Modules § Mill plugin), and those classes must be in a package inside `mill` to compile (`../research/mill-runner-progress-without-trait.md` § 5.1).
- Under ConPTY and MinTTY the value was `false`, where ScalaTest under Mill's own test tasks colours its output (`../research/mill-runner-progress-without-trait.md` § 5.3).

## The test event log on the JVM

- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it must accept the runner argument beside the module's arguments that `../SPECIFICATION.md` R-125 passes. The argument carries the event log file's absolute path.
- The file's fixed name must be one Mill does not use in a `processFolder` (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
- The one worker's `processFolder` is the folder the test command owns for the module, outside the test command's task folder (`../SPECIFICATION.md` R-110), which each test command clears before any test starts.
- Event log reading polls the file. 10 ms is the only interval tested (`../research/jvm-file-channel.md` § 2, § 8).
