# Can the test command run every selected module's tests after another task fails, without `--keep-going`?

This note covers, at Mill `1.1.10`:

- whether an exclusive command can call `Evaluator.execute` concurrently, one call per module, and what the calls share;
- other routes by which plugin code keeps its nested evaluation going when the user did not give `--keep-going`;
- for each route, whether a module's tests start as soon as that module compiles, and how the Mill API it uses is marked.

Question: [Can the test command run every selected module's tests after another task fails, when Mill's `--keep-going` is not given?](https://github.com/jamesgorman2/scalatest/issues/43). Facts only; no design is chosen.

Two notes hold facts this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`: the exclusive command that takes the `Evaluator`, fail-fast inside a nested `execute`, and Mill's compatibility policy.
- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`: what was observed when one `execute` ran every module's `testForked`.

## Short answer

- **Concurrent `execute` calls break Mill's thread pool** (§ 2). Each call wraps the command's one `ThreadPoolExecutor` in its own `ExecutionContexts.ThreadPool`, whose task counter starts at 0, and the pool's queue asserts that no two queued tasks share a counter value. In all three kept runs with the default `--jobs`, four of six calls threw that assertion's `AssertionError` within 0.12 s of starting, the other two never returned, and no test started. Mill neither detects nor forbids the calls.
- **Concurrent calls also run shared upstream tasks once per call, at the same time** (§ 2). Nothing dedupes or locks a task across calls. With `--jobs 1`, which gives Mill no pool, a task every module depends on ran five times at once, `shared.compile` compiled twice into one `dest`, and three calls failed with `NoSuchFileException` on a shared worker's `dest`.
- **One route meets both conditions: an `Evaluator` built over a copy of the command's `mill.exec.Execution` with `failFast = false`** (§ 3). The plugin reaches the private `EvaluatorImpl.execution` field by reflection. One `execute` on that evaluator ran every module's tests after a compile failure and after a test failure, without `--keep-going`. Each module's tests started on its own compile: 10.4 s for modules that compiled at 9.0 s, while another module's compile ran until 20.9 s. Results and start times matched `--keep-going`.
- **That route rests on members with no compatibility promise** (§ 5):
  - `EvaluatorImpl.execution` is `private val`.
  - `mill.exec` is outside Mill's binary-compatibility policy.
  - The policy lists `mill.eval`, but Mill's build does not run MiMa on the module that holds it.
  - Neither `mill.eval` nor `mill.exec` is on a build's compile class path; the route was written with reflection only.
- **Re-executing the skipped goals keeps going, but delays their tests** (§ 4.1). A module skipped in one round starts only after every task already running in that round has finished. Here that was 57.0 s instead of 16.4 s.
- **Turning a test task's failure into a success value removes only one trigger** (§ 4.2). Any upstream task that does not succeed, such as a failed compile, still sets fail-fast.
- **No task-graph shape, public member or plugin-settable configuration turns fail-fast off for one call** (§ 4.3, § 4.4). `mill-opts: ["--keep-going"]` in the build header makes `--keep-going` the default for every Mill command in that project; it is project configuration, not plugin code.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `MCL` | `.planning/event-stream-reporting/research/mill-command-lifetime.md` | as committed |
| `EPS` | `.planning/event-stream-reporting/research/emitter-plugin-spike.md` | as committed |

Permalink base: `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/`. Paths are relative to the `[mill]` root. Abbreviations:

- `EV` is `core/api/src/mill/api/Evaluator.scala`.
- `EP` is `core/api/src/mill/api/EvaluatorProxy.scala`.
- `EI` is `core/eval/src/mill/eval/EvaluatorImpl.scala`.
- `EX` is `core/exec/src/mill/exec/Execution.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `ECX` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `PL` is `core/internal/src/mill/internal/PromptLogger.scala`.
- `BOOT` is `runner/daemon/src/mill/daemon/MillBuildBootstrap.scala`.
- `MAIN` is `runner/daemon/src/mill/daemon/MillMain0.scala`.

Statements marked *inferred* follow from the cited source and were not observed. § 7 lists what was not determined.

## 1. What was run

A throwaway Mill build, outside the repository and not committed, with one exclusive command per route; § 3.1 gives the code of the route that worked. Each command takes the `Evaluator` and module selectors, resolves each selector with `resolveModulesOrTasks`, and builds `<module>.testForked()` for each, as the spike did (`EPS` § 1).

- **Fixture modules,** each a `ScalaTests` module with `TestModule.ScalaTest`, each depending on a module `shared`:
  - `a.test` and `b.test`: one passing 2 s test.
  - `failing.test`: one failing test.
  - `broken.test`: does not compile.
  - `late.test`: passes; its `forkArgs` depends on a `Task.Input` that sleeps 15 s.
  - `slowc.test`: passes; its `compile` is overridden to depend on a `Task.Input` that sleeps 20 s.
- **A shared task.** Every module's `forkArgs` depends on `shared.counter`, a cached `Task` that sleeps 1.5 s and appends its start and end times to a file outside the workspace.
- **Measures.** Each test appends its start time to a file. A test module's compile time is the modification time of `out/<module>/test/compile.json`. The command reports each `testForked` result from `executionResults.transitiveResults`. Times are from the start of the command's body.
- **Commands:**
  - `probe.single`: one `execute` over every module's `testForked`, as in `EPS` § 2.
  - `probe.concurrent`: one `execute` per module, each on its own `new Thread` started from the command's thread.
  - `probe.globalEc`: one `execute` per module, each in a `Future` on `scala.concurrent.ExecutionContext.global`.
  - `probe.reflect`: one `execute` on an evaluator over a copy of the `Execution` with `failFast = false` (§ 3.1).
  - `probe.rerun`: one `execute` over every module, then another over the goals skipped by fail-fast, until none is left (§ 4.1).
- **Runs.** Selectors `a.test b.test failing.test broken.test late.test slowc.test`, after `clean` of the fixture modules unless marked warm. All runs under `--no-daemon` except one `probe.reflect` under the daemon. `probe.single` and `probe.reflect` also ran warm with `a.test failing.test late.test`, with no compile failure. Each run was made once; the concurrent commands' earlier runs, made before the probe had a watchdog, failed the same way and were not kept.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`, on Windows 11 (`10.0.22631`), x86-64, with Azul Zulu `21.0.10` fetched by Mill.

## 2. Concurrent `execute` calls from one command

### 2.1 What the calls share

All calls go to one `mill.eval.EvaluatorImpl` and its one `mill.exec.Execution` (`EI`, `EvaluatorImpl.execute`).

- **Fail-fast.** Each call's `failed` flag is local to `mill.exec.Execution.execute0` (`EX`), so a failure in one call does not skip another call's tasks (`MCL` § 2.4).
- **The thread pool.** All calls share the `ThreadPoolExecutor` that `MAIN` creates with `ExecutionContexts.createExecutor` and passes to the evaluator, and its single `PriorityBlockingQueue` (`ECX`). There is no executor when `--jobs` is 1 (`MAIN`), and tasks then run on the calling thread through `ExecutionContexts.RunNow`.
  - Each call's `evaluateTerminals`, inside `Execution.execute0`, wraps that executor in a new `ExecutionContexts.ThreadPool` (`EX`).
  - `ExecutionContexts.ThreadPool.priorityRunnableCount` is a field of each wrapper, so each wrapper numbers its tasks from 0 (`ECX`).
  - `ExecutionContexts.ThreadPool.PriorityRunnable.compareTo` breaks ties on that number and asserts `this == o || this.priorityRunnableIndex != o.priorityRunnableIndex` (`ECX`). Two calls' tasks queued at once can carry the same number.
  - `ExecutionContexts.ThreadPool.updateThreadCount`, which `blocking` calls to grow and shrink the shared executor, synchronises on its wrapper, not on the executor (`ECX`). Test tasks call `Task.fork.blocking` (`libs/javalib/src/mill/javalib/TestModuleUtil.scala`). *Inferred:* two calls can interleave their read-then-set updates of the executor's pool sizes.
- **Tasks and `out/`.** The calls share the `out/` folder, `workerCache` and the profile logger.
  - Each call plans its own goals (`PlanImpl.plan` in `Execution.execute0`), and its futures map lives in that call (`EX`). A task upstream of several modules, such as a shared module's `compile` or a `JvmWorkerModule` task, is planned and run by every call that needs it.
  - `GroupExecution.executeGroupCached` reads and writes the task's `meta.json`, and deletes a non-persistent task's `dest` before running it, with no lock (`GE`). The only `out/` lock, `mill-out-lock`, is taken once per Mill command by `Server.withOutLock` (`MAIN`; `libs/daemon/server/src/mill/server/Server.scala`).
  - `workerCache` is read and updated under `workerCache.synchronized`, but a worker is created outside that lock (`GroupExecution.loadUpToDateWorker`, `GroupExecution.handleTaskResult`; `GE`). *Inferred:* two calls can each create a worker, and the first one stored is replaced without being closed.
  - Each call writes `out/mill-dependency-tree.json` and `out/mill-invalidation-tree.json` (`ExecutionLogs.logDependencyTree`, `ExecutionLogs.logInvalidationTree`, called from `Execution.execute0`; `EX`). *Inferred:* the last call to write each file wins.
- **The prompt.** Each call runs inside `logger.prompt.withPromptUnpaused` (`Execution.executeTasks`; `EX`). `PromptLogger.RunningState.withPromptPaused0` keeps one `paused0` flag and restores the state it found when its call ends (`PL`). Each call also sets the one prompt header through `setPromptHeaderPrefix` with its own counts, and calls `clearPromptStatuses` when it ends (`EX`; `PL`). *Inferred:* the first call to end pauses the prompt while the others still run, its header counts replace the others', and its end clears every running task's prompt line.
- **The evaluator handle.** The command's `Evaluator` is `new EvaluatorProxy(() => Evaluator.currentEvaluator)` (`libs/util/src/mill/util/TokenReaders.scala`, `EvaluatorTokenReader.read`). `Evaluator.currentEvaluator` is a `DynamicVariable` read on the calling thread at each call (`EV`; `EP`). Threads started from the command's thread inherit it; both `new Thread` and freshly created `ExecutionContext.global` threads reached `execute` (§ 2.2). *Inferred:* a thread created before the command started sees another evaluator or `null`.

### 2.2 What happened

| Run | `--jobs` | Calls that returned a result | Calls that threw | Calls that never returned | `shared.counter` body ran |
|---|---|---|---|---|---|
| `new Thread`, first | default | none | 4, at 0.35 s | 2 | once |
| `new Thread`, second | default | none | 4, at 0.41 s | 2 | twice, overlapping |
| `ExecutionContext.global` | default | none | 4, at 0.37 s | 2 | three times, overlapping |
| `new Thread` | 1 | 3: `broken.test` `Skipped`, `failing.test` `Failure`, `late.test` `Success` | 3 | 0 | five times, all at once |

- **Default `--jobs`.** In every run, each call that threw ended with `java.util.concurrent.ExecutionException: Boxed Exception`, caused by `java.lang.AssertionError: assertion failed`. In the first `new Thread` run, pool threads also printed `Exception in thread "execution-contexts-threadpool-1-thread-<n>" java.lang.AssertionError: assertion failed` 142 times, from `PriorityRunnable.compareTo` inside `PriorityBlockingQueue.take`. Thread numbers rose to 758 as the executor replaced dead threads. That run also printed `java.lang.NullPointerException: tried to cast away nullability, but value is null` from `scala.concurrent.impl.Promise$Transformation.run` on a pool thread. The other two runs printed nothing to stderr after the command's key.
- **Calls that never returned.** In an earlier run that was not kept, a thread dump 150 s in showed both such threads in `Await.result` inside `Execution.evaluateTerminals`, under `EvaluatorImpl.execute`. In every run, no test of any module started and no test module's `compile.json` was written.
- **`--jobs 1`.**
  - `shared.compile` printed `compiling 1 Scala source to out/shared/compile.dest/classes ...` twice at once, and `done compiling` twice.
  - `a.test`, `b.test` and `slowc.test` threw `java.nio.file.NoSuchFileException: …\out\mill.javalib.JvmWorkerModule\internalWorker.dest`.
  - `failing.test` and `late.test` started their tests 0.8 s to 0.9 s after their own compile. *Inference:* a call running a shared worker task deleted its `dest` while another call was using it (§ 2.1).
  - *Inferred:* with no executor, `Task.fork.async` runs its body at once on the calling thread (`ExecutionContexts.RunNow.async`; `ECX`), so a module's test fork groups run one after another.

### 2.3 Does Mill detect or forbid it

- No. `EvaluatorImpl.execute` and `Execution.executeTasks` hold no lock and check for no concurrent call (`EI`; `EX`). `Execution.executionNestingDepth` only counts calls to choose when to print the final status (`EX`).

## 3. An evaluator whose `Execution` has fail-fast off

### 3.1 How

- `failFast` is a constructor field of the case class `mill.exec.Execution` and is read on every task's completion (`Execution.execute0`; `EX`). `MillBuildBootstrap.makeEvaluator0` sets it to `!keepGoing` and builds the evaluator reflectively, with `EvaluatorImpl`'s three-parameter constructor and `Execution`'s shortest constructor (`BOOT`).
- `EvaluatorImpl` holds its `Execution` as `private val execution` and has public constructors taking one (`EI`). `Evaluator` has no member that returns it (`EV`).
- The command's `Evaluator` is one or more `mill.api.EvaluatorProxy`s; `EvaluatorProxy.delegate0` is a public `var` holding the function that yields the next evaluator (`EP`). Unwrapping every proxy gave `mill.eval.EvaluatorImpl` in both modes.
- `mill.eval` and `mill.exec` are not on a build's compile class path: `mill.eval.EvaluatorImpl` in `build.mill` failed with `value eval is not a member of mill`. The probe therefore used reflection only:

```scala
def unwrap(e: Evaluator): Evaluator = e match {
  case p: EvaluatorProxy => unwrap(p.delegate0()); case x => x
}
val impl = unwrap(ev)                                     // mill.eval.EvaluatorImpl
val f = impl.getClass.getDeclaredField("execution"); f.setAccessible(true)
val exec = f.get(impl).asInstanceOf[Product]              // mill.exec.Execution
val elems = exec.productIterator.toArray.map(_.asInstanceOf[AnyRef])
elems(exec.productElementNames.indexOf("failFast")) = java.lang.Boolean.FALSE
val execCopy = exec.getClass.getConstructors.maxBy(_.getParameterCount).newInstance(elems*)
val ev2 = impl.getClass.getConstructors.minBy(_.getParameterCount).newInstance(
  allowPositionalCommandArgs, selectiveExecution, execCopy).asInstanceOf[Evaluator]
ev2.execute(tasks)
```

### 3.2 What happened

Cold runs, no `--keep-going`; times from the start of the command's body:

| Module | One `execute`, no flag | One `execute`, `--keep-going` | Fail-fast-off copy, no flag | Fail-fast-off copy, daemon |
|---|---|---|---|---|
| `a.test`, `b.test` | `Skipped` | `Success`; tests 12.5 s | `Success`; tests 10.4 s | `Success`; tests 8.4 s |
| `failing.test` | `Skipped` | `Failure`; tests 12.5 s | `Failure`; tests 10.4 s | `Failure`; tests 8.4 s |
| `broken.test` | `Skipped` | `Skipped` | `Skipped` | `Skipped` |
| `late.test` | `Skipped` | `Success`; tests 16.4 s | `Success`; tests 18.7 s | `Success`; tests 16.4 s |
| `slowc.test` | `Skipped`; compile not run | `Success`; compile 20.6 s, tests 21.6 s | `Success`; compile 20.9 s, tests 22.0 s | `Success`; compile 20.7 s, tests 21.6 s |
| Other test modules compiled | 7.7 s | 7.9 s | 9.0 s | 7.1 s |
| `execute` returned | 20.9 s | 23.8 s | 24.3 s | 23.7 s |

- **Without the flag, one `execute` skipped every module.** `broken.test.compile` failed as the other test modules finished compiling, before any test task started. `execute` returned only after the 20 s input that nothing still needed had finished.
- **The copy kept going.** Its results matched `--keep-going` in every row. The outer `Execution`'s `failFast` read `true`. `a.test`, `b.test`, `failing.test` and `slowc.test` started their tests 1.1 s to 1.4 s after their own compile: 10.4 s for the modules that compiled at 9.0 s, while `slowc.test.compile` ran until 20.9 s. `late.test` started once its 15 s input had finished.
- **A test failure.** Warm, with `a.test failing.test late.test`: one `execute` without the flag gave `late.test` `Skipped` after `failing.test` failed; the copy gave `late.test` `Success`, its tests starting at 16.2 s.
- **Shared task.** `shared.counter`'s body ran once in every copy run.
- **Output.** Mill printed `broken.test.compile task failed` and `failing.test.testForked task failed`, as it does under `--keep-going` (`GroupExecution.executeGroup`; `GE`; `MCL` § 2.4).

### 3.3 What else the copy changes

- **What the copy shares.** A case-class copy shares every field but `failFast` with the command's `Execution`: the executor, `workerCache`, the profile logger, `baseLogger`, `exclusiveSystemStreams` and `getEvaluator` (`EX`). One `execute` on it plans every module together, so a shared task runs once, as in a single `execute`.
- **Final status.** `executionNestingDepth` is a field initialised in the class body, so the copy starts at 0 (`EX`). *Inferred:* the copy's call counts as outermost and sets the prompt header's final `SUCCESS` or `N FAILED` when it returns, before the command ends. Output redirected to files cannot show it.
- **`close()`.** `Execution.close` closes the profile logger, which the copy shares (`EX`). *Inferred:* closing the copy would close the command's profile logger.
- **Nested exclusive commands.** `getEvaluator` still returns the command's evaluator, so an exclusive command run by the copy gets fail-fast on (`GE`, `GroupExecution.wrap`).

## 4. Other routes

### 4.1 Re-executing the goals fail-fast skipped

`probe.rerun` called `execute` again on the goals whose result was `Skipped` and no task upstream of which had failed (`Evaluator.transitiveTasks`; `EV`). Cold, no flag:

| Round | Goals | Outcome | Returned |
|---|---|---|---|
| 1 | all six | all `Skipped`; `broken.test.compile` failed | 20.8 s |
| 2 | all but `broken.test` | `a.test`, `b.test` `Success`; `failing.test` `Failure`; `late.test`, `slowc.test` `Skipped` | 41.2 s |
| 3 | `late.test`, `slowc.test` | both `Success` | 64.2 s |

- Every module's tests ran, but each round's skipped modules waited for that round to end. `late.test`'s tests started at 57.0 s, against 16.4 s under `--keep-going`; `slowc.test`'s at 62.1 s, against 21.6 s.
- Rounds 2 and 3 each took more than 20 s. *Inference:* the 15 s and 20 s `Task.Input`s ran again in each round. Cached tasks were not rerun; `shared.counter` ran once.
- A failure in a later round skips that round's unstarted goals again; round 2's test failure skipped `late.test` and `slowc.test`.
- *Inferred:* a failed task's `meta.json` is removed (`GroupExecution.executeGroupCached`; `GE`), so a goal whose upstream failed reruns and fails again in every round it is included in. The probe left such goals out.
- APIs: `Evaluator.execute`, `Evaluator.transitiveTasks`, `Evaluator.Result.executionResults` (public, `mill.api`) and `mill.api.daemon.ExecResult`.

### 4.2 Turning a module's failure into a success value

- The flag is set when any task's result in the call is not a success, `ExecResult.Skipped` included (`Execution.execute0`; `EX`).
- A plugin task can return success when tests fail; for example, it can run the tests itself and return their results instead of failing. That removes the test-failure trigger observed in § 3.2.
- It cannot remove an upstream trigger. A task with an input that did not succeed is `Skipped` without running (`GroupExecution.executeGroup`; `GE`), and the failed input has already set the flag. In § 3.2 the trigger was `broken.test.compile`. Not run.

### 4.3 Task-graph shape

- Every terminal task in an `execute` checks the flag once its upstream futures complete, and records itself as skipped if it is set (`Execution.execute0`; `EX`). Non-exclusive and exclusive batches share the flag. Only tasks already started when the flag is set escape it (`EPS` § 2). No shape of one call's graph avoids this.

### 4.4 Public members and configuration

- `failFast` is set only by `MillBuildBootstrap.makeEvaluator0`, from `keepGoing` (`BOOT`), and by `mill.testkit.UnitTester`. No member of `Evaluator`, `EvaluatorProxy`, `mill.api.internal` or `mill.api.daemon` reads or sets it. `withBaseLogger` and `withIsFinalDepth` copy the `Execution` with `failFast` unchanged (`EI`; `EX`).
- `keepGoing` is `bspMode || config.keepGoing.value` (`MAIN`): BSP always keeps going.
- `mill-opts` in the build header, `.mill-opts` or `.config/mill-opts` passes default options to Mill (`website/docs/modules/ROOT/pages/cli/build-header.adoc`). The launcher prepends them to the command line (`runner/launcher/src/mill/launcher/MillLauncherMain.scala`; `MillProcessLauncher.loadMillConfig`). `mill-opts: ["--keep-going"]` therefore turns fail-fast off for every Mill command in that project. A plugin cannot set it.

## 5. API marking and compatibility

The compatibility policy covers `mill.api`, `mill.util`, `mill.eval`, `mill.resolve`, `mill.scalalib`, `mill.scalajslib` and `mill.scalanativelib`; other packages "offer no compatibility guarantees" (`MCL` § 7). Mill's build runs MiMa only on modules extending `MillStableScalaModule` or `MillStableJavaModule` (`mill-build/src/millbuild/MillStableJavaModule.scala`). Among them are `core/api`, `core/api/daemon` and `libs/util`. `core/eval/package.mill` and `core/exec/package.mill` extend `MillPublishScalaModule`, so `mill.eval` is not MiMa-checked although the policy lists it.

| Route | Meets both conditions | API | Marker in `[mill]` |
|---|---|---|---|
| Concurrent `execute` (§ 2) | No: breaks the pool, races shared tasks | `Evaluator.execute` | Public, `mill.api`, MiMa-checked; evaluator commands are documented as experimental (`MCL` § 7) |
| Fail-fast-off copy (§ 3) | Yes | `EvaluatorProxy.delegate0` | Public `var`, `mill.api`, no annotation, MiMa-checked |
| | | `mill.eval.EvaluatorImpl` constructors | Public, `final class`, `mill.eval`: in the policy, not MiMa-checked; not on a build's compile class path |
| | | `EvaluatorImpl.execution` | `private val`; reached by reflection only |
| | | `mill.exec.Execution`, its constructor and `failFast` | Public case class, `mill.exec`: outside the policy, not MiMa-checked; not on a build's compile class path |
| Re-execute skipped goals (§ 4.1) | No: skipped modules wait for the round | `Evaluator.execute`, `transitiveTasks`, `Evaluator.Result` | Public, `mill.api`, MiMa-checked |
| | | `mill.api.daemon.ExecResult` | Public sealed trait, `mill.api.daemon`, MiMa-checked |
| Success value for a failure (§ 4.2) | No: upstream failures still trigger | — | — |
| `mill-opts: ["--keep-going"]` (§ 4.4) | Yes, but project configuration, not plugin code | build header | Documented |

- *Inferred:* the fail-fast-off copy fails at run time, not at compile time, if a later Mill renames `execution` or `failFast`, changes `Execution`'s constructor, or stops making `Execution` a case class.

## 6. Against the earlier notes

- `MCL` § 8 left open whether several `execute` calls can run concurrently from one exclusive command. They can be made, but they break Mill's pool at the default `--jobs` and run shared tasks concurrently (§ 2).
- `MCL` § 2.4 and `EPS` § 2 held: without `--keep-going`, one `execute` skipped every task not yet started when a task failed (§ 3.2).

## 7. What was not run or not determined

- Linux, macOS, Scala.js and Native; terminals other than output redirected to files.
- The prompt effects inferred in § 2.1 and § 3.3.
- A wrapper task that turns a test failure into a success value (§ 4.2).
- Compiling a plugin against `mill-core-eval` and `mill-core-exec` artifacts instead of using reflection, and whether that loads a second copy of those classes.
- Setting `failFast` on the command's own `Execution` by reflection instead of copying it.
- Concurrent `execute` calls each on its own copy of the `Execution` with its own executor.
- Whether `Execution`'s constructor or fields changed between Mill versions; only `1.1.10` was read.
- The `--watch` and BSP paths, `--halt-on-failure`-style use of a fail-fast-on copy, and several Mill commands sharing one daemon.
