# Running a selected module's tests from the test command without a plugin trait

At Mill `1.1.10`: whether an exclusive command can run a selected test module's tests on the JVM, Scala.js and Native, holding `.planning/event-stream-reporting/SPECIFICATION.md` R-053 and taking each run's stdout and stderr (R-040, R-042), when the module mixes in no trait the plugin provides (R-092). It covers public equivalents of the protected members Mill's test tasks use, building the inputs without them, calling them by reflection, and evaluating Mill's own test task from plugin code. For each route it gives the runtimes it worked on and how the Mill API it uses is marked. Answers [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45). Facts only; no route is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`, cited as `MPC`: the JVM capture routes and what they were compared on.
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: how Scala.js and Native runs are launched, and their hooks.
- `.planning/event-stream-reporting/research/native-stdio-wrapper.md`, cited as `NSW`: a launcher passed as Native's `binaryFile`.
- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`, and `.planning/event-stream-reporting/research/mill-nested-keep-going.md`, cited as `MNK`: worker streams, and Mill's compatibility policy and MiMa coverage (`MNK` § 5).

## Short answer

- **Yes, with no trait and no reflection** (§ 3.4, § 4, § 5). The tests ran, with their output captured, on the JVM, Scala.js and Native; R-053's working directory, arguments and environment were compared on the JVM only. The plugin takes the module's public `testForked` or `testOnly` command. Mill evaluates the named tasks below it. The plugin itself calls `Task.evaluate` on the anonymous tasks between them and the command, then on the command itself, with its own `TaskCtx` and streams. Mill's own test body therefore runs unchanged for that module. On Native the plugin also replaces the value of `nativeLink` with a launcher (`NSW`).
  - No run's output reached the console, on Windows and Linux, under the daemon and `--no-daemon`. The plugin received each stream in order.
  - Against Mill's own `testForked` and `testOnly`, these matched: the classes run, the test results, the JUnit XML test cases, and each JVM worker's working directory, arguments and environment. The exceptions are the worker-count and duplicate-class variation `MPC` § 5 records, which Mill's own task shows too.
- **The protected members have no public equivalent of the same value** (§ 2.1). `aheadOfTimeDiscoveredTestClassesIfNeeded` is `None` unless the module sets the protected `discoverTestsWithZinc`. Mill sets it only in `TestScalaJSModule.Junit4ScalaJs`. Building the JVM inputs with `None` from public tasks worked, and matched `testForked` on a module without Zinc discovery (§ 3.1). On Native, Scala Native's `TestAdapter` built from public tasks with a launcher worked (§ 3.5).
- **Reflection on the protected members works** (§ 2.2, § 3.2, § 3.3). Scala's `protected` is `public` in bytecode, and `Class.getMethod` returned both members. A task-returning member yields a new `Task.Anon` per call. Mill evaluated it as an input of the plugin's task, and the named tasks under it are the module's own memoised instances.
- **Every route needs `@experimental` API. `public` and `member` also need `@internal` `internalWorker`, and `adapter` needs `@internal` `TestRunner` and `TestRunnerUtils`** (§ 6). The command route needs none of these beyond implementing `TaskCtx`, whose `fork` member's type is `@experimental`. It relies on Mill's public, unmarked `Task.inputs` and `Task.evaluate`, and on Mill's own test body running correctly under a `TaskCtx` it did not build.
- **A task built outside the module gets no `Task.dest` and is under Mill's filesystem checker** (§ 2.4). Every route supplied its own folder per module and turned the checker off around its writes.
- **Not run:** Native on Linux, macOS, a pseudo-terminal, a killed worker, more than one round per case, R-053's working directory, arguments and environment on Scala.js and Native (§ 8).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[jar]` | `com.lihaoyi:mill-libs-javalib_3:1.1.10` and `mill-libs-scalanativelib_3:1.1.10` from Maven Central, read with `javap -p` | `1.1.10` |
| `[proto]` | this repo, branch `prototype/no-trait-test-run`, `.planning/event-stream-reporting/prototype-no-trait-test-run/` | `1a2ab1cbc` |

A `[mill]` path `P` is at `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/P`. Abbreviations:

- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TK` is `core/api/src/mill/api/Task.scala`.
- `TCX` is `core/api/java11/src/mill/api/TaskCtx.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `PI` is `core/exec/src/mill/exec/PlanImpl.scala`.
- `ECX` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `SJM` is `libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala`.
- `SNM` is `libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala`.
- `B` is `[proto]` `build.mill`.

A statement marked *inference* follows from the code read or the runs and was not observed directly. A statement marked *general* is JVM or Scala behaviour that no source here was opened for. An unmarked statement with no `[mill]`, `[jar]` or `B` citation was observed in the § 1 runs.

## 1. What was run

- **Fixtures,** none mixing in anything of the prototype's (`B` lines 47–83):
  - `jvm.test`: the `MPC` § 1 `gamma` fixture.
  - `zinc.test`: one suite, with `override protected def discoverTestsWithZinc = true` in the module.
  - `js.test` (Scala.js `1.20.2`, Node) and `native.test` (Scala Native `0.5.12`): `XSuite`, two tests each writing 20 numbered lines to each of stdout and stderr, and `YSuite`, one line to each.
- **The command,** `notrait.run --route <route> <selector>...`, is exclusive and takes the `Evaluator` (`B` lines 330–386). It resolves the selectors as `.planning/event-stream-reporting/research/emitter-plugin-spike.md` § 1 does, builds one goal per test module, runs every goal in one `Evaluator.execute`, and prints one result line per module. Class globs come from `NOTRAIT_GLOBS`. Each goal writes into `out/notrait/modules/<route>/<module>`, a folder for that module outside the command's own `Task.dest`, and saves what it captured there.
- **Capture,** in every route: a `TaskCtx` that delegates to the goal's own ctx except `dest`, `args` and `fork` (`B` lines 133–149). Its `fork` is `MPC`'s `CaptureFork` (`B` lines 118–130), except in `adapter`, which keeps the goal's own `fork` (`B` line 299). The body also runs inside `SystemStreamsUtils.withStreams` over the plugin's buffers (`B` line 113). On Native a launcher writes the binary's stdout and stderr to files the plugin reads after `close()` (`NSW` § 1; `B` lines 151–183).
- **Routes:**

  | Route | Runtimes | How the goal is built |
  |---|---|---|
  | `public` | JVM | `TestModuleUtil` as `testTask` builds it, from the module's public tasks, `discoveredClassesOpt = None` (`B` lines 201–222) |
  | `member` | JVM | the same, with `aheadOfTimeDiscoveredTestClassesIfNeeded` called by reflection and used as a task input (`B` lines 346–350) |
  | `hook` | JVM, Scala.js, Native | the protected `testTask(args, globs)` called by reflection; the plugin evaluates the task it returns (`B` lines 351–354, 242–288) |
  | `command` | JVM, Scala.js, Native | the public `testForked()` or `testOnly(globs*)`; the plugin evaluates it as for `hook` (`B` lines 355–358) |
  | `adapter` | Native | Scala Native's `TestAdapter` from public tasks, as `NSW` § 1 builds it but from outside the module (`B` lines 291–325) |

- **The matrix,** `run.ps1` (Windows) and `run.sh` (Linux), one round per case, under the daemon and `--no-daemon`. All `mill` output went to files.
  - JVM: Mill's `jvm.test.testForked` and the four JVM routes, passing and with `SPIKE_FAIL`.
  - Class globs: `jvm.test.testOnly gamma.G0*` and the four JVM routes with `NOTRAIT_GLOBS=gamma.G0*`.
  - Scala.js and Native: Mill's `testForked` of each, then `hook`, `command` and `adapter`.
  - One `command` run over `jvm.test zinc.test js.test native.test`.
