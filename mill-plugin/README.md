# ScalaTest's Mill plugin

The plugin `com.edc:scalatest-mill_mill1_3` adds the test command `mill scalatest/test`. It runs the tests of one or more JVM test modules and writes their consolidated human-readable output to stdout and, on request, their consolidated JSON output to a file.

Each plugin release is a release of this fork. The plugin, the fork's ScalaTest artifacts and the consolidation library share one version, `3.2.20`.

## What it supports

- Mill 1.3.1 only.
- One or more JVM test modules per command, each testing with this fork at the plugin's release.
- Each module's tests run in the workers that Mill's `testForked` starts for each fork group that holds a test class to run, or that `testOnly` starts for each such group where class globs are given (§ Running the test command).
  - A module that does not set `testBatchFrameworkTasks` runs through Mill's test queue.
    - With `testParallelism` on (the default), Mill starts a fork group's first worker. It starts each further worker only where a test class remains in the group's queue, up to the lesser of Mill's `--jobs` and the number of the group's test classes to run. The group's workers run in parallel.
    - With `testParallelism` off, each fork group gets one worker, and the workers run one after another.
  - A module that sets `testBatchFrameworkTasks` is given no queue of its classes. Each fork group gets one worker, whatever the module's `testParallelism`. The worker is given the group's class list as `testForkGrouping` gives it, or, where class globs are given, the classes in that list that a class glob matches. Where more than one fork group holds a class to run, the workers run in parallel.
  - A module that does not set `testForkGrouping` has one fork group. Where no class glob is given, a module with no test class still runs one worker.
- Test modules on Scala 3.3 or later. The fork is built with Scala 3.3.8, so a test module on Scala 2, 3.1 or 3.2 cannot use it.
- Human-readable output on stdout, and JSON output to a file in the format [JSON.md](JSON.md) gives. The only output arguments are `-oH`, a bare `-o` and `-fJ <path>`.

## Publishing

Every artifact is published only to the local Ivy repository, `~/.ivy2/local`. Publish in this order.

1. At the repository root, publish the fork and the consolidation library with sbt:

   ```
   sbt scalatestCompatible/publishLocal scalacticDotty/publishLocal scalatestDotty/publishLocal
   ```

   On Windows, run `sbt.bat`. `scalatestDotty` publishes core, the style artifacts and the consolidation library. The sbt build publishes no Scala 2 artifacts.

2. In `mill-plugin/`, publish the plugin with its Mill build:

   ```
   ./mill --no-daemon plugin.publishLocal
   ```

   On Windows, replace `./mill` with `./mill.bat` in GitBash, `.\mill.bat` in PowerShell, or `mill.bat` in cmd. This applies to every `./mill` command below.

   The bootstrap scripts run Mill 1.3.1, as pinned in `mill-plugin/.mill-version`.

## Testing the plugin

Publish the fork and the library first (§ Publishing, step 1). Then, in `mill-plugin/`:

```
./mill --no-daemon plugin.test.testForked + plugin.integration.testForked
```

`plugin.test` holds the unit tests. `plugin.integration` runs Mill 1.3.1 on each fixture build under `plugin/integration/resources/`. It publishes the plugin to its own test repository, so it needs no `plugin.publishLocal`.

### Running one area's integration tests

Every integration scenario carries the tag of each plugin area it tests. The tags are in `org.scalatest.mill.tags`. A Java annotation tags a whole suite; `AreaTag` or `CoreTag` tags one scenario.

The core set holds every scenario tagged `Core`: one for each stage of the test command, end to end, and `EveryScenarioHasAnAreaTagSpec`, which fails when a scenario carries no area tag. `test-all.sh`, `test-all.ps1` and CI run every scenario.

For a change to one area, run the unit tests, the core set and that area's tag. Each `-n` adds a tag's scenarios to the run:

```
./mill --no-daemon plugin.test.testForked + plugin.integration.testForked -n org.scalatest.mill.tags.Core -n org.scalatest.mill.tags.Workers
```

The core set alone:

```
./mill --no-daemon plugin.integration.testForked -n org.scalatest.mill.tags.Core
```

A change under `dotty/` also needs these steps, at the repository root:

