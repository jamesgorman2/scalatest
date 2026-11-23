---
kind: mill-build-tooling
description: How Mill 1.x, its bootstrap scripts, testkit, BSP server and plugin API misbehave, and how to read a meta-build's classpath. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, driving Mill's BSP server, or inspecting a meta-build's classpath.
---

<learning>
date: 2026-11-14
---
On Windows, Mill's `sh` bootstrap script `mill`, run from GitBash, stops with `This native mill launcher supports only Linux and macOS.` Run `mill.bat` instead; GitBash starts it directly.
</learning>

<learning>
date: 2026-11-14
---
On Windows, `mill.testkit.IntegrationTester` (Mill 1.3.1) cannot start a Mill executable built by `JavaModule.assembly` (`out.jar`) or `JavaModule.launcher` (`run`). `launcher` writes an extensionless script, valid as both shell and batch; `assembly` writes a jar with such a script prepended. `CreateProcess` rejects both, and `os.call` fails with `CreateProcess error=193, %1 is not a valid Win32 application`.

In a separate task, copy the `launcher` script to a `.bat` file, and pass that path as the tester's `millExecutable`. Off Windows, pass the `launcher` script itself.
</learning>

<learning>
date: 2026-11-14
---
`mill --meta-level 1 show compileClasspath` lists the meta-build's classpath, including the jars a `build.mill` header's `//| mvnDeps` adds. Use it to check that a plugin and its dependencies reached the meta-build.

`show` prints each `PathRef` as `ref:v0:<hash>:<path>` or `qref:v1:<hash>:<path>`; on Windows the path holds a drive colon. Read the output with `upickle.default.read[Seq[mill.api.PathRef]]`, never by splitting on `:`.

A Maven-layout repository, such as a `publishLocalTestRepo` folder, stores a jar under one folder per group part (`com/edc/…`); the local Ivy repository, under one folder for the whole group (`com.edc/…`).
</learning>

<learning>
date: 2026-11-14
---
In Mill 1.3.1, a `Task.Command` taking a `mill.api.Evaluator` parameter, declared outside Mill's own sources, fails `Discover[this.type]` with `No mainargs.TokensReader[mill.api.Evaluator] found for parameter ...`. Mill's own modules get the reader through `import mill.{Task, given}` or `mill.util.TokenReaders.*`.

Import `mill.util.TokenReaders.millEvaluatorTokenReader` in the file declaring the command.
</learning>


<learning>
date: 2026-11-14
---
A Scala 3 file declaring `package <name>` and that package's package object, such as one extending `mill.api.ExternalModule.Alias`, fails to compile with `Cyclic reference involving val <import>` when the file has any import: the package object's members are in scope where the file's imports resolve.

Write no import in that file; name each type from `_root_`, such as `_root_.mill.api.ExternalModule.Alias`.
</learning>

<learning>
date: 2026-11-14
---
`mill.testkit.IntegrationTester.EvalResult`'s `out` and `err` (Mill 1.3.1) strip every ANSI escape sequence and trim the text, so a test of colour that reads them sees none, even under `--color true`.

Read the process's own output, `evalResult.result.out.text()` and `evalResult.result.err.text()`, to test colour.
</learning>

<learning>
date: 2026-11-14
---
A Mill module's `compileMvnDeps` reach neither its runtime classpath nor its `ScalaTests` test module's classpath. A Mill plugin that depends on Mill through `compileMvnDeps` therefore has no Mill or os-lib classes in its unit tests: a test naming `os.*` fails with `Not found: os`.

Keep classes under unit test free of Mill and os-lib types, and do the Mill wiring, such as setting `os.Inherit.out`, in the class that calls Mill. Test that wiring under testkit.
</learning>

<learning>
date: 2026-11-16
---
Mill 1.3.1's BSP server answers every `buildTarget/test` request with the JSON-RPC error `Internal error.`, logging `scala.MatchError: (of class build_.package_$)` from `mill.bsp.worker.MillBspEndpoints.buildTargetTest`. It maps the evaluators' root modules with a partial `case m: BspModuleApi`, and a build's root module (`package build`) is not one. It would also test only targets that are root modules. No test runs.

A test of `buildTarget/test` on Mill 1.3.1 can only compare the error response. Catch the `ExecutionException` whose cause is `org.eclipse.lsp4j.jsonrpc.ResponseErrorException` and record its `getResponseError`.
</learning>

