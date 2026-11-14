# Consolidated human-readable output for one JVM module: the test command

Technical design of how the test command is reached, splits a module's arguments, captures a worker's output, takes its result and writes to stdout. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Critical code paths

### The test command's entry point

- The test command is the `test` command of the top-level `ExternalModule` object `org.scalatest.mill.TestCommandModule`. Beside it, package `scalatest`'s package object, `object package extends mill.api.ExternalModule.Alias(TestCommandModule)`, gives it the prefix `scalatest`, as Mill 1.3.1's `package object spotless extends ExternalModule.Alias(SpotlessModule)` does (`libs/javalib/src/mill/javalib/spotless/package.scala`).
  - The package object names Mill's and the plugin's types from `_root_`. Any import in its file causes a cyclic reference, since the package object's members are in scope where the file's imports resolve.
- Mill's selector parser accepts a single-segment prefix before `/` (`core/api/src/mill/api/internal/ParseArgs.scala` `selector`). It resolves prefix `scalatest` by loading class `scalatest$`, then `scalatest.package$`, the package object, from the meta-build's class loader (`core/resolve/src/mill/resolve/Resolve.scala` `resolveRootModule`, at Mill tag `1.3.1`). The plugin's integration tests run `mill scalatest/test core.test` on fixture builds that add the plugin by their header line.
- The command takes its arguments as a `mainargs.Leftover[String]`, so that an argument beginning `-` reaches it rather than Mill's argument parser.

### A module's own ScalaTest arguments

The test command reads the module's arguments from Mill's `TestModule.testArgsDefault`, where `testForked` takes them, and passes the worker those `../../SPECIFICATION.md` R-125 allows. As in `testForked`, discovery receives the same arguments, without the runner argument.

- A flag cannot be told from a value by its leading `-`: in `-z -o`, `-o` is a test-name substring. The split therefore needs the arities of `org.scalatest.tools.ArgsParser.parseArgs`, but `ArgsParser` is `private[tools]`, only sorts arguments, and throws on one it does not recognise.
- Core's argument splitter (`../../ARCHITECTURE.md` § Modules § ScalaTest core) is `org.scalatest.tools.ArgumentSplitter`, in core's Scala 3 JVM sources only. It returns the arguments in the order given, each as one of two kinds:
  - an argument whose flag `parseArgs` recognises, with its values: the flag without what is written onto it (`-o` for `-oD`), then the argument as written and its values;
  - an argument whose flag `parseArgs` does not recognise, ungrouped, under no flag. The test command fails on the first such argument and names it. A null argument is an unrecognised argument where a flag is read; where a value is read, it is kept as the flag's value.
- The splitter recognises the runner argument's flag, which `parseArgs` reads only through `parseArgsWithTestEventLog` (`test-event-log.md` § The test event log on the JVM).
- The splitter keeps its own table of each flag's arity. The splitter never throws:
  - a flag short of its values keeps those it has, as `parseArgs` does for every flag but two;
  - where `parseArgs` throws, the splitter does as follows:
    - `-W` short of its values, or with values that are not integers, keeps the next two arguments, or those that remain;
    - `--test-event-log` with no file keeps the flag alone.
- Tests keep the table in step with `parseArgs`:
  - One runs `parseArgs` and the splitter over every flag `parseArgs` recognises, the runner argument included, each with its values and followed by another flag, and requires the same split. The values look like flags, except those of `-k` and `-K`, which `parseArgs` reads only where they do not begin with `-`, and those of `-W`, which are negative integers.
  - The other gives both every printable ASCII character as a flag, written alone and with a character written onto it, and requires that the splitter recognise exactly those `parseArgs` recognises, and no flag the first test leaves out.
- The library's **Split a module's ScalaTest arguments** (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) is `org.scalatest.consolidation.ScalaTestArgument.split`, which returns the splitter's result in the library's public type `ScalaTestArgument`.

### Mill's `Running Test Class` lines

At `testLogLevel` `Debug`, Mill's default, the worker writes `Running Test Class <name>` to stderr for each class it runs (`libs/javalib/src/mill/javalib/TestModule.scala` `testLogLevel` and `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `runClaimedTestClass`, at Mill tag `1.3.1`). `../../SPECIFICATION.md` R-129 keeps the line out of captured output.

The test command passes the worker the higher of `Info` and the module's own `testLogLevel`, so a module's `Warn` or `Error` is kept. Mill then never writes the line. No captured line is matched by text, so a test that writes the same line keeps it.

### Capturing a JVM worker's stdout and stderr

The worker's stdout and stderr become the tagged lines **Add a run** takes (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library).

- The test command makes Mill's test-runner subprocess call with `os.Inherit.out` and `os.Inherit.err` each set to an `os.ProcessOutput.ReadBytes`. os-lib then reads each pipe on its own thread, in chunks that ignore line ends (`../../research/mill-worker-channels.md` § 1.2, § 2.3; `../../research/mill-parallel-worker-capture.md` § 4).
- Each stream has its own line splitter, fed only by that stream's reader thread. It holds bytes up to a `\n` byte, drops one `\r` directly before it, decodes the held bytes, and appends the line, tagged with its stream, to the run's one sequence under a lock.
  - Splitting before decoding never cuts a character in an ASCII-compatible charset whose multi-byte sequences never hold byte `0x0A`. That covers every encoding `native.encoding` names, among them UTF-8, the single-byte charsets, MS932, GBK, MS949 and Big5.
  - A lone `\r` stays in the line's text.
  - Lines of the two streams enter the sequence in the order their splitters completed them. `../../SPECIFICATION.md` R-043 asks no more.
