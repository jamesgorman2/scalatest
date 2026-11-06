# Channels from a Scala.js or Native run to plugin code in Mill's process

On Scala.js and Scala Native under Mill: which channels can carry a run's event stream to plugin code in Mill's process while the run is in progress, and whether plugin code can take the run's stdout and stderr before they reach the console. Answers [Which channels can carry a Scala.js or Native run's event stream and worker output to the plugin under Mill?](https://github.com/jamesgorman2/scalatest/issues/37). Facts only; no channel or hook is chosen or recommended.

Other notes hold facts this one relies on and does not repeat:

- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`: the JVM answer (§§ 1–3), how Mill passes a Scala.js or Native run its framework arguments (§ 4), and the APIs for running plugin code after tests fail (§ 5).
- `.planning/structured-test-reporting/research/js-native-structure-and-capture.md`, cited as `JSN`: runs, channels and capture on Scala.js and Scala Native.
- `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`: what hosts plugin code across a Mill command, and what Mill does to its console output.

## Short answer

- **Socket.** Both runtimes can open a TCP socket to Mill's process: the test adapters' own com channels already do, to `127.0.0.1` (§ 2). The address can reach a JS VM through the framework arguments, `jsEnvConfig`'s `env`, or Node's own arguments, and a native process through the framework arguments or `forkEnv`. A native binary's command line carries only the com port, and `TestMain` rejects any other argument (§ 2.2). Neither com channel can be reused for a second stream (§ 2.3). A Node socket write that is still queued when Node exits is lost, and Node exits as soon as the com socket closes (§ 2.1).
- **Working directory and files.** Both processes inherit Mill's own working directory: under the daemon `out/mill-daemon/sandbox`, and under `--no-daemon` `out/mill-no-daemon/<sig>/sandbox`. This holds for every module, every run, and under the daemon every command (§ 3.1). A relative path is therefore never unique to a run. On Windows, Node and Scala Native open files with sharing modes that allow a JDK `FileInputStream` to read the file while it is being written (§ 3.2).
- **Logger.** Mill passes one `Logger` per task. It prints `info`, `warn` and `error` lines with `println` to the task's streams, filtered by `Task.testReporter.logLevel` and not by `testLogLevel`. `trace` is never filtered, and `error` cannot be filtered out (§ 4.1). Mill builds that logger inline in `@internal` code, so no parameter substitutes it (§ 4.2). Code that calls `Task.execute` itself chooses the loggers, and the adapters call exactly those loggers (§ 4.2). *Inference:* wrapping the `Framework` that `testFrameworkInstance` returns lets JS plugin code supply its own loggers (§ 6.1).
- **JS VM stdout and stderr.** Each goes through a pipe to an unjoined thread that writes to `System.out` or `System.err`. In Mill's process those are the per-thread proxies, so *inference:* the bytes reach the test task's `PrefixLogger` and `.log` file (§ 5.1). Plugin code that builds its own `TestAdapter` can give it a `JSEnv` that replaces `RunConfig.onOutputStream` and so receives both pipes itself (§ 5.1). Mill's own `getFramework` path offers no such hook (§ 6.1).
- **Native process stdout and stderr.** Both are inherited descriptors, and no public Scala Native or Mill API changes that (§ 5.2). Under the daemon they are the daemon's `stdout` and `stderr` files. A `TailManager` thread copies those files line by line to the command's top-level logger, and so to the console, while every command runs (§ 5.3). Under `--no-daemon` they are the launcher's own console descriptors (§ 5.3). This contradicts `JSN` § 2.1, which says this output does not reach the console under the daemon.
- **Hooks.** Scala.js has `protected def testFrameworkInstance` and `protected def testTask`. Native has only `protected def testTask`, and everything its body uses to get a framework is `private[scalanativelib]`. A Native override can still build Scala Native's public `TestAdapter` from the public `nativeLink`, `allForkEnv` and `bridgeFullClassPath` tasks (§ 6.2).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`), the version `MWC` pins |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939aab0be02540dbaf29d8bc01e509fd86aa3`), the version `JSN` pins. Mill resolves `scalajs-sbt-test-adapter` at the module's `scalaJSVersion` (`[mill]` `SJM` line 90) |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.5.0` (`1b560e68ce3065e0832ee0e6db638c111f3b1984`), the `scalajs-env-nodejs` and `scalajs-js-envs` version `[mill] mill-build/src/millbuild/Deps.scala` lines 39 and 46 pin |
| `[sn]` | <https://github.com/scala-native/scala-native> | tag `v0.5.12` (`65dd513df188300d0180514ad828308facc5ca61`), the version `JSN` pins. Mill resolves `test-runner_2.13` at the module's `scalaNativeVersion` (`SNM` lines 51–70) |
| `[scala3]` | <https://github.com/scala/scala3> | tag `3.8.2`, the Scala version `[mill] Deps.scala` line 18 pins |
| `[node]` | <https://github.com/nodejs/node> | tag `v22.21.1`: `doc/api/*.md` and the bundled `deps/uv`. Mill does not pin Node: `JsEnvConfig.NodeJs` runs `node` from the path (`[mill] libs/scalajslib/src/mill/scalajslib/api/JsEnvConfig.scala` lines 24–29) |
| `[jdk]` | <https://github.com/openjdk/jdk21u> | tag `jdk-21.0.5-ga`. Mill does not pin the daemon's JDK; this is a reference version |