<learning>
date: 2026-11-16
---
To drive Mill 1.3.1's BSP server from a test:

- Spawn `<mill executable> --bsp --jobs 1` in the workspace. This is the argv `--bsp-install` writes to `.bsp/mill-bsp.json`.
- Connect with `ch.epfl.scala:bsp4j:2.2.0-M2`, the version Mill's BSP worker is built with.
- Give lsp4j's `Launcher.Builder` a Scala trait extending `BuildServer`, `ScalaBuildServer` and `JvmBuildServer` as the remote interface.
- Send `build/initialize`, then `build/initialized`, before any request, and `build/shutdown`, then `build/exit`, at the end.

`--bsp` without `--no-daemon` runs through the regular Mill daemon and shares the regular `out/` folder. A test that compares two sessions must stop the daemon between them (`IntegrationTester.removeProcessIdFile()`, with the tester in daemon mode) and remove the module's `out/` folder, or the second session finds the first one's compiled classes and sends no compile notifications.

`buildTarget/scalaTestClasses` is deprecated in bsp4j; calling it under `-Werror` needs `@nowarn("cat=deprecation")`.
</learning>

<learning>
date: 2026-11-17
---
With its ticker on, Mill 1.3.1 re-renders each line a task writes through `Task.log.streams.out` when it adds its prefix (such as `1] `):

- where Mill colours its console output, the prefix itself is coloured, as `ESC[34m1]ESC[39m `;
- an ANSI reset `ESC[0m` in the task's line comes out as `ESC[39m`;
- a reset directly followed by another colour is dropped: `ESC[36mAESC[0mESC[31mB` comes out as `ESC[36mAESC[31mB`; each character keeps its colour.

The task's own colours reach stdout under `--color false` too. `mill.testkit.IntegrationTester` runs with `--ticker false`, which hides these effects, so pass `--ticker true` to test what a user sees. A test comparing colours on prefixed lines must:

- allow colour sequences around the prefix;
- treat `39` as a foreground reset;
- for a line of several colours, remove each reset directly followed by a colour from both actual and expected before comparing.
</learning>

<learning>
date: 2026-11-18
---
`mill.testkit.IntegrationTester` (Mill 1.3.1) runs Mill with `--ticker false`. Without the ticker, Mill puts no prefix such as `1] ` on the lines a task writes, so a test of what a user sees at a console misses the prefix.

To test the prefix, pass `--ticker true` ahead of the task in the arguments to `eval`; Mill takes the later flag. Under the ticker:

- Mill may put the task's name after the prefix on the first line the task writes: a task `banner` printing `building banner` shows as `1] banner building banner`. Assert that a line holds a prefix and ends with the text, never that it is the prefix followed by the text.
- Mill writes to stderr, with the task's prefix, a line naming the task when it starts and again with its duration when it ends (`1] mymodule/mytask`, `1] mymodule/mytask 3s`).
- When checking which lines carry a task's prefix, allow those task-name lines on stderr by matching them exactly: the prefix and the task's name, optionally followed by a space and a duration. Matching on the prefix and task name alone also passes the task's own first line, which carries the task's name too.

`--ticker true` does not make Mill draw its progress prompt: without a terminal, Mill prints no prompt, and the only status line is the final one (`146/146, 1 FAILED] …`). `testForked` still writes its task's progress line naming the class it runs (`146] core.test.testForked Running Test Class samples.QueueFlatSpec`) to stderr, but never a count such as `1/1 completed`. A test can show the class-name progress line, but cannot assert that Mill's progress count is absent; observe it under a pseudo-terminal.
</learning>

<learning>
date: 2026-11-18
---
Mill 1.3.1, through its bootstrap scripts and its launcher (`mill.launcher.MillLauncherMain`), takes the current working directory as the workspace root and never searches upward for `build.mill`. Run from a folder below the workspace root, it creates an `out` folder there, finds no build, and fails to resolve any task or external module the build adds, with errors such as `Cannot resolve external module <name>/`. `mill.testkit.IntegrationTester.eval(..., cwd = <subfolder>)` behaves the same.

A requirement or test that runs `mill` from a folder below the workspace root cannot pass under Mill 1.3.1. Inside a task, resolve a relative path argument against `mill.api.BuildCtx.workspaceRoot`, which under Mill 1.3.1 is always the folder `mill` was run from.
</learning>