- For a change under `dotty/consolidation/`, run the library's tests: `sbt scalatestConsolidation/test`.
- Before any plugin test, republish the fork and the library (§ Publishing, step 1). The plugin and the fixture builds take them from `~/.ivy2/local`, so without this step they test the old versions.

Run a tag when one of its files changes. Paths are from the repository root. A name without a path is in `mill-plugin/plugin/src/org/scalatest/mill/`, or, where marked "in the library", in `dotty/consolidation/src/main/scala/org/scalatest/consolidation/`.

| Tags | Files |
|---|---|
| `Arguments` | `TestCommandArguments`, `AllowedFlag`, `FrameworkArguments`, `ClassGlob`, `StdoutContent`, `RunArguments`, `ModuleScalaTestArguments`, `ModuleSelectorArgument`; `ScalaTestArgument` in the library |
| `Modules` | `ModuleSelection`, `SelectedTestModule`, `ModuleCheck`, `ModuleCheckTask`, `CannotRunReason`, `ResolvedDependency`, `Organization`, `ArtifactName`, `LibraryVersion`, `FailFastOffEvaluator`, `EvaluatedTasks`, `FailedTask`, `FailedModuleCheck`, `FirstFailure`, `TaskName` |
| `Workers` | `JvmTestModuleInputs`, `WorkerLayout`, `ForkGroupLabel`, `PaddedIndex`, `PathUnderModule`, `WorkerRun`, `WorkerProcess`, `ProcessExit`, `DelegatingTaskCtx`, `ModuleFolder`, `ModuleFolderTaskCtx` |
| `Capture` | `OutputCapture`, `LineSplitter`, `StreamLines`, `EventLogReader`, `EventLogFile`, `EventLogLines`, `WorkerTaskCtx` |
| `HumanReadable` | `HumanReadableOutput` and `ConsolidationState` in the library |
| `Json` | `JsonOutputFile`; `JsonOutput` and `JsonOutputWriter` in the library; `mill-plugin/consolidated.schema.json` |
| `MillCoexistence` | `TestCommandModule`, other than the `test` command's name and parameters; the `plugin` module's dependencies and publishing settings in `mill-plugin/build.mill`; the plugin header of each fixture build |
| `Arguments`, `Modules`, `Json` | `TestCommandFailure` |

`TestCommand.scala` and `JvmTestModuleRun.scala` change in most tasks, so they are mapped by member:

| File | Member | Tags |
|---|---|---|
| `TestCommand.scala` | `run` | `Arguments`, `Modules`, `Json` |
| | `checkAndRunTests`, `addModuleFailures` | `Modules` |
| | `consolidateSelectedModules` | `Json`, `HumanReadable`, `Capture` |
| | `moduleRunTask`, `clearedModuleFolder` | `Workers` |
| `JvmTestModuleRun.scala` | `execute`, `layOutWorkers`, `runWorkers`, `processEndedWith`, `resultLog`, `inModuleFolder`, `WorkerLauncher`, `QueueLauncher`, `BatchLauncher`, `BatchSelector`, `filesystemIgnoresCase`, `launcherOrReportFailure`, `layOutForkGroups` | `Workers` |
| | `discoveredTestClasses`, `attachingDiscoveryOutput`, `startWorker`, `eventLogFileName` | `Capture` |
| | `reportNoTestResultForClassGlobs` | `Arguments` |

For a change to one of these, run the tags of every suite that uses it, directly or through a helper:

- an integration test helper in `mill-plugin/plugin/integration/src/org/scalatest/mill/`, such as `EveryWorkerFixture`, other than those the list below names;
- a fixture build under `mill-plugin/plugin/integration/resources/`, such as `every-worker`, other than those the list below names, except a change to its plugin header.

Run every test (`plugin.test.testForked + plugin.integration.testForked`) when any of these change:

