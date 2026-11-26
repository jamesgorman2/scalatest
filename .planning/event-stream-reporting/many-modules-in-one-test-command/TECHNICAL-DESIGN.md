# Many modules in one test command: technical design

## Summary

This design checks every selected JVM test module before any test starts, then runs each module's tests in its own anonymous task inside the test command's nested evaluation with fail-fast off (`../decisions/architecture-running-tests.md` "Inside the nested evaluation, each selected JVM test module's tests run in an anonymous task…"). Each module's tests must start once that module compiles, so nothing evaluated before the nested evaluation may wait on a compile. The tasks of the nested evaluation ran on Mill 1.3.1 (`../research/mill-nested-module-run.md` § 3, § 4, § 5). The check before the nested evaluation has not been run.

Bare `AC-nnn` IDs are `SPECIFICATION.md`'s. *The fail-fast-off evaluator* is the evaluator the test command builds over a copy of Mill's `Execution` with fail-fast off (`../decisions/architecture-running-tests.md` "The test command runs every selected module's tests in one nested evaluation…").

## Critical code paths

### The check before any test starts

- The test command resolves every module selector in its own body, because Mill gives a task that is not an exclusive command an `Evaluator` that throws.
- The test command then calls `execute` once on the fail-fast-off evaluator, over one anonymous *check task* for each selected JVM test module. A module's check task reads:
  - the module's `testArgsDefault`;
  - the Coursier resolution of the module's runtime dependencies: the module's `millResolver` resolving the module's `coursierDependencyTask` in the runtime configuration, as `resolvedRunMvnDeps` does. The resolution covers the dependencies of every module the module depends on, and fetches metadata only.
- A check task evaluates no compile. A module's tests therefore wait on every selected module's dependency resolution and `testArgsDefault`, but on no other module's compile (AC-007).
- A JVM test module tests with the release of this fork where its resolution holds `com.edc:scalatest-core_3` at the version the consolidation library was built at (AC-010, AC-011, AC-020).
- Once that `execute` returns, and before **Start**, the test command fails where any selected module is:
  - a Scala.js or Native test module;
  - a JVM test module that does not test with the release of this fork;
  - a JVM test module whose `testArgsDefault` holds an argument that `ModuleScalaTestArguments.kept` rejects.
- That failure names each such module and each rejected argument (AC-010, AC-011, AC-019). The test command fails before **Start**, so it writes no consolidated output.
- A module whose check task does not succeed, as where its dependencies fail to resolve, is a module whose tests did not start (AC-015). The fail-fast-off evaluator still runs every other module's check task. The test command builds no task in the nested evaluation for a module whose check task did not succeed, and adds that module to the consolidation after **Start** (§ A module whose tests cannot start).
- The test command builds each module's `RunArguments` in its own body, from the arguments `ModuleScalaTestArguments.kept` keeps from the module's `testArgsDefault` and from the framework arguments.

### One task per module

- The test command calls `execute` a second time on the fail-fast-off evaluator, over one anonymous task for each module whose check task succeeded (AC-005, AC-006, AC-015).
- The one upstream task of a module's anonymous task is the inputs task that `JvmTestModuleInputs` builds from the module's tasks. The anonymous task's body runs the module's `JvmTestModuleRun` with the module's `RunArguments`.
- The second `execute` is the first to evaluate any module's compile. Evaluating any module's compile before the second `execute` would hold each module's tests until every other module had compiled (AC-007).

### The module run as a task body

- The module run takes its anonymous task's `Task.ctx()` as its command context, in place of the command's. `ModuleFolderTaskCtx` still wraps that context.
- The anonymous task's body calls the module run inside `BuildCtx.withFilesystemCheckerDisabled`. The block is inside the anonymous task's body, not around `execute`:
  - Mill runs a task that is not a command under a filesystem checker. The checker refuses writes outside the task's own `dest`. An anonymous task has no `dest`, and the module folder lies beside the command's `Task.dest`.
  - `fork.async` carries into each forked body the checker setting in force where `fork.async` is called, so the workers forked inside the block also run with the checker off.

### A module whose tests cannot start

- Where a task upstream of a module's anonymous task fails, Mill marks the anonymous task `Skipped` and never runs its body. The module run therefore never adds such a module to the consolidation.
- After the second `execute` returns and before **End**, the test command reads each module's anonymous task's result from the evaluation's results:
  - For a module whose anonymous task is `Skipped`, the test command calls **Add a module whose tests did not start** (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library) with one failure (AC-006, AC-015). That failure's message holds one line for each failed task upstream of the anonymous task, in order of task name, naming the task and its error. The evaluation's results hold each failed upstream task's failure under that task's name.
  - For a module whose anonymous task ends in `Failure` or `Exception`, as where `ZincOp.GetTestTasks` throws, the test command attaches a failure naming the error to that module (**Attach a failure to a module**). The consolidation state is then failed, so the test command fails.
- For a module whose check task did not succeed, the test command calls **Add a module whose tests did not start** after **Start**, with one failure. That failure's message is built as for a `Skipped` anonymous task, from each failed task among the check task and the tasks upstream of the check task. The check task itself fails where the module's resolution fails, since the check task resolves the module's dependencies in its own body.