- When its stream ends, a splitter appends the line it still holds, if it holds any bytes. Mill's call returns only once both reader threads have finished (`../../research/mill-worker-channels.md` § 2.3), so the sequence is complete when the call returns.
- Mill's worker prints to stdout what its runner's `done` returns (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` `handleRunnerDone`, at Mill tag `1.3.1`). Given the runner argument, `org.scalatest.tools.Framework`'s `done` returns an empty string in place of ScalaTest's end-of-run summary. Mill prints nothing for an empty string, so the summary is never captured (`../../SPECIFICATION.md` R-045).
- A worker writes a piped stdout and stderr in the platform's native encoding unless its `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`. The splitter decodes with the charset Mill's JVM names in its `native.encoding` system property, or `Charset.defaultCharset()` where that property is absent, and replaces malformed bytes with U+FFFD rather than dropping them.

### The test command's result

This serves `../specification/test-command.md` AC-084 to AC-087 and AC-157.

- The library keeps the consolidation state (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**) readable from any thread without a lock: event log reading adds events on its own thread while the test command's thread reads the state.
  - Each operation that makes the state failed does so before it returns, so a read made after that operation returns sees the failure.
  - The events that report a failure (`../../SPECIFICATION.md` R-046) are `TestFailed`, `TestCanceled` and `SuiteAborted`. A `RunAborted` or `RunStopped` is not among them (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "What the library takes").
- The test command reads the state once, after **End** returns.
- Mill's test-runner subprocess call returns a `Result[(String, Seq[TestResult])]` (`../../research/mill-worker-channels.md` § 2.3), which the test command leaves unread, a `Result.Failure` included.
  - A worker that dies makes the call return a `Result.Failure`, not throw (`../../research/mill-worker-channels.md` § 3.1, "A dead worker"). The captured line sequence is then complete (§ Capturing a JVM worker's stdout and stderr), and the test command adds the worker's run, or its process where the `processFolder` holds no event log file (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "On the JVM, on Scala.js under `NodeJs` and on Native").
  - The call throws where Mill's `CREATE_NEW` write of `testargs` is refused, before it starts the worker (`../../research/mill-test-worker-args.md` § 2.3), so that throw leaves no worker, no captured line and no event log. The write is not refused in a `processFolder` the test command has cleared and starts one worker in (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "On the JVM, on Scala.js under `NodeJs` and on Native").
  - `TestModuleUtil.runTests()` also throws when a worker future failed (`../../research/mill-worker-channels.md` § 5), but the test command drives the test queue itself (`../../ARCHITECTURE.md` § Data flow) and never calls it.
  - A user interrupt does not reach this path: the command's thread does not regain control (`../../research/mill-command-lifetime.md` § 5; `../../SPECIFICATION.md` R-069, R-116).
- AC-087 needs no check of the module's framework or release.
  - MUnit and uTest have no emitter. 3.2.20's `ArgsParser`, which its `Framework.runner` calls, throws on the runner argument (`test-event-log.md` § The test event log on the JVM).
  - The worker's `processFolder` therefore holds no event log file when Mill's call returns, so the test command adds the worker as a process that ended before any run existed in it. That makes the consolidation state failed (`../../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**).

## Stdout

- The test command writes human-readable output through `Task.log.streams.out`, whose lines reach stdout whole, with Mill's prefix (`../../research/emitter-plugin-spike.md` § 8.1, § 8.2).
- It reads Mill's colour decision from `Task.log.prompt.colored` (`core/api/daemon/src/mill/api/daemon/Logger.scala` at Mill tag `1.3.1`). `Task.log.prompt` is `private[mill]`, so the class that reads it is in a package inside `mill` (`../../research/mill-runner-progress-without-trait.md` § 5.1).
  - The value was `false` under ConPTY and MinTTY (`../../research/mill-runner-progress-without-trait.md` § 5.2), so there human-readable output is uncoloured unless Mill is given `--color true`.
- The discovery runner puts nothing on the console, so the test command need not silence it (`../../SPECIFICATION.md` R-072, R-044), provided discovery is given no reporter argument, as R-125 withholds the module's.
  - `org.scalatest.tools.Framework` builds `SbtLogInfoReporter` only in a task's `execute`, which discovery never calls, and `Framework.runner` drops the standard-out reporter configuration from those its runner dispatches to.
  - `ScalaTestRunner.done` returns ScalaTest's end-of-run summary rather than printing it. Mill's `TestRunnerUtils.getTestTasks0` (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` at Mill tag `1.3.1`) ends with `runner.done()` and discards that summary; only Mill's run path prints it, and given the runner argument there is none to print (§ Critical code paths § Capturing a JVM worker's stdout and stderr).
