# Many modules in one test command: technical design

## Summary

The test command runs every selected JVM test module's tests in one nested evaluation with fail-fast off (`../ARCHITECTURE.md` § Data flow). This design places each module's run inside that evaluation. Each module's tests must start once that module compiles and run whatever happens to another module's compile, while no worker's output reaches the console. Every choice below ran on Mill 1.3.1 (`../research/mill-nested-module-run.md` § 3, § 4, § 5).

Bare `AC-nnn` IDs are `SPECIFICATION.md`'s.

## Critical code paths

### One task per module

- Each module's own task (`../ARCHITECTURE.md` § Data flow) is a `Task.Anon`. Its one upstream task is the inputs task that `JvmTestModuleInputs` builds from the module's own tasks. Its body is the plugin's module run, `JvmTestModuleRun`.
- The test command calls `execute` once, over every module's task, on the fail-fast-off evaluator (AC-005, AC-006, AC-015).
- No separate `execute` evaluates a module's inputs before the module runs. The command's own evaluator has fail-fast on. Evaluating every module's inputs before any module run would also hold each module's tests until every other module had compiled (AC-007).
- Module resolution, the clearing of each module folder and the consolidation's **Start** stay in the command's body, before `execute`. A module's task cannot do them, because Mill gives a task that is not an exclusive command an `Evaluator` that throws.
- Every module's task adds its runs to the one consolidation, each from a thread of Mill's pool.

### The module run as a task body

- The module run takes its task's own `Task.ctx()` as its command context, in place of the command's. `ModuleFolderTaskCtx` still wraps that context.
  - The module run's forked workers therefore go to the evaluation's pool, which uses the command's executor and `jobs`.
  - A worker that Mill runs on the calling thread runs on the module's task's pool thread, not on the command's thread.
- The module's task calls the module run inside `BuildCtx.withFilesystemCheckerDisabled`. That block must be inside the task's body, not around `execute`:
  - Mill runs a task that is not a command under a filesystem checker. The checker refuses writes outside the task's own `dest`, and an anonymous task has no `dest`. The module folder lies under the command's `Task.dest`.
  - `fork.async` carries the checker in force where it is called into each forked body, so the workers forked inside the block also run with the checker off.
- Process capture is unchanged (`../ARCHITECTURE.md` § Modules § Mill plugin, Process capture). `WorkerTaskCtx` sets `os.Inherit.out` and `os.Inherit.err` inside each worker's body, after Mill binds the task's own streams, so its values win on the forked path and on the calling-thread path.

### A module whose tests cannot start

- Where a task upstream of a module's task fails, Mill marks the module's task `Skipped` and never runs its body. Such a module therefore never reaches the consolidation through its module run.
- After `execute` returns and before **End**, the test command reads each module's task's result from the evaluation's results. For each module whose task is `Skipped`, the test command calls **Add a module whose tests did not start** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library). It gives that call the failure of each failed task upstream of the module's task, which the evaluation's results hold under that task's name (AC-006, AC-015).
