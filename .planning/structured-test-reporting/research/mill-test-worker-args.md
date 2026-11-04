# Can the Mill plugin give each test-worker JVM its own JSON file?

Can the Mill plugin, without ScalaTest's directory argument, guarantee one distinct JSON file per Mill test-worker JVM? Context: `.planning/structured-test-reporting/SPECIFICATION-DECISIONS.md` lines 45–56 (the directory-argument decision); `.planning/structured-test-reporting/ARCHITECTURE.md` lines 35, 68, 138; [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2). Scope: the JVM `TestModule` (`testForked`, `testOnly`, `testCached`). Scala.js and Native override `testTask` (`[mill] libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala` line 408, `libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala` line 492) and are not covered.

## Short answer

**Conditional: yes, by plugin-side means, but not through `TestModule`'s argument tasks alone.**

- Every test-worker JVM of one test command gets identical framework args, env and system properties, and Mill has no per-worker hook (§ 1, § 2). Setting `testArgsDefault`, `forkArgs` or `forkEnv` therefore cannot give each worker a distinct absolute path.
- With `testSandboxWorkingDir = true`, the default, each worker JVM has a unique working directory, `Task.dest[/<group>][/worker-<i>]/sandbox`. Mill fails rather than start a second JVM in a folder already used in the same run (§ 2.3, I-1). A relative `-fJ` path then yields one file per JVM. With the setting off, every JVM runs in `forkWorkingDir`, the workspace root by default, and the files collide. The plugin can read the setting and fail the build instead of colliding silently (I-2).
- A guarantee that holds without that condition: the plugin sets `testFramework` to a plugin-owned shim class on the test classpath. The shim rewrites `-fJ` to a per-JVM unique name, then delegates to `org.scalatest.tools.Framework` (I-3). The shim runs inside the worker JVM, so the working directory does not matter.
- A caveat for every option, ScalaTest's directory argument included: before any worker starts, Mill calls `Framework.runner` once more with the same args, outside any worker, for test discovery, and never calls `done()` on it (§ 1.4). ScalaTest 3.2.19 opens `-f` files when the runner is constructed. Unless the JSON writer defers creating its file, each test command leaves one extra file from a run that never finished (I-5).

The rejection recorded in `SPECIFICATION-DECISIONS.md` line 50 says the relative-path approach "fails silently when a user turns off Mill's `testSandboxWorkingDir`". That holds for a plugin that only passes a relative path. It does not hold for a plugin that checks the setting or uses a shim. The decision's other grounds, sbt projects sharing a path and bare `Runner` users (lines 50 and 52), are outside this note.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b7`) |
| `[os]` | <https://github.com/com-lihaoyi/os-lib> | tag `0.11.8`, the version `[mill] mill-build/src/millbuild/Deps.scala` line 166 pins |
| `[ti]` | <https://github.com/sbt/test-interface> | tag `v1.0`, the version `[mill] mill-build/src/millbuild/Deps.scala` line 185 pins |
| `[this]` | this repo | `7be3a1375` |
| `[#2]` | issue 2 on `jamesgorman2/scalatest`, body and its one comment | read 2026-11-04 |

Paths are relative to each source's root. Abbreviations for `[mill]`:

- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `RM` is `libs/javalib/src/mill/javalib/RunModule.scala`.
- `TR/` is `libs/javalib/testrunner/src/mill/javalib/testrunner/`.

No experiment was run for this note. §§ 1–4 are read from source. § 5 holds the inferences, numbered I-1 to I-5.

## 1. How Mill passes framework args to test JVMs

### 1.1 Entry tasks

- `mill foo.test` runs the test module's default task, `testForked` (TM line 34).
- `testForked(args*)` passes `testArgsDefault() ++ args` (TM lines 159–166).
- `testOnly` passes `testArgsDefault()` plus the args after `--`, and uses the args before `--` as class selectors (TM lines 223–235).
- `testCached` passes `testCachedArgs`, which defaults to `testArgsDefault` (TM lines 182, 191–193).
- Mill 1.1.10 has no `testArgs` task.
- All three commands call `testTask(args, globSelectors)`, a `protected def` (TM lines 312–341). It builds one `TestModuleUtil` from `forkArgs()`, `allForkEnv()`, `testFramework()`, `testForkGrouping()`, `testSandboxWorkingDir()`, `forkWorkingDir()`, `testParallelism()` and the args, then calls `runTests()`.
- `testLocal` instead runs the framework in Mill's own process (TM lines 347–358).

### 1.2 Grouping and distribution

