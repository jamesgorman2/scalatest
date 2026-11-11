# Channels from a Mill test worker to plugin code in Mill's process

Which of three candidate channels can carry a run's event stream to plugin code in Mill's process while the run is in progress: an append-only file in the worker's sandbox directory, a socket, and the worker's stdout. Answers [Which channels can carry a run's event stream from a Mill test worker to the plugin, live?](https://github.com/jamesgorman2/scalatest/issues/27). Facts only; no channel is chosen or recommended.

Other notes hold facts this one relies on and does not repeat:

- `.planning/event-stream-reporting/research/mill-test-worker-args.md`, cited as `MILL`: how Mill launches test-worker JVMs.
- `.planning/event-stream-reporting/research/js-native-structure-and-capture.md`, cited as `JSN`: runs, channels and capture on Scala.js and Scala Native.
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: the channels, stdout and stderr, and hooks of a Scala.js run on Node or a Native run under Mill.
- `.planning/event-stream-reporting/research/emitter-and-mill-channel.md`, cited as `EMC`: what was known about a channel before this note.
- `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`: what can host plugin code across a Mill command, and what Mill does to an exclusive command's streams.
- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`: what was observed when several of this note's routes were run.
- `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`, cited as `MPC`: what was observed when plugin code took the raw streams of parallel workers.
- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`: what was observed when plugin code evaluated a module's test task itself.

## Short answer

- **Worker stdout and stderr are two pipes, not inherited descriptors and not files.** Mill spawns each `MillTestRunnerMain` JVM with `os.Inherit` for both, and inside a non-exclusive Mill task `os.Inherit` resolves to a pipe that a Mill thread pumps into the task's logger streams (§ 1.1–1.3). A stream that is the process's original stream is inherited instead (§ 1.3). An exclusive command runs with `exclusiveSystemStreams` (`MCL` § 4.2); whether a worker spawned under them is piped or inherits under `--no-daemon` was not determined (§ 6).
- **Mill line-buffers and prefixes that output on its way to the console, and tees it unprefixed to a log file.** On the parallel path the file is per worker, `Task.dest[/<group>]/worker-<i>.log`, holding stdout and stderr together, and under Mill's own `testForked` Mill's ticker lines too (§ 1.4–1.5; `MPC` § 3).
- **Plugin code in Mill's process can take the raw streams by calling `TestModuleUtil` or by evaluating the test task itself** (§ 2):
  - A `testTask` override that calls `super.testTask` cannot.
  - A direct `callTestRunnerSubprocess` call spawns on the caller's thread and honours the caller's `os.Inherit.out` and `os.Inherit.err`. Observed (`EPS` § 6).
  - A direct `runTests()` call does so on its sequential path, observed (`MTW` § 5). On its parallel path it does so when given a plugin-built `TaskCtx` whose `fork.async` sets them inside the body, observed (`MPC` § 2–4); a plugin-supplied `log` must be compiled in a package inside `mill`, observed only as a meta-build source (§ 2.2).
  - Plugin code that evaluates the module's `testForked`, `testOnly` or `testTask` itself, with such a `TaskCtx` and inside its own `withStreams`, takes them with no `TestModuleUtil` call of its own. Observed (`MTW` § 3.3–3.4, § 5).
  - Inside the worker, a shim `testFramework` can replace `System.out` and `System.err` before Mill's runner captures `System.out`. Observed (`EPS` § 6).
- **Sandbox file.** Found by a relative path, only while `testSandboxWorkingDir` is on. It sits under `Task.dest`, where Mill itself polls two files every 10 ms. On Windows, whether it can be read while the worker appends to it depends on the share mode of the worker's writer (§ 3.1). The discovery runner resolves the same relative path against Mill's own working directory (§ 3.1).
- **Socket.** Found through an address in the arguments, environment or system properties, which are the same for every worker. Mill is not involved in the transport. The discovery runner receives the same address (§ 3.2).
- **Stdout.** Needs no discovery. It shares one pipe with test output, Mill's own runner lines and the `done()` string. The discovery runner's `System.out` is not a worker pipe (§ 3.3).
- **After a JVM worker dies**, the sandbox file stays on disk until the task next runs, and Mill reads both pipes to end of stream. By *general* operating-system behaviour, bytes handed to the operating system before the death remain in the file and are still read from the pipes, and bytes buffered inside the worker JVM are lost (§ 3.1–3.3). Socket bytes written but not yet received: `EPS` § 4.
- **Scala.js and Native have no `MillTestRunnerMain` worker and no discovery runner.** Their runs execute through the test adapters in Mill's own process (§ 4). Whether data a JS VM or native process wrote survives its death: `JNC` § 7.
- **Plugin code that must run after tests fail, inside the test task or by evaluating it, needs `@experimental` API**, which Mill excludes from its binary-compatibility check (§ 5).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`), the version `MILL` pins |
| `[os]` | <https://github.com/com-lihaoyi/os-lib> | tag `0.11.8` (`b14ae532fbbf082b2c22d9f58f7f475be3e3fe79`), the version `[mill] mill-build/src/millbuild/Deps.scala` line 166 pins |
| `[this]` | this repo | `9dac25699` |

Paths are relative to each source's root. A `[mill]` path `P` is at `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/P`, and an `[os]` path at `https://github.com/com-lihaoyi/os-lib/blob/b14ae532fbbf082b2c22d9f58f7f475be3e3fe79/P`. Abbreviations for `[mill]`:

- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TR/` is `libs/javalib/testrunner/src/mill/javalib/testrunner/`.
- `JVM` is `libs/util/java11/src/mill/util/Jvm.scala`.
- `SSU` is `core/api/java11/src/mill/api/SystemStreamsUtils.scala`.
- `EC` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `INT/` is `core/internal/src/mill/internal/`.

No experiment was run for this note; observations are cited from `EPS`, `MPC` and `MTW`. Every `[mill]`, `[os]` and `[this]` citation was opened at the version above. A statement marked *inference* follows from the code read and was not observed. A statement marked *general* is operating-system or JDK behaviour that no source here was opened for. § 6 lists what could not be determined.

"Worker" in §§ 1–3 is a `MillTestRunnerMain` JVM. On the JVM each is one run (`MILL` § 1.4).

## 1. How a JVM worker's stdout and stderr are wired

### 1.1 The spawn

- `callTestRunnerSubprocess` spawns the worker through `Jvm.spawnProcess` with `stdin = os.Inherit` and `stdout = os.Inherit`, and passes no `stderr` (TMU lines 147–163).
- `Jvm.spawnProcess` defaults `stderr` to `os.Inherit` and `mergeErrIntoOut` to `false`, and forwards all of them to `os.proc(...).spawn` (JVM lines 134–172).

### 1.2 What `os.Inherit` means at the spawn

- `spawn` replaces each `os.Inherit` with the current value of a `scala.util.DynamicVariable`: `os.Inherit.in`, `os.Inherit.out` and `os.Inherit.err` (`[os] os/src/ProcessOps.scala` lines 310–313; `[os] os/src/SubProcess.scala` lines 547–556). The lookup happens on the thread that calls `spawn`.
- Each variable's default is `os.Inherit` itself, which is `ProcessBuilder.Redirect.INHERIT` (`[os] os/src/SubProcess.scala` lines 547–556). `os.InheritRaw` is the same redirect and cannot be overridden (lines 562–567).
- A `ProcessOutput` whose `processOutput` returns a `Runnable` gets its own thread, named `<command> stdout thread` or `<command> stderr thread`, started straight after the process (`[os] os/src/ProcessOps.scala` lines 344–357).

### 1.3 What Mill sets `os.Inherit` to

- `SystemStreamsUtils.withStreams(streams)` sets the three variables for the duration of its body (SSU lines 21–58):
  - stdout becomes `PumpedProcessOutput(streams.out)`, and stderr `PumpedProcessOutput(streams.err)`. Each is `Redirect.PIPE` plus an `InputPumper` from the process's stream to the given stream (lines 16–20, 35–41).
  - A stream that is the process's original stream becomes `os.InheritRaw` instead (lines 31–41).
  - stdin becomes `DummyInputStream` unless it is the original stdin (lines 31–33).
- Mill calls `withStreams` around every task body (GE line 867), around every `Task.fork.async` body (EC line 135), and around every runnable submitted to the task thread pool (EC lines 61–67).
- `InputPumper` reads up to 1024 bytes at a time and, after each read, writes and flushes them to the destination. It stops at end of stream or on a write failure (`[mill] core/constants/src/mill/constants/InputPumper.java` lines 34–58).
- `callTestRunnerSubprocess` returns only after the process has exited and both pumper threads have finished (TMU lines 165–175).

So a worker's stdout and stderr are two separate pipes into Mill's process, each drained by its own thread in chunks of up to 1024 bytes with no regard to line ends. *Inference:* bytes of stdout keep their order, bytes of stderr keep theirs, and nothing orders one against the other.

### 1.4 Where the pumped bytes go

Which streams the pumps write to depends on the path through `runTestFuture` (TMU lines 293–308):

| Path | Condition | Destination streams |
|---|---|---|
| Parallel | `testParallelism` on and more than one class in total | The `Task.fork.async` logger: a `MultiLogger` over a `PrefixLogger(ctx.log, Seq(label), …)` and a `FileLogger(dest / os.up / s"${dest.last}.log")`, where `dest` is the worker's `processFolder` (EC lines 109–147; TMU lines 293–306) |
| Sequential | otherwise | The test task's own streams: `block(ctx.log)` runs on the task's thread (TMU line 307), whose `os.Inherit` values were set by GE line 867 from the task's `MultiLogger` over the task's `PrefixLogger` and a `FileLogger` on the task's log path (GE lines 503, 643–656, 861–867; `[mill] core/exec/src/mill/exec/Execution.scala` lines 264–270) |

- The parallel path's label is the padded worker index, or `<group index>-<worker index>` with several groups (TMU lines 289–291).
- The parallel path's log file is therefore `Task.dest[/<group>]/worker-<i>.log`, beside the worker's folder (`MILL` § 2.3 gives `processFolder`). `Task.fork.async` documents it: "the path for the log file (dest + ".log") for any stdout/stderr `println`s that occur within that future" (`[mill] core/api/java11/src/mill/api/TaskCtx.scala` lines 213–216).
- The task's log path is `out/<task segments>.log`, beside `<task>.dest` (`[mill] core/api/src/mill/api/ExecutionPaths.scala` lines 12–23).
- A `MultiLogger`'s `out` writes each byte array to both loggers' `out`, and likewise `err` (`INT/MultiLogger.scala` lines 13–17; `INT/MultiStream.scala` lines 3–25).
- A `FileLogger` uses one file stream for both `out` and `err`, writes bytes unchanged, opens the file on first write, and truncates it (`INT/FileLogger.scala` lines 8–45).

So on the parallel path Mill already writes each worker's stdout and stderr, unprefixed and merged, to one per-worker file under `Task.dest`. *Inference:* the two streams are merged in the order the two pumper threads deliver their chunks. Under Mill's own `testForked` that file also holds Mill's ticker lines, which `MultiLogger.ticker` passes to the `FileLogger` (`MPC` § 3). On the sequential path the same bytes go to the test task's log file, with anything else the task prints.

### 1.5 What Mill does to the bytes before the console

The `PrefixLogger` half of each `MultiLogger`:

- **Line buffering.** `out` and `err` are each a `PrintStream` over a `LineBufferingOutputStream` (`INT/PrefixLogger.scala` lines 47–56). It holds bytes until a `\n`, or a `\r` not followed by `\n`, ignores `flush()`, and releases an unterminated last line only on `close()` (`INT/LineBufferingOutputStream.scala` lines 13–88).
- **Routing.** Each complete line goes to `prompt.logPrefixedLine(logKey, line, logToOut)`. Stdout lines go to the prompt's `out` unless the logger redirects out to err; stderr lines always go to its `err` (`INT/PrefixLogger.scala` lines 33–36, 47–56; `INT/PromptLogger.scala` lines 169–174).
- **Prefixing and rewriting.** With the ticker enabled, `logPrefixedLine` prints the logger's key before each line, prints the task's header line the first time a key writes, strips ANSI codes other than colours, re-emits the colour in force at the end of the previous line, and appends a colour reset (`INT/PromptLogger.scala` lines 169–262). The ticker-off path, and how `PromptLogger`'s own streams reach the terminal: `MCL` § 4.1.
- **Interleaving.** A worker's stdout and stderr pass through separate `LineBufferingOutputStream`s, and each line is written under a lock on its destination stream (`INT/PromptLogger.scala` lines 217–219). *Inference:* the order of a stdout line against a stderr line, and of one worker's lines against another's, is arrival order. Where both streams reached one terminal, a worker's stdout and stderr lines were observed merged into one: `.planning/event-stream-reporting/research/emitter-plugin-spike.md` § 8.2.

### 1.6 What the worker itself writes to those streams

Besides what tests and ScalaTest write:

- Every sbt test-interface `Logger` call is `systemOut.println(msg)`, filtered by `testLogLevel`; `trace` prints the stack trace to `systemOut` (`TR/TestRunnerUtils.scala` lines 179–191). `testLogLevel` defaults to `Debug` (TM line 249).
- `systemOut` is `System.out` as it is when task execution starts, read after `framework.runner(...)` has returned: "Capture this value outside of the task event handler so it isn't affected by a test framework's stream redirects" (`TR/TestRunnerUtils.scala` lines 252–254, 309–311, 380–394).
- A non-empty `done()` result is printed with `println`, which is `scala.Console.out` (lines 229–239).
- At `Debug`, each claimed class prints `Running Test Class <name>` to `System.err` (lines 321–322).
- An exception escaping `main0` is printed with `println(e)` and `e.printStackTrace()` (`TR/TestRunnerMain0.scala` lines 44–48).
- Mill's `TestReporter` in the worker does nothing on events (`[mill] core/api/daemon/src/mill/api/daemon/internal/TestReporter.scala` lines 42–48).

## 2. Can plugin code take the raw streams?

### 2.1 Through a `testTask` override that calls `super.testTask`

- `super.testTask` builds the `TestModuleUtil` and calls `runTests()` inside its own `Task.Anon` (TM lines 312–341). The process handle is a local of `callTestRunnerSubprocess` and is not returned (TMU lines 147–181).
- Mill sets the `os.Inherit` variables around the task body (§ 1.3). *Inference:* the override's own body runs only after `super.testTask`'s result exists (`MILL` I-4), so nothing in it can change what the spawn resolves.
- What such an override can reach is the `.log` files of § 1.4, and only when its body runs (§ 5).

### 2.2 Through a direct `runTests()` call

- **Sequential path.** The spawn happens on the thread that called `runTests()` (TMU line 307). Observed: with the call inside the plugin's `withStreams`, which sets `os.Inherit.out` and `os.Inherit.err` (§ 1.3), a single-class run's worker streams reached the plugin's streams and not the console (`MTW` § 5). *Inference:* a plugin that wraps the call in `os.Inherit.out.withValue(...)` and `os.Inherit.err.withValue(...)` supplies the `os.ProcessOutput` that reads the worker's pipes, and Mill's pump to the logger streams is not created.
- **Parallel path.** Each worker is spawned inside `Task.fork.async`, which calls `withStreams(logger.streams)` on a pool thread (EC lines 129–137) and so sets `os.Inherit.out` and `os.Inherit.err` again (SSU lines 47–49). The caller's values are not seen. The logger is built from `ctx.log` of the `TaskCtx` given to `TestModuleUtil` (TMU lines 54, 200; EC lines 112–116).
  - A plugin can pass `TestModuleUtil` a `TaskCtx` of its own (`MPC` § 2.2). Observed: one whose `fork.async` sets `os.Inherit.out` and `os.Inherit.err` inside the body took every worker's raw streams (`MPC` § 3–4).
  - Under that `TaskCtx`, `worker-<i>.log` of § 1.4 was still written but held only Mill's ticker lines (`MPC` § 3).
  - A plugin-supplied `log` cannot be built in package `build`: `Logger.prompt` is `private[mill]` (`MPC` § 2.2). It can be built as a meta-build source in a package inside `mill` (`.planning/event-stream-reporting/research/mill-runner-progress-without-trait.md` § 5.1).

### 2.3 Through a direct `callTestRunnerSubprocess` call

- The method is public on the `@experimental` class and takes `(baseFolder, resultPath, selector, poll)` (TMU lines 30–31, 112–122). It returns `Result[(String, Seq[TestResult])]`, not the process.
- It spawns on the calling thread, so the caller's `os.Inherit.out` and `os.Inherit.err` values apply (`EPS` § 6). os-lib's public `os.ProcessOutput.ReadBytes` and `os.ProcessOutput.Readlines` each read the pipe on a dedicated thread (`[os] os/src/SubProcess.scala` lines 500–540). Mill waits for those threads before returning (TMU lines 165–175).
- `poll` runs on the calling thread about every 10 ms while the process or a pumper thread is alive (TMU lines 165–175).
- `selector` is either `Left(globs)`, where the one JVM runs every matching class with no queue (`TR/TestRunnerMain0.scala` lines 11–23), or `Right((startingClass, queueFolder, claimFolder))`, where the caller must prepare the queue and claim folders as `runTestFuture` does (TMU lines 183–191, 310–340).
- It makes no discovery call: `ZincOp.GetTestTasks` is sent only by `runTests()` (TMU lines 82–92).
- It does not write the JUnit XML report or build the failed-test list; `runTests()` does both through `TestModuleUtil.handleResults` (TMU lines 101–109, 395–436). The public equivalent is `TestModule.handleResults(doneMsg, results, ctx, testReportXml)` (TM lines 742–755).

### 2.4 Through a shim `testFramework` in the worker

- The worker instantiates `testFramework` by reflection in `MillTestRunnerMain`'s own class loader, before it creates the runner (`TR/TestRunnerMain0.scala` lines 15, 26; `TR/Framework.scala` lines 6–12; `[mill] libs/javalib/testrunner/entrypoint/src/mill/javalib/testrunner/entrypoint/MillTestRunnerMain.java` lines 38–42; `TR/TestRunnerUtils.scala` lines 380–382).
- Mill captures `System.out` only after `framework.runner(...)` returns (§ 1.6). A shim that calls `System.setOut` and `System.setErr` in `runner` also receives Mill's test-interface `Logger` lines, and can keep the original streams for itself (`EPS` § 6). *Inference:* the same holds for a shim that calls them in its constructor.
- Such a replacement sees only writes made through `System.out` and `System.err`. Writes to the file descriptors, such as JVM warnings, native code and child processes, go straight to the pipes (`JSN` § 4.2 states the same limit for Native).
- Whether `scala.Console` is initialised before the shim runs, and so whether the `done()` `println` follows a replaced `System.out`: `EPS` § 6.
- The shim cannot reach the Mill end of the pipes; it runs only in the worker.
- The shim is also instantiated in Mill's own process: by the discovery call (`TR/GetTestTasks.scala` lines 7–25) and by BSP class discovery (TM lines 368–380; `MILL` § 3). There `System.out` is Mill's process-wide proxy to per-thread streams (SSU lines 69–94; `[mill] runner/daemon/src/mill/daemon/MillDaemonMain0.scala` line 54). *Inference:* a shim that replaced `System.out` unconditionally would replace it for the whole Mill process.

### 2.5 By evaluating the test task in plugin code

- An exclusive command can itself evaluate a module's public `testForked` or `testOnly`, or the protected `testTask` reached by reflection. It calls `Task.evaluate` on each anonymous task under the command, with its own `TaskCtx` and inside its own `withStreams` (`MTW` § 2.3). Mill's own `testTask` body then builds the `TestModuleUtil` with the plugin's ctx (`MTW` § 3.3).
- Observed: with a ctx whose `fork.async` sets `os.Inherit.out` and `os.Inherit.err` inside the body, the plugin took every worker's raw streams, through that ctx on the parallel path and through its `withStreams` on the sequential path, and nothing reached the console (`MTW` § 5).

## 3. The candidates on a JVM worker

### 3.1 An append-only file in the sandbox directory

- **How the worker finds it.** By a path relative to its working directory, and only while `testSandboxWorkingDir` is on (`MILL` § 2.1, § 2.3).
- **Reading it while the run is in progress.**
  - Every worker's `processFolder` exists, with a `result.log`, before any worker is spawned: `runTestFuture` writes it outside the forked block, for every worker index (TMU lines 210–229, 287–288). The `sandbox` folder is created just before the spawn (TMU lines 142–145).
  - Mill reads files in these folders live itself. The `poll` callback re-reads `claim.log` every 10 ms (TMU lines 165–175, 332–361), and `waitForFutures` re-reads every `result.log`, retrying when one is "malformed … since that might happen transiently during a write" (TMU lines 597–623).
  - `runTests()` blocks until every worker has finished (TMU lines 231–235).
  - On Windows, the JDK's `FileInputStream` shares a file it opens for reading and writing (`JNC` § 3.2). Whether Mill's process can read the file while the worker appends to it then depends on the share mode the worker's writer opens it with, which was not determined (§ 6).
- **The discovery runner.** It resolves a relative path against the working directory of the process it runs in (`MILL` § 1.4, I-5). The working directory of Mill's own process: `JNC` § 3.1. *Inference:* where the discovery runner runs in Mill's own process, under the daemon the discovery runners of every module and every command resolve one relative path to the same file, and under `--no-daemon` and BSP those of every module in one command do.
- **A dead worker.** The file stays on disk. `Task.dest` is cleared only when the task next runs (`MILL` § 2.3). *General:* bytes already handed to the operating system survive the death of the process; bytes still in a buffer inside the JVM do not. Mill reports the worker as `Result.Failure("Test reporting Failed: … out.json does not exist")` (TMU lines 177–178).
- **Mill APIs.** `Task.dest`. The folder layout under it is shown in Mill's examples (`MILL` § 2.3) and is otherwise set only by the body of the `@experimental` `TestModuleUtil`.

### 3.2 A socket

- **How the worker finds it.** Through an address in the framework arguments, the environment or the system properties (`MILL` § 2.1, § 2.2). The reader cannot identify a worker unless the emitter sends an identifier.
- **Getting the address to the worker.** `testTask` takes `args` as a `Task[Seq[String]]` (TM lines 312–315), and `testForked` builds it as a `Task.Anon` over `testArgsDefault() ++ args` (TM lines 159–166). `testArgsDefault`, `forkArgs` and `forkEnv` are cached tasks (`MILL` § 2.2). Prior art for the argument form is `-K host port` (`EMC` § 2).
- **Reading it while the run is in progress.** The reader is ordinary JVM code in Mill's process; no Mill code sits between a worker's socket and it. No Mill source restricting a worker's network access was looked for.
- **The discovery runner.** It receives the same arguments (`MILL` § 1.4), so an emitter that connects when its runner is constructed connects from Mill's own process as well, and that runner never calls `done()`. ScalaTest 3.2.19's `SocketReporter` connects in its constructor (`[this] jvm/core/src/main/scala/org/scalatest/tools/SocketReporter.scala` lines 26–29), and reporters are built when the runner is constructed (`MILL` § 1.4). When such a connection closes was not determined.
- **A dead worker.** What the reader has received stays with the reader. *General:* the reader then sees end of stream or a connection reset. Whether bytes the worker had written but the reader had not yet received are delivered: `EPS` § 4.
- **Mill APIs.** None for the transport. The reader's lifetime is § 5.

### 3.3 The worker's stdout

- **How the worker finds it.** It needs no discovery.
- **Reading it while the run is in progress.** Mill reads it live (§ 1.3). Plugin code reaches the bytes through the `.log` file of § 1.4, or as raw bytes by the routes of § 2.2, § 2.3 and § 2.5. A `testTask` override that calls `super.testTask` reaches neither during the run (§ 2.1).
- **What shares the stream.** Test output, ScalaTest's test-interface `Logger` lines, Mill's `done()` `println` (§ 1.6), and anything written to file descriptor 1. Through the `.log` file, stderr shares it too (§ 1.4).
- **What reaches the console.** Unless plugin code takes the pipes (§ 2.2, § 2.3, § 2.5), everything on them also goes to the console through the `PrefixLogger` (§ 1.5).
- **The discovery runner.** In Mill's own process its `System.out` is the proxy to the calling thread's streams (§ 2.4), which are the test task's logger streams (§ 1.4). In a JVM-worker subprocess its streams are forwarded to the client (`[mill] libs/javalib/worker/src/mill/javalib/worker/MillJvmWorkerMain.scala` lines 81–86). In neither case is it a worker pipe.
- **A dead worker.** Mill keeps reading until both pipes reach end of stream (§ 1.3). *General:* bytes written to the pipe before the death are still read; bytes in a buffer inside the JVM are lost.
- **Mill APIs.** `os.Inherit.out` and `os.Inherit.err` are public os-lib API. Using them needs plugin code that calls `TestModuleUtil` or evaluates the test task itself (§ 2.2, § 2.3, § 2.5). The per-worker `.log` path is documented on `Task.fork.async` (§ 1.4).

## 4. Scala.js and Native under Mill

- **No worker JVM and no discovery runner.** `TestScalaJSModule` and `TestScalaNativeModule` override `testTask` to call `TestRunner.runTestFramework` in Mill's own process on the adapter's `Framework`, and make `testLocal` an alias of `testForked` (`[mill] libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala` lines 395–432; `[mill] libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala` lines 488–519). They build no `TestModuleUtil`, so nothing sends `ZincOp.GetTestTasks`. ScalaTest runs only in the JS VMs or native processes (`JSN` § 1).
- **Arguments.** The framework arguments reach the adapter's runner unchanged (same lines). How Mill launches each JS VM and native process, with its environment and working directory: `JNC` § 1, § 3.1.
- **File.** `JSN` § 3; `JNC` § 3.
- **Socket.** `JNC` § 2.
- **Stdout and stderr.** `JNC` § 5.
- **The test-interface `Logger`.** The channel that reaches Mill's process in order on both runtimes (`JSN` § 2.1). What Mill does with its lines: `JNC` § 4.
- **Hooks for plugin code.** `JNC` § 6.

## 5. Running plugin code during the run and after tests fail

| What | Where | Marked |
|---|---|---|
| `testTask` | `protected def`, overridable (TM line 312) | none |
| `TestModuleUtil`, its `runTests`, `callTestRunnerSubprocess`, `runTestQueueScheduler`, `runTestFuture`, `prepareTestClassesFolder` | public members of a `final class` (TMU lines 30–31, 62, 112, 183, 198, 269) | `@mill.api.daemon.experimental` |
| `TestModule.handleResults` | public (TM lines 742–755) | none |
| `TestArgs`, `TestRunnerUtils`, `TestRunner`, `MillTestRunnerMain0` | `MILL` § 2.2; `TR/TestRunner.scala` line 6 | `@internal` |
| `Task.dest`, `Task.Worker` | `[mill] core/api/src/mill/api/Task.scala` lines 87, 297–311 | none |
| `Task.fork.async` | The `Task.fork` accessor (`[mill] core/api/src/mill/api/Task.scala` line 131) returns `TaskCtx.Fork.Api`, which declares `async` inside `object Fork` (`[mill] core/api/java11/src/mill/api/TaskCtx.scala` lines 193–235) | `@experimental` on `object Fork`; none on the accessor |
| `os.Inherit.in`, `out`, `err`; `os.ProcessOutput` | `[os] os/src/SubProcess.scala` lines 494–556 | none |

- `@experimental` is documented as "experimental API, which is not guaranteed to stay", and `@internal` as "internal API, which is not guaranteed to stay" (`[mill] core/api/daemon/src/mill/api/daemon/experimental.scala`; `[mill] core/api/daemon/src/mill/api/daemon/internal/internal.scala`). Mill's MiMa check excludes `@experimental` members (`[mill] mill-build/src/millbuild/MillStableJavaModule.scala` line 100).
- **Inside the test task, after failures.** `runTests()` returns failing tests and a missing `out.json` as a `Result.Failure` value (TMU lines 99–109, 177–178, 395–420), so code after a direct call runs. It throws when a selector matches nothing (lines 97, 104) and when a worker future failed (line 235). *Inference:* the body of a `testTask` override that calls `super.testTask` does not run after a failure (`MILL` I-4).
- **Mill's list of failed tests** is the message of that `Result.Failure` (TMU lines 401–418). The caller of `runTests()` holds it and decides what its own task returns.
- **Inside the test task, during the run.** `runTests()` and `callTestRunnerSubprocess` block. Plugin code runs during the run through `poll` on `callTestRunnerSubprocess` (§ 2.3), and through `fork.async` of a plugin-built `TaskCtx`, once per worker on the parallel path (§ 2.2, § 2.5).
- **Outside the test task.** An exclusive command that takes an `Evaluator`: `MCL` § 2. A `Task.Worker`: `MCL` § 3.3. An exclusive command that evaluates the test task itself (§ 2.5) receives the task's `Result`, failure included, and runs after it (`MTW` § 4); it implements `TaskCtx`, whose `fork` member's type is `@experimental` (`MTW` § 6).

## 6. What was not determined

- Whether each remaining *inference* above holds when run.
- Which tasks, if any, run with the process's original streams, so that a worker's descriptors are inherited rather than piped (§ 1.3). An exclusive command receives `exclusiveSystemStreams`, the command's raw streams (`MCL` § 4.2). Under the daemon they are not the daemon's own stdout (`EPS` § 8.1); whether they are the process's original streams under `--no-daemon` was not determined.
- What the Mill client does to `PromptLogger`'s output (§ 1.5).
- Whether the worker's `System.out` is flushed on every write, and what `System.exit(0)` does to bytes buffered in the JVM (§ 3.3).
- On Windows, the share mode the worker's writer opens a sandbox file with, which decides whether Mill's process can read it while the worker appends (§ 3.1).
- What `testLocal` on a JVM module does with test output beyond running the framework in Mill's process (`MILL` § 1.1).
- Whether any of this holds on Mill versions other than `1.1.10`.