Paths are relative to each source's root. Abbreviations:

- `[mill]`:
  - `SJM` is `libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala`.
  - `SJW` is `libs/scalajslib/src/mill/scalajslib/worker/ScalaJSWorker.scala`.
  - `SJWI` is `libs/scalajslib/worker/1/src/mill/scalajslib/worker/ScalaJSWorkerImpl.scala`.
  - `SNM` is `libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala`.
  - `SNWI` is `libs/scalanativelib/worker/0.5/src/mill/scalanativelib/worker/ScalaNativeWorkerImpl.scala`.
  - `TRU` is `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala`.
  - `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
  - `SSU` is `core/api/java11/src/mill/api/SystemStreamsUtils.scala`.
  - `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
  - `MPL` is `runner/launcher/src/mill/launcher/MillProcessLauncher.scala`.
- `[sjs]`: `TA/` is `test-adapter/src/main/scala/org/scalajs/testing/adapter/`.
- `[envs]`: `JE/` is `js-envs/src/main/scala/org/scalajs/jsenv/`, and `NE/` is `nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/`.
- `[sn]`: `SNTR/` is `test-runner/src/main/scala/scala/scalanative/testinterface/`, `SNTI/` is `test-interface/src/main/scala/scala/scalanative/testinterface/`, and `SNJL/` is `javalib/src/main/scala/java/`.

`[envs]` `v1.5.0` differs from `v1.4.0`, the version `JSN` cites, in its main sources only by two removed `final` modifiers (`NE/ComSupport.scala` line 238; `JE/RunConfig.scala` line 122). *Inference, resolution not run:* `scalajs-sbt-test-adapter` `1.20.2` depends on `scalajs-js-envs` `1.4.0` (`JSN` § Sources), and `scalajs-env-nodejs` `1.5.0` on `1.5.0`, so Mill's resolver loads `1.5.0`.

No experiment was run for this note. Every citation was opened at the version above. A statement marked *inference* follows from the code read and was not observed. A statement marked *general* is operating-system, JDK or Scala behaviour that no source here was opened for. § 7 lists what could not be determined.

The default `jsEnvConfig` is `JsEnvConfig.NodeJs()` (`SJM` line 274). Other JS environments are covered only where stated; their filesystem and `require` limits are `JSN` § 2.3.

## 1. How Mill launches a run's processes

### 1.1 Scala.js