- **The analysis,** `analyse.py`, checks for run output on the console, the order of what each route captured, and the comparisons of § 4 against the control of the same platform, mode and outcome. Its output is `[proto]` `results/windows-summary.txt` and `results/linux-summary.txt`.
- **A trial round** on Windows ran before the matrix. It found the filesystem-checker failure of § 2.4 and was not kept.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`, Azul Zulu `21.0.10` fetched by Mill.

| Platform | Runtimes run | |
|---|---|---|
| Windows 11 (`10.0.22631`), x86-64 | JVM, Scala.js (Node `24.13.1`), Native (LLVM `20.1.8`, MSVC linker) | `mill.bat` through `cmd /c` |
| Ubuntu 26.04 under WSL 2, x86-64 | JVM, Scala.js (Node `22.21.1`) | `./mill`, in a copy of the prototype in the WSL file system, as `MPC` § 1 ran. No clang was installed, so Native did not run |

## 2. What the source shows

### 2.1 The protected members and the public tasks

| Member | Where | Marking | Value |
|---|---|---|---|
| `discoverTestsWithZinc: Boolean` | `TM` line 78 | `protected def`, `false` | `true` only in `TestScalaJSModule.Junit4ScalaJs` (`SJM` line 444) or a user's override |
| `zincAnalysisFileDiscoveredTestClasses: T[Seq[(String, Int)]]` | `TM` lines 114–130 | `protected def`, a named task | Zinc discovery from `zincAnalysisFile` |
| `aheadOfTimeDiscoveredTestClassesIfNeeded: Task[Option[Seq[(String, Int)]]]` | `TM` lines 138–144 | `protected def`, returns a new `Task.Anon` per call | `Some(zincAnalysisFileDiscoveredTestClasses())` if `discoverTestsWithZinc`, else `None` |

- `testTask`, Scala.js's `testFrameworkInstance`, and Native's `withScalaNativeBridge` and `toWorkerApi` are as `JNC` § 6.1–6.2 lists them. `testTask` also returns a new `Task.Anon` per call (`TM` lines 312–341).
- **No public task yields the value of `aheadOfTimeDiscoveredTestClassesIfNeeded`.** The public `discoveredTestClasses` uses Zinc discovery when `discoverTestsWithZinc` is on, but keeps only the names, not the `Int` of each pair (`TM` lines 90–94). Its inputs are the public `compile().analysisFile` (`[mill]` `libs/javalib/src/mill/javalib/JavaModule.scala` line 142) and `ZincOp.DiscoverTestsZinc` in `mill.javalib.api.internal` (`TM` lines 116–129). Nothing public says whether `discoverTestsWithZinc` is on.
- **Every other input `testTask` passes to `TestModuleUtil` is a public task** of the module or of `jvmWorker()`, including `testUseArgsFile`, `forkWorkingDir`, `javaHome` and `propagateEnv` (`TM` lines 241, 312–339; `[mill]` `libs/javalib/src/mill/javalib/RunModule.scala` lines 36, 48, 64, 197; `libs/javalib/src/mill/javalib/JavaHomeModule.scala` line 29; `libs/javalib/src/mill/javalib/JvmWorkerModule.scala` lines 36, 42, 156). `internalWorker` is `@internal` (`JvmWorkerModule.scala` line 156).
- **Named tasks are memoised per module.** A `def` holding `Task { ... }` is wrapped in `cachedTask` (`[mill]` `core/api/src/mill/api/internal/Cacher.scala` lines 11, 67), so plugin code calling `m.forkArgs` gets the instance Mill's own tasks use. Observed: `m.forkArgs eq m.forkArgs` was `true`.

### 2.2 Protected members from outside the module

- *General:* Scala's `protected` restricts access in Scala code compiled against the class's Scala signature; it is not a JVM access flag.
- `[jar]`: in the bytecode of `mill.javalib.TestModule`, `aheadOfTimeDiscoveredTestClassesIfNeeded`, `testTask`, `discoverTestsWithZinc` and `zincAnalysisFileDiscoveredTestClasses` are each a `public default` interface method. In `mill.scalanativelib.ScalaNativeModule`, the `private[scalanativelib]` `withScalaNativeBridge` and `toWorkerApi` are also `public default`.
- Observed: `m.getClass.getMethod("aheadOfTimeDiscoveredTestClassesIfNeeded")` and `getMethod("testTask", classOf[Task[?]], classOf[Task[?]])` returned a `public` method on each fixture's module class. Invoked, each returned a `Task.Anon` (`B` lines 190–198, 346–354).
- *Inference, not run:* Java source in the build, compiled with `javac`, could call these methods directly, because `javac` reads only the bytecode flags.

### 2.3 How Mill evaluates a task, and what plugin code can call

- `Task` is a public, `sealed` abstract class. Its `inputs` is a public `val`, and `evaluate(args: TaskCtx): Result[T]` is a public method documented "Evaluate this task" (`TK` lines 28–48). `Task.Anon` and `Task.Command` evaluate `evaluate0(ctx.args, ctx)` (`TK` lines 514–523, 525). `Task.Named` is a public trait (`TK` line 428). `Task.sequence` is public (`TK` line 151). None carries an annotation.
- A plan groups every task under the nearest named task or goal (`PI` lines 8–21, 30–56). Within a group, Mill evaluates each task in order, with a `TaskCtx.Impl` whose `args` are the task's input values in `inputs` order (`GE` lines 516–529, 548). `TaskCtx.Impl` is `private[mill]` (`TCX` line 42).
- So plugin code can do what the group does: build a `TaskCtx` whose `args` are the input values, and call `evaluate` (`B` lines 242–288).
  - The plugin's goal depends on `Task.sequence` of the named tasks reached from the root through anonymous tasks only (`B` lines 225–239). Mill evaluates those, cached as usual.
  - The plugin then evaluates every anonymous task, and finally the root, itself.
  - What `GE`'s `wrap` does around each group (`GE` lines 830–890) applies only to the plugin's own goal, not to the tasks the plugin evaluates. That covers the streams, `os.dynamicPwdFunction`, the filesystem checker, `Evaluator.withCurrentEvaluator` and the context class loader. The plugin sets the streams, the working-directory function and the checker itself.
- **What Mill evaluated.** For `testForked`, the named tasks below the anonymous ones were the inputs `testTask` reads (§ 2.1): 19 on `jvm.test`, 20 on `zinc.test`. Among them were `@internal` `JvmWorkerModule/internalWorker`, the protected `zinc.test.zincAnalysisFileDiscoveredTestClasses` (on `zinc.test` only), `ScalaJSWorkerExternalModule/scalaJSWorker` on Scala.js, and `ScalaNativeWorkerExternalModule/scalaNativeWorker` on Native. The plugin names none of them; it reaches them through `inputs`.
- **What the plugin evaluated itself.** For the `command` route, that was the command's anonymous argument and selector tasks, the `testTask` task and the command. On Scala.js it also included `testFrameworkInstance`. On Native it included `withScalaNativeBridge`. Mill's group for `testForked` itself never ran.

### 2.4 What a goal built outside the module meets

- **No `Task.dest`.** An unnamed goal is executed with `paths = None` (`GE` lines 259–262, 453–470), so `Task.dest` throws "No `dest` folder available here" (`GE` lines 782–792). Every route's ctx supplies `out/notrait/modules/<route>/<module>` instead (`B` line 134).
- **The filesystem checker.** For a goal that is not a `Task.Command`, a write inside the workspace outside its valid `dest`s fails (`GE` lines 797–827, 849–857). In the trial round, the Native launcher write failed with "Writing to out/notrait/modules/command/native.test/launcher not allowed during execution of `Task.Anon@…(build_.package_#notrait.graphGoal)`". Every route wraps its body in `BuildCtx.withFilesystemCheckerDisabled`, which is public and unmarked (`[mill]` `core/api/java11/src/mill/api/BuildCtx.scala` lines 33–37). `fork.async` carries the submitter's checker into each worker's body (`ECX` lines 127, 133).
- **Mill's prompt and failure lines.** Mill labelled each goal `Task.Anon@<hash>(build_.package_#notrait.<builder>)` in its line prefix. When `G03` failed, Mill's own `[error] … 1 tests failed:` list, which `testForked` prints, did not appear (§ 4). *Inference:* Mill passes no task label for an unnamed group (`GE` lines 453–461).

## 3. The routes

### 3.1 `public`: the JVM inputs from public tasks, `None` for discovery

- It ran on both platforms, both modes. The R-053 comparison of `jvm.test` is in § 4.
- On `zinc.test`, it passed `None` where Mill passes `Some(1 classes)` (§ 3.2). The suite still ran, found by class-file discovery. *Inference:* the two differ in which classes are discovered wherever class files and Zinc analysis disagree, which Mill's comment names as the reason for `Junit4ScalaJs` (`SJM` lines 442–444).
- JVM only: Scala.js and Native do not use `TestModuleUtil`.

### 3.2 `member`: reflection on `aheadOfTimeDiscoveredTestClassesIfNeeded`

- Two calls returned two different `Task.Anon` instances (`eq` was `false`), each `Task.Anon@…(mill.javalib.TestModule#aheadOfTimeDiscoveredTestClassesIfNeeded)`.
- Used as an input of the plugin's `Task.Anon`, Mill evaluated it in the plugin's group and gave `None` on `jvm.test` and `Some(1 classes)` on `zinc.test`. An anonymous task is not cached (`TK` lines 314–319), so a new instance per call costs no cache entry. Its named input, `zincAnalysisFileDiscoveredTestClasses`, is the module's own memoised task.
- JVM only, as § 3.1.

### 3.3 `hook`: reflection on `testTask`, evaluated by the plugin

- It ran on the JVM (both platforms), Scala.js (both platforms) and Native (Windows). On Scala.js and Native, invoking the method ran the `TestScalaJSModule` or `TestScalaNativeModule` override, whose task printed as `Task.Anon@…(mill.scalajslib.TestScalaJSModule#testTask)` or the Native equivalent.
- On the JVM, `TestModuleUtil` received the plugin's ctx, because the `using` ctx of `new TestModuleUtil(...)` inside `testTask`'s body is the ctx passed to `evaluate` (`TM` lines 316–340). Its `fork.async` was therefore `CaptureFork`, as in `MPC` Route A.
- On Scala.js, the VM started while the plugin evaluated `testFrameworkInstance` inside its own `withStreams`. Its stdout and stderr, and the test-interface logger lines, all reached the plugin's buffers.
- On Native, the plugin replaced the value of `nativeLink` with a `PathRef` to the launcher before evaluating `testTask` (`B` lines 254–262). `testTask`'s body reads `nativeLink()` only to pass it as the test binary (`SNM` lines 497–502).

### 3.4 `command`: the public `testForked` or `testOnly`, evaluated by the plugin

- It is the `hook` route rooted at the public command instead of the protected member. It involves no reflection and no protected member. Its results matched `hook` on every runtime and platform run (§ 4, § 5).
- With class globs it used `testOnly(globs*)`, whose own argument parsing produced the selector task (`TM` lines 223–235).
- In one command over `jvm.test zinc.test js.test native.test`, all four succeeded and nothing reached the console, on Windows; on Linux, without Native, likewise.

### 3.5 `adapter`: Scala Native's `TestAdapter` from public tasks

- It ran on Windows under both modes. It built the adapter from `nativeLink`, `allForkEnv`, `runClasspath`, `compile`, `testArgsDefault`, `testFramework` and `testReportXml`, all public, with the plugin's own logger and launcher. It ran the framework through `TestRunner.runTestFramework` with discovery `None`, and reported through `TestModule.handleResults`.
- `scala-native` `test-runner_2.13` `0.5.12` was on the build's own class path, as in `NSW` § 1, not loaded at the module's version from `bridgeFullClassPath`.

## 4. Against R-053

Each route run was compared with Mill's own task of the same platform, mode and outcome: `testForked`, or `testOnly gamma.G0*` for class globs.

| Compared | Result |
|---|---|
| Exit status | the same in every run |
| Classes run per fork group, as a set | the same in every JVM run |
| JUnit XML test cases (route's folder against the task's `Task.dest`) | the same on Linux in every run, and on Scala.js and Native on Windows. On Windows JVM runs, the same except where a class ran on two workers (below) |
| Workers that started a JVM | the same except, on Linux, `hook` under the daemon, passing and failing (3 against 2 in `group-1`); on Windows, 4 runs under the daemon (3 against 2 in `group-1` for `command` passing and failing and `member` passing; 1 against 2 for `public` failing) |
| JVM working directory, input arguments, environment, command; on Linux the full command line | the same in every run, once paths into the task's folder are replaced by a placeholder and `MILL_PARAMS` (the launcher's command line, `MPC` § 5) is set aside, except two artefacts: `NOTRAIT_GLOBS`, the prototype's own variable, which reached the workers through `propagateEnv`; and the folder of a worker present on one side only |
| Working directory | `<module folder>/<group>/worker-<i>/sandbox`, as under `<Task.dest>` for Mill's task |
| Failure message | the goal's failure message, as the plugin printed it, had the text of Mill's `[error] … tests failed:` line and the list under it, `1 tests failed: gamma.G03 t2`, in every failing run, except Windows `public`, daemon: `2 tests failed`, `G03` having run on two workers |

