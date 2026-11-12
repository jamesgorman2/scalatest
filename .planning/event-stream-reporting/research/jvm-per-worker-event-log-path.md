# Giving each JVM test worker its own event log path when test sandboxing is off

At Mill `1.1.10`, with `testSandboxWorkingDir` off, every test worker of a module runs in `forkWorkingDir`, so one relative path no longer names a file in each worker's `processFolder`. This note establishes how each worker can learn its own event log path when the test command drives Mill's test queue itself, what else differs per worker inside the worker JVM, what a module-level folder would lose, and whether one mechanism serves sandboxing on and off. It feeds `.planning/event-stream-reporting/ARCHITECTURE.md` § APIs § The emitter and the Mill plugin (the runner argument; the event log file on the JVM). Facts only; no design is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-test-worker-args.md`, cited as `MILL`: how Mill launches workers, and each `processFolder`.
- `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`, cited as `MPC`: Route B, the plugin driving the queue through `callTestRunnerSubprocess`.
- `.planning/event-stream-reporting/research/jvm-file-channel.md`, cited as `JFC`: the file-per-run spike whose prototype this note's spike patches.
- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`: `callTestRunnerSubprocess` and worker streams.

## Short answer

- **A framework argument per worker works, with no Mill API beyond what Route B already uses** (§ 1, § 2). A worker's framework arguments are the `args` constructor field of the `TestModuleUtil` on which `callTestRunnerSubprocess` is called. The test command already calls that method once per worker, so it can call it on a `TestModuleUtil` built for that worker, whose `args` end with the worker's own absolute path. *Ran:* on Windows and Linux, under the daemon and `--no-daemon`, every one of 95 worker JVMs wrote its file at the path its argument named, in its own `processFolder`, with sandboxing off and on, and no file appeared anywhere else.
- **Nothing else must stay shared between those instances** (§ 1.2). `TestModuleUtil` holds no mutable state; the queue folder, claim folder, `processFolder` and result path are parameters of the call, and discovery, results and the JUnit XML report are the test command's own code under Route B.
- **R-053 holds as far as it was checked** (§ 2.3). Between workers of one module, each worker's `testargs` differed only in `arguments` and in the three fields that differ per worker under Mill's own task. The comparison against `testForked` itself is `MPC` § 5's; it was not repeated with sandboxing off.
- **Inside the worker, only the `testargs` path on the JVM command line names the worker** (§ 3). `sun.java.command` held it in all 95 JVMs, and its parent folder was the `processFolder`. It is a HotSpot property, space-joined, so a path holding a space cannot be split out of it for certain. `ProcessHandle` gave no arguments on either platform. No environment variable, system property or `TestArgs` field that names the worker reaches a test-interface `Runner`.
- **A module-level folder with a name per run loses the worker tie** (§ 4). `callTestRunnerSubprocess` does not return the process, so the plugin has no PID to match the `runStarted` event's against. *Source-read and inferred; not run.*
- **The same mechanism works with sandboxing on** (§ 2.2, § 5): the sandbox-on fixture ran under it with the same results.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[proto]` | this repo, branch `prototype/jvm-file-channel`, `.planning/event-stream-reporting/prototype-jvm-file-channel/` | `de937039d` |
| `[spike]` | `[proto]` with the changes of § 2.1, in a scratch copy outside the repo; not committed | — |

A `[mill]` path `P` is at `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/P`. Abbreviations, as in `MILL` and `MPC`:

- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TR/` is `libs/javalib/testrunner/src/mill/javalib/testrunner/`.

Every `[mill]` citation was opened at that commit. A statement marked *inference* follows from the code read or the runs and was not observed directly. A statement marked *general* is JVM or operating-system behaviour no source here was opened for. An unmarked statement with no `[mill]` citation was observed in the § 2 runs.

## 1. Where a worker's framework arguments come from

### 1.1 The path of the arguments