- `testTask` gets `(close, framework)` from `testFrameworkInstance`, which calls `ScalaJSWorker.getFramework(scalaJSToolsClasspath(), jsEnvConfig(), testFramework(), fastLinkJSTest())` (`SJM` lines 399–406, 413). The worker loads the Scala.js tools in their own class loader, sharing only `sbt.testing.` and `TestReporter` with Mill (`SJW` lines 19–31).
- `rawGetFramework` builds `new TestAdapter(env, inputs, TestAdapter.Config().withLogger(logger))`. It sets no `env` on the adapter's config (`SJWI` lines 202–227). The logger prints every message to the `Console.err` in force when it is created (`SJWI` lines 66–77).
- For Node, the `JSEnv` is `NodeJSEnv` with the config's `executable`, `args`, `env` and `sourceMap` (`SJWI` lines 229–231; `[mill] libs/scalajslib/worker/1/src/mill/scalajslib/worker/jsenv/NodeJs.scala` lines 15–21).
- `NodeJSEnv` runs `executable :: args` and feeds the program to Node's standard input (`NE/NodeJSEnv.scala` lines 58–64, 98–166). It adds `NODE_MODULE_CONTEXTS=0` to the config's `env` (lines 72–73).
- `ExternalJSRun.startProcess` puts that `env` into a `ProcessBuilder`'s environment and sets no working directory (`JE/ExternalJSRun.scala` lines 129–154). A `ProcessBuilder`'s environment starts as a copy of the current process's, and its working directory defaults to the current process's (`[jdk] src/java.base/share/classes/java/lang/ProcessBuilder.java` lines 67–73).
- The adapter starts one VM per JVM thread that calls it (`JSN` § 1.1). `testFrameworkInstance` is a `Task.Anon`, so Mill groups it with the named task that uses it (`[mill] core/exec/src/mill/exec/PlanImpl.scala` lines 30–59). It evaluates a group's tasks in one loop with one logger (`GE` lines 502–548). *Inference:* `loadFrameworks` and the run execute on the same thread, so one VM serves the run.

### 1.2 Scala Native

- `testTask` calls `withScalaNativeBridge.apply().apply(_.getFramework(nativeLink().path.toIO, allForkEnv(), toWorkerApi(logLevel()), testFramework()))` (`SNM` lines 492–502).
- `getFramework` builds `TestAdapter.Config().withBinaryFile(testBinary).withEnvVars(envVars).withLogger(logger(logLevel))` (`SNWI` lines 135–156). The logger prints to the `Console.err` in force when it is created (`SNWI` lines 28–40).
- The adapter starts one process per JVM thread through `NativeRunnerRPC` with `args = Seq.empty` (`SNTR/adapter/TestAdapter.scala` lines 108–130). `NativeRunnerRPC` opens `new ServerSocket(0, 1)` and passes its port to `ProcessRunner` (`SNTR/NativeRunnerRPC.scala` lines 14–33).
- `ProcessRunner` runs `[<CROSSCOMPILING_EMULATOR words>] <binary absolute path> <port>` with `inheritIO()`. It adds `envVars` to the inherited environment and sets no working directory (`SNTR/ProcessRunner.scala` lines 17–45). `CROSSCOMPILING_EMULATOR` is read from `sys.env` of Mill's own process (lines 19–23).
- `allForkEnv` is `javaHomePathForkEnv() ++ forkEnv() ++ MILL_WORKSPACE_ROOT` (`[mill] libs/javalib/src/mill/javalib/RunModule.scala` lines 48–52), plus `MILL_TEST_RESOURCE_DIR` on a test module (`TM` lines 303–307). `forkEnv` is an overridable task (`RunModule.scala` line 41).
- The Native `testTask` does not read `forkWorkingDir`, `testSandboxWorkingDir`, `forkArgs` or `propagateEnv` (`SNM` lines 492–520), and neither does the Scala.js one (`SJM` lines 408–431).

## 2. A socket

### 2.1 From a Node.js VM

- **Node can connect to Mill's process.** The com channel's setup script runs `require('net').connect(<port>, '127.0.0.1')` inside the VM, and the JVM end is a `ServerSocket` on `127.0.0.1` with an ephemeral port (`NE/ComSupport.scala` lines 209–220, 245–251). Getting `require` in an ES module: `JSN` § 2.3.
- **How a VM can learn an address:**
  - The framework arguments. `TestRunnerUtils.getTestTasks` passes `args` to `framework.runner` (`TRU` lines 124–133), and they reach the runner in the VM unchanged (`MWC` § 4).
  - `JsEnvConfig.NodeJs.env`, which becomes the process environment (§ 1.1). Node exposes it as `process.env`, "an object containing the user environment" (`[node] doc/api/process.md` line 1596).
  - `JsEnvConfig.NodeJs.args`, which are arguments to the Node executable, placed before the program (§ 1.1).
  - Mill sets nothing on `TestAdapter.Config.env` (§ 1.1). `forkEnv` does not reach a JS VM (§ 1.2).
