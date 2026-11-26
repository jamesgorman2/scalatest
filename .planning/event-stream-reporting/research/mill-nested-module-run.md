# The plugin's JVM module run as a task in a fail-fast-off nested evaluation, at Mill 1.3.1

This note covers, at Mill `1.3.1`:

- whether the route of `.planning/event-stream-reporting/research/mill-nested-keep-going.md` § 3.1, one `execute` on an evaluator built over a copy of `mill.exec.Execution` with `failFast = false`, still works;
- whether, inside that evaluation, an anonymous task per selected module can run the plugin's own JVM module run (`JvmTestModuleRun`), downstream of that module's inputs, so that each module starts on its own compile while another module's compile fails;
- whether the workers' output still stays off the console (Route A, `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`) when the module run is a task body rather than the command's body.

Question: [Many modules in one test command - The fail-fast-off nested evaluation on Mill 1.3.1](https://github.com/jamesgorman2/scalatest/issues/242). Facts only; no design is chosen.

Two notes hold facts this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-nested-keep-going.md`, cited as `MNK`: the route at Mill `1.1.10`, concurrent `execute` calls, and the API markings.
- `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`, cited as `MPW`: Route A, a `TaskCtx` whose `fork.async` sets `os.Inherit` inside the body.

## Short answer

- **The route works unchanged at 1.3.1** (§ 2, § 3). `mill.exec.Execution` is still a case class with a `failFast` field, `EvaluatorImpl.execution` is still a `private val`, and `EvaluatorImpl`'s constructors still take 3 and 4 parameters. The `MNK` § 3.1 code ran as written. A variant that passes the command's `scriptModuleInit` to the 4-parameter constructor ran with the same results.
- **Each module's run started on its own compile, and kept going after another module's compile failed** (§ 3). In four cold runs, the module runs of `a.test`, `b.test`, `failing.test` and `batch.test` started 0.01 s to 0.18 s after their own compile. `broken.test.compile` failed at about the same time. `slowc.test` compiled at about 20.8 s, 15 s after that failure, and its run started 0.06 s later. With fail-fast on, `slowc.test` was `Skipped` and its compile never ran. The copy's results and start times matched `--keep-going`.
- **No worker output reached the console** (§ 4). In all eight runs, every worker's stdout, stderr and child-process lines reached the plugin's consolidation and none reached the console. That held on each worker path:
  - queue workers forked through `fork.async`;
  - a queue worker on the task's own thread;
  - batch workers forked through `fork.async`.
- **The task body runs on Mill's pool, not on the command's thread** (§ 5). Its `Task.ctx().fork` is an `ExecutionContexts.ThreadPool` over the same executor as the command's, with the same `jobs`. Mill gives a non-command task a filesystem checker and an `Evaluator` that throws. The module run already disables the checker, as it does in the command today, and uses no `Evaluator`.
- **A module whose compile fails never reaches the consolidation** (§ 3.3). Its anonymous task is `Skipped` without running, so only the evaluation's results tell the test command about it.
- **1.3.1 adds launcher locking** (§ 6). Several launchers can share one daemon. A nested `execute` releases the command's exclusive lease and takes a read lease, and `Execution` retries a whole evaluation when another launcher rewrites a task whose lease it dropped. *Inferred:* a retry would run every module's anonymous task again. Not run.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.3.1` (`e5e91a4fac1edbfa1c8c96a3cf1c5f474579132f`) |
| `[mill-1.1.10]` | the same repository | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`), for comparison |
| `[plugin]` | this repository, `mill-plugin/plugin/src/org/scalatest/mill/` | `d3e2bf024` |
| `MNK`, `MPW` | as above | as committed |

Permalink base: `https://github.com/com-lihaoyi/mill/blob/e5e91a4fac1edbfa1c8c96a3cf1c5f474579132f/`. Paths are relative to the `[mill]` root. Abbreviations:

- `EX` is `core/exec/src/mill/exec/Execution.scala`.
- `EI` is `core/eval/src/mill/eval/EvaluatorImpl.scala`.
- `EP` is `core/api/src/mill/api/EvaluatorProxy.scala`.
- `EV` is `core/api/src/mill/api/Evaluator.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `ECX` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `TTC` is `core/exec/src/mill/exec/TaskThreadContext.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `BOOT` is `runner/daemon/src/mill/daemon/MillBuildBootstrap.scala`.
- `MAIN` is `runner/daemon/src/mill/daemon/MillMain0.scala`.
- `LL` is `core/api/daemon/src/mill/api/daemon/internal/LauncherLocking.scala`.

Statements marked *inferred* follow from the cited source and were not observed. § 7 lists what was not run.

## 1. What was run

A throwaway Mill `1.3.1` build, outside the repository and not committed.

- **The plugin's code.** The meta-build compiled every source file of `[plugin]` except `TestCommandModule.scala`, against `com.edc::scalatest-consolidation:3.2.20` from the local Ivy repository. The probe's own code sits in package `org.scalatest.mill`, so it can reach the plugin's `private[mill]` classes.
- **Fixture modules.** Each is a `ScalaTests` module with `TestModule.ScalaTest` and `com.edc::scalatest::3.2.20`, built with Scala `3.3.8`. Each depends on a module `shared` whose helper the tests call:
  - `a.test`: three classes, `testParallelism` on (the default), so the module run forks three queue workers through `fork.async`.
  - `b.test`, `failing.test`: one class each, so Mill runs the one queue worker on the calling thread (`TMU` lines 336–374). `failing.test`'s test fails.
  - `broken.test`: does not compile.
  - `slowc.test`: one class; its `generatedSources` depends on a `Task.Input` that sleeps 20 s, so its compile starts after 20 s.
  - `batch.test`: `testBatchFrameworkTasks` on, two fork groups of one class, so the module run forks one batch worker per group through `fork.async`.
- **Each test** appends its start time to a file outside the workspace. It writes five numbered lines to `System.out` and five to `System.err`, and starts a child process with `inheritIO()` that writes one line. It then sleeps 2 s.
- **The command,** `probe.run`, is an exclusive command that takes the `Evaluator`, a mode and the module selectors.
  - It resolves each selector to a `TestModule` and starts one `Consolidation`, whose human-readable and JSON outputs go to files, not the console.
  - It clears each module's folder in the folder `modules` beside its own `Task.dest`, as `TestCommand` does (`[plugin]` `TestCommand.scala` lines 70–75).
  - It builds one `Task.Anon` per module. The task depends on a copy of `JvmTestModuleInputs.task`, which is private, for that module. Its body:
    1. records its start time and thread;
    2. builds a `JvmTestModuleRun` with the module's inputs, no class globs, no framework arguments, the module's folder, and the task's own `Task.ctx()` as the command context;
    3. calls `execute(consolidation)` inside `BuildCtx.withFilesystemCheckerDisabled`, as `TestCommand` does (`[plugin]` `TestCommand.scala` lines 46–62).
  - It calls `execute` once over every module's task, on the evaluator the mode names. It then logs each task's result from `executionResults.transitiveResults` and calls `consolidation.end`.
- **Modes:**
  - `plain`: the command's own evaluator.
  - `copy110`: the evaluator built by the `MNK` § 3.1 code as written.
  - `copy`: the same, but built with the 4-parameter `EvaluatorImpl` constructor and the command's own `scriptModuleInit`.
- **Measures.** Times are from the start of the command's body:
  - a test module's compile time is the modification time of `out/<module>/test/compile.json`;
  - `broken.test`'s failure time is the modification time of `out/broken/test/compile.log`, Mill's last write to that task's log;
  - a module run's start is the first line of its task body;
  - a test's start comes from the file it appends to.
- **Runs.**
  - Selectors `a.test b.test failing.test broken.test slowc.test batch.test`.
  - Cold runs followed a verified `clean` of the fixture modules. Warm runs reused every fixture module's compile except `broken.test`'s.
  - All runs used `--no-daemon` except one `copy` run under the daemon, and `--ticker false`. Each `mill` command's stdout and stderr went to files.
  - Each run was made once.

Versions: Mill `1.3.1` on Azul Zulu `25.0.3`, on Windows 11 (`10.0.22631`), x86-64.

## 2. The route at 1.3.1

### 2.1 What the source shows

- `Execution` is a case class with 26 fields, `failFast` among them (`EX` lines 17–44). `failFast` is read once each task completes: `if (failFast && res.newResults.values.exists(_.asSuccess.isEmpty)) failed.set(true)` (`EX` line 340). Each terminal task returns `None` without running once `failed` is set (`EX` line 301).
- Against `[mill-1.1.10]`, `Execution` gained `workspaceLocking` and `runArtifacts` and lost `versionMismatchReasons`. The route copies by `productIterator` and `productElementNames`, so these changes did not touch it.
- `Execution` has two constructors, of 26 and 25 parameters. The shorter one builds `profileLogger` itself, and `BOOT` calls it by reflection (`EX` lines 50–110; `BOOT` lines 835–860).
- `MillBuildBootstrap.makeEvaluator0` still passes `!keepGoing` as `failFast` (`BOOT` line 854), and `keepGoing` is still `bspMode || config.keepGoing.value` (`MAIN` line 393).
- `EvaluatorImpl` still holds the `Execution` as `private val execution`. Its primary constructor takes `allowPositionalCommandArgs`, `selectiveExecution`, `execution` and `scriptModuleInit`. A secondary constructor takes the first three and builds a new `ScriptModuleInit()` (`EI` lines 26–43). `[mill-1.1.10]` has the same two constructors.
- `EvaluatorProxy.delegate0` is still a public `var` (`EP` line 7).
- `Evaluator.allowPositionalCommandArgs` and `selectiveExecution` are `private[mill]` (`EV` lines 24–25), as at `1.1.10`. Code in package `org.scalatest.mill` failed to compile against them with `private[mill] method allowPositionalCommandArgs can only be accessed from package mill`, so the probe read them by reflection.

### 2.2 What the probe saw

In every `copy` and `copy110` run:

- Unwrapping every `EvaluatorProxy` gave `mill.eval.EvaluatorImpl`, under the daemon and under `--no-daemon`.
- The `execution` field held `mill.exec.Execution`, with arity 26 and constructor parameter counts 25 and 26. `EvaluatorImpl`'s constructors took 3 and 4 parameters.
- The command's own `Execution` read `failFast = true`.
- The copy's `execute` returned a result for every goal and threw nothing.

## 3. Module runs as tasks downstream of each module's inputs

### 3.1 Cold runs

Times in seconds from the start of the command's body. `compile` is the module's `compile.json`; `run` is the start of its task body; `tests` is its earliest test start.

| | `copy`, `--no-daemon` | `copy`, daemon | `copy110`, `--no-daemon` | `plain` + `--keep-going` | `plain` |
|---|---|---|---|---|---|
| `broken.test.compile` failed, about | 6.36 | 5.51 | 5.01 | 5.68 | 5.36 |
| `a.test`, `b.test`, `failing.test`, `batch.test` compile | 6.02–6.07 | 5.00–5.02 | 4.74–4.76 | 5.25–5.27 | 4.93–4.96 |
| their runs start | 6.06–6.19 | 5.06–5.18 | 4.79–4.89 | 5.27–5.39 | 5.00–5.12 |
| their tests start | 7.56–7.99 | 6.40–6.88 | 6.07–6.49 | 6.74–7.13 | 6.33–6.77 |
| `slowc.test` compile | 20.81 | 20.73 | 20.76 | 20.84 | not run |
| `slowc.test` run starts | 20.87 | 20.79 | 20.82 | 20.90 | — |
| `slowc.test` tests start | 21.62 | 21.53 | 21.56 | 21.75 | — |
| `broken.test` task | `Skipped` | `Skipped` | `Skipped` | `Skipped` | `Skipped` |
| `slowc.test` task | `Success` | `Success` | `Success` | `Success` | `Skipped` |
| other tasks | `Success` | `Success` | `Success` | `Success` | `Success` |
| `execute` returned | 23.77 | 23.67 | 23.71 | 23.92 | 20.49 |
| Suites the consolidation completed | 8 of 8 | 8 of 8 | 8 of 8 | 8 of 8 | 7 of 7 |

- **Each run started on its own compile.** The four modules' runs started 0.01 s to 0.18 s after their own compile.
- **The copy kept going.** `slowc.test`'s compile started after the 20 s input, about 15 s after `broken.test.compile` had failed. Its run started 0.06 s after it compiled, and its test 0.7 s to 0.9 s later. Its results and start times matched `--keep-going` within 0.25 s.
- **Fail-fast on.** In each run the four modules' runs had started before `broken.test.compile` failed, and they ran to completion: Mill skips only tasks not yet started. `slowc.test`'s compile never ran and its task was `Skipped`. `execute` returned only once the 20 s input that nothing still needed had finished, as `MNK` § 3.2 saw at `1.1.10`.
- **Output.** Under the copy and under `--keep-going`, Mill printed `[error] broken.test.compile task failed`, which it prints only when `failFast` is off (`GE` line 735). Under `plain` it printed the compile error without that line.

### 3.2 Warm runs

With every fixture module's compile reused except `broken.test`'s, the four modules' runs started at 0.60 s to 1.06 s, and `broken.test.compile` failed at 4.16 s to 4.84 s. Under `copy110` and `plain` + `--keep-going`, `slowc.test`'s run started at 20.49 s and 20.43 s and every other task succeeded. Under `plain`, `slowc.test` was `Skipped`.

### 3.3 A module whose inputs fail

`broken.test`'s anonymous task was `Skipped` without running, so the plugin's module run never started for it. The consolidation's human-readable and JSON output did not name `broken.test`. The compile failure is in the evaluation's `transitiveResults`, under `broken.test.compile`.

## 4. Worker output under the task body

| Run | Console lines holding a worker's output | Worker lines in the consolidation's JSON output |
|---|---|---|
| each of the six runs in which `slowc.test` ran | 0 | 40 stdout, 40 stderr, 8 child-process lines: 8 workers × (5 + 5 + 1) |
| each of the two `plain` runs | 0 | 35, 35, 7: 7 workers |

- **Worker paths exercised in every run:**
  - `a.test` laid out three queue workers in `worker-0` to `worker-2`, each forked through `WorkerTaskCtx.fork.async` (`[plugin]` `WorkerTaskCtx.scala` lines 24–33).
  - `b.test`, `failing.test` and `slowc.test` each ran one queue worker in the fork group's folder, on the task body's own thread, through `WorkerTaskCtx.runOnThisThread` (`[plugin]` `JvmTestModuleRun.scala` line 179).
  - `batch.test` ran one batch worker per fork group, each forked through `fork.async` (`[plugin]` `JvmTestModuleRun.scala` line 217).
- **The console** held only Mill's compile lines, the compile error, `broken.test.compile task failed` where § 3.1 says, and the launcher's lines.
- **Mill's log files.** No file under `out/` held a worker's line. Each forked queue worker's `worker-<n>.log` held only Mill's ticker, the class name, as `MPW` § 3 saw.
- **The plugin's own checks.** The plugin adds a root failure when a worker's output went to the console or a worker failed to start (`[plugin]` `WorkerRun.scala`). No run's output held one.

## 5. What changes when the module run is a task body

- **Thread and pool.**
  - The command's body ran on `main` under `--no-daemon` and on `MillServerActionRunner` under the daemon. Each module run ran on an `execution-contexts-threadpool-<n>-thread-<m>` thread.
  - The body's `Task.ctx().fork` was `mill.exec.ExecutionContexts$ThreadPool`, as is the command's, and `jobs` was 16 in both.
  - A non-exclusive task's context takes `fork = executionContext`, the evaluation's `ExecutionContexts.ThreadPool` over the shared executor (`GE` line 690; `EX` line 249). The copy shares the command's `ec` field, so the module run's workers go to the same executor.
- **`fork.blocking`.** `ExecutionContexts.ThreadPool.enterBlocking` and `leaveBlocking` now synchronise on the executor, not on the wrapper (`ECX` lines 49–70). `MNK` § 2.1 found the `1.1.10` code synchronising on the wrapper. The module run waits on its workers inside `ctx.fork.blocking` (`[plugin]` `JvmTestModuleRun.scala` line 71), which raises the pool's size for the wait. Neither run hung or starved.
- **Filesystem checker.** A non-command task runs under an `ExecutionChecker` that refuses reads and writes inside the workspace outside its own `dest`, its upstream workers' `dest`s and its upstream `PathRef`s (`GE` lines 913–944, 966–984). An anonymous task has no `dest`, and the module folder lies beside the command's `Task.dest`, so the module run needs the checker off. `BuildCtx.withFilesystemCheckerDisabled` sets `os.checker` to `os.Checker.Nop` (`core/api/java11/src/mill/api/BuildCtx.scala` lines 51–52). `fork.async` captures `os.checker`'s value when it is called and binds it in the forked body (`ECX` lines 97–124; `TTC` lines 33–43), so workers forked inside that block run with the checker off. *Inferred:* without the block, the module run would fail on its first write to the module folder; not run.
- **Streams.** A non-exclusive task's body runs with the task's own logger streams bound (`GE` lines 977–984). `WorkerTaskCtx` sets `os.Inherit.out` and `err` inside each worker's body, after Mill binds its own, so its values win on both the forked path and the calling-thread path (§ 4).
- **Evaluator.** In a non-exclusive task, `Evaluator.currentEvaluator` is a proxy that throws `No evaluator available here; Evaluator is only available in exclusive commands` (`GE` lines 985–994). The module run uses no `Evaluator`.
- **Prompt.** A non-exclusive task gets a prompt line, and each `fork.async` adds one for its worker (`EX` lines 289–298; `ECX` line 115). The prompt was off in every run (`--ticker false`, output to files). The copy's early final status, which `MNK` § 3.3 inferred, is outside this note's question.

## 6. Launcher locking, new in 1.3.1

- `LauncherLocking` "lets multiple launchers run concurrently" (`LL` lines 6–10). Under a daemon client or BSP, `MillMain0` builds a `LauncherLockingImpl`; otherwise it uses `LauncherLocking.Noop` (`MAIN` lines 299–330).
- **The nested evaluation's lease.** A nested `execute` suspends the command's exclusive lease and takes its own, a read lease unless a goal is a global-exclusive command (`EX` lines 394–443). Several commands' tasks can therefore run beside the test command's nested evaluation. The copy shares the command's `workspaceLocking`.
- **Retries.** `Execution.execute0` re-runs `execute0Once` from scratch when it catches `RetryDueToDroppedTaskLock` (`EX` lines 149–164). That is thrown when the evaluation re-takes a read lease it had dropped on an upstream task and another launcher has since rewritten that task (`EX` lines 670–716).
  - *Inferred:* a retry plans and runs every goal again. An anonymous task has no cache, so every module run whose body had already started would run again, adding its runs to the consolidation a second time.
  - Not run: every probe run had one launcher.

## 7. What was not run or not determined

- Linux and macOS; a terminal, and so Mill's live prompt and the copy's final status.
- `--jobs 1`, where `fork.async` runs its body at once on the calling thread (`ECX` lines 20–40).
- A second launcher on the same daemon, and the retry in § 6.
- The module run without `BuildCtx.withFilesystemCheckerDisabled` (§ 5).
- Class globs and framework arguments; the `-oH`, `-o` and `-fJ` outputs as `TestCommand` writes them.
- A worker that dies or is killed, and Mill cancelling a running task.
- Scala.js and Native modules.
- The test command's own code for several modules: the probe's command stands in for it.