- `args` is a constructor parameter of `TestModuleUtil` (TMU line 40).
- `callTestRunnerSubprocess(baseFolder, resultPath, selector, poll)(using ctx)` takes no arguments parameter. It builds the worker's `TestArgs` with `arguments = args`, from the instance, and writes it to `<baseFolder>/testargs` (TMU lines 112–143).
- The worker reads that file and passes `testArgs.arguments` to `framework.runner(args.toArray, Array[String](), cl)` (`TR/TestRunnerMain0.scala` lines 8, 24–36; `TR/TestRunnerUtils.scala` lines 367–382).
- So the arguments a worker receives are those of the `TestModuleUtil` instance its `callTestRunnerSubprocess` call was made on. There is no other input: the `testargs` file is written inside the call, with `CREATE_NEW` (`MILL` § 2.3), so the caller cannot write or amend it.

### 1.2 What an instance holds, and what the call takes

- **Instance state.** Besides its constructor parameters, a `TestModuleUtil` has three `private val`s computed from them: `jvmArgs` and `props` from `forkArgs`, and `testRunnerClasspathArg` from `scalalibClasspath` (TMU lines 56–60). It has no `var` and no collection it fills (TMU lines 31–370).
- **What `callTestRunnerSubprocess` reads from the instance** (TMU lines 127–162): `testFramework`, `runClasspath`, `args`, `props`, `testClasspath`, `testLogLevel`, `discoveredClassesOpt`, `jvmArgs`, `forkEnv`, `propagateEnv`, `testrunnerEntrypointClasspath`, `testSandboxWorkingDir`, `forkWorkingDir`, `useArgsFile`, `javaHome`.
- **What it takes per call**: the `processFolder` (`baseFolder`), the result path, the queue folder and claim folder inside `selector`, `poll`, and the `TaskCtx`, from which it reads `log.prompt.colored` and `env` (TMU lines 112–122, 134, 152).
- **What Route B does not take from a `TestModuleUtil`** (`MPC` § 1, § 6): discovery is the plugin's own `ZincOp.GetTestTasks` call; the folder layout, first claim, scheduling and result merging are the plugin's copies; the JUnit XML report and failure list come from the public `TestModule.handleResults`. `prepareTestClassesFolder` reads no instance field (TMU lines 183–191), and `jobsProcessLength` reads only the ctx's `jobs` (TMU lines 193–196).
- *Inference:* two instances built from the same values except `args` behave, at `callTestRunnerSubprocess`, exactly as one instance would except for `TestArgs.arguments`. Nothing has to be shared between them.
- **Other per-instance values that reach the worker**, by the same construction: `forkEnv` (the worker's environment, TMU line 152) and `forkArgs` (JVM arguments, or system properties under `testUseArgsFile`, TMU lines 56, 131, 151). A `TaskCtx` given per call also sets the environment through `Task.env`, only while `propagateEnv` is on (TMU line 152). None of these was run.

### 1.3 API and copied code

- **API.** The `TestModuleUtil` constructor and `callTestRunnerSubprocess`, both on the `@mill.api.daemon.experimental` class (TMU lines 30–31, 112). Route B already uses both (`MPC` § 6). A per-worker instance adds no `@internal`, `@experimental` or `private[mill]` member to that list.
- **Scheduler code.** None beyond what Route B copies. Route B already holds each worker's `processFolder` at the point of the call.
- **Cost per instance.** One `forkArgs` partition and one class path string (TMU lines 56–60, 374–393).

## 2. The spike: one `TestModuleUtil` per worker

### 2.1 What was run

`[spike]` is `[proto]`'s `build.mill` and emitter (`JFC` § 1) with these changes:

- **The `queue` route** (`[proto]` `build.mill` lines 408–504). The `TestModuleUtil` construction became a function of the arguments. With `SPIKE_PERWORKER=1`, each worker's call is made on its own instance:

  ```scala
  val u = if (perWorker) mkUtil(fullArgs ++ Seq(s"-Dspike.file=${j.folder / "events.log"}")) else util
  u.callTestRunnerSubprocess(j.folder, j.folder / "result.log", Right((starting, j.queue, claimFolder)), () => ())(using ctx)
  ```

  `j.folder` is the worker's `processFolder`. Discovery ran with `SPIKE_DISC=withheld`, so it received `testArgsDefault` only (`JFC` § 6).
- **The pool.** `[proto]` submits every worker index to a pool of `jobs` threads. `[spike]` uses one thread when `testParallelism` is off or the module has one class, so that the worker indexes of a group run one after another, as Mill's sequential path does (TMU lines 293–308).
- **The emitter.** Given `spike.file`, it creates that file with `Files.newOutputStream(file, CREATE_NEW, WRITE, APPEND)` instead of `<dir>/<UUID>.events`, and writes one more line holding `sun.java.command`, `ProcessHandle.current().info().arguments()` and `commandLine()`, and `java.class.path`.
- **After the last worker,** the route prints, for each worker index: whether `testargs` exists in its folder; whether `events.log` does; the header's PID and working directory; which top-level keys of the `testargs` JSON differ from the first started worker's; the suites the file records against the classes in the folder's `claim.log`; and whether the parent of the last space-separated word of `sun.java.command` is the folder. It also counts every `events.log` under the module's folder that is not in a worker's folder.
- **Fixtures.** `alpha`'s four suites (`JFC` § 1), each module with `testFramework = "spike.NotingFramework"`:

  | Module | `testSandboxWorkingDir` | Grouping, parallelism | `processFolder`s (`MILL` § 2.3) |
  |---|---|---|---|
  | `nosand.test` | off | two fork groups of two, parallel | `<group>/worker-<i>` |
  | `nosandone.test` | off | one group, parallel | `worker-<i>` |
  | `nosandseq.test` | off | two fork groups of two, `testParallelism` off | `<group>` |
  | `alpha.test` | on | two fork groups of two, parallel | `<group>/worker-<i>` |
  | `empty.test` | on | no test class | `worker-0` |

- **Runs.** `mill -j 3 spike.test --route queue nosand.test nosandseq.test nosandone.test alpha.test empty.test`, three rounds under the daemon and one under `--no-daemon`, on each platform, output redirected to files. On Linux the runs were in a copy in the WSL file system, as `JFC` § 1's were.
  - Windows round 1 ran before the pool change. `nosandseq.test` failed in it with `java.nio.file.FileAlreadyExistsException: …\nosandseq.test\group-0-alpha.A1\testargs`: both worker indexes of a group claimed a class at once and the second `testargs` write was refused, as `MILL` I-1 infers. Its other four modules are counted below.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`; workers on Azul Zulu `21.0.10`, fetched by Mill.

| Platform | |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | `mill.bat` through `cmd /c` |
| Ubuntu 26.04 under WSL 2 (kernel `6.18.58.1-microsoft-standard-WSL2`), x86-64 | `./mill` |

### 2.2 What was observed

| | Windows | Linux |
|---|---|---|
| Worker JVMs started (a `testargs` in the folder) | 46 | 49 |
| With `events.log` in their own `processFolder` | 46 | 49 |
| Whose file's suites were the classes of that folder's `claim.log` | 46 | 49 |
| Worker indexes that started no JVM, with an `events.log` | 0 | 0 |
| `events.log` files outside a worker's folder | 0 | 0 |
| Modules reported `Success` | 19 of 19 run to the end | 20 of 20 |

- **Sandboxing off.** Every worker of `nosand.test`, `nosandone.test` and `nosandseq.test` recorded the workspace root as its working directory, and each wrote its file in its own `processFolder`.
- **Sandboxing on.** Every worker of `alpha.test` and `empty.test` recorded `<processFolder>/sandbox` as its working directory, and each wrote its file in its `processFolder`, outside the `sandbox`.
- **A worker given no class.** `empty.test`'s one worker wrote its file in `worker-0` in all 8 runs.
- **A worker index that claimed nothing** (`group-1`'s `worker-1` in `nosand.test` and `alpha.test`, most runs) started no JVM, and its folder held no `events.log`.
- **A class on two workers.** On Windows a class ran on two or three workers of one group in every run, as `MPC` § 5 records. Each such worker's file was in its own folder and held that class.
- **`CREATE_NEW`.** No worker failed to create its file: no folder was given to two JVMs.

### 2.3 Against R-053

- Within each module, the top-level keys of a worker's `testargs` that differed from the first started worker's were `arguments`, `outputPath`, `resultPath` and `globSelectors`, in every run, and no others. The last three hold the worker's own `processFolder`, queue and claim paths, and differ per worker under Mill's own task (`MILL` § 2.1). `arguments` differed only in the per-worker argument, by construction (§ 2.1).
- *Inference:* the JVM arguments, environment, class path and working directory of the spawn come from instance fields that are equal across the instances (§ 1.2), so they are as under one instance.
- Route B against Mill's own `testForked`: `MPC` § 5, with sandboxing on. It was not repeated here with sandboxing off (§ 6).
- `SPECIFICATION.md` R-053 allows the arguments "plus only what is required to turn the emitter on, with its test event log, …". *Inference:* an argument that differs per worker only in the event log path is within that; the files land in the folder the test command owns for the module (R-110), not in the working directory.

## 3. What else differs per worker inside the worker JVM

With sandboxing off, the working directory, environment, JVM arguments and framework arguments are the same for every worker of a module under one `TestModuleUtil` (`MILL` § 2.1). What remains:

| Candidate | Reachable from a `Runner`? | Evidence | Fragility |
|---|---|---|---|
| The `testargs` path, last main argument of the JVM (TMU line 153), through the `sun.java.command` system property | Yes: a system property | *Ran:* in all 95 JVMs its last space-separated word was `<processFolder>/testargs` (§ 2.1) | A HotSpot property, not in the Java specification (*general*). The main arguments are joined by spaces: with the JDK 21 used here, `java P "x,y" "C:\dir with space\worker-0\testargs"` gave `sun.java.command=[P x,y C:\dir with space\worker-0\testargs]`, so a workspace path holding a space cannot be split from the class path argument before it for certain. It relies on Mill's `mainArgs` order and on the file's name, both set in the body of an `@experimental` method |
| The same path through `ProcessHandle.current().info()` | Yes | *Ran:* `arguments()` was absent in all 95 JVMs. `commandLine()` was absent in all 46 on Windows; in all 49 on Linux it was present, about 4 096 characters long, and ended inside the class path, before `testargs` | Not usable on either platform |
| `TestArgs.outputPath`, `resultPath`, the claim folder, the queue folder | No | `main0` holds the `TestArgs` in a local and passes the runner `arguments` only (`TR/TestRunnerMain0.scala` lines 8–36; `TR/TestRunnerUtils.scala` lines 367–382). `MillTestRunnerMain0` is loaded in a class loader the framework's is not a child of (`MILL` § 1.3) | Reachable only by reading the `testargs` file, which needs its path (row 1) |
| An environment variable or system property Mill sets per worker | — | None exists: the environment is `Task.env ++ forkEnv` and the properties are `props`, the same for every spawn of an instance (TMU lines 131, 151–152; `MILL` § 2.1–2.2) | — |
| `java.class.path` under `testUseArgsFile` | Yes | *Ran:* on Windows it was a temporary jar of its own per worker, `…\Temp\run-<n>.jar`; on Linux the module's class path, the same for every worker | The jar is made by `os.temp` inside `callTestRunnerSubprocess` and not returned (TMU lines 155–157), so the plugin does not know which worker has which |
| The JVM's PID, matched by the plugin afterwards | Yes: `ProcessHandle.current().pid()` | `callTestRunnerSubprocess` keeps the process in a local and returns `Result[(String, Seq[TestResult])]` (TMU lines 147–181); `poll` receives nothing (TMU lines 121, 174) | The plugin does not learn a worker's PID from Mill. *Inference:* it could list its own child processes around each spawn, which does not tell apart workers spawned at the same time on different threads |
| The classes the run is given, against each folder's `claim.log` | Yes: `Runner.tasks` | *Ran:* each file's suites were its folder's claimed classes (§ 2.2) | Known only after the first task, so not for a worker given no class or one that dies before its first task. On Windows a class is in two workers' `claim.log` in most runs (`MPC` § 5) |

## 4. A module-level folder with a name per run

Not run. The earlier design, and `JFC`'s spike, gave every worker of a module one absolute folder, in which each run creates a file named by its run ID (`JFC` § 1). That needs no per-worker value, with sandboxing on or off. Against the `processFolder` design it changes:

- **The worker tie.** The plugin holds, per worker it started, the `processFolder`, the claims and the captured stdout and stderr; it holds no PID (§ 3). *Inference:* the PID in a file's `runStarted` event matches nothing the plugin holds, so a file is tied to its worker only by § 3's rows: `sun.java.command` written into the `runStarted` event, or the classes run against `claim.log`.
- **A worker that dies before the `runStarted` event.** *Inference:* the plugin sees one file fewer than the workers it started. Which worker has none is known only if every other file is tied to its worker. `ARCHITECTURE.md` § APIs § The emitter and the Mill plugin's "a `processFolder` with no event log file is … a worker in which no run existed" no longer follows from the folder alone.
- **Captured output.** `ARCHITECTURE.md`'s "a worker's stdout and stderr belong to the run whose event log file is in the worker's `processFolder`" needs the same tie.
- **The file's name.** A fixed name guarded by Mill's one-JVM-per-`processFolder` (`MILL` § 2.3) becomes a name the run must make unique.
- **Live reading.** The plugin lists a folder for new files (`JFC` § 1) instead of opening a path it knew before it started the worker.

## 5. One route for sandboxing on and off

- **Ran:** the per-worker argument gave the same result on `alpha.test` and `empty.test`, with sandboxing on, as on the sandbox-off modules (§ 2.2).
- **What the relative-path route relies on that the per-worker argument does not:** reading the module's `testSandboxWorkingDir` to choose a form; the worker's working directory being `processFolder/sandbox` (TMU lines 142, 154), set in the body of an `@experimental` method; and the run resolving the relative path before anything changes how the JVM resolves one.
- **What the per-worker argument adds with sandboxing on:** the workers' `arguments` then differ from each other (§ 2.3), where one relative path leaves them equal; and each argument holds an absolute path.
- **Both rely on** the `processFolder` the test command chose being the `baseFolder` it passes to the call (§ 1.2).

## 6. What was not run or not determined

- Route B with a per-worker argument against Mill's own `testForked` or `testOnly`, with sandboxing off: classes, worker count, JVM arguments, environment, working directory, results and JUnit XML (`MPC` § 5 compared them with sandboxing on and one instance).
- Live reading of a file in a `processFolder`, where Mill and Route B's `poll` read `claim.log` and `result.log`; every file here was read after its worker ended. `JFC` § 2 read files live in a folder of the plugin's own.
- A worker that dies or is killed, before or after the `runStarted` event, under a per-worker argument.
- A workspace path holding a space, a non-ASCII character, or longer than the Windows path limit, in the argument or in `sun.java.command`; only the plain `java` check of § 3 was made.
- How ScalaTest's argument parsing treats the form the real runner argument will take; the spike passed the path as a `-D` config value.
- A per-worker `forkEnv`, `forkArgs` or `TaskCtx` environment (§ 1.2), and a module-level folder with PID or `sun.java.command` matching (§ 4).
- `sun.java.command` on a JVM other than HotSpot-based Zulu 21, and `ProcessHandle` on macOS.
- `testOnly` with class globs; `discoverTestsWithZinc` on; `javaHome` set; more than three daemon rounds and one `--no-daemon` round per platform.
- macOS; Mill versions other than `1.1.10`.