- `testForkGrouping` defaults to a single group holding every discovered class (TM lines 203–205). Classes in different groups never share a JVM (TM lines 195–202).
- For each group, Mill writes one empty file per class into the group's `test-classes/` queue (TMU lines 183–191). It then creates worker indexes `0 until min(jobs, numTests)`, where `jobs` is Mill's `--jobs` (TMU lines 193–196, 210–229).
- `testParallelism` defaults to `true` (TM line 212). When it is on and the run has more than one class, each worker index runs on its own `Task.fork.async` future. Otherwise the workers run one after another on the task's thread (TMU lines 293–308).
- A worker spawns a JVM only if it can claim a class, which it does by atomically moving the class's file into its own `claim/` folder. Worker 0 spawns even when it claims nothing (TMU lines 314–328; `TR/TestRunnerUtils.scala` lines 357–365).
- Inside the JVM, the worker keeps claiming classes from the same queue until the queue is empty (`TR/TestRunnerUtils.scala` lines 344–352).

### 1.3 Test-runner main and argument serialization

- For each worker JVM, Mill writes a `TestArgs` (`TR/Model.scala` lines 6–23) as JSON to `<processFolder>/testargs` (TMU lines 127–143). It carries:
  - `arguments = args`
  - `sysProps`
  - the worker's own `outputPath`, `resultPath` and claim paths
- Mill spawns `mill.javalib.testrunner.entrypoint.MillTestRunnerMain` (TMU lines 147–163) with:
  - main args `<testrunner classpath> <testargs path>`
  - JVM args from `forkArgs`
  - env `Task.env ++ allForkEnv`, with `Task.env` included only when `propagateEnv` is on
  - cwd `sandbox` or `forkWorkingDir` (§ 2.3)
- `testUseArgsFile` defaults to on under Windows (TM line 241). When it is on, the `-D` entries of `forkArgs` move from the JVM args into `sysProps` (TMU lines 374–393). The worker sets them with `System.setProperty` before it creates the runner (`TR/TestRunnerMain0.scala` line 9).
- `MillTestRunnerMain` loads `MillTestRunnerMain0` in an isolated class loader that shares `sbt.testing` (`[mill] libs/javalib/testrunner/entrypoint/src/mill/javalib/testrunner/entrypoint/MillTestRunnerMain.java` lines 17–42).
- `main0` reads `args(1)`, loads `testFramework` by class name, and runs the tests (`TR/TestRunnerMain0.scala` lines 6–36; `TR/Framework.scala` lines 6–12). It then writes `outputPath` and calls `System.exit(0)` (`TR/TestRunnerMain0.scala` lines 43, 52).

### 1.4 `Framework.runner` calls

- **In each worker JVM:** exactly one call, `framework.runner(args.toArray, Array[String](), cl)`, with empty `remoteArgs`. The call is at `TR/TestRunnerUtils.scala` line 382 on the queue path and line 133 on the selector path. `done()` is called once at the end, and a non-empty result is `println`ed (lines 229–244, 354).
- **What the test interface says about runs:**
  - `[ti] src/main/java/sbt/testing/Framework.java` lines 19–36: `runner` "Initiates a run", and `remoteArgs` are "for the run in a forked JVM".
  - `[ti] src/main/java/sbt/testing/Runner.java` lines 4–12: a run lasts from `Framework.runner` until `done`.
  - Each Mill worker JVM is therefore one run.
- **One more call, before any worker starts:**
  - `runTests` sends the JVM worker a `ZincOp.GetTestTasks` request that carries the same `args` (TMU lines 82–92).
  - That request calls `framework.runner(args, …)` and `runner.tasks`, then drops the runner without calling `done()` (`TR/GetTestTasks.scala` lines 7–25; `TR/TestRunnerUtils.scala` lines 124–154, 399–411; `[mill] libs/javalib/worker/src/mill/javalib/zinc/ZincWorker.scala` lines 678–679).
  - It runs in Mill's own process. When `javaHome` or runtime options are set, it runs in a JVM-worker subprocess instead (`[mill] libs/javalib/worker/src/mill/javalib/worker/JvmWorkerImpl.scala` lines 39–40). Neither process runs in a test sandbox.
- **What ScalaTest does when that runner is constructed:**
  - Its constructor builds the dispatch reporter and fires `RunStarting` (`[this] jvm/core/src/main/scala/org/scalatest/tools/Framework.scala` lines 676, 687).
  - A `-f` file reporter opens, and so truncates, its `FileOutputStream` when it is constructed (`[this] jvm/core/src/main/scala/org/scalatest/tools/PrintReporter.scala` lines 115–130).

## 2. Per-worker args, env and working directory

### 2.1 Every worker gets identical values