- **"failure line DIFFERS"** in `[proto]` `results/windows-summary.txt` and `results/linux-summary.txt`, on every failing route run, is not a difference in that message. `analyse.py` compares the console lines holding `tests failed`: a route's console has the plugin's line and not Mill's `[error]` line (§ 2.4).
- **A class run twice.** No Linux run had one. On Windows, the runs with a class on two workers of one group: under `--no-daemon`, every JVM run, Mill's own tasks included; under the daemon, `testForked` in 1 of 2 runs, `testOnly` in none, the routes in 4 of 8 runs without globs and none of 4 with them. This is the behaviour `MPC` § 5 records for Mill's own task and for both of its routes. With one round per case, these counts do not show whether a route changes the rate.
- **Scala.js and Native environment and arguments** were not recorded. *Inference:* the `hook` and `command` routes run Mill's own body with Mill's own input values, except Native's binary, which the launcher starts with the arguments and environment it receives (`NSW` § 1).
- **Folders.** No route wrote into any `out/<module>/test/testForked.*` or `testOnly.*` path it was given. *Inference, not checked:* Mill's own test tasks' cached results and folders are untouched, since Mill never ran those groups (§ 2.3).

## 5. Against R-040 and R-042

- **Nothing reached the console.** In every route run on both platforms, the console held none of the run's lines: no `SEQ-*`, `CHILD-*`, `X-*`, `Y-*` or `Z-*` line, no JVM warning, no `Running Test Class` and no ScalaTest summary. Under Mill's own tasks all of them did, except Native under the daemon, whose lines went to `out/mill-daemon/stdout` (`NSW` § 2).
- **The plugin received every stream, in order.** Each JVM worker's stdout and stderr were kept under the worker's `fork.async` key. A single-class JVM run (`zinc.test`), which spawns on the calling thread (`MWC` § 2.2), was captured through `withStreams`' `os.Inherit`. The Scala.js VM's streams and the logger lines came through `withStreams`. The Native binary's streams came from the launcher's files. In every run, each `SEQ-OUT`, `SEQ-ERR`, `X-OUT` and `X-ERR` sequence ran 1 to N without gap or reordering. `[proto]` `results/windows-summary.txt` flags `xOut` and `xErr` out of order in the two Windows all-module `command` runs: there `analyse.py` joins `js.test`'s and `native.test`'s captured files under one key, and each module's own files run 1 to 20.
- **When bytes arrived** was not measured; the plugin wrote its buffers out after `execute` returned.

