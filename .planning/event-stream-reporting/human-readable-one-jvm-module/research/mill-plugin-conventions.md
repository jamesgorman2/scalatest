# Mill 1.x plugin conventions, and the Mill internals the plugin drives at 1.1.10 and the newest 1.x release

How Mill 1.x plugins are named, added to a build and integration-tested, and whether the newest Mill 1.x release keeps the internals the test command drives on the JVM (`.planning/event-stream-reporting/research/mill-runner-progress-without-trait.md` § 3, § 5.3, § 7). Answers [What are Mill 1.x plugin conventions, and does the latest 1.x keep the internals the plugin drives?](https://github.com/jamesgorman2/scalatest/issues/62), for [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61). Facts and their sources only; no option is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-runner-progress-without-trait.md`, cited as `MRP`: the `queue` route and its API marking (§ 3, § 7).
- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`.
- `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`: the exclusive command in an `ExternalModule` (§ 2.1) and Mill's compatibility policy (§ 7).
- `.planning/event-stream-reporting/research/mill-test-worker-args.md`, cited as `MTA`: grouping and the queue (§ 1.2, § 2.2).

## Short answer

- **Newest 1.x release.** As of 2026-11-11 the newest stable Mill 1.x release is `1.3.1` (2026-10-19). `1.4.0-M2` (2026-11-04) is a milestone, marked pre-release. There is no stable `1.2.x`: `1.2.0-RC1` is the only `1.2` tag (§ 1).
- **Artifact naming.** A Mill 1.x plugin's artifact ID is `<name>_mill1_3`: the platform suffix `_mill1`, then the Scala 3 binary suffix. Mill's own plugin example publishes `myplugin_mill1_3`, and every Mill 1.x third-party plugin checked on Maven Central uses that form. Those plugins depend on `mill-libs` or `mill-libs-scalalib` in `provided` scope, at `1.0.0` for four of the five checked (§ 2).
- **Loading a plugin.** A build loads a plugin as an ordinary dependency of its meta-build. That is `//| mvnDeps: ["<group>::<name>::<version>"]` in the `build.mill` header, where the second `::` adds `_mill$MILL_BIN_PLATFORM`, or `mvnDeps` on a `MillBuildRootModule` in `mill-build/build.mill`. The header form is shorthand for the same meta-build. A command in an `ExternalModule` the plugin ships is run as `mill <package>.<Object>/<command>` once the plugin is on that classpath, with nothing in the build's source referring to it (§ 3).
- **Testkit.** Integration tests run a Mill launcher built from `com.lihaoyi:mill-runner-launcher_3:<millVersion>` on a fixture build. They find the locally built plugin through `COURSIER_REPOSITORIES`, which holds the plugin's `publishLocalTestRepo` folder and then `ivy2Local|central`. Artifacts published by another build, such as sbt's `publishLocal`, are found through the same variable. The integration tests run the launcher's Mill version: a different `mill-version` in the fixture only draws a warning (§ 4).
- **Internals at `1.3.1` against `1.1.10`** (§ 5):
  - Unchanged: `ZincOp.GetTestTasks`, `TestRunnerUtils.globFilter`, `TestModuleUtil.prepareTestClassesFolder`, `TestModuleUtil.callTestRunnerSubprocess` and `JvmWorkerModule.internalWorker`, in signature, marking and visibility; `TestModuleUtil` is still a public `final class` marked `@experimental`.
  - Changed: `TestModuleUtil`'s constructor gains a trailing `@unroll` parameter, `testBatchFrameworkTasks: Boolean = false`.
  - Changed: Mill's queue scheduling, which the test command copies, now starts one worker per fork group when `testParallelism` is off.
  - Changed: a new public `TestModule.testBatchFrameworkTasks` runs each group's classes without the queue or claim files.
  - Changed: test discovery now calls the runner's `done()`.
  - Changed: Mill is built on Scala `3.9.0`, not `3.8.2`.
  - At `1.4.0-M2` the same six internals are unchanged from `1.3.1`, except that `callTestRunnerSubprocess` writes `testargs` inside `MappedRoots.withMapping(Seq())`.
- **Beyond the question,** `mill.exec.Execution`, which the test command copies (`.planning/event-stream-reporting/decisions/architecture-running-tests.md` "The test command runs every selected module's tests in one nested evaluation of its own…"), changed in 743 lines between `1.1.10` and `1.3.1`. From `1.3.0-M1` an exclusive command no longer takes the daemon-wide workspace lock (§ 6).

## What was read and what was run

- **Read:** the Mill source and docs at tags `1.1.10`, `1.3.1` and `1.4.0-M2`; Mill's GitHub releases and tags; directory listings, `maven-metadata.xml` and POMs on `repo1.maven.org`.
- **Run:**
  - `gh api` against the Mill repository.
  - `git fetch` and `git diff` of the three tags in `C:\Users\james\AppData\Local\Temp\claude\f2d2279f41cfddc6\16bd5d6a-086e-404b-a12b-7f281f4263d1\scratchpad\research-62\`.
  - `curl` against Maven Central.
- **Not run:** Mill itself. No plugin was built, published or loaded, and no integration test ran.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[1110]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[131]` | <https://github.com/com-lihaoyi/mill> | tag `1.3.1` (`e5e91a4fac1edbfa1c8c96a3cf1c5f474579132f`) |
| `[14M2]` | <https://github.com/com-lihaoyi/mill> | tag `1.4.0-M2` (`16880c4d00cf1ee7e02c8a7fe11171c0e99ee917`) |
| `[rel]` | `gh api repos/com-lihaoyi/mill/releases` and `.../tags` | read 2026-11-11 |
| `[central]` | <https://repo1.maven.org/maven2/> | read 2026-11-11 |

Paths are relative to the Mill repository root and hold at all three tags unless a tag is named. Abbreviations:

- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TRU` is `libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala`.
- `ZOP` is `libs/javalib/api/src/mill/javalib/api/internal/ZincOp.scala`.
- `JWM` is `libs/javalib/src/mill/javalib/JvmWorkerModule.scala`.
- `EX1` is `example/extending/plugins/1-writing-mill-plugins/build.mill`; `EX2` is `example/extending/plugins/2-plugin-integration-tests/build.mill` and its `myplugin/integration/` folder.
- `IMP` is `website/docs/modules/ROOT/pages/extending/import-mvn-plugins.adoc`.

A statement marked *inference* follows from the cited source and was not observed. A statement marked *general* is Scala or JVM behaviour no source here was opened for.

## 1. Mill 1.x releases

- Stable 1.x releases after `1.1.10` (2026-09-25): `1.3.0` (2026-10-10) and `1.3.1` (2026-10-19). Pre-releases: `1.3.0-M1`, `1.4.0-M1`, `1.4.0-M2` (2026-11-04). `1.2.0-RC1` (2026-06-17) has no final release, and its tag points at the same commit as `2.0.0-M1` (`[rel]`).
- `1.3.0-M1`'s changelog entry lists changes "since Mill 1.1.10", so the `1.3` line follows `1.1.10` directly (`[131]` `changelog.adoc` § 1.3.0-M1).
- `mill-libs_3`, `mill-testkit_3`, `mill-runner-launcher_3` and `mill-libs-javalib_3` are on Maven Central at `1.1.10`, `1.3.0`, `1.3.1` and `1.4.0-M2` (`[central]`).

## 2. Artifact naming

- **The suffix.**
  - `JavaModule.artifactId` is `artifactName() + artifactSuffix()`, and `JavaModule.artifactSuffix` is `platformSuffix()`, empty by default (`libs/javalib/src/mill/javalib/JavaModule.scala`).
  - `ScalaModule.artifactSuffix` is `s"${platformSuffix()}_${artifactScalaVersion()}"`, where `artifactScalaVersion` is the Scala binary version unless `crossFullScalaVersion` (`libs/scalalib/src/mill/scalalib/ScalaModule.scala`).
- **The platform.**
  - Mill's meta-build sets `platformSuffix` to `s"_mill${BuildInfo.millBinPlatform}"` (`runner/meta/src/mill/meta/BootstrapRootModule.scala`).
  - `millBinPlatform` is the major version, except on a `.0.0-M` or `.0.0-RC` tag, where it is the whole tag (`build.mill`, `def millBinPlatform`). For every 1.x release it is `1`.
- **Mill's example** (`EX1`, the same at `[1110]` and `[131]`):
  - It sets `def platformSuffix = s"_mill1"`, `millVersion = "1.0.6"`, `scalaVersion = "3.7.4"`, `jvmVersion = "11"` and `mvnDeps = Seq(mvn"com.lihaoyi::mill-libs:$millVersion")`.
  - `publishLocal` prints `Publishing com.lihaoyi:myplugin_mill1_3:0.0.2`.
  - It says: "Mill 1.x is _backwards compatible_ but not _forwards compatible_ with regard to plugins, so a plugin published for a particular version of Mill will work on _newer_ versions of Mill, but not _older_ versions. … it is usually best to publish for as old a 1.x version of Mill as you can". The plugin's own build may run a newer Mill than the one it compiles against.
- **Published third-party plugins** (`[central]` directory listings): `com.goyeau:mill-scalafix_mill1_3`, `mill-git_mill1_3`; `io.github.alexarchambault.mill:mill-native-image_mill1_3`; `com.github.lolgab:mill-mima_mill1_3`, `mill-scalablytyped_mill1_3`, `mill-crossplatform_mill1_3`; `io.stryker-mutator:mill-stryker4s_mill1_3`; `com.carlosedp:mill-aliases_mill1_3`; `de.tototec:de.tobiasroeser.mill.jacoco_mill1_3`; `ba.sake:mill-openapi4s_mill1_3`; `io.github.hoangmaihuy:mill-missinglink_mill1_3`.
  - The `1.0.0` release candidates had their own platforms, e.g. `mill-mima_mill1.0.0-RC1_3`. The `0.12` line used `_mill0.12_2.13`.
  - No `1.x` plugin checked uses a minor-version platform such as `_mill1.3`.
- **The Mill dependency of published plugins** (each latest POM, `[central]`):

  | Artifact | Version | Mill dependency |
  |---|---|---|
  | `mill-scalafix_mill1_3` | `0.6.2` | `mill-libs-scalalib_3:1.0.0`, `provided` |
  | `mill-native-image_mill1_3` | `0.2.7` | `mill-libs-scalalib_3:1.0.0`, `provided` |
  | `mill-mima_mill1_3` | `0.2.2` | `mill-libs_3:1.0.0`, `provided` |
  | `de.tobiasroeser.mill.jacoco_mill1_3` | `0.1.0-4-67008a` | `mill-libs_3:1.0.0`, `provided` |
  | `mill-stryker4s_mill1_3` | `1.1.1` | `mill-libs-scalalib_3:1.1.7`, `provided`; `mill-testkit_3:1.1.7`, `test` |

  `provided` leaves the Mill jars to the Mill that loads the plugin. Mill's own example uses `mvnDeps`, which would publish the dependency in `compile` scope.
- **A library with no Mill dependency** is an ordinary `ScalaModule` artifact with no platform suffix: `<name>_3` for Scala 3 (*inference* from `ScalaModule.artifactSuffix` with an empty `platformSuffix`).

## 3. How a build loads a plugin, and how a plugin's command is invoked

- **Build header.** "Mill plugins are ordinary JVM libraries jars and are loaded as any other external dependency with the `//| mvnDeps` mechanism" (`IMP` § Importing Plugins).
  - `"<group>::<plugin>::<version>"` is equivalent to `"<group>::<plugin>_mill$MILL_BIN_PLATFORM:<version>"`, and `:::` does the same for a full-Scala-version artifact (`IMP` § Use the specific Mill Binary Platform notation).
  - `$MILL_VERSION`, `$MILL_BIN_PLATFORM`, or an empty version (`org::name:`) are substituted with the running Mill's values (`runner/meta/src/mill/meta/MillIvy.scala`, `processMillMvnDepsignature`, `substituteMillVersion`).
  - Mill's integration-test fixture loads its plugin with `//| mvnDeps:` / `//| - com.lihaoyi::myplugin::0.0.1` (`EX2` `myplugin/integration/resources/integration-test-project/build.mill`).
- **The header is the meta-build's configuration.**
  - Header keys other than `mill-*` "are uniformly treated as overrides for tasks on the meta-build". `repositories` adds resolvers. In a `build.mill.yaml` the same keys sit under `mill-build:` (`website/docs/modules/ROOT/pages/cli/build-header.adoc`).
  - When evaluating meta-level `d`, Mill reads the header of the parent folder's `build.mill.yaml` or `build.mill` as static task overrides (`runner/daemon/src/mill/daemon/MillBuildBootstrap.scala`, `makeEvaluator`, `tryReadParent`).
- **`mill-build/`.**
  - "The meta-build lives in the `mill-build/` directory, and needs to contain a top-level module of type `MillBuildRootModule`. If you don't configure it explicitly, a built-in synthetic meta-build is used" (`website/docs/modules/ROOT/pages/extending/meta-build.adoc`).
  - Its `mill-build/build.mill` declares `def mvnDeps` like any module (`example/extending/metabuild/4-meta-build/mill-build/build.mill`).
  - "Mill plugins are defined as `mvnDeps` in the meta-build" (`meta-build.adoc` § Finding plugin updates).
  - `MillBuildRootModule` is `@internal` (`runner/meta/src/mill/meta/MillBuildRootModule.scala`).
  - Whether header `mvnDeps` and a `mill-build/build.mill` `def mvnDeps` combine, or one replaces the other, was not determined.
- **What the meta-build always gets.** `BootstrapRootModule.mandatoryMvnDeps` adds `mill-libs` and `mill-runner-autooverride-api` at the running Mill's version, and `scalaVersion` is Mill's own (`BootstrapRootModule.scala`). The running Mill's jars are therefore on the meta-build classpath whatever the plugin declares.
- **Commands in an `ExternalModule`.**
  - An `ExternalModule` must be a top-level object. Its segments are its fully qualified name followed by `/` (`core/api/src/mill/api/ExternalModule.scala`; at `[131]` and `[1110]` alike).
  - A selector `<prefix>/<task>` is resolved by loading class `<prefix>$` or `<prefix>.package$` from the root module's class loader. The class must be an `ExternalModule` or an `ExternalModule.Alias`, and anything else fails with "Cannot resolve external module" (`core/resolve/src/mill/resolve/Resolve.scala`, `resolveRootModule`).
  - The docs' form is `mill foo.Bar/qux` for `object Bar extends mill.api.ExternalModule` in package `foo`, with `lazy val millDiscover = Discover[this.type]` (`website/docs/modules/ROOT/pages/fundamentals/modules.adoc` § External Modules).
  - *Inference:* the plugin jar on the meta-build classpath is enough, and the build's source need not mention the module. `Resolve.resolveRootModule` reaches an external module only through an explicit `<prefix>/` selector. A wildcard such as `__.test`, resolved from the build's root module, does not walk into it.
- **Aliases.**
  - A `def` in `build.mill` returning an `ExternalModule` gives it a short name, run as `./mill myAutoformat` for `def myAutoformat = mill.javalib.palantirformat.PalantirFormatModule` (`example/fundamentals/modules/10-external-module-aliases/build.mill`; present at `[1110]`).
  - `ExternalModule.Alias` is a second, library-side form: a top-level object wrapping an existing `ExternalModule` (`ExternalModule.scala`).
  - Whether `__` wildcards walk into a `def` alias in `build.mill`, and so whether `mill __.test` would reach a `test` command under one, was not determined.
- **Exclusive commands.** From `1.3.0-M1`, `Task.Command(exclusive = true)` no longer takes the daemon-wide workspace lock, "so concurrent launchers may still run their own non-conflicting tasks while an exclusive command runs". A new `globalExclusive = true` takes it (`[131]` `core/api/src/mill/api/Task.scala`, `Command`; `[131]` `changelog.adoc` § 1.3.0-M1). An `Evaluator` parameter is available to both (`[131]` `core/api/src/mill/api/Evaluator.scala`).

## 4. Integration tests under `mill.testkit`

- **The plugin's own build** (`EX2`, the same at `[1110]` and `[131]` apart from `new IntegrationTester` becoming `IntegrationTester`):
  - `object integration extends ScalaTests, TestModule.Utest` depends on `com.lihaoyi::mill-testkit:$millVersion`.
  - A nested `object millExecutable extends JavaModule` depends on `com.lihaoyi:mill-runner-launcher_3:$millVersion` with `mainClass = Some("mill.launcher.MillLauncherMain")`.
  - `integration.forkEnv` sets `MILL_EXECUTABLE_PATH` to `millExecutable.assembly().path`. It also sets `COURSIER_REPOSITORIES` to the plugin's `publishLocalTestRepo` folder as a `file:` URI, followed by the inherited `COURSIER_REPOSITORIES` or, failing that, `ivy2Local|central`.
- **The test** builds `IntegrationTester(daemonMode, workspaceSourcePath = <resources>/integration-test-project, millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH")))`. It then calls `tester.eval(...)` and checks `isSuccess`, `out`, `err` and `tester.out("<task>")` (`EX2` `myplugin/integration/src/mill/testkit/IntegrationTests.scala`). `ExampleTester.run` instead runs the `/** Usage */` block in a fixture's `build.mill` (`.../ExampleTests.scala`).
- **`publishLocalTestRepo`** publishes this module's jar, sources jar and POM into a Maven-layout folder in its own `Task.dest`, and nothing else (`libs/javalib/src/mill/javalib/PublishModule.scala`, `publishLocalTestRepo`; present at `[1110]`).
- **Other local artifacts.**
  - The launcher reads `COURSIER_REPOSITORIES` as override repositories when it resolves the Mill daemon (`runner/launcher/src/mill/launcher/CoursierClient.scala`, `resolveMillDaemon`).
  - Mill's resolution reads its default repositories from `COURSIER_REPOSITORIES` (`libs/javalib/src/mill/javalib/CoursierConfigModule.scala`).
  - *Inference:* scalatest-core and the consolidation library, published by the sbt build to the local Ivy repository, resolve through `ivy2Local` in that variable. A Maven-layout folder resolves when it is added as another `file:` URI. Both apply to the fixture's meta-build and to its test modules' dependencies, since one Mill resolves both.
- **The Mill version the tests run.**
  - The launcher resolves the daemon at its own `BuildInfo.millVersion` (`CoursierClient.scala`).
  - The daemon only prints "Mill version … is different than configured for this directory!" when `.mill-version`, `.config/mill-version` or the `mill-version` header differs (`runner/daemon/src/mill/daemon/MillMain0.scala`, `checkMillVersionFromFile`).
  - *Inference:* each integration-test run covers exactly the launcher's Mill release. Testing on several releases needs a launcher per release, for example a cross module over `millVersion`.
- **The Mill JVM.** `default-mill-jvm-version` is `zulu:21` at `[1110]` and `zulu:25` at `[131]` and `[14M2]`.

## 5. The internals the plugin drives, `1.1.10` against `1.3.1`

`git diff 1.1.10 1.3.1` over `ZOP`, `libs/javalib/api/src/mill/javalib/api/internal/InternalJvmWorkerApi.scala`, `libs/javalib/testrunner/src/mill/javalib/testrunner/Model.scala` and `JWM` is empty. `TMU`, `TM` and `TRU` changed.

| Internal | `1.1.10` | `1.3.1` | `1.4.0-M2` |
|---|---|---|---|
| `TestModuleUtil` (`TMU`) | `@mill.api.daemon.experimental final class`; constructor ends `jvmWorker: InternalJvmWorkerApi, @com.lihaoyi.unroll discoveredClassesOpt: Option[Seq[(String, Int)]] = None)(using ctx: TaskCtx)` | same marking; constructor adds a last parameter `@com.lihaoyi.unroll testBatchFrameworkTasks: Boolean = false` | as `1.3.1` |
| `TestModuleUtil.prepareTestClassesFolder(selectors2: Seq[String], base: os.Path): os.Path` | public | unchanged signature; writes each queue file with `foreach` instead of `zipWithIndex.foreach`, same files | unchanged |
| `TestModuleUtil.callTestRunnerSubprocess(baseFolder, resultPath, selector: Either[Seq[String], (Option[String], os.Path, os.Path)], poll: () => Unit)(using TaskCtx)` | public | unchanged signature and body | unchanged signature; the `testargs` write is wrapped in `MappedRoots.withMapping(Seq())`, "so we only have local absolute paths" |
| `ZincOp.GetTestTasks(runCp, testCp, framework, selectors, args, discoveredClassesOpt)` (`ZOP`), `Response = Seq[String]` | public case class in package `mill.javalib.api.internal` | unchanged | unchanged |
| `TestRunnerUtils.globFilter(selectors: Seq[String]): String => Boolean` (`TRU`) | member of `@internal object TestRunnerUtils` | unchanged | unchanged |
| `JvmWorkerModule.internalWorker: Worker[InternalJvmWorkerApi]` (`JWM`) | `@internal def` | unchanged | unchanged; the worker's `ZincCompilerBridgeProvider` takes `logTicker = ctx.log.ticker` in place of `logInfo` |
| `TestResult` (`Model.scala`) | `@internal case class` | unchanged | unchanged |
| `TestModule.aheadOfTimeDiscoveredTestClassesIfNeeded`, `testTask`, `discoverTestsWithZinc` (`TM`) | `protected def` | unchanged | unchanged |
| `TestModule.handleResults` (`TM`) | public | unchanged | unchanged |

- **Binary compatibility of the constructor.**
  - *General:* `@com.lihaoyi.unroll` makes the compiler keep the earlier arities as forwarders, so bytecode compiled against `1.1.10`'s constructor still links on `1.3.1`.
  - *Inference:* a call built for `1.1.10` gets `testBatchFrameworkTasks = false` on `1.3.1`, which is `1.3.1`'s default path.
- **Mill's queue scheduling changed** (`TMU`, `runTestQueueScheduler`, `jobsProcessLength`; `TestModuleUtil.testSubprocessCount`, `private[mill]`).
  - At `1.1.10` each fork group gets `max(min(jobs, numTests), 1)` worker indexes whether `testParallelism` is on or off.
  - At `1.3.1` it gets `testSubprocessCount`: that same number when `testParallelism` is on and more than one class is filtered, otherwise `1`.
  - The public `jobsProcessLength(numTests)` became `jobsProcessLength(filteredClassCount, numTests)`.
  - The public `runTestFuture`'s second parameter changed from `groupFolderData: Seq[(Path, Path, Int)]` to `groupCount: Int`, and its group label now comes from a private `TestGroup.label`.
  - The `processFolder` rule is unchanged: `groupFolder / workerLabel` when `testParallelism && filteredClassCount != 1`, else `groupFolder` (`runTestFuture`).
  - `MRP`'s `queue` prototype copies the `1.1.10` count (`cappedJobs = math.max(math.min(ctx.jobs, numTests), 1)`, branch `prototype/no-trait-progress`, `build.mill`). *Inference:* with `testParallelism` off it can start more worker indexes in a group than `1.3.1`'s own task does. At `1.1.10` the extra indexes find the queue drained, except index 0, which spawns even when it claims nothing (`MTA` § 1.2), so how many JVMs start differs from `1.3.1` only where index 0 is not the one that claimed.
- **`testBatchFrameworkTasks`** (`TM`, a new public `T[Boolean]`, default `false`; `TMU`, `runTestBatchScheduler`).
  - When a module sets it, `runTests` skips `ZincOp.GetTestTasks` and runs one subprocess per fork group, with `Left(testClassList)` as the selector, so the group has no queue and no claim files. A group with no classes gets an empty queue folder and `claim/` folder.
  - The doc comment names Weaver's `GlobalResource` as the reason.
  - *Inference:* a copy of the `1.1.10` queue scheduling runs such a module through the queue, unlike Mill's own task on `1.3.1` (`.planning/event-stream-reporting/SPECIFICATION.md` R-053). A test manifest compiled from claim files has no claims to read for it.
- **Discovery now ends its runner.** `TestRunnerUtils.getTestTasks0`, which serves `ZincOp.GetTestTasks`, calls `runner.done()` in a `finally`, and `getTestTasks` calls it when discovery throws. The `1.3.0` release note is "fix: call Runner.done() after test discovery (backport #7620)" (`[rel]`). The test command's own discovery runs through this same op (`MRP` § 7).
- **Mill's Scala version.** `mill-build/src/millbuild/Deps.scala` `scalaVersion` is `3.8.2` at `[1110]` and `3.9.0` at `[131]` and `[14M2]` ("Update Scala to 3.9.0", `[131]` `changelog.adoc` § 1.3.0-M1).
  - *General:* a Scala 3 compiler reads TASTy from its own minor version or older. So a plugin compiled against `mill-libs` `1.3.1` needs Scala `3.9.x`, while one compiled on `3.8.2` against `1.1.10` loads on Mill's `3.9.0` runtime.

## 6. Other internals the test command relies on

These are outside the issue's list. They are recorded because they bear on the release to build against.

- **`mill.exec.Execution`,** which the test command copies with fail-fast off (`.planning/event-stream-reporting/research/mill-nested-keep-going.md`), changed by 743 lines. `GroupExecution` changed by 754 lines and `ExecutionContexts` by 132, between `1.1.10` and `1.3.1` (`git diff --stat`). `1.3.0-M1` introduced per-task read/write locking between launchers. `EvaluatorImpl` still holds `private val execution: Execution` (`core/eval/src/mill/eval/EvaluatorImpl.scala`).
- **`Logger`** gains a `private[mill] def waitMessageNeedsLabel: Boolean = false` (`core/api/daemon/src/mill/api/daemon/Logger.scala`). `PrefixLogger` changed by 4 lines. `TaskCtx` changed in one constructor call only.
- *Inference:* the fail-fast-off copy of `Execution` made at `1.1.10` cannot be assumed to match `1.3.1`. It would need to be re-copied, and `.planning/event-stream-reporting/research/mill-nested-keep-going.md` re-checked, for any release the plugin supports at or after `1.3.0`.

## 7. What was not determined

- Whether a plugin built against `1.1.10` loads and passes its integration tests on `1.3.1`. Nothing was run.
- Whether header `mvnDeps` and `mill-build/build.mill`'s `mvnDeps` combine (§ 3).
- Whether `__` wildcards walk into a `def` alias of an `ExternalModule` (§ 3).
- Whether, under `1.3`'s per-task locking, a second launcher can run Mill's own test task on a module while the test command, an exclusive command that no longer takes the workspace lock, drives that module's queue. The two use different folders under `out/`. Whether anything else collides was not checked.
- The local-repository spelling `COURSIER_REPOSITORIES` accepts beyond `ivy2Local`, `central` and `file:` URIs.
- Mill's own `1.3.1` docs for `ExternalModule` and plugins were read from the source tree, not the published site.

## 8. Options the findings leave open

### 8.1 Artifact naming

- **`<name>_mill1_3` for the plugin** (`::<name>::<version>` in a header), the form every Mill 1.x plugin checked uses.
  - Costs: none beyond choosing the name and group. The suffix says nothing about which 1.x releases are supported, which the plugin decides at run time (`.planning/event-stream-reporting/decisions/test-command.md` "On an unsupported Mill release, the test command runs and warns.").
- **A minor-version platform such as `_mill1.1_3`.**
  - Costs: no 1.x plugin does this, and `::` would not resolve it, because `$MILL_BIN_PLATFORM` is `1`. A build author would write the full artifact ID.
- **The consolidation library as `<name>_3`,** Scala 3 only (§ 2).
  - Costs: nothing for R-023, which only requires that it be published and depend on no Mill artifact.
- **`mill-libs` scope:**
  - `provided`, as published plugins do. Costs: the plugin's POM does not pin Mill.
  - `compile`, as Mill's example does. Costs: the POM names a Mill version that the running Mill's own jars then override on the meta-build classpath (§ 3).

### 8.2 How a build adds the plugin

- **Header `//| mvnDeps: ["<group>::<name>::<version>"]`.**
  - Costs: none for a `build.mill` build. A `build.mill.yaml` build writes the same under `mill-build:`.
- **`mill-build/build.mill`,** for builds that already have one.
  - Costs: a meta-build module to maintain. Interaction with header `mvnDeps` not determined.
- Either way the plugin, scalatest-core and the consolidation library land on the meta-build classpath. The plugin is the meta-build's only change, and the test modules are untouched (R-092).

### 8.3 How the test command is invoked

- **`mill <package>.<Object>/<command> <selector> [args]`** on an `ExternalModule` the plugin ships, as `mill.javalib.PublishModule/publishAll` is run (`MCL` § 2.1).
  - Costs: the package and object name are typed on every invocation, so a short top-level package keeps it brief. *Inference:* `mill __.test` does not reach it, whatever the command is named (§ 3), so R-060 holds.
- **The same, plus a `def` alias the build author adds in `build.mill`.**
  - Costs: one line in the build. Whether `__.test` reaches a `test` command under the alias is not determined, so a command not named `test` avoids the question.
- **A command on a trait or module the build defines.**
  - Costs: the build must define something beyond the header, and anything under the build's root module is reached by `__` wildcards.

### 8.4 Which Mill release to build against

- **`1.1.10`.** Every research note ran there.
  - Under R-120 the floor is `1.1.10`, and the plugin compiles on Scala `3.8.2`. By Mill's backward compatibility it loads on `1.3.x`.
  - Costs: under `.planning/event-stream-reporting/decisions/test-command.md` "A release of the Mill plugin supports exactly the Mill releases its integration tests passed on…", supporting `1.3.x` needs the integration tests to pass there.
  - The `1.1.10` copies of the queue scheduling and of `Execution` differ from `1.3.1`'s (§ 5, § 6). Supporting both may need behaviour chosen by the running Mill version.
  - `1.1.10` is the last release of a line that `1.3` has superseded.
- **`1.3.1`,** the newest stable 1.x.
  - Floor `1.3.1`, Scala `3.9.0`.
  - Costs: the queue `MRP` § 3 observed must be re-run there, and `Execution` re-copied (§ 6). Users on `1.1.x` are below the floor, where nothing holds (`decisions/test-command.md` "On an unsupported Mill release, the test command runs and warns." Costs).
  - The scheduling copy must follow `testSubprocessCount` and handle `testBatchFrameworkTasks` (§ 5).
- **`1.4.0-M2`.**
  - Costs: a milestone, with `millBinPlatform` still `1`, but no stability promise; its `testargs` path mapping is new (§ 5).
- **Any choice:** integration tests run exactly the launcher's Mill release (§ 4), so each supported release needs its own launcher and test run.
