# Many modules in one test command: technical design

Bare `AC-nnn` IDs are `SPECIFICATION.md`'s. *The fail-fast-off evaluator* is the evaluator the test command builds over a copy of Mill's `Execution` with fail-fast off (`../decisions/architecture-running-tests.md` "The test command runs every selected module's tests in one nested evaluation…").

## Summary

This design checks every selected JVM test module before any test starts, then runs each module's tests in its own anonymous task inside the test command's nested evaluation with fail-fast off (`../decisions/architecture-running-tests.md` "Inside the nested evaluation, each selected JVM test module's tests run in an anonymous task…"). No module's tests may wait on another selected module's compile, so nothing evaluated before the nested evaluation may wait on a compile. Experiments on Mill 1.3.1 confirm the nested evaluation's per-module tasks (`../research/mill-nested-module-run.md` § 3, § 4, § 5). The capture of what test discovery writes follows from Mill 1.3.1's source (`../research/mill-test-discovery-output.md`), and has not been tried on Mill 1.3.1. The AC-007 scenario confirms that on Mill 1.3.1 the check tasks, Coursier resolution included, evaluate no compile.

## Components

### The consolidation library's additions

`org.scalatest.consolidation.Consolidation` has a method for each of these operations of `../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, beside `addRootFailure` and `addRootOutput`:

- **Attach output or a failure to a module**, which also serves **Add a module whose tests did not start**: `addModuleFailure` and `addModuleOutput`. The library keeps each module's failures and output beside the module's runs, as it keeps the root's. As a result:
  - a module given a failure or output but no run appears in both outputs, ordered by name with the other modules however late it is added (AC-006, AC-015, AC-024);
  - the JSON writer writes a module's `outcome` and `failures` (AC-006, AC-009, AC-015, AC-022, AC-024) and its `output` (AC-025);
  - the human-readable writer writes a module's output lines and its `*** MODULE ABORTED ***` block after the module's runs, built as it builds the root's (AC-023, AC-026);
  - attaching a failure fails the consolidation state (`../SPECIFICATION.md` R-137).
- **Add a process that ended before any run existed in it**: `addProcessEndedBeforeAnyRun`, which takes the process's module.
- **Read whether a module has failed**: `hasFailed`, which the module run's no-match check reads in place of the consolidation state (`../SPECIFICATION.md` R-053a, R-098e).
- **Read the version the library was built at**: `Consolidation.scalaTestVersion`, which the check before any test starts compares with each module's resolved `com.edc:scalatest-core_3` (§ The check before any test starts).

## Critical code paths

### The check before any test starts

- The test command resolves every module selector in its own body, because Mill gives a task that is not an exclusive command an `Evaluator` that throws.
- The test command then calls `execute` once on the fail-fast-off evaluator, over one anonymous *check task* for each selected JVM test module. A module's check task reads:
  - the module's `testArgsDefault`;
  - the Coursier resolution of the module's runtime dependencies, which covers the dependencies of every module the module depends on:
    - The check task calls the module's `millResolver`'s `resolution` on the module's `coursierDependencyTask`, which names the runtime configuration itself. `resolution` fetches metadata only.
    - `resolvedRunMvnDeps` uses the same resolver and root dependency but calls `classpath`, whose parameters set the runtime configuration as the default configuration and add the Gradle runtime variant attributes. `resolution` takes the module's `resolutionParams` unchanged, so the check's resolution has neither.
- The test command holds each module's check result, its resolution included, once that `execute` returns.
- A check task evaluates no compile. A module's tests therefore wait on every selected module's dependency resolution and `testArgsDefault`, but on no other module's compile (AC-007).
- A JVM test module tests with the release of this fork where its resolution holds `com.edc:scalatest-core_3` at the version the consolidation library was built at (AC-010, AC-011, AC-020).
- Once that `execute` returns, and before **Start**, the test command fails where any selected module is:
  - a Scala.js or Native test module;
  - a JVM test module that does not test with the release of this fork;
  - a JVM test module whose `testArgsDefault` holds an argument that `ModuleScalaTestArguments.kept` rejects.
- That failure names each such module and each rejected argument (AC-010, AC-011, AC-019). The test command fails before **Start**, so it writes no consolidated output.
- A module whose check task does not succeed, as where its dependencies fail to resolve, is a module whose tests did not start (AC-015; § A module whose tests cannot start). The fail-fast-off evaluator still runs every other module's check task.
- The test command builds each module's `RunArguments` in its own body, from the arguments `ModuleScalaTestArguments.kept` keeps from the module's `testArgsDefault` and from the framework arguments.

### One task per module

- The test command calls `execute` a second time on the fail-fast-off evaluator, over one anonymous task for each module whose check task succeeded (AC-005, AC-006, AC-015).
- The one upstream task of a module's anonymous task is the inputs task that `JvmTestModuleInputs` builds from the module's tasks. The anonymous task's body runs the module's `JvmTestModuleRun` with the module's `RunArguments`.
- The second `execute` is the first to evaluate any module's compile. Evaluating any module's compile before the second `execute` would hold each module's tests until every other module had compiled (AC-007).

### The module run as a task body

- The module run takes its anonymous task's `Task.ctx()` as its task context, in place of the command's. `ModuleFolderTaskCtx` wraps that context and gives test discovery the module folder as its `dest`, as an anonymous task's context has none.
- Each worker's `TaskCtx` (`WorkerTaskCtx`) logs through the exclusive command's logger, not the anonymous task's. Mill's test queue names each test class a worker claims through the ticker of the worker's logger. The exclusive command's logger, unlike the anonymous task's, has no line on Mill's prompt, so the class names stay off the console (`.docs/learnings/mill-build-tooling.md`, 2026-11-26).
- The anonymous task's body calls the module run inside `BuildCtx.withFilesystemCheckerDisabled`. The block is inside the anonymous task's body, not around `execute`:
  - Mill runs a task that is not a command under a filesystem checker. The checker refuses writes outside the task's own `dest`. An anonymous task has no `dest`, and the module folder lies beside the command's `Task.dest`.
  - `fork.async` carries into each forked body the checker setting in force where `fork.async` is called, so the workers forked inside the block also run with the checker off.

### A module's failures and output

- The test command adds no module name to a failure it attaches to a module. Mill's own text, such as task names and `testOnly`'s message, stays as Mill writes it.
- The module run attaches each failure it raises to its module with **Attach output or a failure to a module**, not to the root (AC-009, AC-022, AC-024). These are the failures of `JvmTestModuleRun.launcherOrReportFailure`, `JvmTestModuleRun.layOutForkGroups`, `JvmTestModuleRun.reportNoTestResultForClassGlobs` and `WorkerRun.end`.
- The module run captures each line test discovery writes to stdout and stderr while `ZincOp.GetTestTasks` runs, and attaches it to the module with **Attach output or a failure to a module** (AC-025, AC-026). The capture follows `../research/mill-test-discovery-output.md` § 5.1:
  - The capture wraps the `jvmWorker.apply(ZincOp.GetTestTasks(...))` call in `JvmTestModuleRun.discoveredTestClasses`, on the module's anonymous task thread.
  - The capture binds `SystemStreams.current`, which receives discovery's writes where the module has no `javaHome`. The capture also binds `Console.out` and `Console.err`, which receive discovery's writes where the module has a `javaHome`.
  - The capture's sink is a `WorkerOutputCapture`. The capture binds `SystemStreams.current` and `Console` to one stdout `PrintStream` and one stderr `PrintStream` over that sink. Each `PrintStream` is built with Mill's JVM's `Charset.defaultCharset()`, and the `WorkerOutputCapture` decodes with that same charset.
  - Discovery's writes are encoded with that one charset, with or without `javaHome`. Without `javaHome`, Mill's proxy `System.out` and `System.err` encode with that charset before the bytes reach the bound streams. With `javaHome`, the bound `PrintStream` encodes each string that `RpcConsole` forwards (`../research/mill-test-discovery-output.md` § 2.1, § 2.2).
  - The capture does not use `WorkerOutputCapture.workerCharset`, which names `native.encoding`, the encoding of a worker JVM's piped streams. No discovery write passes through a worker JVM's piped stream.
  - Each binding holds on the thread that made it and on every thread created during the call, since a `DynamicVariable` is inheritable. A binding does not hold on a thread that existed before the call. Each module's discovery runs on its own anonymous task thread, so the lines of modules discovered at the same time stay apart.
  - The capture does not use `SystemStreamsUtils.withStreams`. That method also binds `os.Inherit`, so a JVM worker subprocess that the call starts would send its stderr into the capture for the rest of the subprocess's life.
  - In a `finally`, the capture ends its streams, attaches what it holds to the module, and then stops accepting lines, as `WorkerOutputCapture.stopCapturing` does. The module's discovery output therefore survives a `ZincOp.GetTestTasks` that throws. A thread created during the call that writes after the call ends adds nothing to the module.

### A module whose tests cannot start

- Where a task upstream of a module's anonymous task fails, Mill marks the anonymous task `Skipped` and never runs its body. The module run therefore never adds such a module to the consolidation.
- After the second `execute` returns and before **End**, the test command reads each module's anonymous task's result from the evaluation's results:
  - For a module whose anonymous task is `Skipped`, the test command calls **Add a module whose tests did not start** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) with one failure (AC-006, AC-015). That failure's message holds one line for each failed task upstream of the anonymous task, in order of task name, naming the task and its error. The evaluation's results hold each failed upstream task's failure under that task's name.
  - For a module whose anonymous task ends in `Failure` or `Exception`, the test command attaches a failure naming the error to that module with **Attach output or a failure to a module** (`../SPECIFICATION.md` R-072b, R-137).
  - A `ZincOp.GetTestTasks` that throws ends the anonymous task in `Exception`, with or without `javaHome`, even where the throwable is a static initializer's `ExceptionInInitializerError` (`../research/mill-test-discovery-output.md` § 3). The module run therefore catches nothing around discovery.
- For a module whose check task did not succeed, the test command calls **Add a module whose tests did not start** after **Start**, with one failure. That failure's message is built as for a `Skipped` anonymous task, from each failed task among the check task and the tasks upstream of the check task (AC-015).
  - The check task itself fails where the module's resolution fails, since the check task resolves the module's dependencies in its own body.
  - The check task has no task name, so the line for the check task's own failure names no task. That line is the resolution's error, as Coursier reports it.
- Until the task *Tasks that fail before a module's tests start* builds the above, the test command adds no such module to the consolidation. As a stop-gap, the test command fails with each failure that the first or the second `execute` reports, and also where the consolidation state is *ended, failed*. It joins these with Mill's `Result.Failure.join`, so Mill names each failed task and its error. Once that task adds such modules to the consolidation, the consolidation state alone decides the test command's result (`../decisions/architecture-library-and-modules.md` "The consolidation library, not the Mill plugin, decides whether the test command has failed.").