## 6. API marking per route

Mill's compatibility policy and MiMa coverage: `MNK` § 5. All the `mill.api` types below are in the policy. `Task` (`core/api/src`) is MiMa-checked. `TaskCtx`, `BuildCtx` and `SystemStreamsUtils` are in `core/api/java11`, whose module extends `MillPublishScalaModule` (`[mill]` `core/api/package.mill` line 15), so they are not.

| Route | Runtimes run | Mill API | Marking |
|---|---|---|---|
| `public` | JVM | the API of `MPC` § 6 Route A, which this route builds from outside the module | as `MPC` § 6 records |
| | | `BuildCtx.withFilesystemCheckerDisabled`, `SystemStreamsUtils.withStreams`, `Task.Anon` | public, unmarked |
| | | discovery value | `None`, which equals Mill's only when the protected `discoverTestsWithZinc` is `false` |
| `member` | JVM | as `public`, plus `aheadOfTimeDiscoveredTestClassesIfNeeded` | `protected def`; `public` in bytecode; reached by `java.lang.reflect` |
| `hook` | JVM, Scala.js, Native | `testTask` | `protected def`; `public` in bytecode; reached by `java.lang.reflect` |
| | | `Task.inputs`, `Task.evaluate`, `Task.Named`, `Task.sequence` | public, unmarked, `mill.api` |
| | | `TaskCtx` and `Fork.Api`; `BuildCtx.withFilesystemCheckerDisabled`; `SystemStreamsUtils.withStreams` | as `public` |
| | | Native: the value of `nativeLink` replaced | public task; relies on `testTask`'s body using it only as the binary (`SNM` lines 497–502) |
| `command` | JVM, Scala.js, Native | `testForked`, `testOnly` | public commands, unmarked |
| | | everything `hook` uses except reflection | as `hook` |
| `adapter` | Native | `nativeLink`, `allForkEnv`, `runClasspath`, `compile`, `testArgsDefault`, `testFramework`, `testReportXml` | public tasks |
| | | `TestRunner.runTestFramework` | `@internal` object (`[mill]` `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunner.scala` line 6) |
| | | `TestRunnerUtils.globFilter` | `@internal` object (`[mill]` `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` line 20) |
| | | `TestModule.handleResults` | public, unmarked (`TM` lines 749–755) |
| | | `TaskCtx`, implemented in full, with the goal's own `fork`; `BuildCtx.withFilesystemCheckerDisabled`; `SystemStreamsUtils.withStreams` | as `public` |
| | | Scala Native `TestAdapter` | public (`JNC` § 6.2), on the build's class path at the build's version |