<learning>
date: 2026-11-18
---
Under Mill's `testForked`, several test classes run in each worker JVM, and the ScalaTest `Tests: succeeded N` and `Suites: completed N` summary lines can repeat a worker's running total after each class (successive lines read 9, 12, 15, 18, 18). Summing those lines over a run gives a total that changes from run to run with no source change (218, then 205, for the same tests).

`out/<module>/testForked.dest/test-report.xml` miscounts too. Some test cases are repeated, and which ones changes from run to run: the same 126 tests gave 132 entries in one run and 134 in the next. Its `classname` attribution also shifts between runs.

Count tests as the distinct `<testcase>` names in that report, and read failures, errors and skips from it. Never sum the summary lines.
</learning>

<learning>
date: 2026-11-19
---
In Mill 1.3.1, `mill.javalib.TestModuleUtil.runTestFuture(filteredClassCount, groupCount, groupFolder, testClassQueueFolder, groupName, maxProcessLength, paddedGroupIndex, processIndex)` is public, on the `@experimental` class, and starts one worker of Mill's own test queue:

- it chooses the worker's `processFolder` (`groupFolder / worker-<i>` where `testParallelism` is on and `filteredClassCount != 1`, else `groupFolder`), claims the worker's first class and calls `callTestRunnerSubprocess` on the same instance;
- in that parallel case it forks through `Task.fork.async` of the instance's constructor `TaskCtx`, with Mill's priorities; otherwise it runs the worker synchronously on the calling thread and returns a completed future;
- the future's third element is `None` where a worker other than index 0 found the queue empty and started no JVM.

A plugin that needs per-worker framework arguments or a per-worker `TaskCtx` can build one `TestModuleUtil` per worker and call `runTestFuture` on each, rather than copying Mill's scheduler. Lay out each group's queue with `prepareTestClassesFolder` first. `waitForFutures` and result handling are `private[mill]`: await every future yourself, and do not treat a worker that started no JVM as a process that died.
</learning>

<learning>
date: 2026-11-19
---
In Mill 1.3.1, a test module that sets `testBatchFrameworkTasks` runs through the `private` `TestModuleUtil.runTestBatchScheduler`, not through the test queue. It differs from the test queue as follows:

- it runs no `ZincOp.GetTestTasks` discovery: each fork group's worker is given the group's classes exactly as `testForkGrouping` gives them, including classes that do not exist;
- each fork group holding a class gets one worker, in the group's folder (no `worker-<i>` folder), with `Left(classList)` as the selector;
- the workers are forked through `Task.fork.async(groupFolder, groupLabel, "", priority = -1)` whenever more than one group holds a class, whatever `testParallelism` says; a single group runs on the calling thread;
- where no group holds a class, fork group 0 runs with an empty `test-classes` queue and `claim` folder (`Right((None, queue, claimFolder))`), because `Left(Nil)` would select every class;
- it writes `result.log` as `(0L, 0L)` before starting each worker.

A plugin that must start the same processes calls the public `callTestRunnerSubprocess` itself and reproduces these rules; it cannot reuse `runTestFuture`, which always drives the queue.
</learning>

<learning>
date: 2026-11-19
---
In Mill 1.3.1, a test worker's process folder is:

- `<task>.dest` with one fork group, or `<task>.dest/<group folder>` with several;
- plus `worker-<i>` where `testParallelism` is on and the module selects anything other than exactly one test class in total (zero classes included);
- never `worker-<i>` under `testBatchFrameworkTasks`.

Where `testSandboxWorkingDir` is true (the default), the worker JVM starts in `<process folder>/sandbox`.

A testkit `IntegrationTester` defaults `baseWorkspacePath` to `os.pwd`. Unless `MILL_TEST_SHARED_OUTPUT_DIR` is set, it puts each test build's workspace in a new `baseWorkspacePath/run-<n>` folder. With both defaults, an integration test run by an outer build's `testForked` gets the workspace `<outer process folder>/sandbox/run-<n>`. The test build's own workers run under that workspace's `out` folder by the same rules.