- **Writes are asynchronous.** `socket.write` "Returns `true` if the entire data was flushed successfully to the kernel buffer. Returns `false` if all or part of the data was queued in user memory" (`[node] doc/api/net.md` lines 1487–1489).
- **Exit abandons queued writes.** `process.exit()` forces exit "even if there are still asynchronous operations pending that have not yet completed fully" (`[node] doc/api/process.md` lines 1832–1835). The com setup script calls `process.exit(0)` when the com socket closes (`NE/ComSupport.scala` line 287), and Mill's `close()` closes it (§ 5.1). *Inference:* bytes still queued in Node's memory on a second socket when Mill closes the adapter are lost.
- jsdom and browser environments: no `require` (`JSN` § 2.3). Whether they can open a socket to Mill's process was not examined.

### 2.2 From a native test process

- **The process can connect to Mill's process.** `TestMain` opens `new Socket("127.0.0.1", serverPort)` (`SNTI/TestMain.scala` lines 77–78), using Scala Native's `java.net.Socket` (`SNJL/net/Socket.scala` lines 5, 57).
- **The command line carries only the com port.** `main` throws `IllegalArgumentException("One argument expected")` unless there is exactly one argument (`SNTI/TestMain.scala` lines 61–65), and the adapter passes none besides the port (§ 1.2).
- **How a process can learn an address:**
  - The framework arguments, as on JS (§ 2.1).
  - `forkEnv`, through `allForkEnv` (§ 1.2).
- **Environment lookup by name.** `System.getenv(key)` looks up `key.toUpperCase()` (`SNJL/lang/System.scala` lines 77–78). On Windows the map's keys are upper-cased; on Unix they are stored as given (lines 490–537). *Inference:* on Unix, `System.getenv` finds only variables whose names are upper case. `System.getenv()` returns the whole map.
- *General:* each `write` on a `java.net.Socket` hands bytes to the operating system, which delivers them after the process exits. Not verified for Scala Native's implementation.

### 2.3 The adapters' own com channels

- **Node.** The port is fixed in the setup script. User code sees only `scalajsCom.init`, which "May only be called once" and is taken by the test bridge, and `scalajsCom.send` (`NE/ComSupport.scala` lines 289–307; `JE/JSEnv.scala` lines 43–55).
- **Native.** The socket is a constructor argument of the `private[testinterface]` `NativeRPC` (`SNTI/NativeRPC.scala` lines 15–22).
- **What carries user data over them.** `Logger`, `EventHandler` and `notifyServer` (`JSN` § 2.1, § 2.2).

## 3. The working directory and a file

### 3.1 The working directory

- Neither launcher sets a working directory (§ 1.1, § 1.2), so each JS VM and native process starts in the working directory of Mill's process (`[jdk]` `ProcessBuilder.java` lines 71–73).
- **Under the daemon,** that directory is `<out>/mill-daemon/sandbox`. The launcher spawns the daemon with `cwd = daemonDir / "sandbox"`, where `daemonDir` is `<out>/mill-daemon` (`MPL` lines 52–72, 83–84, 101–103; `[mill] runner/launcher/src/mill/launcher/MillLauncherMain.scala` line 101). Mill's own background-run code finds the daemon's files by resolving `Paths.get(".")` and going up one level (`RunModule.scala` lines 383–389).
- **Under `--no-daemon` and BSP,** it is `<out>/mill-no-daemon/<sig>/sandbox`, where `<sig>` is random per Mill command (`MPL` lines 24–28, 39, 83–84; `MillLauncherMain.scala` lines 43, 65–67).
- So a relative path resolves to one file for every module and every run of a command, and under the daemon for every command.
- `Task.dest` and `nativeWorkdir` (`SNM` line 146) are not the processes' working directory.

### 3.2 Reading a file while the run writes it