- `args`, `props`, `jvmArgs`, `forkEnv` and `testFramework` are fields of the one `TestModuleUtil`. Every spawn uses them unchanged (TMU lines 31–56, 127–139, 147–163).
- The only values that differ per worker are paths Mill owns: `outputPath`, `resultPath`, the claim folder, the `testargs` path and the cwd.
- `allForkEnv` adds `MILL_WORKSPACE_ROOT` and `MILL_TEST_RESOURCE_DIR`. Both are set per module, not per worker (RM lines 48–52; TM lines 303–307).

### 2.2 No per-worker hooks

- `testArgsDefault`, `forkArgs`, `forkEnv`, `allForkEnv`, `testFramework`, `forkWorkingDir`, `testSandboxWorkingDir`, `testParallelism` and `testForkGrouping` each produce one value per task evaluation (TM; RM lines 36, 41, 64). No task or callback takes a worker index.
- What a plugin can override or call:
  - `testTask` is `protected`, so a plugin can override it (TM line 312).
  - `TestModuleUtil` is a public `final class` annotated `@mill.api.daemon.experimental`, and its `runTests` and `callTestRunnerSubprocess` are public (TMU lines 30–31, 62, 112).
  - `TestArgs`, `TestRunnerUtils` and `MillTestRunnerMain0` are `@internal` (`TR/Model.scala` line 6; `TR/TestRunnerUtils.scala` line 20; `TR/TestRunnerMain0.scala` line 5).
- Inside the worker JVM, no env var or system property names the worker. Two things identify it:
  - its cwd, when sandboxed (§ 2.3)
  - the `testargs` path in its main args, which reaches only `MillTestRunnerMain0` (TMU line 153; `TR/TestRunnerMain0.scala` line 8)

### 2.3 Working directories

Mill sets each worker's `processFolder` as follows (TMU lines 241–267, 279–285):

| Case | `processFolder` |
|---|---|
| One group, with `testParallelism` off or only one class in total | `Task.dest` |
| One group, `testParallelism` on, more than one class | `Task.dest/worker-<i>` |
| Several groups, `testParallelism` off | `Task.dest/<g>` |
| Several groups, `testParallelism` on | `Task.dest/<g>/worker-<i>` |

In the table:

- `<g>` is the class name for a group of one class, and `group-<n>-<first class>` otherwise.
- `<i>` is the worker index, left-padded.

The working directory follows from `processFolder`:

- The cwd is `processFolder/sandbox` when `testSandboxWorkingDir` is on. Otherwise it is `forkWorkingDir`, whose default is the workspace root (TMU lines 142, 145, 154; RM line 64; TM lines 293–301).
- Mill's examples show the resulting layout:
  - `out/foo/test/testForked.dest/sandbox` (`[mill] example/depth/sandbox/2-test/build.mill` lines 38–57)
  - `…/testForked.dest/worker-0` (`[mill] example/scalalib/testing/5-test-parallel/build.mill` lines 22–32)
  - `…/testForked.dest/group-0-foo.GroupX1/worker-...` (`[mill] example/scalalib/testing/7-test-group-parallel/build.mill` lines 25–33)
- With the sandbox off, "unit tests run in the root of your project workspace" (`[mill] example/depth/sandbox/2-test/build.mill` lines 92–94).
- Mill writes `testargs` with `os.write` (TMU line 143), which opens the file with `CREATE_NEW` (`[os] os/src/ReadWriteOps.scala` line 80). A second spawn into an already-used `processFolder` therefore throws.
- `Task.dest` "is cleared before your task runs" and is "unique for every Task or Command" (`[mill] core/api/src/mill/api/Task.scala` lines 79–84). Evaluation deletes it before a non-persistent task runs uncached (`[mill] core/exec/src/mill/exec/GroupExecution.scala` lines 320–323).
- `testForked` and `testCached` each have their own `Task.dest`.

## 3. Ways the plugin could get unique files

| Option | What the source permits | Failure modes |
|---|---|---|
| Relative `-fJ <name>` | Each JVM's cwd is unique while the sandbox is on (§ 2.3). | With `testSandboxWorkingDir = false`, every JVM shares `forkWorkingDir` and the files collide (TMU line 154). The files then land outside `Task.dest`, which Mill never clears (§ 2.3). The user can override the setting, because it is an ordinary task on the module (TM line 301). `testLocal` runs in Mill's own process (TM lines 347–358). The discovery runner resolves the path against a non-sandbox cwd (§ 1.4, I-5). |
| Collecting or renaming files after the run | Worker folders lie under `Task.dest` (§ 2.3). A `testTask` override, or a direct `TestModuleUtil.runTests()` call, sees `Task.dest` (TM line 312; TMU line 62). | It does not prevent collisions, only gathers files. If the plugin collects after `super.testTask`, a test failure skips the collection (I-4). |
| Placeholder in an arg | Mill passes args verbatim: `arguments = args` reaches `framework.runner(args.toArray, …)` with no substitution (TMU line 130; `TR/TestRunnerUtils.scala` lines 133, 382). | Something inside the worker JVM must expand the placeholder: either ScalaTest, which means ScalaTest's help, or a plugin shim (next row). |
| Shim `testFramework` | `testFramework` is a class name that the worker loads with `getDeclaredConstructor().newInstance()` from its own classpath (TM line 60; `TR/Framework.scala` lines 6–12; `TR/TestRunnerMain0.scala` lines 15, 26). | The discovery runner also passes through the shim (I-5). So does BSP class discovery, which calls `Framework.framework(testFramework())` (TM lines 368–380). The shim must be published as a test-classpath dependency (I-3). |
| Reimplementing the scheduler in `testTask` | `testTask` is overridable (TM line 312). | The reimplementation must use `@internal` `TestArgs` and `MillTestRunnerMain0` (§ 2.2). |