- **What `hook` and `command` rely on beyond the signatures.** First, that evaluating a group's tasks in order with their input values as `args` is all Mill's evaluation of them needs (§ 2.3). Second, that the tasks under the command read nothing from the parts of `GE`'s `wrap` the plugin does not reproduce. Third, on Native, the use of `nativeLink` noted above. *Inference:* a later Mill that moves work into those parts, or changes how a test task's body reads its inputs, changes the result without failing at compile time.

## 7. Against the specification and the earlier notes

- `MPC` § 6 reached `aheadOfTimeDiscoveredTestClassesIfNeeded` only from a trait mixed into the test module. Reflection reaches it without one (§ 3.2). The `command` route does not need it, because Mill's own body passes it (§ 3.4).
- `MWC` § 2.1 found that a `testTask` override calling `super.testTask` cannot take the raw streams. Under the `hook` and `command` routes the plugin, not Mill, evaluates that same task, with the plugin's ctx and streams, and does take them (§ 3.3, § 5).
- `JNC` § 6.2 names `testTask` as Native's only overridable member. The `command` route runs Native's tests with the launcher without overriding it (§ 3.4).
- `JNC` § 5.1 infers that the JS VM's `PipeOutputThread` writes to the streams of the thread that started the VM. Observed: with the plugin's `withStreams` around that start, the VM's stdout and stderr reached the plugin and not the console (§ 5).
- `.planning/event-stream-reporting/SPECIFICATION.md` R-092: every route held it on the runtimes it ran on, since no fixture names anything of the prototype's (§ 1). Only `command` and `hook` held it on all three runtimes, and only `command` without reflection. `public` holds it by passing `None` for discovery. *Inference:* that breaks R-053 wherever class files and Zinc analysis disagree (§ 3.1).