- **Writing.**
  - Node: `fs.appendFileSync` is synchronous: "Synchronously append data to a file" (`[node] doc/api/fs.md` lines 5341, 5368).
  - Scala Native: `FileOutputStream` writes through a descriptor it opens itself (`SNJL/io/FileOutputStream.scala` lines 56–80).
  - Partial-write and Node-version limits: `JSN` § 2.3.
- **Sharing on Windows.**
  - libuv opens files with `FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE` unless `UV_FS_O_EXLOCK` is given (`[node] deps/uv/src/win/fs.c` lines 478–482).
  - Scala Native's `FileOutputStream` opens with `FILE_SHARE_READ | FILE_SHARE_WRITE` (`SNJL/io/FileOutputStream.scala` lines 60–70).
  - The JDK's `FileInputStream` opens with `FILE_SHARE_READ | FILE_SHARE_WRITE` (`[jdk] src/java.base/windows/native/libjava/io_util_md.c` lines 214–221, 246).
  - *Inference (general Windows share-mode rules):* Mill's process can open and read the file while either runtime holds it open for writing.
- *General:* on POSIX, opening a file for reading does not conflict with a writer.
- A path unique to a run must be absolute and reach the process through the routes of § 2.1 or § 2.2. *Inference:* `forkEnv` and `jsEnvConfig` are cached tasks, so they give one value per module; a value that differs per run must be computed inside the test task.

## 4. The test-interface `Logger`

### 4.1 What Mill does with the loggers it passes

- `executeTasks` passes each task one `Logger` (`TRU` lines 156–206):
  - `debug`, `info`, `warn` and `error` call `systemOut.println(msg)` when `testReporter.logLevel` is at or below that level, and `trace` always calls `t.printStackTrace(systemOut)` (lines 179–191).
  - `ansiCodesSupported` is `true` (line 187).
- `testReporter` is `Task.testReporter` (`SJM` line 420; `SNM` line 509). Its default is `DummyTestReporter`, whose `logLevel` is `Info` (`[mill] core/api/src/mill/api/Evaluator.scala` line 101; `core/api/daemon/src/mill/api/daemon/internal/TestReporter.scala` lines 16–17, 54–57). `Error` is the highest level (same file, lines 21–26), so `error` lines always print. `testLogLevel` (`TM` line 249) is not read on these runtimes.
- `systemOut` is `System.out` read at the start of `runTasks` (`TRU` lines 252–254). In Mill's process that is `ThreadLocalStreams.Out`, which forwards each write to `SystemStreams.current.value.out` of the writing thread (`SSU` lines 84–113). `current` is a `DynamicVariable` (`[mill] core/api/daemon/src/mill/api/daemon/SystemStreams.scala` lines 28–29), which is backed by an `InheritableThreadLocal` (`[scala3] library/src/scala/util/DynamicVariable.scala` line 43).
- **Where the calls run.** The adapter calls the loggers from its message handlers (`TA/TaskAdapter.scala` lines 28–57; `SNTR/adapter/TaskAdapter.scala` lines 18–48). Those handlers run on the receiver thread that reads the com socket (`NE/ComSupport.scala` lines 49–74; `SNTR/ComRunner.scala` lines 38–68; dispatch: `JSN` § 2.1). That thread is created when the VM or process starts (same lines), on the test task's thread (§ 1.1, § 1.2).
- *Inference:* every logger line reaches the test task's `PrefixLogger` and `.log` file (`MWC` § 1.4–1.5), and so the console.
- Mill also prints `Test result: <n> completed[, <m> failures.]` to `systemOut` after each class's tasks, and the `done()` string with `println` (`TRU` lines 229–244, 260–273).

### 4.2 Substituting the loggers

- No parameter of `TestRunner.runTestFramework` or `runTestFramework0` carries loggers. The logger is an anonymous class inside the `private` `executeTasks` (`TRU` lines 156–191, 278–298), and `TestRunner` and `TestRunnerUtils` are `@internal` (`[mill] libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunner.scala` line 6; `TRU` line 20).
- **The adapters call exactly the loggers given to `execute`,** indexed by the position the run sends (`TA/TaskAdapter.scala` lines 31–39; `SNTR/adapter/TaskAdapter.scala` lines 21–29). Code that calls `Task.execute(handler, loggers)` itself therefore decides where every line goes. Mill prints nothing for those calls.
- `ansiCodesSupported` of each logger is sent to the run with the request (`TA/TaskAdapter.scala` lines 42–43).
- The loggers are valid only between task start and `execute` returning (`JSN` § 2.1).