## 4. Reasoning in issue #2

- The question in the issue body: "Runs can share the same `-fJ <file>` argument: sbt `testGrouping` forks, Mill test-parallelism worker JVMs, or one module on several JVMs or platforms. R-003 forbids giving `-fJ <file>` a new meaning. How do they avoid overwriting each other's JSON…?" `[#2]`
- The decision comment on the directory argument: "**A new reporter argument takes a directory** and writes `<dir>/<run ID>.json`, one file per run, so Mill test-worker JVMs and other runs sharing the argument never overwrite each other (R-071)." `[#2]`
- The decision comment on caller responsibility: "**Callers own a fresh directory per invocation**: the Mill plugin's `Task.dest`, and a delete before `test` in the sbt recipe (R-053, R-060)." `[#2]`
- The decision comment on run identity: "The process that runs `done()` (for `Runner`, its own) mints a random run ID when the run starts". `[#2]`

The issue establishes that runs share arguments and records the directory argument as its answer. It gives no reason why the Mill plugin cannot keep files apart. The only such reasoning in the planning docs is in `SPECIFICATION-DECISIONS.md` line 50: the sandbox can be turned off, and a bare `Runner` user faces the same risk. ScalaTest owns the run ID because it mints the ID in the process that runs `done()`. That ownership is separate from who names the file.

## 5. Inferences

- **I-1.** Within one command, every JVM Mill spawns has its own `processFolder`. This follows from the table in § 2.3 and from the `CREATE_NEW` write of `testargs`.
  - With `testParallelism` off, a worker JVM can die, for example by calling `System.exit`, and leave classes unclaimed. The next worker index then claims a class in the same `processFolder`, and Mill throws on writing `testargs` (TMU lines 143, 284–285, 328). The task fails instead of overwriting the earlier file.
  - A user-defined `testForkGrouping` can repeat a class name, or contain two names that differ only in case on a case-insensitive filesystem. Both workers then share a folder, and the same throw follows.
- **I-2.** The plugin can make a disabled sandbox fail loudly. One of its tasks reads `testSandboxWorkingDir()` and fails, or warns, when the value is `false`. Overriding the setting to `true` in the plugin trait does not work, because a user's override later in the linearization still wins.
- **I-3.** The shim gives one file per JVM regardless of the working directory.
  - It receives `args` in `runner(args, remoteArgs, cl)`, replaces the `-fJ` value with a name unique to its JVM (for example an absolute plugin directory plus a UUID or PID), and calls `org.scalatest.tools.Framework.runner` with the result.
  - It forwards `name`, `fingerprints` and the returned `Runner` unchanged.
  - Costs: one published artifact added to the test module's dependencies, and Metals or Bloop runs reading `testFramework` through BSP also get the shim.
  - The resulting file name is not ScalaTest's run ID.
- **I-4.** In Mill, a task whose upstream fails does not run its body. A `testTask` override that calls `super.testTask(...)()` therefore never reaches its collection code when tests fail, because `handleResults` returns `Result.Failure` (TMU lines 395–420). Collection needs a direct `new TestModuleUtil(...).runTests()` call, an `@experimental` API, followed by gathering the files and then returning the `Result`.
- **I-5.** The discovery runner (§ 1.4) gets the same args as the workers. If the JSON writer opens its destination when the runner is constructed, as 3.2.19's `-f` does, each test command produces one extra file, written by a run that never calls `done()`. Where the file lands depends on the option:
  - With a relative path, it lands in Mill's process cwd, outside `Task.dest`, and is never cleared.
  - With the shim, it lands wherever the shim points.
  - With ScalaTest's directory argument, it lands in the plugin's directory as an extra `<run ID>.json`. A consolidator would read it as a run that never finished (compare [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9)).

  This applies whoever owns file naming. Not observed.