## 8. What was not run or not determined

- Native on Linux: no clang was installed in WSL. Native under an emulator. macOS.
- A pseudo-terminal or Mill's live prompt; all output was redirected to files.
- More than one round per case, so the worker-count and duplicate-class rates of § 4 are not compared.
- A worker, VM or native process that dies or is killed under any route.
- `testOnly` with framework arguments after `--`; only class globs were given.
- `testCached` and `testLocal`.
- Scala.js and Native environment and arguments against Mill's own task (§ 4).
- A Scala.js route from public tasks only: a plugin-built `TestAdapter` with its own `Input`s and Scala.js tools class path (`JNC` § 6.1).
- A JVM route that rebuilds Zinc discovery from `compile().analysisFile` and `ZincOp.DiscoverTestsZinc`, and reading `discoverTestsWithZinc` by reflection (§ 2.1).
- Calling the protected members from Java source (§ 2.2).
- Whether Mill's `out/<module>/test/testForked.*` and `testOnly.*` folders and caches are untouched by every route (§ 4).
- Whether the daemon's `stdout` and `stderr` files received anything during a Native route run; they were inspected only after the controls.
- When captured bytes reached the plugin (§ 5).
- Other routes: no Mill mechanism was found that adds behaviour to a module without the build defining it, such as an external module or a module-level hook, in the sources read (`TM`, `TMU`, `TK`, `GE`, `SJM`, `SNM`). No wider search was made.
- Versions other than § 1 lists.