- in `TestCommand.scala`, anything that cuts across members, such as the class's constructor parameters or a member's signature that other members or `TestCommandModule` call;
- the name or parameters of the `test` command in `TestCommandModule`, because every scenario runs it;
- `EnglishList`, or the alias in `mill-plugin/plugin/src/scalatest/package.scala`;
- any library file the tables do not name, such as `Consolidation` or `TreeAssembler`;
- the event-log format: `dotty/core/src/main/scala/org/scalatest/tools/TestEventLogEmitter.scala` and `dotty/core/src/main/scala/org/scalatest/events/TestEventLog*.scala`;
- in `mill-plugin/`: `.mill-version`, `mill`, `mill.bat`, and, in `build.mill`, the `Versions` object and the `plugin.test` and `plugin.integration` modules;
- the shared integration test helpers: `FixtureBuilds`, `FixtureBuild`, `FixtureName`, `SharedFixtureBuilds`, `SingleModuleFixture`, `SeveralModulesFixture`, `WorkspaceReset`, `TestCommandResult`, `JsonNode`, `JsonPattern`, `JsonOutputFixture`, `Durations`, `IntegrationEnvironment` and `MillConsole`;
- the fixture builds that those helpers read: `arguments`, `class-globs-and-framework-arguments`, `json-output` and `several-modules`;
- the tags in `mill-plugin/plugin/integration/src/org/scalatest/mill/tags/`, because a wrong tag drops scenarios from a `-n` run;
- the JSON output's keys or shape, because scenarios of every area read the `-fJ` output;
- how the human-readable output is rendered, beyond one kind of line.

## Adding the plugin to a build

The build runs Mill 1.3.1. Add the plugin to the header of its `build.mill`:

```
//| mvnDeps: ["com.edc::scalatest-mill::3.2.20"]
```

If the header already has `mvnDeps`, add `"com.edc::scalatest-mill::3.2.20"` to it.

A test module run by the test command must be built with Scala 3.3 or later and depend on this fork's `scalatest` at the plugin's release, directly or through a module or library it depends on:

```scala
//| mvnDeps: ["com.edc::scalatest-mill::3.2.20"]
package build

import mill.*
import mill.scalalib.*

object core extends ScalaModule {
  def scalaVersion = "3.3.8"

  object test extends ScalaTests, TestModule.ScalaTest {
    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")
  }
}
```

Nothing else in the build names the plugin. Mill's own test tasks, such as `mill core.test`, behave as they do without it.

## Running the test command

```
mill scalatest/test <module selectors> [-oH | -o] [-fJ <path>] [-- [<class globs>] [-- <framework arguments>]]
```

For example, `mill scalatest/test core.test util.test`.

- **Module selectors**: one or more, each a separate argument before the first `--`, in any order among the output arguments. `+` cannot join them, as Mill takes `+` to start its next goal.
  - Each resolves to the modules it names, such as `__.test`, and keeps only the test modules among them, so `__` selects every test module. Each test module it keeps must be a JVM test module.
  - A test module that several module selectors resolve to runs its tests once.
  - The same class globs and framework arguments apply to every selected module.
  - Every selected module's tests run, whatever happens to the other modules' tests, whether or not Mill's `--keep-going` is given.
  - A module's tests start once every selected module's dependencies have resolved and that module has compiled. They do not wait for another selected module to compile.
