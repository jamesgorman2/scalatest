# Capturing a module's test discovery output on Mill 1.3.1

Where Mill 1.3.1 runs `ZincOp.GetTestTasks` for a JVM test module, where a write from a test class's static initializer goes during it, whether discovery runs that initializer, and whether it runs again on a later test command in the same daemon. Answers [Many modules in one test command - Capturing a module's test discovery output on Mill 1.3.1](https://github.com/jamesgorman2/scalatest/issues/243). Facts only; no capture is chosen.

Other notes hold facts this one relies on and does not repeat:

- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`: how a JVM worker's streams are wired, and that the discovery runner is created in Mill's own process (§ 2.4).
- `.planning/event-stream-reporting/research/mill-nested-module-run.md`, cited as `MNR`: the module run as an anonymous task's body in the test command's nested evaluation.
- `.docs/learnings/mill-build-tooling.md`, cited as `MBT`: what `testBatchFrameworkTasks` changes.

## Short answer

- **(a) Process and thread.** With no `javaHome`, Mill's class scan and ScalaTest's `Runner.tasks` run in Mill's own process, on the thread that calls `InternalJvmWorkerApi.apply`: in the test command, the module's anonymous task's thread. With any `javaHome`, they run in a `MillJvmWorkerMain` subprocess, on the connection-handler thread the subprocess starts for that one request, while the calling thread waits (§ 1).
- **(b) Stream.** Without `javaHome`, `System.out` and `System.err` are Mill's process-wide proxies to the writing thread's `SystemStreams.current`, which Mill binds to the anonymous task's logger streams. With `javaHome`, the subprocess forwards each write over the RPC socket, and the calling thread prints it to its own `Console.out` or `Console.err`, which Mill binds to the same streams (§ 2).
- **(c) The initializer runs during discovery.** Mill passes `explicitlySpecified = false`. ScalaTest's `shouldDiscover` therefore evaluates `accessible`, which loads the class with `Class.forName(name, true, loader)` and so runs its static initializer, on the thread of (a). Mill's own scan only loads classes with `ClassLoader.loadClass` and reflects on them, which by *general* JVM rules does not initialize them (§ 3).
- **(d) No reuse.** Each `GetTestTasks` call builds a new class loader with `Jvm.withClassLoader` and closes it afterwards. Nothing caches it, so the initializer runs on every call, and so on every test command. What Mill does cache across commands in one daemon is the JVM worker, its own class loader, and with `javaHome` the subprocess, keyed on `(javaHome, runtimeOptions)`. None of them holds the test classes (§ 4).
- **For the design.** The plugin can capture one module's discovery output by binding the calling thread's `SystemStreams.current`, `Console.out` and `Console.err` around its `jvmWorker.apply(ZincOp.GetTestTasks(...))` call. `SystemStreamsUtils.withStreams` binds all three, and it also binds `os.Inherit`, so a JVM worker subprocess spawned during the call would pump its stderr descriptor into the capture for the rest of its life. Concurrent modules stay apart because each binding is per thread (§ 5). Things that would make AC-025 and AC-026 unreliable: § 5.2.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.3.1` (`e5e91a4fac1edbfa1c8c96a3cf1c5f474579132f`) |
| `[this]` | this repo | `faf99896d` |

Paths are relative to each source's root. A `[mill]` path `P` is at `https://github.com/com-lihaoyi/mill/blob/e5e91a4fac1edbfa1c8c96a3cf1c5f474579132f/P`. Abbreviations for `[mill]`:

- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TR/` is `libs/javalib/testrunner/src/mill/javalib/testrunner/`.
- `JW/` is `libs/javalib/worker/src/mill/javalib/worker/`.
- `ZW` is `libs/javalib/worker/src/mill/javalib/zinc/ZincWorker.scala`.
- `RPC/` is `libs/rpc/src/mill/rpc/`.
- `SSU` is `core/api/java11/src/mill/api/SystemStreamsUtils.scala`.
- `SS` is `core/api/daemon/src/mill/api/daemon/SystemStreams.scala`.
- `TTC` is `core/exec/src/mill/exec/TaskThreadContext.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `JVM` is `libs/util/java11/src/mill/util/Jvm.scala`.

`[this]` paths: `JTMR` is `mill-plugin/plugin/src/org/scalatest/mill/JvmTestModuleRun.scala`, `JTMI` is `mill-plugin/plugin/src/org/scalatest/mill/JvmTestModuleInputs.scala`, and `FW` is `jvm/core/src/main/scala/org/scalatest/tools/Framework.scala`.

No experiment was run for this note. Every `[mill]` and `[this]` citation was opened at the version above. A statement marked *inference* follows from the code read and was not observed. A statement marked *general* is JVM behaviour that no source here was opened for. § 6 lists what could not be determined.

## 1. Where discovery runs (a)

### 1.1 The call

- The plugin's module run sends `ZincOp.GetTestTasks` in `JvmTestModuleRun.discoveredTestClasses`, called from `layOutWorkers` before any worker starts. It calls `inputs.jvmWorker.apply(op, javaHome = inputs.javaHome)` with no `javaRuntimeOptions`, so they default to `Nil` (`[this]` JTMR; `[mill]` `libs/javalib/api/src/mill/javalib/api/internal/InternalJvmWorkerApi.scala`).
- It does not call it where the module sets `testBatchFrameworkTasks` (`[this]` JTMR `layOutWorkers`). Mill's own `TestModuleUtil.runTests` skips discovery in the same case (TMU `runTests`; `MBT`).
- In the test command, the module run is the body of the module's anonymous task. That body runs on a Mill pool thread, `execution-contexts-threadpool-<n>-thread-<m>` (`MNR` § 5).

### 1.2 Without `javaHome`

- `JvmWorkerImpl.apply` uses `localZincApi` when `javaHome.isEmpty && javaRuntimeOptions.isEmpty` (`JW/JvmWorkerImpl.scala`).
- `localZincApi` calls `zincLocalWorker.apply` directly, and `ZincWorker.apply` matches `ZincOp.GetTestTasks` and calls `mill.javalib.testrunner.GetTestTasks(msg)` (`JW/JvmWorkerImpl.scala`; ZW `apply`). Neither starts a thread.
- `GetTestTasks.apply` runs `TestRunnerUtils.getTestTasks0` inside `Jvm.withClassLoader`, and `getTestTasks0` calls `getTestTasks`, which builds the runner, runs Mill's class scan (`discoverTests`) and then calls `runner.tasks` (`TR/GetTestTasks.scala`; `TR/TestRunnerUtils.scala`).

So the scan and `Runner.tasks` run in Mill's process, on the anonymous task's thread.

### 1.3 With `javaHome`

- Any `javaHome` makes `JvmWorkerImpl.apply` build a `SubprocessZincApi` (`JW/JvmWorkerImpl.scala`).
  - Where the `javaHome` is a Java older than 17, `workerJavaHome` is `None`, and the subprocess runs on the `java` under Mill's own `java.home` (`JW/JvmWorkerImpl.scala`; JVM `jdkTool`).
  - Otherwise the subprocess runs on the module's `javaHome`.
- `SubprocessZincApi.apply` takes a subprocess from `subprocessCache`, keyed on `SubprocessZincApi.Key(javaHome, runtimeOptions)`, opens a new socket to it, and sends one `JvmWorkerRpcServer.Request` holding the op (`JW/SubprocessZincApi.scala`). The subprocess's main class is `mill.javalib.worker.MillJvmWorkerMain`, started with `Jvm.spawnProcess` (`JW/JvmWorkerImpl.scala`).
- In the subprocess, the server starts a `HandleRunThread-<socket>` thread for each accepted socket, and that thread starts a `ConnectionHandler(...)` thread that calls `handleConnection` (`[mill]` `libs/daemon/server/src/mill/server/Server.scala`). `MillJvmWorkerMain`'s `handleConnection` runs `JvmWorkerRpcServer.run()`, which handles each request on that same thread and calls `ZincWorker.apply`, and so `GetTestTasks` (`JW/MillJvmWorkerMain.scala`; `JW/JvmWorkerRpcServer.scala`; `RPC/MillRpcServer.scala` `run`, `onAsk`).
- In Mill's process, the calling thread waits in `MillRpcClient`'s `awaitForResponse` until the response arrives (`RPC/MillRpcClient.scala`).

So the scan and `Runner.tasks` run in the subprocess, on the connection-handler thread for that request. *Inference:* two modules' concurrent calls with the same key reach one subprocess on two sockets, and so run on two threads.

## 2. Where a static initializer's `System.out` and `System.err` go (b)

### 2.1 Without `javaHome`

- Mill's daemon and no-daemon mains run inside `SystemStreamsUtils.withTopLevelSystemStreamProxy`, which sets `System.out` to `ThreadLocalStreams.Out` and `System.err` to `ThreadLocalStreams.Err` (`[mill]` `runner/daemon/src/mill/daemon/MillDaemonMain0.scala`; `runner/daemon/src/mill/daemon/MillNoDaemonMain0.scala`; SSU). Each forwards every write to `SystemStreams.current.value.out` or `.err` (SSU `ThreadLocalStreams`), and `SystemStreams.current` is a `scala.util.DynamicVariable` (SS).
- `ThreadLocalStreams.Out` and `Err` are `PrintStream`s built with no charset, over a `ProxyOutputStream` that passes each byte array to the current stream unchanged (SSU `ThreadLocalStreams`). *General:* a `PrintStream` built with no charset encodes with the JVM's `Charset.defaultCharset()`. So the bytes that reach `SystemStreams.current.value.out` are already encoded with Mill's JVM's default charset.
- Mill runs a task body inside `TaskThreadContext.bind`, which calls `SystemStreamsUtils.withStreams(streams)` (GE `wrap`; TTC). For a task that is not exclusive, `streams` is the task logger's streams (GE `wrap`; `MNR` § 5).
- `withStreams` binds `SystemStreams.current`, `Console.in`, `Console.out`, `Console.err`, `os.Inherit.in`, `os.Inherit.out` and `os.Inherit.err` for its body (SSU `withStreams`).
- The discovery class loader's parent is the platform loader. Only `sbt.testing.` classes come from Mill's loader (TR/GetTestTasks.scala; `[mill]` `core/api/daemon/src/mill/api/daemon/MillURLClassLoader.scala`). *General:* `java.lang.System` is a bootstrap class, so the test class's `System.out` is the same proxy.

So, on the writing thread, a write goes through the proxy to the anonymous task's logger streams. A *general* consequence: class initialization runs on the thread that triggers it, which is the thread of § 1.2.

### 2.2 With `javaHome`

- `MillJvmWorkerMain.main` also runs inside `withTopLevelSystemStreamProxy`. `handleConnection` wraps `server.run()` in `withStreams(SystemStreams(PrintStream(server.clientStdout.asStream), PrintStream(server.clientStderr.asStream), DummyInputStream))` (`JW/MillJvmWorkerMain.scala`).
- `clientStdout` and `clientStderr` send each piece of text to Mill's process as `MillRpcServerToClient.Stdout` or `Stderr`, on the request's socket (`RPC/MillRpcServer.scala`; `RPC/RpcConsole.scala`).
- `asStream` is an `RpcConsole.Utf8OutputStream`, which decodes the bytes it receives as UTF-8 and sends the text as strings (`RPC/RpcConsole.scala`). The subprocess's `PrintStream`s are built with no charset, so *general:* they encode with the subprocess's `Charset.defaultCharset()`.
- In Mill's process, `awaitForResponse` handles those messages on the calling thread with `RpcConsole.stdoutHandler` and `stderrHandler`, which print each string to `Console.out` and `Console.err` (`RPC/MillRpcClient.scala`; `RPC/RpcConsole.scala`). There, `Console.out` and `Console.err` are what `withStreams` bound for the anonymous task (§ 2.1), and each encodes the string with its own charset.
- *Inference:* both kinds of message travel on one socket in the order they were sent, and the calling thread handles all of them before it reads the response, so all forwarded output reaches the calling thread's streams before `apply` returns.

So a write goes to the subprocess's per-connection stream, then over the socket, then to the calling thread's `Console.out` or `Console.err`, and so to the anonymous task's logger streams. The calling thread's `System.out` is not involved.

## 3. Whether discovery runs the initializer (c)

- **What Mill passes.** `TestRunnerUtils.getTestTasks` builds each `TaskDef` as `TaskDef(cls.getName.stripSuffix("$"), fingerprint, false, Array(new SuiteSelector))`, so `explicitlySpecified` is `false`. It does so only for the classes that pass `classFilter`, which `GetTestTasks` builds from the class globs with `TestRunnerUtils.globFilter` (`TR/TestRunnerUtils.scala`; `TR/GetTestTasks.scala`).
- **What ScalaTest then does.** `Runner.tasks` drops the task definitions that `-w` and `-m` exclude, creates a task for each of the rest, and keeps those whose `shouldDiscover` is true (`[this]` FW `tasks`). `shouldDiscover` is `taskDefinition.explicitlySpecified || ((accessible || runnable) && isDiscoverableSuite(suiteClass))`. With `false`, it evaluates `accessible`, which forces `suiteClass`, which is `Class.forName(taskDefinition.fullyQualifiedName, true, loader)` (`[this]` FW `loadSuiteClass`). *General:* `initialize = true` runs the class's static initializer if it has not yet run in that loader.
- **Mill's own scan.** Without ahead-of-time results, `discoverTests` calls `cl.loadClass` on every `.class` file in the test classpath and reads `getModifiers`, `getConstructors`, `isInterface`, `isMemberClass` and `isAnonymousClass`. `matchFingerprints` calls `isAssignableFrom`, `isAnnotationPresent` and `getDeclaredMethods`. With ahead-of-time results (`discoverTestsWithZinc`, `false` by default on `TestModule`), it calls `cl.loadClass` on each listed name (`TR/TestRunnerUtils.scala` `discoverTests`, `matchFingerprints`; TM `discoverTestsWithZinc`, `aheadOfTimeDiscoveredTestClassesIfNeeded`). *General:* none of these calls initializes a class. So the scan loads every class in the test classpath, but initializes none.
- **What the scan keeps.** For every framework but JUnit 5's Jupiter, the scan keeps only a class that is neither abstract nor an interface, is neither a member nor an anonymous class, and matches one of the framework's fingerprints. The class must also have exactly one public constructor, or no public constructor and a name ending in `$` (`TR/TestRunnerUtils.scala` `discoverTests`). *General:* a Java class that is not public gets a default constructor that is not public either, so the scan drops it.
- **The runner.** The framework is created with `cl.loadClass(name).getDeclaredConstructor().newInstance()` in the same loader (`TR/Framework.scala`).

So, for a class that the scan keeps, that passes the class globs and that `-w` and `-m` do not exclude, discovery runs the static initializer, once, on the thread of § 1, inside `Runner.tasks`.

- The initializer runs whether or not ScalaTest then discovers the class.
- An initializer that throws raises `ExceptionInInitializerError`. That is not an `Exception`, so `loadSuiteClass`'s `catch` does not catch it.
  - `getTestTasks` calls `runner.done()` and rethrows it, and `Jvm.withClassLoader` rethrows it. `withClassLoader` first replaces it with a `Result.SerializedException`, which extends `Throwable`, only where the discovery class loader defined its class or `withClassLoader` replaced its cause (`TR/TestRunnerUtils.scala` `getTestTasks`; JVM `withClassLoader`; `[mill]` `core/api/daemon/src/mill/api/daemon/Result.scala` `partialFrom`).
  - With `javaHome`, the subprocess's `MillRpcServer.onAsk` returns every throwable but `InterruptedException` to Mill's process, and `MillRpcClient` throws it there as a `Result.SerializedException` (`RPC/MillRpcServer.scala` `onAsk`; `RPC/MillRpcClient.scala`).
  - `GroupExecution` turns each throwable from a task body that `mill.api.daemon.internal.NonFatal` matches into `ExecResult.Exception` (GE). That `NonFatal` matches what Scala's `NonFatal` matches and every `LinkageError` (`[mill]` `core/api/daemon/src/mill/api/daemon/internal/NonFatal.scala`).

  So a throwing initializer ends the module's anonymous task in `Exception`, with or without `javaHome`.
- The worker does the same again. `TestRunnerUtils.runTasksFromQueue` also builds each claimed class's `TaskDef` with `explicitlySpecified = false` (`TR/TestRunnerUtils.scala`). *Inference:* the worker that claims the class runs its initializer once more, in the worker JVM.

## 4. Whether a later test command reuses a class loader (d)

- `Jvm.withClassLoader` creates a new `MillURLClassLoader` over `runCp` for each call, sets it as the thread's context class loader, and closes it in `finally` (JVM `withClassLoader`, `createClassLoader`). `GetTestTasks.apply` calls it once per op (`TR/GetTestTasks.scala`). Neither `JvmWorkerImpl`, `ZincWorker` nor `GetTestTasks` caches the loader or the op's result (`JW/JvmWorkerImpl.scala`; ZW `apply`).
- *General:* a class is initialized once per defining loader, so a class in a new loader is initialized again.
- What Mill does cache across commands in one daemon:
  - `JvmWorkerModule.internalWorker` and `internalWorkerClassLoader` are `Task.Worker`s (`[mill]` `libs/javalib/src/mill/javalib/JvmWorkerModule.scala`). The latter loads the JVM worker's own classpath and is not the discovery loader's parent (§ 2.1).
  - `JvmWorkerImpl`'s `subprocessCache` holds `MillJvmWorkerMain` subprocesses, keyed on `(javaHome, runtimeOptions)`, up to `jobs` of them (`JW/JvmWorkerImpl.scala`; `JW/SubprocessZincApi.scala`). A subprocess keeps its JVM across commands, but builds a new discovery loader for each request just as Mill's process does.

So no class loader that holds the test classes is cached, and every `GetTestTasks` call runs the initializer again, with or without `javaHome`. A second test command in the same daemon reruns it, wherever the module run's anonymous task body runs again on that command.

## 5. Consequences for capturing one module's discovery output

### 5.1 Where the plugin can capture

- **The call site.** `JvmTestModuleRun.discoveredTestClasses`, around `inputs.jvmWorker.apply(ZincOp.GetTestTasks(...))`, on the anonymous task's thread (`[this]` JTMR; § 1.1).
- **What to bind there.** A capture must bind two things:
  - `SystemStreams.current`, for the path without `javaHome` (§ 2.1);
  - `Console.out` and `Console.err`, for the path with `javaHome` (§ 2.2).

  Binding only `System.out`'s target misses the forwarded output, and binding only `Console` misses the initializer's writes on Mill's process.
- **`SystemStreamsUtils.withStreams`.** It is public and unannotated, and binds both (SSU; SS). It also binds `os.Inherit.out` and `os.Inherit.err` to pumps into the capture streams.
  - `subprocessCache.setup` spawns the subprocess on the calling thread with `Jvm.spawnProcess`. `stdout` defaults to `os.Pipe` and `stderr` to `os.Inherit` (`JW/JvmWorkerImpl.scala`; JVM `spawnProcess`). `os.Inherit` resolves on the spawning thread (`MWC` § 1.2).
  - *Inference:* where this call is the first for its key, the long-lived subprocess's stderr descriptor would be pumped into the plugin's capture stream for the rest of that subprocess's life, across commands.
  - Binding `SystemStreams.current.withValue`, `Console.withOut` and `Console.withErr` alone leaves `os.Inherit` as the task bound it.
- **Concurrent modules.** Every binding above is a `DynamicVariable`, so each module's anonymous task thread has its own. Without `javaHome` the initializer runs on that thread (§ 2.1). With `javaHome`, its output arrives on that thread (§ 2.2). *Inference:* concurrent discovery of several modules in one nested evaluation keeps each module's lines apart. Threads that discovery creates during the call inherit the binding, since `DynamicVariable` is inheritable (*general*). Threads that existed before the call do not.
- **On failure.** Where `GetTestTasks` throws (§ 3), output written before the throw has already reached the capture. Attaching it in a `finally` keeps it.

### 5.2 What could make AC-025 and AC-026 unreliable

- **No discovery.** A module that sets `testBatchFrameworkTasks` runs no discovery, so `samples.Noisy`'s line never reaches the module's `output` (§ 1.1). The fixture must leave it unset.
- **Not kept by the scan.** Mill's scan drops `samples.Noisy` before `Runner.tasks` unless it is public, with exactly one public constructor, since a Java class that is not public has no public constructor (§ 3). The fixture must make it public, with one public constructor that takes no argument.
- **Filters before initialization.** A class glob that does not match `samples.Noisy` stops it before `Runner.tasks`, and `-w` or `-m` in the runner arguments drops it inside `tasks` before `shouldDiscover`. In both cases the class is loaded but not initialized (§ 3). `-- samples.* -- -z nothing-matches` does neither.
- **The line appears twice.** `-z` does not affect `shouldDiscover`, so discovery returns `samples.Noisy`. *Inference:* the worker that claims it prints `loading samples.Noisy` again, into that run's output (§ 3). AC-025 asserts only the module node's `output`. In AC-026 the run's lines come before the module's output lines, so the listed lines still end the block, but the line is shown under a run as well.
- **Other discovery-time output.** Discovery constructs ScalaTest's runner and calls `done()` (`TR/TestRunnerUtils.scala` `getTestTasks0`). The runner prints `Resources.deprecatedChosenStyleWarning` when `-y` is given, and `done` sends `RunCompleted` to every reporter the arguments configure (`[this]` FW). A stdout reporter among them would add items to the module's `output`, against AC-025's "exactly one item". The test command's discovery configures none. Discovery receives only `RunArguments.commandLine`, whose flags `AllowedFlag` limits to ones that hold neither `-y` nor a reporter. ScalaTest's sbt `Framework` also drops the standard-out reporter configuration. `getTestTasks0` discards what `done` returns (`[this]` `mill-plugin/plugin/src/org/scalatest/mill/AllowedFlag.scala`, FW `runner`; `TR/TestRunnerUtils.scala` `getTestTasks0`).
- **Writes the capture cannot see.** Writes to file descriptors 1 and 2 directly, and writes from threads that existed before the call, bypass the per-thread bindings (§ 5.1). A plain `System.out.println` in the initializer is neither.
- **Line terminator.** *General:* `println` ends the line with `System.lineSeparator()`, `\r\n` on Windows. The criteria allow any line terminator.

## 6. What was not determined

- Whether each *inference* and *general* statement above holds when run. In particular, that Mill's scan does not initialize classes, and that the initializer runs on every command. The experiment: run the AC-025 fixture's test command twice in one daemon, once without and once with the module's `javaHome`. Each time, record the thread name and `SystemStreams.current` on which `samples.Noisy`'s initializer prints, and check that the line reaches the module's `output` both times.
- Whether a JVM worker subprocess spawned under a `withStreams` capture keeps pumping its stderr into that capture (§ 5.1). The experiment: make the first `javaHome` call of a fresh daemon a capturing discovery, then have the subprocess write to file descriptor 2.
- Whether any of this holds on Mill versions other than `1.3.1`.
