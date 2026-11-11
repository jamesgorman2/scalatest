# Consolidated human-readable output for one JVM module: technical design

## Summary

The emitter lives in scalatest-core, which must compile on JDK 8, where `java.lang.ProcessHandle` does not exist (`research/jvm-pid-and-start-time.md` § Short answer). Yet on the JVM the emitter must give the Mill plugin a PID and start time matching those Mill's JVM reads from `ProcessHandle`. The test command must also split a module's own ScalaTest arguments as ScalaTest's parser does, which that parser cannot do for it, and turn a worker's stdout and stderr, which arrive as byte chunks, into lines. It can read Mill's colour decision only through `private[mill]` API.

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
- Core's argument splitter (`../ARCHITECTURE.md` § Modules § ScalaTest core) does not throw. It returns an argument whose flag `parseArgs` does not recognise ungrouped, under no flag. The test command fails on the first such argument and names it.
- The splitter keeps its own table of each flag's arity. A test runs `parseArgs` and the splitter over every flag `parseArgs` recognises, each followed by values that look like flags, and requires the same split.
  - Sharing `parseArgs`'s arities instead would change `ArgsParser`, which every run goes through with the emitter off (`../SPECIFICATION.md` R-001).
  - Cost: a second arity table, kept in step with `parseArgs` only by that test.

### Mill's `Running Test Class` lines

At `testLogLevel` `Debug`, Mill's default, the worker writes `Running Test Class <name>` to stderr for each class it runs (`libs/javalib/src/mill/javalib/TestModule.scala` `testLogLevel` and `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `runClaimedTestClass`, at Mill tag `1.3.1`). `../SPECIFICATION.md` R-129 keeps the line out of captured output.

- The test command passes the worker the higher of `Info` and the module's own `testLogLevel`, so a module's `Warn` or `Error` is kept. Mill then never writes the line. No captured line is matched by text, so a test that writes the same line keeps it.
- Raising the level loses nothing else: ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which R-041 already silences.
- R-053 does not require the worker to run at the module's own log level.
- Cost: under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

### Capturing a JVM worker's stdout and stderr

The worker's stdout and stderr become the tagged lines **Add a run** takes (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library).

- The test command makes Mill's test-runner subprocess call with `os.Inherit.out` and `os.Inherit.err` each set to an `os.ProcessOutput.ReadBytes`. os-lib then reads each pipe on its own thread, in chunks that ignore line ends (`../research/mill-worker-channels.md` § 1.2, § 2.3; `../research/mill-parallel-worker-capture.md` § 4).
  - Not `os.ProcessOutput.Readlines`: it decodes with the default charset of Mill's JVM and ends a line at a lone `\r`.
- Each stream has its own line splitter, fed only by that stream's reader thread. It holds bytes up to a `\n` byte, drops one `\r` directly before it, decodes the held bytes, and appends the line, tagged with its stream, to the run's one sequence under a lock.
  - A `\n` byte is never part of a multi-byte character in UTF-8 or in any single-byte charset, so splitting before decoding never cuts a character.
  - A lone `\r` stays in the line's text.
  - Lines of the two streams enter the sequence in the order their splitters completed them. `../SPECIFICATION.md` R-043 asks no more.
- When its stream ends, a splitter appends the line it still holds, if it holds any bytes. Mill's call returns only once both reader threads have finished (`../research/mill-worker-channels.md` § 2.3), so the sequence is complete before the test command adds the test classes the worker took.
- A worker writes a piped stdout and stderr in the platform's native encoding unless its `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`. The splitter decodes with the charset Mill's JVM names in its `native.encoding` system property, or `Charset.defaultCharset()` where that property is absent, and replaces malformed bytes with U+FFFD rather than dropping them.
  - Cost: where a module's `forkArgs` set another encoding, its non-ASCII output is mis-decoded.

### A message among its node's children

`SPECIFICATION.md` AC-034 places a scope's message among the scope's children, which otherwise appear in declared order (`../SPECIFICATION.md` R-105).

- Assembly records, with each message attached to a suite or scope, which of that node's children had sent their first event when the message arrived.
- The human-readable writer puts the message after the last of those children in declared order and before the next, as `../SPECIFICATION.md` R-109 places it in JSON output. Messages placed between the same two children keep the order they arrived in.

## Stdout

- The test command writes human-readable output through `Task.log.streams.out`, whose lines reach stdout whole, with Mill's prefix (`../research/emitter-plugin-spike.md` § 8.1, § 8.2).
- It reads Mill's colour decision from `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`). `Task.log.prompt` is `private[mill]`, so the class that reads it is in a package inside `mill` (`../research/mill-runner-progress-without-trait.md` § 5.1).
  - The value was `false` under ConPTY and MinTTY (`../research/mill-runner-progress-without-trait.md` § 5.2), so there human-readable output is uncoloured unless Mill is given `--color true`.

## The test event log on the JVM

- `org.scalatest.tools.ArgsParser` throws on an argument it does not recognise, so it must accept the runner argument.
- Event log reading polls each file every 10 ms, the only interval measured (`../research/jvm-file-channel.md` § 2).

## Testing

The criteria `SPECIFICATION.md` feeds to the consolidation library directly are tested that way, without Mill:

- Under Mill, a JVM `Framework` run ends completed unless its process dies, so the test command cannot produce an aborted or stopped run. A test aborted after it started, and a scope aborted while open, come only from a fatal error, which aborts the run too.
- A test cannot fix the order in which the plugin reads a worker's two streams, nor where it reads a line among the run's events.

Those tests build each run with a test-only builder in the library's tests, not through core's codec, so they do not depend on the event log's format:

- A test declares the run's tree once: its suites with their class names, and their nested suites, scopes and tests in declared order. The builder derives each node's ID, its parent's ID and its declared position, and emits each suite's structure item before that suite's first event.
- The test scripts the run by node name: each node's events, messages attached to a node or to the run, captured lines tagged with their stream, and how the run ended. The builder emits each in the form core's decoder yields, tagged with its node's ID or as the run's.
- It adds the test classes the run's process took after the event stream ends, as the test command does.
- Cost: the builder must yield what the decoder yields. Only the criteria run under Mill catch a drift between them.

Each test compares the whole output, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour.