## 5. Stdout and stderr

### 5.1 A JS VM

- **Mill's path.** `JSEnvRPC` sets `withInheritOut(false)`, `withInheritErr(false)`, and an `onOutputStream` that starts a `PipeOutputThread` from each pipe to `System.out` and `System.err`. Its comment: "We never wait for these threads to finish" (`TA/JSEnvRPC.scala` lines 26–47).
- **The copying thread.** `PipeOutputThread` reads up to 8192 bytes at a time and writes and flushes each chunk until end of stream (`TA/PipeOutputThread.scala` lines 28–45).
- **Where the bytes go.**
  - `ExternalJSRun` calls `onOutputStream` straight after starting the process, on the thread that called `start` (`JE/ExternalJSRun.scala` lines 35–44, 120–127), which is the test task's thread (§ 1.1).
  - `System.out` there is Mill's proxy (§ 4.1).
  - *Inference:* each `PipeOutputThread` inherits the test task's `SystemStreams` when it is created, so the bytes reach the task's `PrefixLogger` and `.log` file, as on a sequential JVM run (`MWC` § 1.4–1.5).
- **End of the VM.**
  - Mill sleeps 100 ms and calls `close()` after `handleResults` (`SJM` lines 424–430). `TestAdapter.close()` closes each com run (`TA/TestAdapter.scala` lines 83–119; `TA/JSEnvRPC.scala` lines 64–73).
  - The VM calls `process.exit(0)` when the com socket closes (`NE/ComSupport.scala` line 287).
  - `process.stdout` writes to pipes are "_synchronous_ on Windows, _asynchronous_ on POSIX" (`[node] doc/api/process.md` lines 4180–4191), and `process.exit()` abandons pending writes to `process.stdout` and `process.stderr` (same file, lines 1832–1835).
  - *Inference:* on POSIX, stdout still queued inside Node when the com socket closes is lost before it reaches the pipe, whoever reads the pipe.
- **What plugin code can do.**
  - `JSEnv` is a public trait (`JE/JSEnv.scala` lines 21–90). `RunConfig.withOnOutputStream` is public (`JE/RunConfig.scala` line 83), and an `onOutputStream` "must be invoked by the JS VM" with both streams when neither is inherited (lines 20–41).
  - `TestAdapter`'s constructor takes any `JSEnv` (`TA/TestAdapter.scala` line 28).
  - *Inference:* a `JSEnv` that delegates to `NodeJSEnv` and replaces `onOutputStream` in `startWithCom` receives both pipes, and the plugin's own reader can wait for end of stream.
  - *Inference:* calling `SystemStreamsUtils.withStreams` (public, `SSU` lines 13, 21–58) around the call that starts the VM would instead route `PipeOutputThread`'s writes to streams of the caller's choosing.
  - Both need plugin code to build the `TestAdapter` itself (§ 6.1).

### 5.2 A native process

- `ProcessRunner` calls `inheritIO()` (`SNTR/ProcessRunner.scala` line 36). The process's stdin, stdout and stderr are Mill's own process's descriptors, and no byte passes through a JVM stream.
- Mill's comment before its 100 ms sleep says the subprocess's stdout streams "to the JVM" (`SNM` lines 514–517), which `ProcessRunner` does not do.
- **Nothing configures it.**
  - `ProcessRunner`, `NativeRunnerRPC` and `ComRunner` are `private[testinterface]` (`SNTR/ProcessRunner.scala` line 9; `SNTR/NativeRunnerRPC.scala` line 14; `SNTR/ComRunner.scala` line 19).
  - `TestAdapter.Config` exposes only `binaryFile`, `envVars` and `logger` (`SNTR/adapter/TestAdapter.scala` lines 133–164).
  - `binaryFile` must exist (lines 22–25) and is run as `<path> <port>` (§ 1.2).
  - *General:* Scala's `private[pkg]` is checked at compile time against the accessing code's package.