A path taken from the test build's processes, such as `user.dir`, can therefore hold a `worker-<i>` segment from the outer build, the test build, both or neither. Reading the first or last `worker-<i>` segment of the whole path can silently report the outer worker. Read the segment only from the part of the path after the test build's workspace path; if there is none, the worker ran in its group folder.
</learning>

<learning>
date: 2026-11-19
---
Under `testParallelism`, Mill 1.3.1's test queue varies between invocations on every platform. It may run a fork group's classes in one worker or split them across several, so which worker runs which class varies. On Windows it also sometimes runs one test class in two workers; both write a `CLAIM` for it in their `claim.log`. This race rarely shows when a scenario runs alone.

An integration test that compares output across invocations, or expects a fixed number of runs, must not depend on the queue. Mill's default is `testParallelism = true`, so set `def testParallelism = false` in the fixture unless parallel workers are what the test is about.
</learning>

<learning>
date: 2026-11-20
---
In Mill 1.3.1, `<module>.testForked <fully qualified class>` does not narrow the run: `testForked`'s arguments go to the test framework, and every discovered class still runs. To run only some classes, use `<module>.testOnly <class> [<class> ...]`.

`testOnly` writes its report to `out/<module>/testOnly.dest/test-report.xml`, not `testForked.dest`, so read results from there after a narrowed run.
</learning>

<learning>
date: 2026-11-22
---
`mill.testkit.IntegrationTester` (Mill 1.3.1) starts its Mill executable directly, not through the `mill.bat` bootstrap script. The test build's processes therefore never see the variables `mill.bat` sets, such as `MILL_PARAMS`: its arguments, less a leading `--no-daemon`, `-i`, `--bsp` or similar. Passing a copy of `mill.bat` as the tester's `millExecutable` does not fix this, as the tester adds `--ticker false`, which then reaches `MILL_PARAMS`.

To test what `mill.bat` sets, call `mill.bat` with `os.call` in the tester's workspace, with the tester's `millTestSuiteEnv` and:

- `MILL_VERSION` set to a version ending in `-jvm`, such as `1.3.1-jvm`, so that `mill.bat` runs `<download folder>\<MILL_VERSION>.bat`;
- `MILL_FINAL_DOWNLOAD_FOLDER` set to a folder holding a copy of the tester's Mill executable under that name, so that `mill.bat` runs it rather than downloading a release.

Pass `--no-daemon` first for a run without the daemon; `mill.bat` leaves it out of `MILL_PARAMS`. With the tester in daemon mode, the tester's `close()` also stops the daemon `mill.bat` started, as both use the workspace's `out/mill-daemon` folder.
</learning>

<learning>
date: 2026-11-22
---
Mill 1.3.1 writes a cross module's name with dots, not brackets, both in task names and in `Segments.render` (so `module.moduleSegments.render`). The `test` module of the cross module `core` at `3.3.8` is `core.3_3_8.test`, and its task is `core.3_3_8.test.testForked`. Each `.`, `/` and `:` in a cross value becomes `_`. Mill's command line still accepts `core[3.3.8].test`, and `Segments.renderBracketSyntax` gives that form.

A requirement, folder name or test that expects `core[3.3.8].test` as "the name Mill writes in a task name" is wrong under Mill 1.3.1. Check a cross name before writing it into a specification by running the build's checked-in launcher from the build's folder, such as `./mill.bat --no-daemon resolve 'core[3.3.8].test._'`.
</learning>

<learning>
date: 2026-11-23
---
In Mill 1.3.1, `testOnly` raises `Test selector does not match any test: <selectors>` in only two places of `mill.javalib.TestModuleUtil.runTests`: before any worker starts, where no class is left once its globs and test discovery have filtered the fork groups, and after its scheduler returns `Result.Success` with no `TestResult`. The second check is skipped whenever laying out a fork group fails or any worker gives no success:

- writing a fork group's `test-classes` queue with `prepareTestClassesFolder`, before its workers start, throws out of `runTests` where it fails;
- a worker that throws, as where writing its `testargs` or spawning its JVM fails, propagates the exception out of `runTests`;
- a worker whose JVM exits without writing `out.json`, as where it dies, gives `Result.Failure("Test reporting Failed: … does not exist")`, and `processTestResults` returns the joined failure.

A plugin that matches `testOnly`'s failures must decide for each of these cases whether it adds the no-match failure: `testOnly` adds it after none of them, only where every worker ended with a result and none held a test.
</learning>
