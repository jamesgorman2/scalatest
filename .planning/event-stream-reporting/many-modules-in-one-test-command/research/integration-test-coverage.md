# Which integration scenarios other tests already cover

The per-scenario classification behind `integration-test-run-time.md` § P5 (issue #260): for each of the 229 scenarios in `mill-plugin/plugin/integration/src/org/scalatest/mill/`, which of its claims other tests assert, and whether it can be removed, simplified or must be kept. Scope: this repo at `2552d698b`. The rule is the user's: an integration test need not be exhaustive when its components are well tested, so a scenario may be removed or simplified when its full test space is covered by the module behaviour tests plus unit tests, or by two or more other tests between them (other integration scenarios included). A claim counts as covered only where another test asserts it; reading production code or Mill's sources, "by construction", and "the same as on the other Scala version" do not count. Where coverage is partial or uncertain, the scenario is kept or simplified.

## Test layers

| Layer | Suites | Runs in CI (`.github/workflows/ci.yml`) |
|---|---|---|
| Integration | `mill-plugin/plugin/integration/src/org/scalatest/mill/*Spec.scala` (`AnyFeatureSpec`, nested Mill through `mill.testkit.IntegrationTester`; `HeaderPluginSpec` is an `AnyFreeSpec`) | Yes, job `test-mill-plugin` |
| Plugin unit | every suite in `mill-plugin/plugin/test/src/org/scalatest/mill/` (`AnyWordSpec`) | Yes, job `test-mill-plugin` |
| Library module behaviour | the `AnyFeatureSpec` suites in `dotty/consolidation/src/test/scala/org/scalatest/consolidation/` (`HumanReadable*Spec`, `JsonOutput*Spec` other than `JsonOutputSpec` and `JsonOutputWriterSpec`, `ModuleFailedSpec`, `ModuleTestResultSpec`, `ChangedRegistrationOrderSpec`, `EmittedRunSpec`, `LibraryWithoutMillSpec`, `MillPluginDocumentationSpec`); they drive the library with real ScalaTest runs, without Mill | Yes, `scalatestConsolidation/test` |
| Library unit | the `AnyWordSpec` suites in the same folder (`ConsolidationSpec`, `JsonOutputWriterSpec`, …) | Yes, `scalatestConsolidation/test` |
| Emitter | `dotty/scalatest-test/src/test/scala/org/scalatest/tools/TestEventLog*Spec.scala` (real suites run, event log compared) and `dotty/scalatest-test/src/test/scala/org/scalatest/events/TestEventLog{Encoder,Decoder}Spec.scala` (unit) | Yes, `scalatestTestDotty/test` |
| Compatibility | `dotty/compatibility-test/src/test/scala/org/scalatest/compatibility/*` (`UpstreamCompatibilitySpec`, …) | No. It runs only with `test-all.sh --compat` (lines 64–67) or `test-all.ps1 --compat`, so it is not counted as cover. |

Below, a suite named without a path is in one of those folders. Three names occur in two folders: `JsonOutputNodesSpec`, `JsonOutputEncodingSpec` and `LibraryWithoutMillSpec`; "library `JsonOutputNodesSpec`" names the one in `dotty/consolidation/`. In each suite's table, # is the scenario's position in the suite, in the order of the test report; "l." is the line of its `Scenario` (for table-driven scenarios, the line of the loop's `Scenario`). "`SeveralModulesSpec` #5" names scenario 5 of that suite's table below.

## Summary

| Suite | Scenarios | Remove | Simplify | Keep | Solo s saved, alone | Solo s saved, after P2–P4 |
|---|---|---|---|---|---|---|
| `TestCommandArgumentsIntegrationSpec` | 59 | 32 | 0 | 27 | 500.5 | 216.8 |
| `SeveralModulesSpec` | 30 | 5 | 0 | 25 | 120.1 | 20.0 |
| `EveryWorkerSpec` | 22 | 3 | 4 | 15 | 108.6 | 33.1 |
| `JsonOutputArgumentSpec` | 24 | 18 | 0 | 6 | 293.7 | 80.3 |
| `JsonOutputNodesSpec` | 16 | 13 | 0 | 3 | 247.8 | 0 |
| `ModulesTheTestCommandCannotRunSpec` | 10 | 6 | 0 | 4 | 180.8 | 24.0 |
| `CapturedOutputSpec` | 11 | 2 | 0 | 9 | 34.7 | 8.0 |
| `TestCommandResultSpec` | 13 | 9 | 0 | 4 | 139.2 | 55.7 |
| `MillOwnTestTasksSpec` | 6 | 0 | 0 | 6 | 0 | 0 |
| `JsonOutputRootSpec` | 6 | 4 | 0 | 2 | 89.7 | 28.5 |
| `TestCommandOutputSpec` | 6 | 4 | 0 | 2 | 94.8 | 35.3 |
| `JsonOutputTreeSpec` | 2 | 1 | 0 | 1 | 45.3 | 9.0 |
| `HeaderPluginSpec` | 8 | 0 | 0 | 8 | 0 | 0 |
| `ModuleSelectorSpec` | 5 | 3 | 0 | 2 | 40.9 | 12.0 |
| `AddingThePluginSpec` | 2 | 0 | 0 | 2 | 0 | 0 |
| `MillBspServerSpec` | 5 | 0 | 0 | 5 | 0 | 0 |
| `LibraryWithoutMillSpec` | 2 | 0 | 0 | 2 | 0 | 0 |
| `CompatibilityFixtureSpec` | 1 | 0 | 0 | 1 | 0 | 0 |
| `JsonOutputEncodingSpec` | 1 | 0 | 0 | 1 | 0 | 0 |
| **Total** | **229** | **100** | **4** | **125** | **1896** | **523** |

Solo seconds are the solo-equivalent seconds of `integration-test-run-time.md` § 1.3 (4337 s in all). "Alone" is the saving with no other proposal applied: the removed scenarios' solo seconds, and for a simplification the whole scenario, since its checks move onto another scenario's eval. "After P2–P4" assumes P2, P3 and P4 are applied first: a removed scenario in a shared sandbox then saves about 4 s (one warm eval, § 1.4), a removed group saves its sandbox build too (about 8 s), a scenario that keeps its own sandbox saves its solo seconds, and `JsonOutputNodesSpec`'s rows save about nothing once P3 has merged them. The 104 removals and simplifications take away 103 of the 216 sandboxes and 112 of the 265 evals.

### Effect on P2, P3 and P4

- **P2.** Most sharing groups shrink, and the saving P2 still offers falls with them. `TestCommandArgumentsIntegrationSpec`'s unedited class-globs group goes from 21 scenarios to 3 (#10, #22, #43) and its output-argument group from 9 to 3 (#45, #47, #48). `JsonOutputArgumentSpec`'s group goes from 22 to 5. `ModulesTheTestCommandCannotRunSpec`'s group of 7 leaves #7 alone. `TestCommandResultSpec`'s group goes from 8 to 2, `ModuleSelectorSpec`'s from 5 to 2, and `CapturedOutputSpec`'s `--no-daemon` group from 6 to 4. `TestCommandOutputSpec`'s group (#1, #2, #5) disappears. `SeveralModulesSpec`'s base-build group goes from 8 to 5, its broken-compile group from 3 to 2, and its AC-005 group from 2 to 1. In `EveryWorkerSpec`, the sandboxing-off, batch-with-parallelism and Zeta/Alpha groups each become one scenario, the parallelism group goes from 5 to 4 and the batch group from 3 to 2. `ModuleSelectorSpec`'s remaining two scenarios both fail before any test starts, so its reset no longer has to order a worker-starting scenario last.
- **P3.** Only `JsonOutputNodesSpec` #2, #11 and #15 remain, so P3 becomes a merge of three rows (3 sandboxes to 1, about 16 s). Its preconditions shrink to giving those three rows distinct suite names and keeping #15's suites sorted opposite to their `suiteName`s.
- **P4.** `JsonOutputRootSpec`'s shared eval serves #1 and #3 only. `JsonOutputTreeSpec`'s shared eval disappears. `JsonOutputArgumentSpec`'s shared eval (#1, #6) disappears. `TestCommandArgumentsIntegrationSpec`'s shared reference run serves #57 and #59 (3 evals instead of 4). `EveryWorkerSpec`'s shared eval of #10 and #16 becomes the #16 merge. `SeveralModulesSpec` #5 and #8 still share one eval. `MillOwnTestTasksSpec`'s P4 change is unaffected.

### End-to-end proof kept for each integration path

| Path | Kept proof |
|---|---|
| Framework-argument rejection | `TestCommandArgumentsIntegrationSpec` #22 |
| Output-argument rejection | `TestCommandArgumentsIntegrationSpec` #48 |
| `-fJ`-argument rejection, leaving every file untouched | `JsonOutputArgumentSpec` #15 |
| Rejection leaving the module folder untouched | `TestCommandArgumentsIntegrationSpec` #42 |
| A `testArgsDefault` argument ScalaTest does not recognise | `ModulesTheTestCommandCannotRunSpec` #9 (AC-019) |
| A module the test command cannot run | `ModulesTheTestCommandCannotRunSpec` #7 (AC-011) |
| A selector Mill cannot resolve | `ModuleSelectorSpec` #3 |
| A selector that resolves to no test module | `ModulesTheTestCommandCannotRunSpec` #10 (AC-016); a parent of a test module: `ModuleSelectorSpec` #1 |
| No selector | `SeveralModulesSpec` #2 (AC-018) |
| Several selectors, de-duplication, mixed resolution, recording, ordering | `SeveralModulesSpec` #1, #5, #7–#10 |
| A failing test | `TestCommandResultSpec` #10; across modules `SeveralModulesSpec` #12; in a parallel worker `EveryWorkerSpec` #6 |
| An aborted suite | `TestCommandArgumentsIntegrationSpec` #12 (`beforeAll`, fails the command); `TestCommandOutputSpec` #4 (constructor) |
| A registered test that never runs | `TestCommandResultSpec` #7 |
| Class globs that match nothing | `TestCommandArgumentsIntegrationSpec` #10; across modules `SeveralModulesSpec` #26 |
| Framework arguments that leave no test | `SeveralModulesSpec` #27 (AC-022) |
| A module whose tests cannot start | `SeveralModulesSpec` #15 (compile), #16 (resolution), #17 (`generatedSources`), #18 (console) |
| A layout failure (fork groups sharing a folder) | `SeveralModulesSpec` #19 (AC-024), with `EveryWorkerSpec` #22's checks |
| A worker JVM that dies after its run started | `TestCommandResultSpec` #8; under class globs `TestCommandArgumentsIntegrationSpec` #13 |
| A worker JVM that does not start | `TestCommandResultSpec` #9 (JVM refuses an option); `EveryWorkerSpec` #21 (Mill refuses to start it) |
| An earlier goal failing, with and without `--keep-going` | `SeveralModulesSpec` #21, #22 |
| Console output through Mill, no daemon | `CapturedOutputSpec` #7, #9, #10, #11; `TestCommandArgumentsIntegrationSpec` #45, #47 |
| Console output through Mill's daemon | `CapturedOutputSpec` #1, #2; worker environment `TestCommandArgumentsIntegrationSpec` #32 |
| `mill.bat` | `TestCommandArgumentsIntegrationSpec` #34, #35. Both cancel off Windows, so CI (`ubuntu-latest`) never runs this path. |
| BSP | `MillBspServerSpec` #1–#5 |
| Mill's own test tasks | `MillOwnTestTasksSpec` #1–#6; `HeaderPluginSpec` #4, #8 |
| Queue, parallelism off | `EveryWorkerSpec` #1, #10 (with #16's checks), #14, #17, #21 |
| Queue, parallelism on | `EveryWorkerSpec` #2, #6, #8, #15 |
| Sandboxing off | `EveryWorkerSpec` #11 (with #3's checks); `CapturedOutputSpec` #8 |
| `testBatchFrameworkTasks` | `EveryWorkerSpec` #9, #12 (with #5's checks), #13, #20 |
| Class globs narrowing a layout | `TestCommandArgumentsIntegrationSpec` #7 (batch), #8 (queue, parallelism on) |
| A module with no test class | `EveryWorkerSpec` #19, #20 |
| JSON file lifecycle | deleted before tests `JsonOutputArgumentSpec` #7; failed delete #21; failed write #22; relative path #23; written with stdout off #4; unchanged when an earlier goal fails `SeveralModulesSpec` #21; replaced `SeveralModulesSpec` #22; deleted on a failure before any test `ModulesTheTestCommandCannotRunSpec` #7, #10 |
| Module folder | `TestCommandArgumentsIntegrationSpec` #36–#44; several modules `SeveralModulesSpec` #11 |
| Discovery output | `SeveralModulesSpec` #28, #29, #30 |
| Scala 3.9 | `AddingThePluginSpec` #2; `HeaderPluginSpec` #6–#8 |

### Acceptance criteria and requirements at risk

In `.planning/event-stream-reporting/many-modules-in-one-test-command/SPECIFICATION.md`:

- **AC-010 has no proving scenario left.** All six rows (`ModulesTheTestCommandCannotRunSpec` #1–#6) go. Each row's claims are asserted for all six modules at once by `ModulesTheTestCommandCannotRunSpec` #7 (AC-011), with the message format in `TestCommandFailureSpec` "…the test command cannot run selected test modules should name each module, on its own line, with why…" and the classification in `ModuleCheckSpec`.
- **Examples rows without a scenario of their own**, while the criterion keeps one: AC-001 rows `alpha.test beta.test`, `beta.test alpha.test` and `__.test alpha.test beta.test` (AC-001 keeps `SeveralModulesSpec` #5); AC-005 row `--keep-going` (keeps #12); AC-006 row `none` (keeps #15).
- **No coverage-table row is left with no proving criterion.** R-059 keeps AC-011 and AC-020; R-077 keeps AC-011, AC-016 and AC-019. Every other row keeps all its criteria.

In the epic's `.planning/event-stream-reporting/SPECIFICATION.md`, two requirements lose their only integration test while lower layers still cover them:

- **R-046a** (ignored and pending tests do not fail): `TestCommandResultSpec` #2 goes. `ConsolidationSpec` "…should stay open where a test is ignored or pending" and `HumanReadableSummaryLineSpec` "A run whose tests succeed, are ignored or are pending" cover it; `JsonOutputTreeSpec` #1 shows real ignored and pending events through Mill.
- **R-130e** (a test executed more than once appears once per execution): `JsonOutputNodesSpec` #9 goes. Library `JsonOutputNodesSpec` "A test executed twice is two nodes, in the order they started" and `TestEventLogNodeEventsSpec` "A suite runs a failed test again" cover it.

R-063 keeps `TestCommandArgumentsIntegrationSpec` #57 for the human-readable output, but its JSON half loses `JsonOutputTreeSpec` #2 and rests on `ModuleScalaTestArgumentsSpec` and #57.

## `TestCommandArgumentsIntegrationSpec` (59 scenarios: 32 remove, 0 simplify, 27 keep)

For one module (`core.test`), the suite proves that class globs, framework arguments, output arguments and `testArgsDefault` reach the run or are rejected as Mill runs it, and that workers get Mill's environment, module folder and console right. Kept proofs per path: globs on the batch path #7 and the queue path #8; a glob matching only a non-test class #10; an aborted suite #12 and a dead JVM #13 under globs; framework arguments over the module's #18, #19; framework-argument rejection #22; environment #31, #32, #34, #35; module folder #36–#44; console #45, #47; output-argument rejection #48; module arguments kept #56, withheld #57, #59.

The unit tests cited are in `mill-plugin/plugin/test/src/org/scalatest/mill/`. `ArgumentSplitterSpec` is `dotty/scalatest-test/src/test/scala/org/scalatest/tools/ArgumentSplitterSpec.scala`.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | Class globs › `samples.Alpha1`; l. 158 | (a) only matching suites run, succeeds: `ClassGlobSpec` "A class glob when it holds no * should match only the name equal to it"; #8 (run holds Alpha1 alone) | remove | – |
| 2 | Class globs › `samples.Alpha*`; l. 158 | (a) as 1: `ClassGlobSpec` "…it ends with *…"; #7; `SeveralModulesSpec` #26 (markers exactly Alpha1, Alpha2) | remove | – |
| 3 | Class globs › `*1`; l. 158 | (a) as 1: `ClassGlobSpec` "…it begins with *…"; `SeveralModulesSpec` #25 (`-- *1 -- -z slow`, markers exactly Alpha1.slow, Beta1.slow) | remove | – |
| 4 | Class globs › `samples.*a2`; l. 158 | (a) as 1: `ClassGlobSpec` "…it holds * between other characters…"; globs end to end `SeveralModulesSpec` #23, #26 | remove | – |
| 5 | Class globs › `samples.Beta1 samples.Alpha2`; l. 158 | (a) two globs: `ClassGlobSpec` "…when several class globs are given…"; `SeveralModulesSpec` #23 (`-- samples.Alpha1 samples.Beta1`) | remove | – |
| 6 | Class globs › one fork group's classes, batch false; l. 176 | (a) one run, fork group 1, Alpha1 and Alpha2: `WorkerLayoutSpec` "…when they keep classes of only the second of two fork groups should give that group one worker in the module's folder, keeping the group's position"; queue path through Mill `EveryWorkerSpec` #1; (b) `user.dir` in `<module folder>/sandbox`: #8; `EveryWorkerSpec` #10 | remove | – |
| 7 | Class globs › one fork group's classes, batch true; l. 176 | (a), (b) as 6 on the batch path: `WorkerLayoutSpec` "Laying out a module's batch workers when class globs keep classes of only the second of two fork groups…" (plan only) | keep | Class globs narrowing a batch layout, and the narrowed worker running in the module folder, through Mill. |
| 8 | Class globs › one class under test parallelism; l. 200 | (a) one run holding Alpha1: `WorkerLayoutSpec` "…exactly one class under test parallelism…" (plan only); (b) `user.dir` is `<module folder>/sandbox`: none | keep | Mill's `runTestFuture` uses the group folder, not `worker-<i>`, when the plugin passes one class under parallelism. |
| 9 | Class globs › `samples.Gamma*` matches no test class; l. 220 | (a) exact no-match failure on the module, none on the root; (b) no markers, no worker; (c) fails: all by #10 (same assertions); `JvmTestModuleRunSpec` "…when the class globs keep no class should attach to the module the failure Mill's testOnly fails with…"; `SeveralModulesSpec` #26 | remove | – |
| 10 | Class globs › `samples.Fast` matches no test class; l. 220 | (a)–(c) as 9; (d) a glob matching only the `Tag` object is a no-match: none (`ClassGlobSpec` "…should match an object's class by the name of the object" shows the glob matches `samples.Fast$`, so only Mill's discovery keeps it out) | keep | The plugin filters by the classes discovery finds; the suite's no-match proof. |
| 11 | Class globs › framework arguments leave no test of the matched classes; l. 249 | (a) exactly one no-match failure on the module, none on the root, no markers, fails: `SeveralModulesSpec` #27 (AC-022: the same per module, for two modules, root with no `outcome` or `failures`); `JvmTestModuleRunSpec` "Reporting, once a module's workers have ended, that its class globs gave no test result…"; `ClassGlobSpec` (an exact name as a glob) | remove | – |
| 12 | Class globs › only matched suite aborts in `beforeAll`; l. 275 | (a) Broken1 aborted with a message in the JSON output: library `JsonOutputNodesSpec` "A suite that aborts before its tests run is aborted…"; emitter `TestEventLogNodeEventsSpec` "A suite whose beforeAll throws"; (b) no no-match failure: `JvmTestModuleRunSpec` "…a run of the module reported a suite as aborted should not attach the failure…"; (c) a real `beforeAll` abort fails the test command: none | keep | The only scenario in which a suite aborted by `beforeAll` fails the test command; also the only integration test of an aborted suite in the JSON output. |
| 13 | Class globs › JVM halts before its tests run; l. 297 | (a) no no-match failure: `JvmTestModuleRunSpec` "…the JVM of a worker of the module ended without writing its result…" (decision only; Mill's `Result.Failure` mapped to `EndedWithoutResult` has no unit test); (b) fails: `TestCommandResultSpec` #8 | keep | A dead worker under class globs reaching "ended without result" through Mill's real `Result`. |
| 14 | Framework arguments › no `--`, every test runs; l. 330 | (a) every test runs, succeeds: `SeveralModulesSpec` #5 (each of six markers once); `TestCommandArgumentsSpec` "…when given no -- should hold no class globs and no framework arguments" | remove | – |
| 15 | Framework arguments › `-- -- -n samples.Fast`; l. 355 | (a) only `fast` runs: `SeveralModulesSpec` #24 (same arguments, exact markers); `TestCommandArgumentsSpec` "…given arguments after a second -- should hold no class globs where nothing stands between the two" | remove | – |
| 16 | Framework arguments › `-- -- -l samples.Fast`; l. 355 | (a) only `slow` runs: parse `TestCommandArgumentsSpec` "…given framework arguments of each form the test command passes on should split them by flag…"; order `RunArgumentsSpec` "…should be the module's arguments, then the framework arguments…"; a framework tag filter end to end `SeveralModulesSpec` #24; `-l` end to end #56 | remove | – |
| 17 | Framework arguments › `-- samples.Alpha* -- -z slow`; l. 355 | (a) `slow` of Alpha1, Alpha2 only: `SeveralModulesSpec` #25 (`-- *1 -- -z slow`); `TestCommandArgumentsSpec` "…should read those between the two as class globs and those after the second as framework arguments" | remove | – |
| 18 | Framework arguments › framework `-Dcolour=blue` over the module's `-Dcolour=red`, batch false; l. 367 | (a) order "module, then framework": `RunArgumentsSpec` "…whose flags the runner takes more than once…"; (b) the runner takes the later `-D`, so the test sees `blue`: none | keep | The framework `-D` wins in the config map on the queue path. |
| 19 | Framework arguments › the same, batch true; l. 367 | (a) as 18; (b) framework arguments reach a batch worker: none (no other scenario passes framework arguments under `testBatchFrameworkTasks`) | keep | Framework arguments reaching the run through the batch launcher. |
| 20 | Framework arguments › `-F 3` replaces the module's `-F 2`; l. 388 | (a) one `-F`, the framework's: `RunArgumentsSpec` "…the module gives -F 2 twice and the test command gives -F 3 should hold the framework argument -F 3…"; (b) arguments reach the run: #18 | remove | – |
| 21 | Framework arguments › module's `-l` beside framework `-F 3`; l. 407 | (a) module arguments kept beside framework ones: `RunArgumentsSpec` "…gives none of them should keep the module's arguments"; (b) module `-l` reaches the run: #56 | remove | – |
| 22 | Framework arguments › `-oD` fails, naming `-oD`; l. 429 | (a) rejection: `TestCommandArgumentsSpec` "…should reject a ScalaTest flag outside those it passes on, naming the flag"; message `TestCommandFailureSpec` "…a framework argument is not one the test command passes on…"; (b) Mill fails before any worker, no markers, no JSON file, message on the console: none | keep | End-to-end proof of the framework-argument rejection path. |
| 23 | Framework arguments › `-s samples.Alpha1`; l. 429 | (a) `TestCommandArgumentsSpec` (as 22, `-s`); (b) #22 | remove | – |
| 24 | Framework arguments › `--unknown`; l. 429 | (a) `TestCommandArgumentsSpec` "…should reject an argument ScalaTest does not recognise, naming it"; (b) #22 | remove | – |
| 25 | Framework arguments › `-n` with no value; l. 429 | (a) `TestCommandArgumentsSpec` "…should reject a flag short of the values ScalaTest's runner takes with it…"; (b) #22 | remove | – |
| 26 | Framework arguments › `-P`; l. 429 | (a) `TestCommandArgumentsSpec` "…should reject a -P alone, naming it"; (b) #22 | remove | – |
| 27 | Framework arguments › `foo`; l. 429 | (a) as 24; (b) #22; #42 | remove | – |
| 28 | Framework arguments › `-n samples.Fast -- samples.Alpha1`; l. 441 | (a) `TestCommandArgumentsSpec` "…given a framework argument's flag among the test command's own arguments should reject it alone, naming it"; message `TestCommandFailureSpec` "…a framework argument's flag is among the test command's own arguments…"; (b) the parse-failure path end to end: #22 | remove | – |
| 29 | Framework arguments › `-n samples.Fast`, no `--`; l. 441 | (a) `TestCommandArgumentsSpec` "…should reject it where no -- is given, naming it"; (b) #22 | remove | – |
| 30 | Environment › `propagateEnv` true, no daemon; l. 477 | (a) `FIXTURE_OUTER` reaches the worker, `forkEnv` wins, `MILL_WORKSPACE_ROOT` is the workspace: #32 (same map, daemon); (b) no-daemon environment: #31 | remove | – |
| 31 | Environment › `propagateEnv` false, no daemon; l. 477 | (a) `FIXTURE_OUTER` absent though Mill's process holds it: none | keep | A worker that ignores `propagateEnv`. |
| 32 | Environment › `propagateEnv` true, daemon; l. 477 | (a) the launching client's environment, not the daemon's: none | keep | Daemon against launching environment. |
| 33 | Environment › `propagateEnv` false, daemon; l. 477 | (a) `FIXTURE_OUTER` absent: #31 (stricter); (b) `forkEnv` and `MILL_WORKSPACE_ROOT` under the daemon: #32 | remove | – |
| 34 | Environment › `MILL_PARAMS` through `mill.bat`, no daemon; l. 518 | (a) `MILL_PARAMS` holds `mill.bat`'s arguments without `--no-daemon`: none | keep | `mill.bat` without the daemon. Cancels off Windows. |
| 35 | Environment › `MILL_PARAMS` through `mill.bat`, daemon; l. 518 | (a) the client's `MILL_PARAMS` reaches the worker: none | keep | `mill.bat` under the daemon. Cancels off Windows. |
| 36 | Module folder › `testReportXml Some("test-report.xml")`, no arguments; l. 586 | (a) module folder exists, holds no JUnit XML: none | keep | R-047 without arguments. |
| 37 | Module folder › `Some("junit-results.xml")`, `-- samples.Alpha*`; l. 586 | (a) as 36, with class globs and another name: none | keep | R-047 with class globs. |
| 38 | Module folder › `Some("test-report.xml")`, `-- -- -n samples.Fast`; l. 586 | (a) as 36, with framework arguments: none | keep | R-047 with framework arguments. |
| 39 | Module folder › `Some("test-report.xml")`, `-- samples.Gamma*`; l. 586 | (a) module folder exists after a no-match: #41; (b) no XML after a no-match: none | keep | R-047 on the no-match path. |
| 40 | Module folder › `None`, `-- * -- -l samples.Fast`; l. 586 | (a) no XML with class globs and framework arguments together: none | keep | R-047 with globs and framework arguments. |
| 41 | Module folder › no-match deletes an earlier command's file; l. 604 | (a) module folder cleared before discovery fails: none | keep | Clearing on the no-match path. |
| 42 | Module folder › rejected framework argument leaves the earlier files; l. 617 | (a) fails naming `foo`: `TestCommandFailureSpec`, #22; (b) module folder unchanged file for file: none | keep | A parse failure touches nothing. |
| 43 | Module folder › Mill's `testForked` report untouched, no `testOnly.dest`; l. 636 | (a) none | keep | No writes in Mill's own task folders (R-110). |
| 44 | Module folder › cross module's folder is `core.3_3_8.test`; l. 664 | (a) folder name: `ModuleFolderSpec` "A module folder when the module is named as Mill writes it in a task name…"; (b) Mill renders `core[3.3.8].test` that way, and the worker runs in its `sandbox`: none | keep | Mill's cross-module naming reaching the module folder. |
| 45 | Output arguments › no `-o`; l. 691 | (a) exact tree and summary line under the ticker: `CapturedOutputSpec` #7 (tree); (b) no other stdout line and no stderr line but Mill's task-name lines carries the prefix: none; (c) a worker started | keep | Nothing else the test command's task writes reaches the console under the ticker (R-106h). |
| 46 | Output arguments › `-oH`; l. 691 | (a) `-oH` means no `-o`: `TestCommandArgumentsSpec` "…given -oH should put human-readable output on stdout, wherever it is given"; `-oH` end to end `SeveralModulesSpec` #18, #30; (b) prefix claim: #45 | remove | – |
| 47 | Output arguments › bare `-o`; l. 719 | (a) no tree lines, no `dividing now`: `JsonOutputArgumentSpec` #4; (b) no summary line anywhere on the console: none | keep | Bare `-o` writes no summary line anywhere. |
| 48 | Output arguments › `-oP` fails; l. 748 | (a) rejection: `TestCommandArgumentsSpec` "…given any other argument beginning with - should reject it, naming it"; message `TestCommandFailureSpec` "…an argument is unexpected…"; (b) fails before any worker, exact console message, no tree lines, no `report.txt`: none once `JsonOutputArgumentSpec` #17 goes | keep | End-to-end proof of the output-argument rejection path. |
| 49 | Output arguments › `-oJ` fails; l. 748 | (a) `TestCommandArgumentsSpec` (same test, `-oJ`); (b) #48 | remove | – |
| 50 | Output arguments › `-oHP` fails; l. 748 | (a) as 49; (b) #48 | remove | – |
| 51 | Output arguments › `-oHd` fails; l. 748 | (a) as 49; (b) #48 | remove | – |
| 52 | Output arguments › `-oHy` fails; l. 748 | (a) as 49; (b) #48 | remove | – |
| 53 | Output arguments › `-fH report.txt` fails; l. 748 | (a) as 49 (`-fH`); (b) #48; `JsonOutputArgumentSpec` #15 | remove | – |
| 54 | Module's own arguments › `-x` in `testArgsDefault`; l. 776 | (a) `-x` named, `-l samples.Slow` kept out: `ModuleScalaTestArgumentsSpec` "…they hold arguments ScalaTest does not recognise should keep none, giving each such argument in the order given"; `ModuleCheckSpec` "…its testArgsDefault holds arguments ScalaTest does not recognise should name each such argument"; message `TestCommandFailureSpec` "…the test command cannot run selected test modules…"; (b) fails before any test, names the argument, no human-readable output: `ModulesTheTestCommandCannotRunSpec` #9 (AC-019) | remove | – |
| 55 | Module's own arguments › `-z -y` reaches the run; l. 791 | (a) `-y` kept as `-z`'s value: `ModuleScalaTestArgumentsSpec` "…every flag is one a run receives should all reach the run…"; (b) ScalaTest's parser keeps them together: `ArgumentSplitterSpec` "keep -z -Dlooks=like-a-flag together, as parseArgs does"; (c) module arguments reach the run: #56 | remove | – |
| 56 | Module's own arguments › `-l samples.Slow` hides the excluded test; l. 804 | (a) a kept module argument takes effect end to end: none | keep | Kept `testArgsDefault` arguments reach the run (R-142). |
| 57 | Module's own arguments › `-oD` leaves the output unchanged; l. 819 | (a) withheld: `ModuleScalaTestArgumentsSpec` "…they hold reporter, JUnit, TestNG and other arguments should withhold each…"; (b) a module reporter argument changes neither the human-readable output nor the console outside it: none once `JsonOutputTreeSpec` #2 goes | keep | R-063 end to end for a reporter argument. |
| 58 | Module's own arguments › `-eD`; l. 819 | (a) withheld: `ModuleScalaTestArgumentsSpec` (same test, `-eD`); (b) a withheld reporter argument changes nothing end to end: #57 | remove | – |
| 59 | Module's own arguments › `-s samples.OtherSuite`; l. 819 | (a) withheld: `ModuleScalaTestArgumentsSpec` (same test); (b) a withheld non-reporter argument does not narrow the run, console clean: none | keep | Withheld module arguments do not reach the run. |

AC and requirement risks: no AC is proved by this suite. Its single-module scenarios resemble AC-008, AC-009, AC-019 and AC-022, whose proofs stay in `SeveralModulesSpec` and `ModulesTheTestCommandCannotRunSpec`. Epic requirements keep integration proofs: R-125a #18, #19, #56; R-125b #22, #48; R-142 #56, #59 and `ModulesTheTestCommandCannotRunSpec` #9; R-047 #36–#40; R-048 #45, #47; R-110 #8, #41–#44.

Savings: 32 removals, 500.5 solo s, 32 sandboxes and 34 evals (#33 and #58 make two each). The class-globs group and the output-argument group lose 24 scenarios, about 4 s each once P2 is applied; the other 8 have their own builds (120.8 s, with #58's P4 share taken off). After P2–P4: about 217 s.

## `SeveralModulesSpec` (30 scenarios: 5 remove, 0 simplify, 25 keep)

The suite proves end to end that one test command selects, runs and reports several test modules, that each module's failure stays on its own node while the others run, and that class globs, framework arguments and discovery output apply per module. It holds the proofs of every AC but AC-010, AC-011, AC-016, AC-019 and AC-020, and every kept scenario proves one of them.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | Several module selectors › on either side of an output argument; l. 171 | (a) both selectors kept, in order, either side of the `-fJ` path: none (`TestCommandArgumentsSpec` "given -fJ followed by a path…" has one selector) | keep | AC-017. |
| 2 | › only an output argument; l. 190 | (a) message "takes one or more module selectors, but was given none": `ModuleSelectorSpec` #4; `TestCommandFailureSpec` "…no module selector is given…"; (b)–(d) no markers, nothing at `<report>`, no consolidated output: none together | keep | AC-018; `ModuleSelectorSpec` #4 goes in its favour. |
| 3 | › `alpha.test beta.test`; l. 221 | (a) nodes in order: #1; (b) each marker once: #5; (c) succeeds: #1, #5 | remove | AC-001 row loses its scenario. |
| 4 | › `beta.test alpha.test`; l. 221 | (a) node order independent of selector order: #10; `JsonOutputWriterSpec` "The JSON output's root when given runs of two modules should hold the modules in ascending order by name"; (b), (c): #5 | remove | AC-001 row loses its scenario. |
| 5 | › `alpha.test __.test`; l. 221 | (a) nodes: #1; (b) a module two selectors select runs once: none | keep | AC-001. |
| 6 | › `__.test alpha.test beta.test`; l. 221 | (a) nodes in order: #10; (b) once each with every module selected twice: #5; (c): #5 | remove | AC-001 row loses its scenario. |
| 7 | › a selector resolving to test and other modules; l. 239 | (a) `__` selects only `alpha.test`, `beta.test`, recorded as such: none | keep | AC-013. |
| 8 | › two selectors sharing a test module each record it; l. 257 | (a) `alpha.test` recorded under both: none (`JsonOutputWriterSpec` writes only what it is given) | keep | AC-002. |
| 9 | › a selector's test modules ordered by name; l. 268 | (a) `String.compareTo` order within a selector: none | keep | AC-014. |
| 10 | › module nodes ordered by name; l. 280 | (a) `String.compareTo` order of nodes: none (library tests sort lower-case names only) | keep | AC-003. |
| 11 | › each module's tests in its own module folder's `sandbox`; l. 292 | (a) two modules, two folders: none (single module `TestCommandArgumentsIntegrationSpec` #6–#8, #44) | keep | AC-004. |
| 12 | Every selected module runs › a test of alpha.test fails, flags none; l. 316 | (a) Beta1's tests run though Alpha1.slow fails, without `--keep-going`: none (`SeveralModulesSpec` #17 and #26 show other kinds of module failure; `JvmTestModuleRunSpec` "…only another module has failed…" is unit); (b) fails: `TestCommandResultSpec` #10 | keep | AC-005: a failing test in one module does not stop another's tests. |
| 13 | › the same, `--keep-going`; l. 316 | (a) as 12 under `--keep-going`: #12 (without the flag); #15 (another module failing, `--keep-going`); (b) fails: #15 | remove | AC-005 row `--keep-going` loses its scenario. |
| 14 | › broken.test does not compile, flags none; l. 336 | (a) Alpha1, Beta1 markers; (b) node order; (d) `alpha.test`, `beta.test` clean; (e) root clean; (f) fails: #17 (same helper, no flags); (c) `broken.test` no run, `aborted`, one failure containing `broken.test.compile`: #15 (JSON); #18 (compile failure, no flags, console); `JsonOutputWriterSpec` "…given a failure but no run…" | remove | AC-006 row `none` loses its scenario. |
| 15 | › the same, `--keep-going`; l. 336 | (a)–(f) under `--keep-going`: none | keep | AC-006; the JSON proof that a compile failure is named on its module. |
| 16 | › cannot start: `com.example::absent:0.0.1`; l. 360 | Coursier's resolution error on the module: none (`FailedTaskSpec` covers the line format only) | keep | AC-015, R-061. |
| 17 | › cannot start: `generatedSources` throws; l. 360 | a non-compile upstream task, others run, no flags: none | keep | AC-015. |
| 18 | › broken.test's failure in the human-readable output; l. 371 | red abort and failure lines for a failure text Mill wrote: `HumanReadableRunSpec` "A module given a failure but no run shows its line and its abort…" (fixed text); #30 | keep | AC-023. |
| 19 | › two of beta.test's fork groups hold only `samples.Beta1`; l. 401 | (a) Alpha runs, Beta does not; (c) `alpha.test`, root clean, fails: none for a layout failure beside another module; (b) `beta.test` no run, `aborted`, one failure naming `samples.Beta1`, not `beta.test`: `EveryWorkerSpec` #22; `JvmTestModuleRunSpec` "…two fork groups would share a processFolder should attach … without naming the module" | keep | AC-024. Takes `EveryWorkerSpec` #22's checks that `beta.test` has no `startedAt`, `endedAt` or `duration` and that no `testargs` exists under its module folder. |
| 20 | › late.test compiles only after a test of alpha.test; l. 428 | none | keep | AC-007, R-056b. |
| 21 | Another goal fails first › `broken.compile`; l. 459 | none | keep | AC-012, R-062. |
| 22 | › `broken.compile`, `--keep-going`; l. 485 | none | keep | AC-021. |
| 23 | Class globs and framework arguments › `-- samples.Alpha1 samples.Beta1`; l. 515 | (a) markers exactly Alpha1.*, Beta1.*: `ClassGlobSpec` "…several class globs are given…" (unit); several globs across modules: none | keep | AC-008 row 1; `TestCommandArgumentsIntegrationSpec` #5 goes in its favour. |
| 24 | › `-- -- -n samples.Fast`; l. 515 | (a) framework arguments reach every module: none | keep | AC-008 row 2, R-053b. |
| 25 | › `-- *1 -- -z slow`; l. 515 | (a) globs and framework arguments together, across modules: none | keep | AC-008 row 3; `TestCommandArgumentsIntegrationSpec` #3 and #17 go in its favour. |
| 26 | › class globs matching only alpha.test's classes; l. 530 | (a)–(d): single-module `TestCommandArgumentsIntegrationSpec` #10; `JvmTestModuleRunSpec` "…the class globs keep no class…" | keep | AC-009. |
| 27 | › framework arguments exclude every test the globs keep; l. 559 | (a) no markers; (b) both modules hold runs, `aborted`, exactly one failure; (c) the `testOnly` message naming `*1`; (d) root clean; (e) fails: single module `TestCommandArgumentsIntegrationSpec` #11 (no run-node check) | keep | AC-022; `TestCommandArgumentsIntegrationSpec` #11 goes in its favour. |
| 28 | › discovery output in JSON, `javaHome` unset; l. 593 | capture through Mill's in-process streams: none (`JvmTestModuleRunSpec` "Capturing what a module's test discovery writes" is unit) | keep | AC-025. |
| 29 | › the same, `javaHome` set; l. 593 | capture when Mill runs discovery in another JVM: none | keep | AC-025. |
| 30 | › beta.test's runs, output and failure in the human-readable output; l. 619 | `HumanReadableRunSpec` (library, fixed input) | keep | AC-026, R-106e. |

AC risks: AC-001 rows `alpha.test beta.test`, `beta.test alpha.test` and `__.test alpha.test beta.test`, AC-005 row `--keep-going` and AC-006 row `none` lose their own scenarios; each AC keeps a proving scenario.

Savings: 5 removals, 120.1 solo s (#3's 51.8 s ran under early load), 5 sandboxes and 5 evals. After P2: each is a warm eval in a group that remains, about 20 s.

## `EveryWorkerSpec` (22 scenarios: 3 remove, 4 simplify, 15 keep)

End to end, the suite proves that the plugin's planned worker folders, run numbers and output capture match what Mill 1.3.1 does on each launch path. Kept proofs: queue with parallelism off #1, #10, #14, #17, #21; queue with parallelism on #2, #6, #8, #15; sandboxing off #11; batch #9, #12, #13; no test class #19, #20.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | Every worker › parallelism off, one worker per fork group; l. 61 | (a) each group's classes in its one run, with its `forkGroup`: plan only, `WorkerLayoutSpec` "…test parallelism is off… two fork groups…"; (b) two runs, group 0 first: `ConsolidatedModuleSpec` "…numbered from 1 by fork group…" | keep | Mill runs each group's classes in the planned worker on the queue path. |
| 2 | › parallelism on, one or two workers per group; l. 77 | (a) membership per group under sandboxing: none; (b) one or two runs per group: plan, `WorkerLayoutSpec` "…test parallelism is on…" | keep | Membership on the forked queue path. |
| 3 | › parallelism on, sandboxing off; l. 94 | (a) membership per group with sandboxing off: none; (b) layout with sandboxing off: #11 | simplify | Its sandbox and eval go; `shouldRunEachForkGroupInItsOwnWorkers(json)` and `shouldSucceed` move onto #11's eval (same build; `-j 4` instead of `-j 2` still allows one or two runs per group). |
| 4 | › batch, one worker per group; l. 112 | (a) membership and (b) runs (fg 0, fg 1): #9 (same configuration, stricter); `WorkerLayoutSpec` "Laying out a module's batch workers… two fork groups…" | remove | – |
| 5 | › batch with parallelism on, one worker per group; l. 129 | (a) one worker per group: #12 (one event log per group folder); (b) membership; (c) succeeds: none on this build | simplify | Its sandbox and eval go; its two checks move onto #12's eval (same build, `-j 4`). |
| 6 | › a test failing in the second fork group; l. 147 | (a) A2's test `failed`: library `JsonOutputNodesSpec` "A failed test holds its outcome…"; `JsonOutputTreeSpec` #1; (b) A1, B1, B2 still run when a test fails in a forked parallel worker: none; (c) fails: `TestCommandResultSpec` #10 | keep | A failing test in one of a module's parallel workers does not stop the module's other workers. |
| 7 | › parallelism on, runs numbered in worker order; l. 171 | (a)–(d) each run in one worker, no two runs of a group share one, a run in `worker-0`, lower number in lower index: #8 (deterministic); `ConsolidatedModuleSpec`; `HumanReadableRunSpec` "A module's runs are numbered by fork group, then worker index…"; `WorkerLayoutSpec` "…index the group's workers from 0…" | remove | – |
| 8 | › two classes that must run at once; l. 207 | worker index equals Mill's `worker-<i>`, two workers at once: none | keep | – |
| 9 | › runs numbered by fork group though group 0 ends last; l. 236 | (b) numbering: `ConsolidatedModuleSpec`, `JsonOutputRunsSpec` "…runs given out of order…"; (c) membership on the batch path: none once #4 goes | keep | Batch membership and numbering with concurrent real workers. |
| 10 | Each worker has its own event log › parallelism off, event log in each group's folder; l. 264 | (a) planned folders: `WorkerLayoutSpec`; (b) `testargs` beside each: none | keep | Takes #16's checks. |
| 11 | › parallelism on, sandboxing off, event log per `worker-<i>`; l. 276 | (a)–(c): plan only, `WorkerLayoutSpec` | keep | Takes #3's checks. |
| 12 | › batch, no queue laid out; l. 302 | (b) `testargs`, (c) no `test-classes` or `claim`: none | keep | Takes #5's checks. |
| 13 | › batch, stdout on its worker's run, none on the console; l. 319 | capture through `callTestRunnerSubprocess`: none (`WorkerRunSpec` is unit; #14, #15 are queue paths) | keep | – |
| 14 | › stdout lines on their worker's run, in order; l. 343 | (c) A1's lines not on B1's run, two workers one after the other on the calling thread: none | keep | – |
| 15 | › parallelism on, stdout and stderr on their run alone; l. 370 | both streams in forked queue workers, none on the console: none | keep | – |
| 16 | › module timed from first worker start to last worker end; l. 402 | (a) module times enclose every run, with two workers: one worker `JsonOutputRootSpec` #3; encoding `JsonOutputWriterSpec` "A module of the JSON output when timed with both of its times…" | simplify | Its sandbox and eval go; its two assertions move onto #10's eval (same build and command, the P4 pair). |
| 17 | Runs in the human-readable output › only the run whose worker wrote is shown; l. 419 | (b) a real worker writing nothing adds no line, two runs: none | keep | R-144. |
| 18 | › a failing test, no worker writes, no run shown; l. 442 | (a) no `Run` line for a failing module with no captured output: `TestCommandResultSpec` #7 (exact tree, failing); `HumanReadableOutcomeSpec` "A test that failed shows *** FAILED ***…"; (b) Alpha then Zeta under the module: `HumanReadableModuleRunsSpec` "The suites of runs that are not shown form one list by name…"; #17; (c) fails: `TestCommandResultSpec` #10 | remove | – |
| 19 | A module with no test class › one empty run; l. 469 | Mill starts worker 0 on an empty queue; succeeds: none | keep | – |
| 20 | › batch, no fork group, one empty run; l. 492 | none | keep | – |
| 21 | A worker that cannot start › other groups' runs kept; l. 516 | Mill's real refusal to start a worker reaching the plugin, later groups still start: none (`WorkerRunSpec` uses a made-up exception) | keep | – |
| 22 | Fork groups that would share a processFolder › fail before any worker starts; l. 553 | (a) module failure: `WorkerLayoutSpec` "two fork groups each hold the same one class…"; `JvmTestModuleRunSpec` "…should attach the groups and the folder to the module…"; `SeveralModulesSpec` #19; (b) root clean, (c) no run: `SeveralModulesSpec` #19; (d) no module times; (e) no `testargs`: none | simplify | Its sandbox and eval go; (d) and (e) move onto `SeveralModulesSpec` #19's eval, for `beta.test`. |

AC and requirement risks: none. R-144 keeps #17, #19; R-105 run numbering keeps #8, #9; R-103 keeps #16's assertions on #10's eval.

Savings: 3 removals and 4 simplifications, 108.6 solo s, 7 sandboxes and 7 evals. After P2–P4: #3, #4, #5, #7, #18 save a warm eval each, #16 nothing more, #22 its own sandbox: about 33 s.

## `JsonOutputArgumentSpec` (24 scenarios: 18 remove, 0 simplify, 6 keep)

The suite proves end to end how the test command takes `-fJ` and treats the file it names. Kept proofs: JSON written with stdout off #4; the file deleted before any test, seen from inside a run #7; a rejected output argument leaving every `-fJ` file untouched #15; a failed delete #21; a failed write after the runs #22; a relative path #23. Only #15 and `TestCommandArgumentsIntegrationSpec` #42 show that a rejected argument leaves existing files as they were.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | The -fJ argument › `core.test -fJ <report>`; l. 34 | (a) root holds `adds two numbers`, succeeded: `JsonOutputRootSpec` #1; (b) human-readable output beside `-fJ`: `JsonOutputTreeSpec` #1; parse `TestCommandArgumentsSpec` "given -fJ followed by a path should take the path…"; (c) succeeds: `SeveralModulesSpec` #1 | remove | – |
| 2 | › `core.test -oH -fJ <report>`; l. 34 | (a) as 1(a); (b) parse `TestCommandArgumentsSpec` "…should keep the meaning of -oH or a bare -o beside it"; both outputs written: `JsonOutputTreeSpec` #1; `-oH` end to end `SeveralModulesSpec` #18 | remove | – |
| 3 | › `core.test -fJ <report> -oH`; l. 34 | (a) as 1(a); (b) `SeveralModulesSpec` #30 (`-fJ <report> -oH -- …`); parse `TestCommandArgumentsSpec` "given -oH should put human-readable output on stdout, wherever it is given" | remove | – |
| 4 | › `core.test -o -fJ <report>`; l. 34 | (a) JSON written while stdout is off: none (parse only); (b) no tree, no `dividing now`: `TestCommandArgumentsIntegrationSpec` #47 | keep | The `StdoutContent` switch with JSON on. |
| 5 | › selector after the path; l. 62 | (a) a selector after the path is taken and recorded: `SeveralModulesSpec` #1; parse `TestCommandArgumentsSpec`; (b) exact record: `JsonOutputRootSpec` #1 | remove | – |
| 6 | › replaces a file from an earlier command; l. 76 | (a) `SeveralModulesSpec` #22 (an earlier report rewritten); `JsonOutputFileSpec` "…written should hold the JSON output in UTF-8, replacing what it held" | remove | – |
| 7 | › no file at the path while the run executes; l. 88 | (a) deleted before any test, absent during the run: none (`JsonOutputFileSpec` "deleted should remove a file at its path" is unit) | keep | R-077. |
| 8 | › written when a test fails, command fails; l. 104 | (a) failed test in the JSON: `JsonOutputTreeSpec` #1; (b) a failing test with `-fJ` fails the command: `SeveralModulesSpec` #12; `TestCommandResultSpec` #10 | remove | – |
| 9–14 | Output arguments rejected › `-fH`, `-fP`, `-fHP`, `-fJH`, `-fJc`, `-f` `<report>`; l. 135 | (a) `TestCommandArgumentsSpec` "given any other argument beginning with - should reject it, naming it"; message `TestCommandFailureSpec` "…an argument is unexpected…"; (b) fails before any worker, no output: `TestCommandArgumentsIntegrationSpec` #48; (c) files untouched: #15 | remove | – |
| 15 | › `-fJ <report> -fJ <other>`; l. 135 | (a) `TestCommandArgumentsSpec` "given a second -fJ should reject it, naming it"; (b) as 9(b); (c) an existing `-fJ` file left as it was, no other created: none | keep | End-to-end proof of the `-fJ` rejection path. |
| 16–17 | › `-oJ -fJ <other>`, `-oP -fJ <other>`; l. 135 | (a) as 9(a); (b) `TestCommandArgumentsIntegrationSpec` #48 (`-oP`); (c) #15 | remove | – |
| 18 | › `-fJ` with no path; l. 135 | (a) `TestCommandArgumentsSpec` "given -fJ with nothing after it should reject it as missing its path"; message `TestCommandFailureSpec` "-fJ has no path after it…"; (b), (c) #15 | remove | – |
| 19 | Deleting and writing › `nothing.test`; l. 166 | (a) fails before any worker, no output: `ModuleSelectorSpec` #3; (b) an existing report deleted before selection fails: `ModulesTheTestCommandCannotRunSpec` #10 (AC-016); `JsonOutputFileSpec` "deleted should remove a file…" | remove | – |
| 20 | › `core`; l. 166 | (a) `ModuleSelectorSpec` #1; (b) `ModulesTheTestCommandCannotRunSpec` #10 | remove | – |
| 21 | › a folder at the path; l. 186 | (a) `JsonOutputFileSpec` "…should fail on a folder at its path, and keep the folder…"; message `TestCommandFailureSpec` "the JSON output path is a folder should name the path"; (b) fails before any worker: none | keep | A failed delete fails the command before any test. |
| 22 | › a path inside a regular file; l. 205 | (a) `JsonOutputFileSpec` "…written should fail with the cause, where a parent is a regular file"; (b) fails after passing tests, output still written: none | keep | `jsonOutputWritten` failing a passing run. |
| 23 | › a relative path; l. 222 | (a) resolved against the workspace root, no other `report.json`: none | keep | – |
| 24 | › creates missing parent folders; l. 242 | (a) `JsonOutputFileSpec` "…written should create its missing parent folders"; end to end #23 (`reports/` is created) | remove | – |

AC and requirement risks: no AC is proved by this suite. Removals rest on `SeveralModulesSpec` #1, #12, #22, #30 and `ModulesTheTestCommandCannotRunSpec` #10, which stay. R-077 keeps #7; R-125b keeps #15.

Savings: 18 removals, 293.7 solo s (the nine rejections #9–#14, #16–#18 are 99.9 s), 18 sandboxes and 18 evals. After P2–P4: 16 warm evals in the group (#1 and #6 share one) plus #8's own sandbox, about 80 s.

## `JsonOutputNodesSpec` (16 scenarios: 13 remove, 0 simplify, 3 keep)

The suite proves that real suites run by the test command give the JSON node fields. Each row is three links, each tested elsewhere: the emitter (`dotty/scalatest-test` `TestEventLog*Spec`, real suites), the library (library `JsonOutputNodesSpec`, notation event logs) and Mill transport (`JsonOutputTreeSpec` #1, `JsonOutputRootSpec` #1, integration `LibraryWithoutMillSpec` #2). `JsonOutputTreeSpec` #1 also asserts end to end the outcomes `failed`, `canceled`, `ignored`, `pending`, a pending scope, nested scopes and a nested suite. Kept: #2, #11, #15.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | failed assertion; l. 22 | library "A failed test holds its outcome…location"; emitter `TestEventLogEventsSpec` "A StackDepth exception"; `JsonOutputTreeSpec` #1 | remove | – |
| 2 | canceled test; l. 43 | (a) outcome: `JsonOutputTreeSpec` #1; (b) message `no database` and `TestCanceledException` from a real cancel: library (notation log) and `TestEventLogEncoderSpec` "write every field the format keeps of a …" (a constructed event); a real cancel in the emitter only in `UpstreamCompatibilitySpec` (`--compat`) | keep | A real cancel's message and exception class through the emitter. |
| 3 | ignored test; l. 53 | library "An ignored test has the end time and thread of its TestIgnored…"; emitter `TestEventLogNodeEventsSpec` "A suite with a tag excluded and a test ignored"; `JsonOutputTreeSpec` #1 | remove | – |
| 4 | pending test; l. 63 | `JsonOutputTreeSpec` #1; library | remove | – |
| 5 | scope label, location, times; l. 70 | library "A scope that closed holds its name, label, location and times…"; `JsonOutputWriterSpec`; emitter `TestEventLogNodeEventsSpec` "A suite whose tests run"; `JsonOutputTreeSpec` #1 | remove | – |
| 6 | pending scope; l. 89 | `JsonOutputTreeSpec` #1 (`A queue` pending); library | remove | – |
| 7 | suite aborted before its tests; l. 96 | library "A suite that aborts before its tests run is aborted, and its test is aborted with reason notRun"; emitter `TestEventLogNodeEventsSpec` "A suite whose beforeAll throws"; end to end `TestCommandArgumentsIntegrationSpec` #12 | remove | – |
| 8 | nested suite; l. 109 | `JsonOutputTreeSpec` #1; library; emitter `TestEventLogNodeEventsSpec` "Nested suites sit under the suite that nests them" | remove | – |
| 9 | test run twice; l. 119 | library "A test executed twice is two nodes, in the order they started"; emitter `TestEventLogNodeEventsSpec` "A suite runs a failed test again" | remove | R-130e loses its only integration test. |
| 10 | messages in order; l. 129 | library (four kinds, in order); emitter `TestEventLogEventsSpec` "MessagesSpec, an AnyFunSpec: a test that succeeds" | remove | – |
| 11 | message from another thread; l. 142 | library; emitter: none | keep | The thread name through the real emitter. |
| 12 | cause and suppressed; l. 153 | library; emitter `TestEventLogEventsSpec` "A cause chain with a suppressed throwable" | remove | – |
| 13 | cycle of causes; l. 172 | library; `TestEventLogEncoderSpec` "writing a throwable should end a cycle of causes with a ref"; `TestEventLogDecoderSpec` "…should yield a cycle of causes as a cycle" | remove | – |
| 14 | throwable with no message; l. 189 | library; `JsonOutputWriterSpec` "…should omit each unknown value, and mark only a native frame"; emitter `TestEventLogEventsSpec` "A throwable whose message is null" | remove | – |
| 15 | top-level suites ordered by name; l. 202 | library; end to end: none | keep | Ordering by `suiteName` (R-105). |
| 16 | tests in declared order; l. 209 | library "A suite's tests are in declared order, not the order they ran"; emitter `TestEventLogEmitterSpec`, `TestEventLogStructureSpec` "A suite whose tests run in random order" | remove | – |

AC and requirement risks: no AC. R-130e loses its only integration test (#9). R-098c and R-095a keep `TestCommandArgumentsIntegrationSpec` #12 and `TestCommandResultSpec` #7; R-097b keeps #11; R-105 keeps #15.

Savings: 13 removals, 247.8 solo s, 13 sandboxes and 13 evals. Once P3 merges the rows they save about nothing; P3 itself becomes a merge of #2, #11 and #15.

## `ModulesTheTestCommandCannotRunSpec` (10 scenarios: 6 remove, 0 simplify, 4 keep)

The suite proves end to end that the check before any test starts rejects modules the test command cannot run, a bad `testArgsDefault` and a selector with no test module: Mill fails before any test, the earlier `-fJ` file is deleted and nothing is written. Kept: #7 (AC-011), #8 (AC-020), #9 (AC-019), #10 (AC-016).

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1–6 | Module selectors alpha.test and `munit.test` / `utest.test` / `upstream.test` / `other.test` / `js.test` / `native.test`; l. 74 | (a) fails, the console holds the cannot-run header and a line naming the module; (b) no markers; (c) nothing at `<report>`; (d) no consolidated output: #7 asserts all four for all six, with `alpha.test` also selected; format `TestCommandFailureSpec` "…the test command cannot run selected test modules should name each module…"; classification `ModuleCheckSpec` "…another version…", "…upstream ScalaTest's core…" | remove | AC-010 loses every proving scenario. |
| 7 | A selector that resolves to every test module; l. 87 | (a) names each of the six; (b)–(d) | keep | AC-011, R-059, R-077; also the proof for `TestCommandResultSpec` #11–#13. |
| 8 | Release reached through `moduleDeps`; l. 99 | none (`ModuleCheckSpec` is given resolved dependencies) | keep | AC-020. |
| 9 | `testArgsDefault` holds an unrecognised argument; l. 120 | (a) fails, names `args.test` under the header and `--not-a-scalatest-argument`: `ModuleCheckSpec`, `ModuleScalaTestArgumentsSpec`, `TestCommandFailureSpec` (unit); (b)–(d) no markers, `<report>` deleted, no consolidated output: none for this failure | keep | AC-019, R-142; `TestCommandArgumentsIntegrationSpec` #54 goes in its favour. |
| 10 | Module selectors alpha.test and util; l. 138 | (a) fails naming `util`: `TestCommandFailureSpec` "…name the selector"; (b)–(d): none for a failing selector beside one that resolves | keep | AC-016. |

AC risks: AC-010 has no proving scenario left; its claims are asserted by #7 and the unit tests named in rows 1–6.

Savings: 6 removals, 180.8 solo s, 6 sandboxes and 12 evals (each makes the setup eval and its own). After P2: the group of 7 leaves #7 alone; six warm evals, about 24 s.

## `CapturedOutputSpec` (11 scenarios: 2 remove, 0 simplify, 9 keep)

End to end, the suite proves that each worker's stdout and stderr reach the console only inside the human-readable output, through Mill as a user runs it. Kept: daemon #1, #2; `--no-daemon` #7 and the rest; the JVM's own output #5; `testLogLevel` Debug #6; sandboxing off #8; console isolation with the ticker on #9; another goal's output #10; a repeated command #11.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | stdout line under `Run 1:`, daemon; l. 41 | exact colour-marked tree through the daemon's client: none | keep | – |
| 2 | each stream's order, daemon; l. 55 | 2000 lines per stream in order through the daemon: none (order alone: `StreamLinesSpec`, `OutputCaptureSpec`, `HumanReadableRunSpec` "Captured stdout and stderr lines keep the order they were fed…") | keep | – |
| 3 | stdout line under `Run 1:`, `--no-daemon`; l. 41 | exact colour-marked tree: #1 (daemon); #7 (`--no-daemon`, `--color true` and `false`, same expected tree); colour with the ticker off: `SeveralModulesSpec` #18 | remove | – |
| 4 | each stream's order, `--no-daemon`; l. 55 | as #2: #2 (daemon); order in the unit tests at #2; both streams captured under `--no-daemon`: `EveryWorkerSpec` #15, `CompatibilityFixtureSpec` #1 | remove | – |
| 5 | the JVM's own line; l. 74 | captured unchanged before ScalaTest starts: `OutputCaptureSpec` (unit) | keep | R-076. |
| 6 | `testLogLevel` Debug; l. 102 | no `Running Test Class`: none | keep | R-129. |
| 7 | colours with Mill's colour on and off; l. 124 | `--color false` keeps the output's colours: none | keep | R-106f. |
| 8 | sandboxing off, `forkEnv` and working directory; l. 147 | (a) `forkEnv`: `TestCommandArgumentsIntegrationSpec` #31; (b) working directory as `testForked` gives it: none | keep | – |
| 9 | failing test writing both streams, ticker on; l. 177 | (a) no summaries: `TestCommandOutputSpec` #6; (b), (c) with the ticker on, and the control without the plugin: none | keep | R-044, R-040. |
| 10 | a task named before the test command; l. 209 | `building banner` outside the output, with Mill's prefix: none | keep | R-050a. |
| 11 | a second command after a fix; l. 232 | only the new run, succeeds, in a warm workspace: none | keep | – |

AC and requirement risks: none. R-043, R-087 and R-106f keep #1, #2, #7.

Savings: 2 removals, 34.7 solo s, 2 sandboxes and 2 evals. After P2: two warm evals in the `--no-daemon` group, about 8 s.

## `TestCommandResultSpec` (13 scenarios: 9 remove, 0 simplify, 4 keep)

The suite proves end to end that the consolidation state becomes Mill's result, with the message `The tests of core.test failed`. Kept: a registered test that never runs #7, a worker JVM that dies after its run started #8, a JVM that ends before any run #9, a failed test #10.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | a succeeding test; l. 27 | (a) state stays open: `ConsolidationSpec` "…given a run whose events report no failure should stay open"; (b) Mill succeeds: integration `LibraryWithoutMillSpec` #1, `TestCommandArgumentsIntegrationSpec` #45, `SeveralModulesSpec` #5 | remove | – |
| 2 | ignored and pending beside a succeeding test; l. 35 | (a) real `TestIgnored`, `TestPending` through Mill: `JsonOutputTreeSpec` #1; (b) state open: `ConsolidationSpec` "…should stay open where a test is ignored or pending"; (c) as 1(b) | remove | R-046a loses its only integration test. |
| 3 | a cancelled test; l. 43 | (a) real `TestCanceled` through Mill: `JsonOutputTreeSpec` #1, `JsonOutputNodesSpec` #2; (b) state fails: `ConsolidationSpec` "given a run with a test that was cancelled should be open and failed"; (c) failed state, Mill fails with the message: #10 | remove | – |
| 4 | a failing test; l. 51 | #10 (same build and message check, more assertions) | remove | – |
| 5 | a suite aborted by `beforeAll`; l. 59 | (a) real abort fails the command: `TestCommandArgumentsIntegrationSpec` #12; (b) state fails: `ConsolidationSpec` "given a run with a suite that aborted…"; (c) the message for a failed state: #10 | remove | – |
| 6 | a skipped registered test is not run; l. 70 | (a) `*** NOT RUN ***`: `HumanReadableRunSpec` "A test with no events and no test to attribute it to shows as not run, and fails the state"; a real run without Mill: `ChangedRegistrationOrderSpec` "A test that later instances no longer register comes after the test that replaces it…"; (b) state fails: same; (c) Mill fails: #7, #10 | remove | – |
| 7 | `ConfigMapWrapperSuite`, `local` not run; l. 89 | (d) a real `ConfigMapWrapperSuite` whose registrations change with the config map: none | keep | The not-run path end to end. |
| 8 | `System.exit(0)`; l. 116 | (c) a real worker dies mid-run, Mill fails: none | keep | – |
| 9 | JVM exits before any run; l. 124 | (c) a JVM that refuses `-XX:+NoSuchOption`: none (`EveryWorkerSpec` #21 is Mill refusing to start a worker) | keep | – |
| 10 | a failed test not named outside the output; l. 138 | (a) Mill fails with the message: #7, #8, #9; (c) the bare test name nowhere else: none (`CapturedOutputSpec` #9 checks other strings) | keep | – |
| 11–13 | a module testing with MUnit / uTest / `org.scalatest:scalatest 3.2.20`; l. 161 | (a) fails before any test, naming the module: `ModulesTheTestCommandCannotRunSpec` #7 (each of `munit.test`, `utest.test`, `upstream.test` named under the header; no markers); (b) the reason `does not depend on com.edc:scalatest-core_3`: `TestCommandFailureSpec` "…should name each module, on its own line, with why…"; `ModuleCheckSpec` "…upstream ScalaTest's core…" | remove | – |

AC and requirement risks: no AC. R-046a loses its only integration test (#2).

Savings: 9 removals, 139.2 solo s, 9 sandboxes and 9 evals. After P2: rows 1–6 are warm evals in the shared `result` build (24 s); rows 11–13 have their own fixtures (31.7 s): about 56 s.

## `MillOwnTestTasksSpec` (6 scenarios: 0 remove, 0 simplify, 6 keep)

The suite proves R-060: each of Mill's own test tasks gives the same exit code, stdout and stderr with the plugin as without it (l. 37). Nothing else compares them.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | `mill core.test` | the module's default task unchanged with the plugin: none (that it is `testForked` is Mill's source, not a test) | keep | – |
| 2 | `mill core.test.testForked` | none (`HeaderPluginSpec` #4 asserts only that it passes) | keep | – |
| 3 | `mill core.test.testOnly samples.CalculatorSuite` | none | keep | – |
| 4 | `mill core.test.testCached` | none | keep | – |
| 5 | `mill core.test.testLocal` | none | keep | – |
| 6 | `mill __.test` | none | keep | – |

AC and requirement risks: none. Savings: none.

## `JsonOutputRootSpec` (6 scenarios: 4 remove, 0 simplify, 2 keep)

The suite proves the root, module and run as the test command writes them through Mill. Kept: the whole layout #1, the times nesting #3.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | one run of one suite, root to test; l. 13 | (b) through Mill: `scalaTestVersion`, `buildTool`, module times, `forkGroup` 0, captured stdout: none together | keep | R-084. |
| 2 | config map with each value's `toString`; l. 70 | (a) `-D` reaches the config map: `TestCommandArgumentsIntegrationSpec` #18, #19; `ModuleScalaTestArgumentsSpec`; (b) in the event log: emitter `TestEventLogFurtherStructureSpec` (`-Ddb=on`); `TestEventLogEncoderSpec` "write a config map as an object of each value's toString…"; (c) on the run node: `JsonOutputWriterSpec` "A run node… should hold its values in a fixed order" | remove | – |
| 3 | root's times enclose the module's; l. 82 | none | keep | – |
| 4 | the build tool; l. 111 | #1 (exact `buildTool`) | remove | – |
| 5 | the selector `core.test`; l. 125 | #1 (exact `moduleSelectors`) | remove | – |
| 6 | the selector `__.test`; l. 125 | `SeveralModulesSpec` #8 (AC-002), #9 (AC-014) | remove | – |

AC and requirement risks: none.

Savings: 4 removals, 89.7 solo s, 4 sandboxes and 4 evals. After P2–P4: #4 and #5 shared #1's eval, #6 shared its sandbox, #2 has its own build: about 29 s.

## `TestCommandOutputSpec` (6 scenarios: 4 remove, 0 simplify, 2 keep)

The suite proves the human-readable output's shape and labels, a suite whose constructor throws, and a console free of ScalaTest's and Mill's summaries. Kept: #4 (constructor abort), #6 (no `*** 1 SUITE ABORTED ***` or `tests failed:` for an aborted suite).

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | a module's line heads its suites; l. 28 | exact tree: `AddingThePluginSpec` #1 (same build state, command and tree) | remove | – |
| 2 | every human-readable line carries Mill's prefix; l. 39 | (a), (b) one prefix on every line, summary last: `TestCommandArgumentsIntegrationSpec` #45; (c) summary colours: `HumanReadableSummaryLineSpec` "A part of the summary line keeps its colour where its count is 0…"; through Mill under the ticker, integration `LibraryWithoutMillSpec` #1 | remove | – |
| 3 | each style's labels; l. 76 | `CompatibilityFixtureSpec` #1 (exact tree with a suite of each style) | remove | – |
| 4 | a suite whose constructor throws; l. 100 | none | keep | – |
| 5 | discovery adds nothing; l. 123 | (a) no root or run line: `AddingThePluginSpec` #1; (b) succeeds: `TestCommandArgumentsIntegrationSpec` #45, integration `LibraryWithoutMillSpec` #1 | remove | – |
| 6 | no ScalaTest summary or Mill list; l. 137 | (a) for a failed test: `CapturedOutputSpec` #9; (b) for an aborted suite: none | keep | Shares #4's eval. |

AC and requirement risks: none. R-106h keeps `TestCommandArgumentsIntegrationSpec` #45; R-072a keeps `AddingThePluginSpec` #1.

Savings: 4 removals, 94.8 solo s (#1 carries the shared run's 44.5 s; #5 0 s), 3 sandboxes and 3 evals. After P2: the group of #1, #2, #5 disappears (one sandbox, two evals) and the `styles` sandbox goes: about 35 s.

## `JsonOutputTreeSpec` (2 scenarios: 1 remove, 0 simplify, 1 keep)

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | both outputs hold the same tree; l. 53 | none | keep | R-020; end-to-end outcomes other removals rest on. |
| 2 | module reporter arguments do not change the JSON; l. 75 | (a) withheld: `ModuleScalaTestArgumentsSpec` "…withhold each with its values…" (`-oD`); (b) a module reporter argument changes nothing end to end: `TestCommandArgumentsIntegrationSpec` #57 | remove | R-063's JSON half then rests on these two. |

Savings: 1 removal, 45.3 solo s, 1 sandbox and 2 evals. After P4 it costs one eval with a `build.mill` recompile, about 9 s.

## `HeaderPluginSpec` (8 scenarios: 0 remove, 0 simplify, 8 keep)

An `AnyFreeSpec` ("A build whose header adds the plugin by one line with a test module on Scala <v> …"). It proves that the one header line brings the fork's published jars onto the meta-build and the module classpath, and that Mill's own `testForked` passes.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | 3.3 › has that line as its whole header; l. 29 | none | keep | 0.1 s. |
| 2 | 3.3 › meta-build classpath; l. 34 | jars from the published `com.edc` artifacts: none | keep | – |
| 3 | 3.3 › `core.test` resolves scalatest and scalactic from the fork; l. 43 | every `scalatest*`, `scalactic*` jar is `com.edc`'s: none | keep | – |
| 4 | 3.3 › passes `core.test.testForked`; l. 52 | passes and prints ScalaTest's summary: none (`MillOwnTestTasksSpec` #2 compares with and without the plugin on a failing fixture; `CapturedOutputSpec` #8 checks one line) | keep | – |
| 5 | 3.9 › has that line; l. 29 | none | keep | 0.0 s. |
| 6 | 3.9 › meta-build classpath; l. 34 | none for this fixture | keep | – |
| 7 | 3.9 › resolves from the fork; l. 43 | none for Scala 3.9 | keep | – |
| 8 | 3.9 › passes `core.test.testForked`; l. 52 | none | keep | – |

AC and requirement risks: none. Savings: none.

## `ModuleSelectorSpec` (5 scenarios: 3 remove, 0 simplify, 2 keep)

The suite proves that the test command, given one selector, fails before any test starts unless the selector resolves to JVM test modules. Kept: #1 (a parent of a test module), #3 (Mill's resolve error).

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | `core`, not a test module; l. 35 | (a) fails, no worker, no tree lines: `ModulesTheTestCommandCannotRunSpec` #10; (b) a selector naming the parent of a test module resolves to no test module: none (`util` in AC-016 holds no module; `ResolvedModuleSelector.from` has no unit test) | keep | – |
| 2 | `coreJS.test`, Scala.js; l. 35 | (a), (c): `ModulesTheTestCommandCannotRunSpec` #7 (`js.test`, `native.test`); (b) the reason line: `TestCommandFailureSpec` "…the test command cannot run selected test modules…" | remove | – |
| 3 | `nosuch.test`, no module; l. 35 | (b) Mill's failure to resolve becomes `UnresolvedModuleSelector`: format only, `TestCommandFailureSpec` "…name the selector and Mill's error" | keep | – |
| 4 | `-oH`, no selector; l. 35 | `SeveralModulesSpec` #2 (same message, plus no markers, no report, no summary); `TestCommandArgumentsSpec` "given no module selector…"; `TestCommandFailureSpec` "…no module selector is given…" | remove | – |
| 5 | `core.test` runs it; l. 53 | succeeds, a worker started, tree starts `[core.test]`: `TestCommandArgumentsIntegrationSpec` #45 (exact tree, worker started); `CapturedOutputSpec` #10; `AddingThePluginSpec` #1 | remove | – |

AC and requirement risks: no AC is proved here; AC-010, AC-016 and AC-018 keep their proofs elsewhere.

Savings: 3 removals, 40.9 solo s, 3 sandboxes and 3 evals. After P2: three warm evals, about 12 s; the remaining #1 and #3 both fail before any test.

## `AddingThePluginSpec` (2 scenarios: 0 remove, 0 simplify, 2 keep)

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | header line alone, Scala 3.3; l. 12 | exact tree after `addPlugin`: none once `TestCommandOutputSpec` #1, #5 go | keep | R-092. |
| 2 | header line alone, Scala 3.9; l. 12 | the only test-command run on Scala 3.9: none | keep | – |

## `MillBspServerSpec` (5 scenarios: 0 remove, 0 simplify, 5 keep)

One daemon tester records one BSP session without the plugin and one with it; each scenario compares one part (R-003). Nothing else drives Mill's BSP server, and the sessions send every request whatever the scenarios assert, so removing a scenario saves nothing.

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | same `workspace/buildTargets`; l. 49 | none | keep | – |
| 2 | same `buildTarget/scalaTestClasses`; l. 49 | none | keep | – |
| 3 | same `buildTarget/jvmTestEnvironment`; l. 49 | none | keep | – |
| 4 | same error for `buildTarget/test`; l. 57 | none | keep | – |
| 5 | same notifications, none holding `[core.test]`; l. 65 | none | keep | – |

## `LibraryWithoutMillSpec` (2 scenarios: 0 remove, 0 simplify, 2 keep)

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | the same human-readable output without Mill; l. 120 | a `Framework` launch fed to the library gives the test command's colour-marked lines, summary included: none | keep | R-113; the success-path and summary-colour proof other removals rest on. |
| 2 | replaying the run gives the same JSON text; l. 146 | none | keep | R-113. |

## `CompatibilityFixtureSpec` (1 scenario: 0 remove, 0 simplify, 1 keep)

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | the compatibility fixture shown in full; l. 259 | the whole real run consolidated to the exact tree: none (`UpstreamCompatibilitySpec` checks the event log only, under `--compat`) | keep | Also the label proof for `TestCommandOutputSpec` #3. |

## `JsonOutputEncodingSpec` (1 scenario: 0 remove, 0 simplify, 1 keep)

| # | Scenario | Claims, and what else asserts each | Class | What goes / what only it proves |
|---|---|---|---|---|
| 1 | escaped characters written as UTF-8, read back unchanged; l. 27 | (a) escape and read-back: library `JsonOutputEncodingSpec` "Text is written verbatim, with a lone surrogate escaped…"; (b) emitter `TestEventLogEventsSpec` "Text: a text holding a lone surrogate"; (c) split UTF-8: `EventLogLinesSpec`; (d) the file is UTF-8: `JsonOutputFileSpec`; (e) the file holds no JSON `null`: none | keep | (e). |

AC and requirement risks: none.