- **`-oH`, or no output argument**: stdout holds the human-readable output.
- **`-o`**: stdout holds no consolidated output. The tests still run and decide the result.
- **`-fJ <path>`**: once every run has ended, the file at `<path>` holds the JSON output ([JSON.md](JSON.md)), whatever the tests' outcomes. A relative path resolves against the workspace root, the folder holding `build.mill`. Missing parent folders are created. Once the arguments are accepted, any file already at `<path>` is deleted, so a file there is always the JSON output of the latest test command that wrote one.
- **`--`**: the arguments after it are read as `mill <module>.testOnly` reads its arguments: class globs, then framework arguments after a second `--`. For example, `mill scalatest/test core.test -- -- -n samples.Fast`. The test command accepts a framework argument only in one of these forms:
  - `-D` with a config map entry joined to it, such as `-Dcolour=blue`;
  - `-P` with a thread count of 1 or more joined to it, such as `-P4`;
  - `-n`, `-l`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W`, followed by the values ScalaTest's runner takes with it;
  - one of the values that follow such a flag, such as `samples.Fast` after `-n`.

  Each selected module runs as one of Mill's own test tasks runs it, except for the arguments each run receives (below):

  - with class globs, as `mill <module>.testOnly <class globs> -- <framework arguments>`;
  - with none, as `mill <module>.testForked <framework arguments>`.

  Where class globs are given, only the test classes whose fully qualified name matches at least one of them run. A class glob without `*` matches only the name equal to it, and each `*` matches any run of characters, including none. For example, `mill scalatest/test core.test -- 'samples.Alpha*'`.

  Every run, with or without class globs, receives the module's own ScalaTest arguments that reach the run (below), then the framework arguments in the order given. For example, `mill scalatest/test core.test -- 'samples.Alpha*' -- -z slow` runs only the tests whose names contain `slow` in the classes matching `samples.Alpha*`. A framework argument therefore takes effect over a module argument wherever ScalaTest takes the later of two arguments, as for two `-D` entries with one key. ScalaTest's runner takes the setting of `-P`, `-F`, `-T`, `-S` and `-W` from one argument only, so a framework argument with one of these flags replaces each module argument with that flag, whereas Mill's `testOnly` and `testForked` pass both on.

The module's own ScalaTest arguments (`testArgsDefault`) reach the run only if they are one of these flags, with their values: `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`.
Every other flag, including every reporter flag, is withheld.

Each worker receives the environment that a worker of Mill's own `testOnly` or `testForked` receives when that task starts from the test command's `mill` command line:

- where the module's `propagateEnv` is on, the environment `mill` started in, overlaid by the module's `allForkEnv`, which by default holds its `forkEnv` and the variables Mill adds, such as `MILL_WORKSPACE_ROOT`;
- where it is off, the module's `allForkEnv` alone.

Where `propagateEnv` is on, each worker receives the variables that the `mill` launcher set for the test command's own `mill` command line, not those of the command that started a running Mill daemon. For example, on Windows `mill.bat` sets `MILL_PARAMS` to that command line's arguments, omitting a leading launcher option such as `--no-daemon`.

The test command fails before any test starts, and writes no consolidated output, in the cases below. It leaves the file at the `-fJ` path as it was when:

- it is given any other output argument, such as `-oP`, `-oHd`, `-fH` or a second `-fJ`;
- it is given `-fJ` with no path;
- a framework argument has none of the forms the test command accepts, such as `-oD`, `-s`, `foo`, a third `--`, `-n` with no value after it, `-W` followed by anything but two integers, `-D` with no config map entry, or a bare `-P`;
- it is given one of `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W`, alone or with its value joined to it, before the first `--`, or anywhere if no `--` is given;
- a folder is at the `-fJ` path, or the file there cannot be deleted.

It leaves nothing at the `-fJ` path when:

- it is given no module selector;
- a module selector resolves to no module, or to no test module. The failure names the module selector;
- a selected test module is a Scala.js or Native test module;
- the dependencies Mill resolves for a selected test module hold no `com.edc:scalatest-core_3` at the plugin's release, as for a module testing with MUnit, uTest, upstream `org.scalatest:scalatest` or another release of this fork;
- a selected module's own ScalaTest arguments hold an argument ScalaTest's runner does not recognise.

The failure names each selected test module the test command cannot run, and each unrecognised argument. A selected module whose dependencies fail to resolve does not cause this failure: only its own tests do not start (below). Where the test command fails this way for another module, the failure also names, under `The tests of these selected test modules cannot start:`, each other selected test module whose tests are known not to start before any test starts, such as one whose dependencies fail to resolve, with the failure its module node would hold.

Where a goal before the test command on the same `mill` command line fails and Mill's `--keep-going` is not given, the test command does not run: it writes no consolidated output and leaves the file at the `-fJ` path as it was.

The test command also fails when:

- a test fails or is cancelled, a suite aborts, or a run aborts or is stopped;
- a test worker dies;
- the JSON output cannot be written to the `-fJ` path. The human-readable output is still written.

It also fails when one of the cases below happens to a selected module. The consolidated output is still written, and the module's node is aborted with a failure ([JSON.md](JSON.md) § Module). The other modules' tests still run.

- the module's dependencies fail to resolve, or a task that its tests depend on fails, such as its compile. The module's tests do not start. The failure holds the resolution's error, or names each failed task as Mill writes its name, with that task's error;
- Mill's test discovery for the module throws. The failure holds the error;
- class globs are given and none of them matches a test class of the module. No test of the module runs. The failure is the one Mill's `testOnly` gives: `Test selector does not match any test: <class globs>`, then `Run discoveredTestClasses to see available tests` on the next line;
- class globs are given, and once every test worker of the module has ended, no run of the module has reported a test as succeeded, failed, ignored, pending or cancelled, or a suite as aborted. This happens, for example, where `-- samples.Alpha1 -- -z nothing-matches` leaves no test of `samples.Alpha1` to run. The module's node holds its runs and the same failure. The failure is left out where laying out a fork group's files or starting or running a test worker of the module failed, or where a test worker's JVM ended without writing its result and the module has already failed, as where one of its runs aborted. The following do not count as such a report:
  - a test shown as ignored only because its suite's structure marks it ignored;
  - a suite or test shown as aborted only because it has no end event, such as one with reason *not run*;
- laying out a fork group's files fails, or starting or running a test worker fails;
- the test command fails to capture a test worker's output, which goes to Mill's console instead;
- a test worker's test event log cannot be read, or holds a line that cannot be decoded;
- two of the module's fork groups would share a `processFolder`, as where each runs only the same test class or, on a case-insensitive filesystem, only classes whose names differ only in case. No test of the module runs;
- two or more of the module's fork groups hold a test class to run, and the test command cannot tell whether the filesystem is case-insensitive. No test of the module runs.

Unless the JSON output cannot be written, where the test command fails once its tests have run, its failure names only the selected modules that failed, such as `The tests of alpha.test and broken.test failed`.

Under the test command, ScalaTest prints no end-of-run summary of its own. Instead, the human-readable output ends with one summary line that counts the suites and tests of every run. Mill's own test tasks still print ScalaTest's summary.

## The module folder

The test command writes each selected module's files in its module folder, `org.scalatest.mill.TestCommandModule/modules/<module>` in Mill's output folder (`out/` by default). `<module>` is the module's name as Mill writes it in a task name, such as `core.test`, or `core.3_3_8.test` for the `test` module of the cross module `core` at `3.3.8`.

- Each file and folder that Mill's `testOnly` or `testForked` would write in its task folder, such as each worker's working directory `sandbox`, is at the same relative path in the module folder. The exception is Mill's JUnit XML report: the test command writes none, whatever the module's `testReportXml`.
- Once it has resolved its module selectors and checked that it can run every selected test module, the test command deletes everything an earlier test command left in each selected module's folder. It does so before any test starts, including for a module whose tests do not start, as where its compile fails or no class glob matches.
- A test command that fails on its arguments (such as a framework argument it does not accept), on a module selector, or on a selected test module it cannot run leaves every module folder as it was.
- It leaves Mill's own `testForked` and `testOnly` task folders as they were.

## Runs in the human-readable output

A run's number is its `number` in [JSON.md](JSON.md) § Run node. The human-readable output shows a run's line, `Run <n>:`, only where the run:

- aborted or stopped;
- has output of its own: its worker's stdout or stderr, or a message sent outside any suite.

The output is laid out as:

- The suites of every run whose line is not shown form one list directly under the module's line, ordered by name. A suite that ran in several runs appears once for each, in order of the runs' numbers.
- Each run whose line is shown follows that list, in order of number, so `Run 10:` follows `Run 2:`. It holds its own suites, then its output, then `*** RUN ABORTED ***` or `*** RUN STOPPED ***` where the run aborted or stopped.
- The module's own output follows its runs, at the same indent as their lines: each line Mill's test discovery wrote for the module, prefixed with `[stdout] `, or with `[stderr] ` and the whole line in yellow.
- Where the module has failures, `*** MODULE ABORTED ***` comes last, in red, at the same indent. Each line of each failure follows it, in red, indented one level more.

For example, fork group 0 runs `Zeta`, whose test writes `hello` to stdout, and fork group 1 runs `Alpha`. Run 1 has output of its own, so its line is shown. Run 2's line is not. The output, without its summary line, is:

```
[core.test]
  Alpha:
    - t
  Run 1:
    Zeta:
      - t
    [stdout] hello
```

For example, in `mill scalatest/test alpha.test beta.test -- 'samples.Alpha*'`, the class glob matches no test class of `beta.test`. That module's part of the output is:

```
[beta.test]
  *** MODULE ABORTED ***
    Test selector does not match any test: samples.Alpha*
    Run discoveredTestClasses to see available tests
```
