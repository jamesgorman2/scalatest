# How structure and test output leave a Scala.js or Scala Native run

Where the JSON writer and the results renderer can run on Scala.js and Scala Native, what channel can carry envelopes or JSON lines, what one run consists of, and how test output can be captured on each runtime ([How do structure and test output leave a Scala.js or Scala Native run?](https://github.com/jamesgorman2/scalatest/issues/4)). Context: `.planning/structured-test-reporting/SPECIFICATION.md` R-020, R-024, R-034, R-036, R-040, R-061; `.planning/structured-test-reporting/ARCHITECTURE.md` § Modules and § Data flow. Options are stated with their costs; no design is chosen.

## Short answer

| Question | Scala.js | Scala Native |
|---|---|---|
| What is one run? | One `Framework.runner` call by the build tool: one *controller* (`MasterRunner`) in one JS VM process, plus one *worker* (`SlaveRunner`) in its own JS VM process per other build-tool thread that calls `tasks` or `execute`, created lazily (§ 1). | The same, with native executables in place of JS VMs (§ 1). |
| Who drives it? | Under Mill 1.1.10, one thread: the controller runs every task and no worker exists. Under sbt 1.11.7, tasks run on sbt task threads, one worker per thread (§ 1.3). | The same. |
| Does any ScalaTest code run on the JVM? | **No.** The JVM side is the build tool's test adapter (`org.scalajs.testing.adapter`, `scala.scalanative.testinterface.adapter`). ScalaTest runs only in the JS or native processes (§ 1). | The same. |
| Where can the JSON writer run? | In each JS process, writing a file through Node's `fs`: **Node only**; jsdom and browser runs have no filesystem. Alternatively the workers forward to the controller through `notifyServer`, and the controller writes, which still needs `fs`. Or JSON lines go out through `Logger` to the build tool, where no ScalaTest code reads them (§ 3). | In each native process, through `java.io.FileOutputStream`, or the workers forward to the controller and the controller writes (§ 3). |
| Where can the results renderer run? | Per suite, in the process that ran it, through that task's `Logger`s; or for the whole run, in the controller's `done()`, after workers have forwarded their results (§ 3). | The same. |
| Channel to the controller | `notifyServer: String => Unit`, routed JS → JVM → JS. Messages are UTF-16 strings, length-prefixed with a 32-bit count, with no other cap. Order is kept per worker. A worker's messages sent before its `done` returns reach the controller before the controller's `done` runs (inference, § 2.2). | The same, over a TCP socket. The native end's `send` takes no lock (§ 2.2). |
| Channel to the build tool | `Logger.info` and the other levels, and `EventHandler.handle`, over the same RPC connection. Both arrive in order and before `execute` returns. Process stdout is a separate pipe that nobody waits for (§ 2.1). | `Logger` and `EventHandler` as on JS. Process stdout and stderr are **inherited** file descriptors of the build tool's JVM, so they bypass `System.out` and the build tool's logging (§ 2.1). |
| Capturing test output | Replace `process.stdout.write` and `process.stderr.write` (Node), or `console.log` and kin (every environment). Neither attributes concurrent async tests. `Console.withOut` and `System.setOut` miss most paths (§ 4.1). | A permanent delegating `System.out`, installed before `scala.Console` initialises, catches `println` and `System.out` on every thread. Writes straight to file descriptor 1, C code and child processes need `dup2` at the descriptor level, which has no per-test attribution (§ 4.2). |

## Sources

| Tag | Source | Version |
|---|---|---|
| `[this]` | this repo | `fe1d39319` |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939a`), the version `project/plugins.sbt` pins |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.4.0` (`ef45761`), the version `[sjs]` `project/Build.scala` lines 1404, 1432-1433 depend on |
| `[jsdom]` | <https://github.com/scala-js/scala-js-env-jsdom-nodejs> | tag `v1.1.1` |
| `[sn]` | <https://github.com/scala-native/scala-native> | tag `v0.5.12` (`65dd513`), the version `project/plugins.sbt` pins |
| `[sbt]` | <https://github.com/sbt/sbt> | tag `v1.11.7` (`9d2a5f4`), from `project/build.properties` |
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b7`), the version prior art used |
| `[prior]` | `C:\Users\james\StudioProjects\scalatest-consolidated-reporter` | working tree, 2026-10-26 |

Paths are relative to each source's root. `[this]` JS paths are under `js/core/src/main/scala/org/scalatest/tools/`, and Native paths under `native/core/src/main/scala/org/scalatest/tools/`. `[prior]` wrapper paths are under `modules/scalatest-consolidated-reporter/`. No experiment was run for this note; claims come from source, and those marked *inference* follow from source without having been observed.

## 1. What one run consists of

### 1.1 Scala.js

- The build tool creates one `TestAdapter` per JS environment and input (`[sjs] sbt-plugin/src/main/scala/org/scalajs/sbtplugin/ScalaJSPluginInternal.scala` lines 672-712; `[mill] libs/scalajslib/worker/1/src/mill/scalajslib/worker/ScalaJSWorkerImpl.scala` lines 202-227).
- The adapter keeps one JS VM per JVM thread that ever talks to it, keyed by thread ID. It starts each VM on that thread's first call (`[sjs] test-adapter/src/main/scala/org/scalajs/testing/adapter/TestAdapter.scala` lines 36-37, 135-152).
- `Framework.runner` on the JVM becomes `RunnerAdapter.apply`. It allocates a run ID and calls `createControllerRunner` in the VM of the calling thread, which constructs ScalaTest's `MasterRunner` there (`RunnerAdapter.scala` lines 90-100; `[sjs] test-bridge/src/main/scala/org/scalajs/testing/bridge/TestAdapterBridge.scala` lines 45-58).
- Every later `tasks` or `execute` call goes to the VM of the calling thread. If that VM is not the controller's, the adapter first calls `createWorkerRunner` in it, which constructs `SlaveRunner` with a `send` that posts to the JVM (`RunnerAdapter.scala` lines 45-51, 68-86; `TaskAdapter.scala` lines 28-48; `TestAdapterBridge.scala` lines 54-56). The number of workers is therefore unknown until the run ends.
- `done` on the JVM calls `done` on every worker and waits, then on the controller (`RunnerAdapter.scala` lines 53-66). Per the interface, the controller's `done` may not be called before every worker's `done` has returned, and on Scala.js `done` may not be called before every task's continuation has run (`[sjs] test-interface/src/main/scala/sbt/testing/Runner.scala` lines 101-109). A worker's `done` string is ignored (same lines).
- VMs outlive runs. The thread-to-VM map belongs to the adapter, not the run, so several runs share a VM, multiplexed by run ID (`TestAdapter.scala` lines 121-133). sbt closes its adapters when a command finishes (`[sjs] sbt-plugin/src/main/scala/org/scalajs/sbtplugin/ScalaJSPlugin.scala` lines 365-371), and Mill when the test task finishes (`[mill] libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala` lines 428-429). Process-global state, such as an installed output capture, can therefore outlive one run.
- `testHtml` is a separate path. sbt's HTML runner calls `framework.runner(Array(), Array(), …)` in the browser, with no arguments, no workers and no JVM (`[sjs] test-bridge/src/main/scala/org/scalajs/testing/bridge/HTMLRunner.scala` lines 130-134).

### 1.2 Scala Native

The adapter is a port of Scala.js's ("Ported from Scala.js", `[sn] test-runner/src/main/scala/scala/scalanative/testinterface/ComRunner.scala` line 4) and keeps the same model. It has one native process per JVM thread (`[sn] test-runner/.../adapter/TestAdapter.scala` lines 108-129) and runs controller and worker `done` in the same order. It skips a worker or controller whose connection has closed (`RunnerAdapter.scala` lines 43-60). Each process is the linked test binary started as `<binary> <port>`; it connects back over TCP (`ProcessRunner.scala` lines 29-44; `[sn] test-interface/.../TestMain.scala` lines 77-96).

### 1.3 How sbt and Mill drive it

- **Mill 1.1.10.** `TestRunnerUtils.runTestFramework0` calls `framework.runner`, then `runner.tasks`, then executes each task in a `while` loop, then calls `runner.done()`, all on one thread (`[mill] libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` lines 123-152, 154-206, 230-240, 278-300). The Scala.js and Scala Native modules use it in-process (`ScalaJSModule.scala` lines 408-431; `libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala` lines 492-520). Every task therefore runs on the controller, and `notifyServer` is never used. `loadFrameworks` runs in a separate `Task.Anon`. If Mill evaluated it on another thread, it would start one more, idle VM (inference).
- **sbt 1.11.7.** The runner is created in `createTestRunners` (`[sbt] main/src/main/scala/sbt/Defaults.scala` lines 1586, 1666), and `runner.tasks` is called in `TestFramework.createTestTasks` (`testing/src/main/scala/sbt/TestFramework.scala` lines 281-282). Each task executes as its own sbt `Task` when `Test / parallelExecution` is true, the default (`Defaults.scala` line 362; `main-actions/src/main/scala/sbt/Tests.scala` lines 362-363, 428-436), or all tasks in one sbt `Task` otherwise (`Tests.scala` lines 462-490). `done()` runs after all of them (`Defaults.scala` lines 1739-1743). Which sbt thread runs each step is up to sbt's scheduler. *Inference:* a parallel run has several workers, and even a serial run usually has one worker, because the controller's thread and the executing thread differ.
- Both plugins reject `Test / fork := true` for these platforms (`ScalaJSPluginInternal.scala` lines 676-680; `[sn] sbt-scala-native/.../ScalaNativePluginInternal.scala` lines 329-333).

### 1.4 Consequences for structure

- The controller and each worker build their own `Tracker` (`[this]` JS `MasterRunner.scala` line 129, `SlaveRunner.scala` line 115; Native lines 131, 116), so ordinals repeat across the processes of one run. IDs minted per process need a per-process component to satisfy R-014.
- Nothing in the protocol tells a process the adapter's run ID or how many workers exist (`RunnerArgs` carries only run ID, framework and arguments to the bridge, `[sjs] test-common/src/main/scala/org/scalajs/testing/common/RunnerArgs.scala`, and the bridge does not pass the run ID to the framework, `TestAdapterBridge.scala` lines 51-58). A worker has no shared run identity unless the controller hands it one through `receiveMessage` (§ 2.2), or the build tool puts one in the arguments.
- No `DispatchReporter` or `SuiteSortingReporter` is compiled for JS or Native (`[this] project/GenScalaTestJS.scala` line 129, `project/GenScalaTestNative.scala` line 119). `TaskRunner` passes `SbtReporter` over `SbtLogInfoReporter` straight into `Args` (`[this]` JS `TaskRunner.scala` lines 141-146). The envelope dispatch in `ARCHITECTURE.md` § Data flow would sit between the wrappers and that reporter.

## 2. Channels and their limits

### 2.1 Process to build tool

| Channel | Path | Ordering and timing | Limits |
|---|---|---|---|
| `Logger.info`, `warn`, `error`, `debug`, `trace` | `RemoteLogger` → `mux.send` → JVM handler → the build tool's logger (`[sjs] TestAdapterBridge.scala` lines 128-140; `TaskAdapter.scala` lines 31-39; `[sn] TestAdapterBridge.scala` lines 118-129) | Same connection as events and the `execute` reply. Messages are dispatched synchronously in arrival order (`[sjs] test-common/.../RPCCore.scala` lines 31-37, 55), so every log line arrives before `execute` returns on the JVM. Only valid between task start and the continuation: handlers are detached when `execute` returns (`TaskAdapter.scala` lines 49-55). | Shown to the user: sbt logs at `info` and may buffer per suite (`[sbt] testing/src/main/scala/sbt/internal/testing/TestLogger.scala` lines 35, 60); Mill `println`s to the task's `System.out` (`TestRunnerUtils.scala` lines 179-191). ScalaTest's `-oJ` already writes each event's JSON this way (`[this]` JS `TaskRunner.scala` lines 230-231). |
| `EventHandler.handle` | Same RPC (`TestAdapterBridge.scala` lines 124-126) | As above | Fixed `sbt.testing.Event` fields; R-004 forbids adding to them. |
| `done()` return value | Controller only; sbt logs it at `info` (`[sbt] main-actions/src/main/scala/sbt/TestResultLogger.scala` lines 118-125); Mill `println`s it (`TestRunnerUtils.scala` lines 233-238) | Last; after every worker's `done` | No logger is available in `done`; the string arrives only at the end, so a crash loses it (R-035). |
| Process stdout and stderr, JS | Node VM pipes → `PipeOutputThread` → JVM `System.out` and `System.err` (`[sjs] test-adapter/.../JSEnvRPC.scala` lines 26-47; `PipeOutputThread.scala`) | Unordered against RPC. "We never wait for these threads to finish" (`JSEnvRPC.scala` lines 32-34). Mill sleeps 100 ms before closing, because output is otherwise dropped (`ScalaJSModule.scala` lines 425-429). | No framing; the user sees it. |
| Process stdout and stderr, Native | `ProcessBuilder.inheritIO()` (`[sn] test-runner/.../ProcessRunner.scala` lines 29-36) | Unordered against RPC; the bytes never pass through the JVM | Goes to the build-tool JVM's real file descriptors, not its `System.out`. Under the Mill daemon it does not reach the console (`[prior] src-native/testreporter/StandardStreams.scala` lines 5-9). *Inference:* the same applies to sbt in server mode, which installs a replacement `System.out`, the reason Scala.js stopped inheriting (`JSEnvRPC.scala` lines 27-30). |

### 2.2 Worker to controller: `notifyServer` and `receiveMessage`

- **Path.** Worker `send` → JVM `msgWorker` handler → `controller.mux.send(msgController, FrameworkMessage(workerId, msg))` → controller `receiveMessage(msg)`. A `Some(reply)` from the controller travels back to that worker's `receiveMessage` (`[sjs] RunnerAdapter.scala` lines 36-39, 75-79; `TestAdapterBridge.scala` lines 64-68, 117-122; `Runner.scala` lines 131-143).
- **Today.** Every event on a worker sends its class name (`[this]` JS `TaskRunner.scala` line 228), and the controller counts them for the summary in `done()` (`MasterRunner.scala` lines 132-150, 192-213). A task on the controller calls `receiveMessage` directly (`MasterRunner.scala` lines 187-190). So the per-event message rate an envelope channel would need already exists; only the payload grows.
- **Size.** Each frame is a 32-bit length followed by that many UTF-16 code units. Scala.js on Node frames this way in both directions (`[envs] nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/ComSupport.scala` lines 72-73, 241-242, 265-305). Scala Native does the same (`[sn] ComRunner.scala` lines 59-63, 206-209; `[sn] test-interface/.../NativeRPC.scala` lines 24-27, 30-47). The only bound is memory: each side builds the whole message, and the JVM side reads it one `readChar` at a time. A worker message crosses two hops and is wrapped twice, in `RunMux` and `FrameworkMessage`.
- **Encoding.** Messages must be valid UTF-16, and the channel's behaviour with an unpaired surrogate is unspecified (`[envs] js-envs/src/main/scala/org/scalajs/jsenv/JSEnv.scala` lines 56-59). Captured test output can contain one. JSON that escapes surrogates as `\uXXXX` avoids it.
- **Ordering.** Per transmitter-receiver pair, delivery is in order and complete while both ends run (`JSEnv.scala` lines 61-81). Messages from different workers interleave in arrival order at the JVM.
- **Relative to `done()`.** *Inference:* a worker's messages sent before its `done` reply are forwarded to the controller synchronously, on the JVM thread that reads that worker's socket (`RPCCore.scala` line 55 onwards dispatches message endpoints synchronously). Only after its `done` reply does the JVM call the controller's `done` (`RunnerAdapter.scala` lines 56-58). The controller runs message endpoints synchronously but defers RPC handlers such as `done` through an execution context (`[sjs] RPCCore.scala` `handleMessage`, `Future.flatMap(bep.exec)`), so it sees every such message before its own `done`. A message sent after the worker's `done` returns is lost or fails ("Unknown opcode … the worker terminated early", `[sjs] RPCCore.scala` lines 95-109).
- **Replies are asynchronous.** A JS worker cannot block on one: it has one thread, and `Await` cannot wait there. ScalaTest's Native worker shows the workaround: it asks the controller to write a temporary file and polls for it every 500 ms (`[this]` Native `Runner.scala` lines 36-50; `MasterRunner.scala` lines 186, 206-214; `SlaveRunner.scala` line 40). *Inference:* whether a Native worker can receive a reply while a task runs depends on which thread runs `execute`. RPC handlers run through `ExecutionContext.global` (`[sn] TestMain.scala` line 79; `test-interface-common/.../RPCCore.scala` lines 111-113), which is a thread pool when multithreading is linked and a queue otherwise.
- **Native thread safety.** `NativeRPC.send` writes the length and then the bytes to a `DataOutputStream` without a lock (`[sn] NativeRPC.scala` lines 24-27), and `RPCCore.send` and `RunMuxRPC.send` add none. ScalaTest on Native reports on `ExecutionContext.global` threads (`[this]` Native `TaskRunner.scala` line 36). *Inference, unverified:* concurrent `send`s from several threads, of events, logs or envelopes, can interleave frames.

### 2.3 Filesystem

- **Node.** Scala.js's `javalib` has no `java.io.File` or `FileOutputStream` (`[sjs] javalib/src/main/scala/java/io/`), so writing means calling Node's `fs` through `js.Dynamic`. A CommonJS module can use `require`, and an ES module needs `process.getBuiltinModule` (Node 20.16 or newer, or 22.3 or newer on Node 22). Prior art does exactly this with `appendFileSync`, one write per append (`[prior] src-js/testreporter/ResultsFiles.scala` lines 9-38). Writes must be synchronous: `done` cannot wait on JS.
- **jsdom.** The test code runs in a jsdom window that is given `scalajsCom`. It runs as a script element, so Node's `require` and `process` are not in its scope (`[jsdom] jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala` lines 82-132; that `process` is absent from the window is *inference*). It has no filesystem, though the com channel works.
- **Browser** (Selenium, `testHtml`): no filesystem.
- **Native.** `java.io.FileOutputStream` works, but its `write` ignores a partial POSIX write, so prior art appends through the stream's channel and checks the count (`[prior] src-native/testreporter/ResultsFiles.scala` lines 8-24).

## 3. Where the JSON writer and results renderer can run

No ScalaTest code runs on the JVM in these runs (§ 1). "Master side" therefore means the controller's JS VM or native process, not the JVM. The only JVM-side code is the build tool's: sbt's test listeners and Mill's test task, which see `sbt.testing.Event`s and logger strings.

### 3.1 JSON writer options

| Option | Works on | Costs |
|---|---|---|
| **W1. Each process writes its own stream.** | Native; Scala.js on Node | One run yields 1 + N files or streams, with N unknown in advance, so R-033's run identity must tie them together across processes that share no ID (§ 1.4). A crash loses only that process's last object, which meets R-035. jsdom and browser runs get no JSON, which violates R-036 for those environments. Needs the Node `fs` shim and its Node-version floor for ES modules (§ 2.3). |
| **W1′. All processes append to one file.** | As W1 | As W1, plus a need for single-write appends of whole lines from concurrent processes, as prior art does (`[prior] ResultsFiles.scala` on both platforms). Ordering across processes is by write time. |
| **W2. Workers forward to the controller through `notifyServer`, and the controller writes.** | Native; Scala.js on Node | One stream per run, and the controller can stamp run identity. Costs two hops per envelope and a doubled payload (§ 2.2). Order across workers is arrival order. A worker crash loses whatever is in flight or unforwarded. The controller still needs `fs`, so jsdom and browser runs are not helped. Under Mill this collapses to W1, with a single process and no forwarding (§ 1.3). |
| **W3. JSON lines through `Logger`.** | Every environment, including jsdom; not `testHtml`, which has no build tool | The only channel that reaches the JVM everywhere. But the lines land in the build tool's console or log, interleaved with human output and prefixed by sbt. Something JVM-side must extract them, and ScalaTest has no code there. The Mill plugin could own a JS or Native test task that wraps the adapter's loggers (*inference*). sbt has no equivalent hook short of a plugin, and an sbt plugin is out of scope. `-oJ` already does this today (§ 2.1). It also conflicts with R-020's progress output on the same loggers. |
| **W4. The controller's `done()` string.** | Every environment with a build tool | Arrives only at the end, so a crash loses it (R-035), and it is printed to the user. Unsuitable for JSON. |
| **W5. Payload in `sbt.testing.Event`.** | — | Violates R-004. Listed only to rule it out. |

### 3.2 Results renderer options

| Option | Costs |
|---|---|
| **R1. Per suite, in the process that ran it.** The renderer writes a suite's results through that task's `Logger`s when its task completes. | No cross-process traffic, and it works under every driver. R-020's results for the whole run become a sequence of per-suite blocks. Interleaving across workers is per task, since each `execute` delivers its log lines before it returns (§ 2.1), though sbt's buffering and scheduling decide the final order. |
| **R2. For the whole run, in the controller's `done()`.** Workers forward structure or results through `notifyServer`, and the controller renders and returns the string. | One results block per run. Relies on the ordering inference in § 2.2. `done()` has no logger, so the output goes to wherever the build tool prints the summary, at `info` under sbt (`TestResultLogger.scala` lines 118-125) and `println` under Mill. A crashed worker's suites appear only as far as forwarded. Under Mill, everything is already on the controller. |
| **R1 + R2.** Progress per task through `Logger`, results at `done()`. | Matches R-020's shape most closely, at R2's cost. |

R-024, the output of failed tests in results, needs captured output at the renderer. Under R2 that output must also travel through `notifyServer`.

## 4. Capturing stdout and stderr during a test

### 4.1 Scala.js

`System.out` and `System.err` are `JSConsoleBasedPrintStream`s. Each one buffers to a newline and then calls `console.log` or `console.error`, looking `global.console` up on every call. `flush()` of a partial line writes it with `↩` and starts the next line with `↪` (`[sjs] javalib/src/main/scala/java/lang/System.scala` lines 40-66, 346-386, 389-390). `scala.Console.out` is a `DynamicVariable` initialised from `System.out` (`[sjs] scalalib/overrides-2.13/scala/Console.scala` line 130). JS has one thread.

| Mechanism | Catches | Misses |
|---|---|---|
| `Console.withOut` | `println` inside the synchronous block | `System.out`, `console.log`, `process.stdout.write`; anything after the test's first asynchronous boundary (`[prior] .docs/learnings/scalatest.md` 2026-10-14) |
| `System.setOut` and `setErr` | `System.out` and `System.err` | `println` once `Console` has initialised; `console.log`; direct `process.stdout.write` |
| Replace `console.log`, `info`, `warn`, `error` and `debug` | `System.out`, `System.err`, `println` and `console.*`, in Node, jsdom (on the window's `console`) and browsers | Direct `process.stdout.write` (Node); a partial line still in `JSConsoleBasedPrintStream`'s buffer at a test boundary, which surfaces with the next write. Must pass TeamCity service-message lines through unaltered (R-007). |
| Replace `process.stdout.write` and `process.stderr.write` (prior art) | Everything above on Node (`[prior] src-js/testreporter/OutputCapture.scala` lines 8-81; learning 2026-10-14) | Not available in jsdom or browsers. Wrapping `Console.out` in a `PrintStream` over `System.out` adds `↩` and `↪` markers (same learning). |
| JVM side: the VM's stdout pipe | Everything the VM prints | No attribution: unordered against events (§ 2.1), and only build-tool code sits there |

Attribution: one thread, so routing by thread separates nothing. Serial async tests run one at a time, so a capture switched at test boundaries attributes their output. Concurrent tests of an async `ParallelTestExecution` suite interleave on the event loop, and nothing found separates them; Node's `AsyncLocalStorage` is unverified (`[prior] .planning/research/test-output-attribution.md` § 5, § 7). Under the adapter, each VM receives one `execute` at a time, because its one JVM thread blocks on each call (`TaskAdapter.scala` lines 45-48; *inference*). Prior art nevertheless guards against overlapping tasks (`[prior] src-js/testreporter/OutputCapture.scala` lines 11-13). The cause is unconfirmed: stray callbacks of a finished suite, or the HTML runner.

### 4.2 Scala Native

`System.out` is `new PrintStream(new FileOutputStream(FileDescriptor.out))` (`[sn] javalib/src/main/scala/java/lang/System.scala` lines 278-281). Scala Native overrides no `scala.Console`, so `Console.out` is the standard library's `DynamicVariable`, whose thread-inherited default is `System.out` as it was when `Console` initialised. Multithreading is linked whenever the program uses threads (`[sn] tools/src/main/scala/scala/scalanative/build/NativeConfig.scala` lines 75-105).

| Mechanism | Catches | Misses |
|---|---|---|
| `Console.withOut` | `println` on the calling thread and threads created inside the block | `System.out`, and threads created earlier: `ExecutionContext.global` workers print into a stale or closed target (`[prior] .docs/learnings/scalatest.md` 2026-10-14) |
| `System.setOut(capture)` per test, with `Console.withOut` | Mostly, but intermittently loses `println` lines | As above (same learning) |
| A permanent delegating `System.out`, installed once before anything initialises `Console`, and retargeted per test (prior art) | `println` and `System.out` on every thread | Writes to `new FileOutputStream(FileDescriptor.out)`, C `printf` through `extern`, child processes, and code that kept the old `System.out` (`[prior] src-native/testreporter/OutputCapture.scala` lines 5-57; `test-output-attribution.md` § 5 table) |
| The same, with a `ThreadLocal` routing target | Separates synchronous tests by thread | `ExecutionContext` continuations and pools; and the Native `TaskRunner` gets no distributor, so no tests run concurrently except async `ParallelTestExecution` (`test-output-attribution.md` § 5). Unverified behind the delegating stream (same note, open question 4). |
| `dup`, `dup2` and `pipe` on file descriptors 1 and 2 (`[sn] posixlib/src/main/scala/scala/scalanative/posix/unistd.scala` lines 66-67, 186-187) | Every byte the process and its children write, C code included | Per-thread or per-test attribution, except by switching at boundaries. A pipe must be drained concurrently or it blocks the writer, so it needs a thread or a temporary file. Each boundary must flush Java and C stdio buffers. POSIX only: Windows needs an equivalent (*inference*). Must pass TeamCity service-message lines through unaltered (R-007). |

On both runtimes ScalaTest's own output goes through `Logger`s, that is RPC, not stdout (`[this]` JS `TaskRunner.scala` lines 208-218), so a stdout capture does not swallow ScalaTest's reporting. A capture that tees to the original stream keeps R-007. On Native, the original stream is the build tool's inherited descriptor (§ 2.1).

## 5. Inferences to confirm by experiment

1. Under sbt 1.11.7, how many workers a parallel run and a serial run actually create, and whether the controller ever executes a task (§ 1.3).
2. That every `notifyServer` message a worker sends before its `done` returns reaches the controller's `receiveMessage` before the controller's `done`, on both platforms (§ 2.2).
3. Whether concurrent `send`s on a multithreaded Native process corrupt frames, at the rate envelopes would add (§ 2.2).
4. Whether a Native worker can receive a `receiveMessage` reply while a task is running (§ 2.2).
5. Whether Native stdout reaches the user under sbt's thin client or server mode (§ 2.1).
6. Whether jsdom's window exposes `process` or `require` (§ 2.3).
7. Whether Mill's `loadFrameworks` thread differs from the test thread, which would start an idle extra VM (§ 1.3).
8. Whether a Scala.js process ever runs two suites' tasks at once, as prior art guards against (§ 4.1).