- **Order.** *Inference:* within one process, bytes written to one descriptor keep their order on that descriptor. Stdout and stderr are separate descriptors.

### 5.3 Where inherited native output goes

- **Under the daemon:**
  - The launcher spawns the daemon with stdout to the file `<out>/mill-daemon/stdout` and stderr to `<out>/mill-daemon/stderr` (`MPL` lines 64–71; `[mill] core/constants/src/mill/constants/DaemonFiles.java` lines 59–67).
  - Every command runs inside `Using.resources(new TailManager(daemonDir), …)` and `tailManager.withOutErr(logger.streams.out, logger.streams.err)` (`[mill] runner/daemon/src/mill/daemon/MillMain0.scala` lines 247, 278–282).
  - `TailManager` starts one `FileToStreamTailer` per file, polling every 2 ms, and writes to the streams set by `withOutErr` (`[mill] runner/daemon/src/mill/daemon/TailManager.scala` lines 9–49).
  - The tailer skips what the file holds on its first read, then copies each `readLine()` result with `println` (`[mill] libs/daemon/client/src/mill/client/FileToStreamTailer.scala` lines 5–58).
  - *Inference:* a native process's output reaches the console through the command's top-level logger while the command runs. It carries no task prefix, does not reach the test task's `.log` file, and arrives line by line within 2 ms of being written.
  - The same files receive every other inherited write: other native runs of the command, and processes Mill runs in the background, which append to them (`RunModule.scala` lines 381–389).
  - This contradicts `JSN` § 2.1, which says, citing prior art, that Native stdout "does not reach the console" under the Mill daemon. Mill's own comment on `SystemStreams.original` agrees with the `TailManager` source (`MCL` § 4.2). No experiment settled which holds.
- **Under `--no-daemon`:** the launcher spawns `MillNoDaemonMain` with the default `os.Inherit` (`MPL` lines 39, 74–79). *Inference:* the native process writes to the launcher's own console descriptors, bypassing Mill's prompt logger. `TailManager` tails `<out>/mill-no-daemon/<sig>/stdout`, which nothing writes.
- **What plugin code can do.** Plugin code in Mill's process can read the daemon's `stdout` and `stderr` files. Nothing found stops `TailManager` copying them: its streams are set only inside `MillMain0` (`MillMain0.scala` line 282). In-process descriptor redirection with `dup2`: `JSN` § 4.2.

## 6. Hooks for plugin code

### 6.1 Scala.js (`TestScalaJSModule`)

| Member | Where | Visibility |
|---|---|---|
| `testFrameworkInstance: Task[(close: () => Unit, framework: Framework)]` | `SJM` lines 398–406 | `protected def`, overridable |
| `testTask(args, globSelectors)` | `SJM` lines 408–431 | `protected def`, overridable; used by `testForked`, `testCached` and `testOnly` (`TM` lines 159–164, 191–192, 223–233) |
| `jsEnvConfig`, `fastLinkJSTest`, `scalaJSToolsClasspath` | `SJM` lines 274, 391–393, 117–119 | public tasks |
| `ScalaJSWorker`, `ScalaJSWorkerExternalModule` | `SJW` lines 15–16, 258–264 | `@internal`, `private[scalajslib]` |
| `ScalaJSConfigModule.jsEnvInputs`, which turns a `Report` into `Input`s | `[mill] libs/scalajslib/config/src/mill/scalajslib/config/ScalaJSConfigModule.scala` lines 240–243 | `private[mill]` |
| `TestAdapter`, `TestAdapter.Config` | `TA/TestAdapter.scala` lines 28, 155–183 | public |
| `NodeJSEnv`, `NodeJSEnv.Config` | `NE/NodeJSEnv.scala` lines 29, 76 | public |

- `testTask` uses `testFrameworkInstance`'s framework through `TestRunner.runTestFramework`, which shares `sbt.testing.` between class loaders (`TestRunner.scala` lines 17–20).
- *Inference:* an override of `testFrameworkInstance` that returns a wrapper around `super`'s framework sees every `runner`, `tasks` and `execute` call, and so can replace the loggers (§ 4.2). The VM has already started inside `super`'s evaluation, so the wrapper cannot change the VM's streams (§ 5.1).
- *Inference:* an override that builds its own `TestAdapter` controls the `JSEnv` and the streams. It must build its own `Input`s and put the Scala.js tools on its own class path at the module's version.

### 6.2 Scala Native (`TestScalaNativeModule`)

| Member | Where | Visibility |
|---|---|---|
| `testTask(args, globSelectors)` | `SNM` lines 492–520 | `protected def`, overridable; the only hook |
| `withScalaNativeBridge`, `toWorkerApi` | `SNM` lines 158–163, 289, 298, 305 | `private[scalanativelib]` |
| `ScalaNativeWorkerApi` (incl. `getFramework`), `NativeLogLevel` (worker API) | `[mill] libs/scalanativelib/api/src/mill/scalanativelib/worker/api/ScalaNativeWorkerApi.scala` lines 6, 41–50 | `private[scalanativelib]` |
| `ScalaNativeWorker`, `ScalaNativeWorkerExternalModule` | `[mill] libs/scalanativelib/src/mill/scalanativelib/worker/ScalaNativeWorker.scala` lines 9, 18 | `private[scalanativelib]` |
| `nativeLink: T[PathRef]`, `allForkEnv`, `forkEnv`, `logLevel`, `bridgeFullClassPath` | `SNM` lines 321, 117, 105–109; `RunModule.scala` lines 41, 48 | public tasks; `bridgeFullClassPath` holds `test-runner_2.13` at the module's version (`SNM` lines 62–66) |
| `scala.scalanative.testinterface.adapter.TestAdapter`, `TestAdapter.Config` | `SNTR/adapter/TestAdapter.scala` lines 18, 133–164 | public |
| `aheadOfTimeDiscoveredTestClassesIfNeeded` | `TM` line 138 | `protected def` |
| `TestModule.handleResults` | `MWC` § 5 | public |

- *Inference:* a `testTask` override can build Scala Native's `TestAdapter` from `nativeLink().path`, `allForkEnv()` and a logger, load the framework, and run it with its own loggers (§ 4.2). It cannot change how the binary's stdio is wired (§ 5.2).
- Discovery, `runner.tasks` and the task loop are in `@internal` `TestRunnerUtils` (`TRU` lines 124–154, 246–298). An override that avoids them must re-implement them.

## 7. What was not determined

- Whether each *inference* above holds when run. In particular:
  - that `PipeOutputThread` and the receiver threads inherit the test task's streams (§ 4.1, § 5.1);
  - that a wrapped `testFrameworkInstance` framework replaces Mill's loggers (§ 6.1).
- Whether inherited native output reaches the console under the daemon, as the `TailManager` source and Mill's comment on `SystemStreams.original` (`MCL` § 4.2) imply, or does not, as `JSN` § 2.1 and the prior art it cites state (§ 5.3).
- Whether Node stdout or socket writes queued when the com socket closes are lost in practice, given Mill's 100 ms sleep (§ 2.1, § 5.1).
- Whether a `TestAdapter` built by plugin code against one Scala.js or Scala Native version works with a test bridge or `test-interface` linked at another (§ 6).
- Whether the Unix `System.getenv` behaviour in Scala Native `0.5.12` is observed as the source implies (§ 2.2).
- Sockets and stdout in jsdom, Selenium, Playwright and PhantomJS environments (§ 2.1).
- Whether Scala Native's `java.net.Socket` writes survive the process's exit (§ 2.2).
- Whether a JS VM or native process that dies or is killed loses bytes it wrote to a socket, a file or stdout (§ 2, § 3.2, § 5).
- What the daemon's environment is relative to the client that ran the command, which decides what a JS VM and a native process inherit beyond `env` and `allForkEnv` (§ 1).
- Whether any of this holds on Mill versions other than `1.1.10`, Scala.js versions other than `1.20.2` with `scalajs-env-nodejs` `1.5.0`, Scala Native versions other than `0.5.12`, or Node versions other than `22`.
