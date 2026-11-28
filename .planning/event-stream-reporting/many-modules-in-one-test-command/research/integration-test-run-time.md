# Where the Mill plugin's integration test time goes, and how to cut it

Why `plugin.test.testForked + plugin.integration.testForked` in `mill-plugin/` took 2388 s, and which changes to the integration tests would shorten the run without weakening them, and how a build task could run only the integration tests for the area it changes (issue #260). Scope: the run of 2026-11-27 07:55–08:34 (local time) on the development machine, the suites and fixtures in `mill-plugin/plugin/integration/`, the lower-layer tests that cover them, the plugin's code in `mill-plugin/plugin/src/` and its history, `test-all.sh`, `test-all.ps1` and `.github/workflows/ci.yml`. No test, build or script was changed, and no run was made for this note: every number comes from the run's files on disk.

## Short answer

- **Almost all the time is the integration suites.** `plugin.integration.testForked` took 2364.5 s of the 2388 s; `plugin.test.testForked` took 5.6 s and compiling took about 20 s (§ 1.1).
- **One suite sets the wall clock.** `TestCommandArgumentsIntegrationSpec` (59 scenarios) ran 2167 s on one worker. It was claimed 173 s into the run, because Mill hands out classes in name order, and it was the only suite left for the last 409 s. The 16 workers were busy 48 % of the run (§ 1.2).
- **The machine is oversubscribed, so parallelism buys little.** Each of the 16 workers launches its own nested Mill JVMs on 8 cores. The same build.mill compile took a median 5.7 s with one worker active and 31.7 s with 16 active. The 16 workers deliver about 2.9 times the throughput of one; the run as a whole was about 1.85 times faster than running every scenario alone, one after another (§ 1.3).
- **The repeated work is a cold build per scenario.** Each of the 216 sandboxes starts from a copy of its fixture, so each pays a cold Mill JVM, a build.mill compile and a test-module compile. Uncontended, a fresh scenario costs 10–15 s, of which 5.7 s is compiling build.mill. The same command in a sandbox already compiled costs 3–5 s (§ 1.4). The 229 scenarios need only about 108 distinct (build.mill, sources) states (§ 1.5).
- **Other tests already cover 104 of the 229 scenarios.** The consolidation library's behaviour tests, the unit tests and other integration scenarios assert every claim of 100 scenarios, and part of 4 more, while each integration path keeps an end-to-end proof. Removing or simplifying them takes away 44 % of the solo work. One acceptance criterion, AC-010, would lose its proving scenario, though AC-011 asserts the same claims (P5).
- **Recommended:** split the two longest suites by Feature, remove or simplify the covered scenarios, then share one sandbox among the remaining scenarios whose build and sources are identical, and one eval among those that run the same command. The model in § 4 predicts about 900–930 s for the integration suites instead of 2364 s, so a full run of about 930–950 s, 60 % less; a more cautious count gives about 1130–1150 s, 52 % less (§ 3).
- **Then, separately, tag the suites by area.** Seven area tags, following the test command's stages, and a core set of eight scenarios (about 169 solo-seconds) let a build task run the integration tests for the area it changes rather than all of them. Tagging adds no saving to the full run. It follows P1, P5, P2 and P4, because P1 turns most tags into whole classes and P5 removes the scenarios that would otherwise need tagging. The same tags can also shard the full run across parallel CI jobs (P6).

## Sources

| Tag | Source |
|---|---|
| `[report]` | `mill-plugin/out/plugin/integration/testForked.dest/test-report.xml`: per-suite and per-scenario times. The report's 229 test cases have 229 distinct names, so the repeats recorded in `.docs/learnings/mill-build-tooling.md` (2026-11-18) did not occur in this run. |
| `[claim]` | `mill-plugin/out/plugin/integration/testForked.dest/worker-NN/claim.log`: each worker's `CLAIM` and `COMPLETED` `System.nanoTime` stamps, anchored to wall time through the file's modification time. |
| `[sandbox]` | `mill-plugin/out/plugin/integration/testForked.dest/worker-NN/sandbox/run-N/out/mill-chrome-profile.json`: Mill's task timeline for the last eval in each of the 216 sandboxes. |
| `[top]` | `mill-plugin/out/mill-chrome-profile.json`: the outer command's task timeline. |
| `[log]` | `test-all-logs/mill-plugin.log`: an earlier run (2026-11-26), 2053 s. |
| `[testkit]` | `mill-testkit_3-1.3.1-sources.jar` in the Coursier cache: `mill/testkit/IntegrationTester.scala`, `mill/testkit/IntegrationTesterBase.scala`. |
| `[this]` | This repo at `22ba3665c`. Suites and helpers are in `mill-plugin/plugin/integration/src/org/scalatest/mill/`. |
| `[coverage]` | [`integration-test-coverage.md`](integration-test-coverage.md): the per-scenario classification behind P5, made against this repo at `2552d698b`, the plugin's unit tests in `mill-plugin/plugin/test/src/org/scalatest/mill/`, the consolidation library's tests in `dotty/consolidation/src/test/scala/`, and the emitter's tests in `dotty/scalatest-test/src/test/scala/`. |
| `[mill]` | `mill-libs-javalib_3-1.3.1-sources.jar` in the Coursier cache: `mill/javalib/TestModule.scala`. |
| `[mainargs]` | `mainargs_3-0.7.8-sources.jar` in the Coursier cache: `Macros.scala`. |
| `[history]` | `git log -- mill-plugin/plugin/src` in this repo at `40a43a338`. |
| `[machine]` | Intel i9-11900KF, 8 cores and 16 logical processors, 32 GB RAM, Windows 11, Defender real-time protection on (exclusions not readable without admin rights). |

The files under `mill-plugin/out/` are build output and will be overwritten by the next run. The scripts that derived the numbers were throwaway and are not kept.

## 1. Where the time goes

### 1.1 The outer command

`[top]`: the whole command took 2388.0 s.

| Task | Start (s) | Duration (s) |
|---|---|---|
| `plugin.compile`, `plugin.test.compile`, `plugin.integration.compile` | 3.3 | about 20, overlapping |
| `plugin.test.testForked` | 18.4 | 5.6 |
| `plugin.integration.testForked` | 23.3 | 2364.5 |
| first integration suite starts | 48.2 | – |

The unit tests are not worth optimising. Everything below is about `plugin.integration`.

### 1.2 Wall clock, summed time and the critical path

`[report]` sums the 19 suites' times to 17,896 s, because 16 workers ran at once. `[claim]` gives each suite's start and end:

| Worker | Suites, in order | Start → end (s from the first claim) |
|---|---|---|
| 06 | `JsonOutputEncodingSpec`, then `TestCommandArgumentsIntegrationSpec` | 0 → 173, then 173 → **2339** |
| 15 | `SeveralModulesSpec` | 2 → 1930 |
| 03 | `EveryWorkerSpec` | 0 → 1629 |
| 05 | `JsonOutputArgumentSpec` | 1 → 1621 |
| 07 | `JsonOutputNodesSpec` | 0 → 1433 |
| 14 | `ModulesTheTestCommandCannotRunSpec` | 1 → 1431 |
| 01 | `CapturedOutputSpec` | 0 → 1328 |
| 10 | `LibraryWithoutMillSpec`, then `TestCommandResultSpec` | 1 → 312, then 312 → 1312 |
| 12 | `MillOwnTestTasksSpec` | 1 → 1104 |
| 02 | `CompatibilityFixtureSpec`, then `TestCommandOutputSpec` | 0 → 211, then 211 → 798 |
| 08, 09, 04, 00, 13, 11 | `JsonOutputRootSpec`, `JsonOutputTreeSpec`, `HeaderPluginSpec`, `AddingThePluginSpec`, `ModuleSelectorSpec`, `MillBspServerSpec` | each ends between 366 and 717 |

- The first 16 classes claimed are the first 16 in name order; the last three (`TestCommandArgumentsIntegrationSpec`, `TestCommandOutputSpec`, `TestCommandResultSpec`) went to the first three workers to finish. The longest suite therefore started late by the accident of its name.
- The queue was empty from 312 s. After that, workers only went idle: 9 were active at 800 s, 4 at 1433 s, 2 at 1629 s, and from 1930 s to 2339 s only worker 06 ran.
- Busy worker-seconds were 17,906 of 37,432 available: **48 % utilisation**.

The 2026-11-26 run `[log]` had the same shape: `TestCommandArgumentsIntegrationSpec` reported `Run completed in 33 minutes, 28 seconds.` and finished last, at 2053 s. Run-to-run variation between the two runs is about 15 %.

### 1.3 Contention

Every sandbox's first eval compiles the fixture's build.mill: one Scala source, with the plugin on the classpath. That is nearly the same work in every sandbox, so its duration measures how loaded the machine was. Taking each `compile` task in `[sandbox]` and counting the workers active at its midpoint:

| Workers active | Samples | Median build.mill compile (s) | Slowdown | Throughput vs one worker |
|---|---|---|---|---|
| 1 | 22 | 5.7 | 1.0 | 1.0 |
| 2 | 24 | 8.7 | 1.5 | 1.3 |
| 4 | 20 | 14.3 | 2.5 | 1.6 |
| 8 | 22 | 24.6 | 4.3 | 1.9 |
| 9–10 | 30 | 25.1 | 4.4 | 2.1–2.3 |
| 16 | 27 | 31.7 | 5.6 | 2.9 |

So throughput saturates at about 2.9 times one worker. Each worker runs a chain of JVMs at once: the outer test worker, the nested Mill launcher, the nested Mill process (`--no-daemon`, so cold every eval) and the nested test worker, each with its own JIT and GC threads, on 8 physical cores. Memory pressure (16 such chains in 32 GB) and on-access virus scanning of the freshly copied sandboxes may add to it; neither can be measured from the files on disk.

The same effect shows in a single suite. `TestCommandArgumentsIntegrationSpec`'s Class globs scenarios ran while 15–16 workers were active and took 80–230 s each; its output-argument scenarios ran alone at the end and took 10–15 s each, though they do comparable work.

Dividing each scenario's time by the slowdown at its midpoint gives its **solo-equivalent** cost: what it would take with the machine to itself. Summed over all 229 scenarios this is about **4337 s**, against the 2339 s the run took, so the 16 workers bought a speed-up of about 1.85.

### 1.4 What one scenario spends its time on

Each `withFixtureBuild` creates an `IntegrationTester` (`FixtureBuilds.scala` lines 8–19). The tester copies the fixture into a new `run-<n>` folder (`[testkit] IntegrationTesterBase.scala` lines 42–46) and runs every eval with `--no-daemon` (`[testkit] IntegrationTester.scala` line 232), because `daemonMode` defaults to false (`FixtureBuilds.scala` line 8). On close it sleeps 500 ms (`[testkit] IntegrationTesterBase.scala` line 123).

A fresh sandbox, measured alone at the end of the run (`TestCommandArgumentsIntegrationSpec`, "With no -o, stdout holds the human-readable output…", sandbox `worker-06/sandbox/run-46`, 14.6 s in `[report]`):

| Step | Seconds |
|---|---|
| Copy the fixture, start the launcher and the Mill JVM | about 1.5 |
| Meta-build: resolve and set up (`meta-level 1` less `compile`) | 1.9 |
| Compile build.mill (`compile`) | 5.7 |
| Compile the fixture's test module (`core.test.compile`) | 2.0 |
| The test command itself, including the nested test worker | about 2.0 |
| Assertions, tester close (including the 500 ms sleep) | about 1.5 |

The same scenario shape in a sandbox already compiled, measured alone (`worker-06/sandbox/run-42` to `run-44`, the second evals of three Module folder scenarios): the eval took 1.9–3.4 s, of which `meta-level 1` was 1.4 s with no compile. Adding the launcher's start, a scenario run this way would cost **3–5 s instead of 10–15 s**.

So about 7–9 s of each fresh scenario, uncontended, is building a sandbox that an earlier scenario already built. A scenario that fails at argument parsing (for example "-oP, holding an output letter other than H, fails the test command", 10.6 s) spends more than half its time compiling build.mill and runs no test.

### 1.5 Per-suite cost

Sandboxes and evals are counted from the code; solo-equivalent is from § 1.3. "Distinct states" is the number of different (build.mill, sources) states the suite's test commands run in, assuming no scenario changes.

| Suite | Scenarios | Sandboxes | Evals | Distinct states | Suite time (s) | Solo-equivalent (s) |
|---|---|---|---|---|---|---|
| `TestCommandArgumentsIntegrationSpec` | 59 | 59 | 68 | 26 | 2167 | 952 |
| `SeveralModulesSpec` | 30 | 30 | 32 | 13 | 1928 | 573 |
| `EveryWorkerSpec` | 22 | 22 | 22 | 18 | 1628 | 374 |
| `JsonOutputArgumentSpec` | 24 | 24 | 24 | 3 | 1620 | 374 |
| `JsonOutputNodesSpec` | 16 | 16 | 16 | 16 (1 if merged, P3) | 1433 | 297 |
| `ModulesTheTestCommandCannotRunSpec` | 10 | 10 | 20 | 4 | 1429 | 297 |
| `CapturedOutputSpec` | 11 | 11 | 15 | 5 | 1328 | 271 |
| `TestCommandResultSpec` | 13 | 13 | 13 | 6 | 1000 | 213 |
| `MillOwnTestTasksSpec` | 6 | 1 | 24 | 2 | 1103 | 212 |
| `JsonOutputRootSpec` | 6 | 6 | 6 | 2 | 717 | 131 |
| `TestCommandOutputSpec` | 6 | 4 | 4 | 2 | 587 | 112 |
| `JsonOutputTreeSpec` | 2 | 2 | 3 | 2 | 548 | 99 |
| `HeaderPluginSpec` | 8 | 6 | 6 | 2 | 505 | 91 |
| `ModuleSelectorSpec` | 5 | 5 | 5 | 1 | 430 | 77 |
| `AddingThePluginSpec` | 2 | 2 | 2 | 2 | 424 | 76 |
| `MillBspServerSpec` | 5 | 1 | 2 BSP sessions | 2 | 365 | 65 |
| `LibraryWithoutMillSpec` | 2 | 2 | 3 | 1 | 311 | 55 |
| `CompatibilityFixtureSpec` | 1 | 1 | 1 | 1 | 211 | 37 |
| `JsonOutputEncodingSpec` | 1 | 1 | 1 | 1 | 172 | 31 |
| **Total** | **229** | **216** | **265** | **about 108** | 17,896 summed | **4337** |

The 216 sandboxes match the 216 `run-<n>` folders under the workers.

Other costs found, none large:

- 216 tester closes × 500 ms = 108 s of solo time.
- `MillOwnTestTasksSpec` runs `resolve _` before each of its 12 tasks (`MillOwnTestTasksSpec.scala` lines 26–30). Only the first in each plugin state compiles anything; the other 10 are cold Mill starts that do nothing.
- Three scenarios in `ModulesTheTestCommandCannotRunSpec` edit build.mill after the shared setup eval, so their sandboxes compile build.mill twice.
- Fixed waits inside fixture tests (`EveryWorkerSpec.scala` line 242 sleeps 3 s; polling loops at `EveryWorkerSpec.scala` lines 618–621 and `SeveralModulesSpec.scala` lines 46–49) are part of what those scenarios test.

### 1.6 CI

`.github/workflows/ci.yml`'s `test-mill-plugin` job runs the same command on one `ubuntu-latest` runner, which for a public repository has 4 vCPUs, so Mill starts 4 test workers. The repository has no recorded workflow runs (`gh run list` returns none), so the CI duration is unknown. By the throughput table in § 1.3, 4 workers deliver about 1.6 times one worker, which puts the CI run near 45 minutes at this machine's per-core speed, and longer on slower cores. This is an estimate, not a measurement.

## 2. Proposed changes

Savings are for `plugin.integration.testForked` on the development machine, from the model in § 4. A solo-second removed saves about 0.35 s of wall clock once the load is balanced, but saves nothing while one suite remains the critical path.

### P1. Split the two longest suites into one suite per Feature

**What.** Make each Feature of `TestCommandArgumentsIntegrationSpec` its own suite class (six: Class globs, line 147; Framework arguments, 315; The environment of each worker, 453; The module folder, 550; The test command's output arguments, 688; The module's own ScalaTest arguments, 774), and likewise `SeveralModulesSpec` (four: lines 169, 311, 444, 506). Move the shared private helpers into a trait beside `FixtureBuilds`.

**Saving.** No work is removed; the load is rebalanced. The largest unit falls from 952 to 292 solo-seconds. Predicted integration time 1740–1820 s instead of 2335 s (**about −550 s**). Splitting every suite by Feature (45 units) predicts 1710–1760 s, little more.

**Risk.** Low. Scenarios, assertions and their order within each Feature are unchanged. More classes means more worker JVM starts, about 1–2 s each. Suite names must still end in `Spec` (`edc-bestpractice/scala/scalatest.md`, "Suite Names Ending in `Spec`").

**Touches.** `TestCommandArgumentsIntegrationSpec.scala`, `SeveralModulesSpec.scala`, new suite files, one new helper trait. Nothing outside `plugin/integration/src`.

### P2. Share one sandbox among scenarios whose build.mill and sources are identical

**What.** Give each group of scenarios with the same build.mill and sources one `IntegrationTester`, created on first use and closed after the group (the `lazy val` pattern `MillOwnTestTasksSpec.scala` lines 18–24 already uses), with an explicit reset before each scenario. The groups and the resets they need:

| Suite | Today → after | Main groups and resets |
|---|---|---|
| `TestCommandArgumentsIntegrationSpec` | 59 → about 28 sandboxes | 21 scenarios on the unedited class-globs build (Class globs 1–5 and 9–11, Framework arguments 1–4 and 9–16, Module folder 8); the 9 output-argument scenarios on `arguments` + `CalculatorSuite`; 3 `testReportXml` scenarios. Reset: empty `markers/`, delete `report.json` and `out/org.scalatest.mill.TestCommandModule/modules/core.test` before any scenario that fails at argument parsing, because parsing fails before the plugin clears them (`plugin/src/org/scalatest/mill/TestCommand.scala` lines 126–140). Run "The test command leaves Mill's own report from an earlier testForked…" last in its group, because it leaves a `testargs` file under `out/core/test/testForked.dest` that every later "no test worker started" check would find. |
| `JsonOutputArgumentSpec` | 24 → 3 | 22 scenarios on `json-output` + `CalculatorSuite`. Reset the module folder before the 13 that fail before it is cleared; rewrite `report.json` as "left over" where a scenario expects it; remove the folder, `blocker`, `reports/` and `missing/` that individual scenarios create. |
| `SeveralModulesSpec` | 30 → 13 | 8 scenarios on the base build; the 5 class-globs scenarios; broken-module groups. Reset `markers/` before each eval (several scenarios count marker lines exactly). |
| `EveryWorkerSpec` | 22 → 11 build texts | Scenarios on the same build text share a sandbox and swap sources, which recompiles only the test module. Delete `markers/` before the scenario whose two suites wait for each other (`EveryWorkerSpec.scala` line 608 fails if it exists). |
| `ModulesTheTestCommandCannotRunSpec` | 10 → 4 | The 6 "alpha.test and X" scenarios and the `__.test` one share one sandbox and one setup eval; restore the setup's `report.json` by copying it, and empty `markers/`. |
| `TestCommandResultSpec`, `CapturedOutputSpec`, `TestCommandOutputSpec`, `HeaderPluginSpec`, `ModuleSelectorSpec` | 41 → about 14 | `ModuleSelectorSpec`'s 5 scenarios share one sandbox, with the 4 rejections run before the one that starts a worker. `HeaderPluginSpec`'s 3 read-only evals per Scala version share one sandbox. In `TestCommandResultSpec` and `CapturedOutputSpec`, scenarios that differ only in the suite file share a sandbox and swap the file. |

Scenarios that stay alone: those under a Mill daemon or started through `mill.bat` (separate testers), the cross-module scenario (it asserts the module folder holds exactly one module), the `-fJ` "exactly one report.json in the workspace" scenario, and anything whose Given is a fresh build.

**Saving.** About 108 fewer sandboxes, at 7–9 solo-seconds each (§ 1.4): **about 800 solo-seconds**. Alone it predicts 1840–1850 s (**about −490 s**), because shortening the longest suite is part of it. With P1, 1430–1480 s. After P5, most of these groups shrink or disappear, and P2 removes about 39 sandboxes, about 300 solo-seconds (P5).

**Risk.** Medium; this is the change that most needs care.

- A scenario no longer starts from a clean `out/`. Each group's reset must restore every piece of state an assertion reads; a missing reset shows as a scenario that passes or fails depending on the scenarios before it. Mitigate by putting the reset in one helper that every shared scenario calls first, and by asserting the precondition (for example, no `testargs` under `out/`) rather than assuming it.
- A failure in one scenario can leave state that fails later scenarios in the group. Each group needs a reset that works whatever the previous scenario did.
- Console assertions may change: a fresh sandbox's first eval prints compile lines; a warm one does not. `MillOwnTestTasksSpec` already runs `resolve _` first to keep compile lines out, so scenarios may rely on either. Check each console assertion when its scenario moves.
- Scenario order within a group becomes significant. ScalaTest runs a suite's scenarios in declaration order, so this is deterministic, but a later edit can break it silently.
- Coverage is not reduced: every scenario still runs its own test command against the same build and sources.

**Touches.** `FixtureBuilds.scala` (a shared-tester helper with resets), `FixtureBuild.scala` (reset operations: clear the module folder, the markers and the report), the suites in the table.

### P3. Run `JsonOutputNodesSpec`'s 16 rows as one build and one eval

**What.** Copy all 16 rows' suites into one build and run `core.test -fJ <report>` once, in a `lazy val`. Each scenario then asserts on its own suite's node. This needs:

- each row's `RowSuite` renamed (all 16 are `samples.RowSuite` in `RowSuite.scala`, and `addSuiteFile` refuses a second copy, `FixtureBuild.scala` line 82), with the location patterns (`JsonOutputNodesSpec.scala` lines 35 and 78) and suite lookups (lines 72, 99, 122, 212) following the rename;
- test lookups scoped to their suite (`json.node(Suite, X).node(Test, "adds")`), because `adds` occurs in 9 rows, and `talks` and `throws` in 2 each, and `JsonNode.node` requires exactly one match (`JsonNode.scala` lines 100–110);
- the nested-suite scenario (lines 112–115) and the top-level-order scenario (line 205) rewritten to check relative order and nesting instead of "the run holds exactly these suites". The renamed suites must still sort in the opposite order to their `suiteName`s, or the ordering scenario loses its point.

**Saving.** 15 sandboxes and 15 evals: **about 270 solo-seconds** (297 → about 25). With P1 and P2, about −90 s more wall clock. P5 removes 13 of the 16 rows, after which P3 merges three rows for about 16 solo-seconds and is not recommended.

**Risk.** Medium-low. One suite's compile error or crash now affects all 16 scenarios' diagnosis, though each still fails. The ordering and nesting scenarios assert less strictly than "exactly", so they must be rewritten carefully to keep their point. No row depends on running alone: the aborted suite aborts in `beforeAll`, test parallelism is off, and throwable ids restart per record.

**Touches.** `JsonOutputNodesSpec.scala`, `JsonOutputFixture.scala`, the files under `plugin/integration/resources/json-output/rows/`.

### P4. Share one eval among scenarios that run the same command on the same build

**What.** Where scenarios run the same command on the same state and only assert different things, run it once:

- `JsonOutputRootSpec`: 4 of its 6 scenarios run `core.test -fJ <report>` on the same build: 6 evals → 3.
- `JsonOutputTreeSpec`: the first scenario's run is the second scenario's first run: 3 → 2.
- `TestCommandArgumentsIntegrationSpec`: the 3 "module's own -oD / -eD / -s leaves the output unchanged" scenarios each make the same reference run with the module's arguments removed; one reference run serves all three: 6 → 4.
- `SeveralModulesSpec`: "Module selectors alpha.test __.test…" and "Two module selectors that share a test module…" run the same command on the same build.
- `EveryWorkerSpec`: the scenarios at lines 264 and 402 run the same command on the same build.
- `JsonOutputArgumentSpec`: "core.test -fJ <report> writes the JSON output…" and "The JSON output replaces a file from an earlier test command" can share one run.
- `MillOwnTestTasksSpec`: drop the 10 `resolve _` evals that compile nothing (keep the first in each plugin state): 24 → 14.

**Saving.** **About 140 solo-seconds.** About −30 to −50 s of wall clock once balanced. After P5, about 86 solo-seconds remain: `MillOwnTestTasksSpec`'s 10 evals, one in `JsonOutputRootSpec` (#1 and #3), the reference run of `TestCommandArgumentsIntegrationSpec`'s own-argument scenarios (#57 and #59), and `SeveralModulesSpec`'s pair. The `JsonOutputTreeSpec` and `JsonOutputArgumentSpec` shared evals go with the scenarios P5 removes.

**Risk.** Low. Each scenario's assertions are unchanged; only the eval is shared. A shared eval failing fails every scenario that reads it, as `MillOwnTestTasksSpec` already accepts.

**Touches.** The suites listed.

### P5. Remove or simplify the integration scenarios that other tests already cover

**What.** Remove 100 of the 229 scenarios and simplify 4, keeping 125. A scenario goes only where every claim it asserts is asserted by other tests: the consolidation library's module behaviour tests plus unit tests, or two or more other tests between them, other integration scenarios included. Each cover was confirmed by reading its assertion. Cover from reading production code or Mill's sources does not count. Nor does cover from the `--compat` suites, which CI does not run. The per-scenario classification, with the claims and the tests that assert each, is in [`integration-test-coverage.md`](integration-test-coverage.md).

The layers compared:

- **Module behaviour tests:** the `AnyFeatureSpec` suites of `dotty/consolidation/src/test/scala/org/scalatest/consolidation/`, run by `scalatestConsolidation/test`. They drive the library with real ScalaTest runs, without Mill.
- **Unit tests:** the `AnyWordSpec` suites in that folder, and every suite in `mill-plugin/plugin/test/src/org/scalatest/mill/`.
- **The emitter's tests:** in `dotty/scalatest-test/`, run by `scalatestTestDotty/test`.

All three run in CI (`.github/workflows/ci.yml`).

| Suite | Scenarios | Remove | Simplify | Keep | Main cover for the removals |
|---|---|---|---|---|---|
| `TestCommandArgumentsIntegrationSpec` | 59 | 32 | 0 | 27 | `TestCommandArgumentsSpec`, `TestCommandFailureSpec`, `ClassGlobSpec`, `RunArgumentsSpec`, `ModuleScalaTestArgumentsSpec`, `WorkerLayoutSpec`; `SeveralModulesSpec`'s AC-008, AC-009 and AC-022 scenarios |
| `JsonOutputArgumentSpec` | 24 | 18 | 0 | 6 | the same parser and message unit tests, `JsonOutputFileSpec`; `JsonOutputRootSpec` #1, `JsonOutputTreeSpec` #1 |
| `JsonOutputNodesSpec` | 16 | 13 | 0 | 3 | library `JsonOutputNodesSpec`, the emitter's `TestEventLog*Spec`; `JsonOutputTreeSpec` #1 |
| `TestCommandResultSpec` | 13 | 9 | 0 | 4 | `ConsolidationSpec`, `HumanReadableRunSpec`, `ChangedRegistrationOrderSpec`, `ModuleCheckSpec`; AC-011 |
| `ModulesTheTestCommandCannotRunSpec` | 10 | 6 | 0 | 4 | AC-011 (#7) asserts every AC-010 row's claims for all six modules at once |
| `SeveralModulesSpec` | 30 | 5 | 0 | 25 | the AC-001 rows kept, AC-015, `JsonOutputWriterSpec` |
| `EveryWorkerSpec` | 22 | 3 | 4 | 15 | `WorkerLayoutSpec`, `ConsolidatedModuleSpec`, stricter scenarios in the suite; the four simplified scenarios' checks move onto another scenario's eval |
| `JsonOutputRootSpec`, `JsonOutputTreeSpec`, `TestCommandOutputSpec`, `ModuleSelectorSpec`, `CapturedOutputSpec` | 30 | 14 | 0 | 16 | scenario #1 of the same suite, `AddingThePluginSpec` #1, `CompatibilityFixtureSpec`, `ModuleScalaTestArgumentsSpec` |
| `MillOwnTestTasksSpec`, `HeaderPluginSpec`, `AddingThePluginSpec`, `MillBspServerSpec`, `LibraryWithoutMillSpec`, `CompatibilityFixtureSpec`, `JsonOutputEncodingSpec` | 25 | 0 | 0 | 25 | – |
| **Total** | **229** | **100** | **4** | **125** | |

Each distinct integration path keeps at least one end-to-end proof (`integration-test-coverage.md` § End-to-end proof kept for each integration path). For example:

- Mill failing before any worker starts, with nothing written, on a rejected framework, output or `-fJ` argument: `TestCommandArgumentsIntegrationSpec` #22 and #48, `JsonOutputArgumentSpec` #15.
- A module the test command cannot run, and an unrecognised `testArgsDefault` argument: AC-011 and AC-019.
- Console output through Mill with and without the daemon, `mill.bat`, BSP, every worker layout, nested evaluation across several modules, a dead or unstarted worker JVM, and the JSON file's lifecycle.

Several removals rely on named scenarios staying. The coverage file says which in each row, so any later removal must check them again.

**Saving.** About 1896 of the 4337 solo-seconds (44 %): 103 of the 216 sandboxes and 112 of the 265 evals. Alone, this predicts 1290–1300 s, against 2335 s (**about −1040 s**). With P1, it predicts 1060–1080 s.

**Effect on P1–P4.**

- **P1.** P1 still pays: without it, `SeveralModulesSpec` (452 solo-seconds after removals) and `TestCommandArgumentsIntegrationSpec` (436) set the critical path. With P1, the largest unit is `EveryWorkerSpec`, at 266.
- **P2.** Most sharing groups shrink:
  - `TestCommandArgumentsIntegrationSpec`'s class-globs group goes from 21 to 3, and its output-argument group from 9 to 3.
  - `JsonOutputArgumentSpec`'s group goes from 22 to 5.
  - `ModulesTheTestCommandCannotRunSpec`'s and `TestCommandOutputSpec`'s groups disappear.

  P2 then removes about 39 sandboxes rather than 108, about 300 solo-seconds rather than 800, and needs fewer resets.
- **P3.** Only `JsonOutputNodesSpec` #2, #11 and #15 remain, so P3 would merge three rows for about 16 solo-seconds. It is no longer worth its preparation.
- **P4.** The shared evals of `JsonOutputTreeSpec` and `JsonOutputArgumentSpec` disappear. About 86 solo-seconds remain, 60 of them in `MillOwnTestTasksSpec`.

The coverage file lists each group's change (`integration-test-coverage.md` § Effect on P2, P3 and P4).

**Risk.** Medium for coverage. Coverage moves down to the library and unit tests: a variant no longer runs through Mill, and a fault in the wiring between Mill and a covered component shows only in the path's kept proof. Against the specifications, which this proposal does not change:

- **AC-010 loses every proving scenario** (`ModulesTheTestCommandCannotRunSpec` #1–#6), in `.planning/event-stream-reporting/many-modules-in-one-test-command/SPECIFICATION.md`. AC-011 (#7) asserts the same claims for all six modules at once, and `TestCommandFailureSpec` and `ModuleCheckSpec` cover the message and the check.
- **Examples rows with no scenario left**, while each criterion keeps one: AC-001 `alpha.test beta.test`, `beta.test alpha.test` and `__.test alpha.test beta.test`; AC-005 `--keep-going`; AC-006 `none`.
- **No row of the coverage table** at the end of that specification is left without a proving criterion.
- **Epic requirements losing their only integration test**, in `.planning/event-stream-reporting/SPECIFICATION.md`, though lower layers still cover them: R-046a (ignored and pending tests do not fail) and R-130e (a test run twice is two nodes). R-063's JSON half also loses `JsonOutputTreeSpec` #2.

Two further risks:

- A simplification moves assertions onto another scenario's eval, so the receiving scenario must gain them before the simplified one is deleted.
- The `mill.bat` scenarios (`TestCommandArgumentsIntegrationSpec` #34, #35) cancel off Windows, so CI never runs that path. That is true today and is not caused by this proposal.

**Touches.** Twelve suites: `TestCommandArgumentsIntegrationSpec.scala`, `SeveralModulesSpec.scala`, `EveryWorkerSpec.scala`, `JsonOutputArgumentSpec.scala`, `JsonOutputNodesSpec.scala`, `ModulesTheTestCommandCannotRunSpec.scala`, `CapturedOutputSpec.scala`, `TestCommandResultSpec.scala`, `JsonOutputRootSpec.scala`, `TestCommandOutputSpec.scala`, `JsonOutputTreeSpec.scala` and `ModuleSelectorSpec.scala`. Fixture files that only removed scenarios use can go as well.

### P6. Tag the integration suites by area, so a build task runs only the tests for the area it changes

**What.** Give every integration scenario an area tag, so that a build task runs the core set below plus the tags for the code it changes, rather than all the integration suites. The areas follow the test command's stages (parse the arguments; select and check the modules; lay out the workers; capture their output; write the output) plus one for coexisting with Mill. They do not follow the epic's features.

Costs are solo-seconds, recomputed per scenario from `[report]` and `[claim]` by the method of § 1.3. They total 4449 today, about 2.6 % above § 1.5's 4337, and 2503 after P5, counting the simplified scenarios as removed. # is the scenario's number in `integration-test-coverage.md`.

| Tag | Members | Run it when these change | Solo s, today → after P5 |
|---|---|---|---|
| Arguments | `TestCommandArgumentsIntegrationSpec`'s Class globs, Framework arguments, output-argument and module's-own-ScalaTest-argument Features; `SeveralModulesSpec` #23–#27; `JsonOutputArgumentSpec` #1–#3, #5, #9–#18 | `TestCommandArguments`, `AllowedFlag`, `FrameworkArguments`, `ClassGlob`, `StdoutContent`, `RunArguments`, `ModuleScalaTestArguments`, `ModuleSelectorArgument`; `ScalaTestArgument` in `dotty/consolidation` | 1027 → 322 |
| Modules | `ModuleSelectorSpec`; `ModulesTheTestCommandCannotRunSpec`; `SeveralModulesSpec` Features 1–3 except #11; `TestCommandResultSpec` Feature 5; `JsonOutputRootSpec` #5–#6; `JsonOutputArgumentSpec` #5 | `ModuleSelection`, `SelectedTestModule`, `ModuleCheck`, `ModuleCheckTask`, `CannotRunReason`, `ResolvedDependency`, `Organization`, `ArtifactName`, `LibraryVersion`, `FailFastOffEvaluator`, `EvaluatedTasks`, `FailedTask`, `FirstFailure`, `TaskName` | 914 → 474 |
| Workers | `EveryWorkerSpec` Features 1 and 4–6; `TestCommandArgumentsIntegrationSpec`'s worker-environment and module-folder Features; `TestCommandResultSpec` Feature 3 (a worker dies); `CapturedOutputSpec` #8; `SeveralModulesSpec` #11 | `JvmTestModuleInputs`, `WorkerLayout`, `ForkGroupLabel`, `PaddedIndex`, `PathUnderModule`, `WorkerRun`, `WorkerProcess`, `ProcessExit`, `DelegatingTaskCtx`, `ModuleFolder`, `ModuleFolderTaskCtx` | 540 → 433 |
| Capture | `CapturedOutputSpec` except #8; `EveryWorkerSpec` Features 2–3; `SeveralModulesSpec` #28–#30; `TestCommandOutputSpec` Feature 3 | `OutputCapture`, `LineSplitter`, `StreamLines`, `EventLogReader`, `EventLogFile`, `EventLogLines`, `WorkerTaskCtx` | 469 → 401 |
| HumanReadable | `TestCommandOutputSpec` Features 1–2; `TestCommandResultSpec` Features 1, 2 and 4; `CompatibilityFixtureSpec`; `LibraryWithoutMillSpec` Feature 1 | the `HumanReadable*` code and the consolidation state in `dotty/consolidation` | 333 → 126 |
| Json | `JsonOutputRootSpec` #1–#4; `JsonOutputTreeSpec`; `JsonOutputNodesSpec`; `JsonOutputEncodingSpec`; `JsonOutputArgumentSpec` #4, #6–#8 and Feature 3; `LibraryWithoutMillSpec` Feature 2 | `JsonOutputFile`; `JsonOutput*` and `JsonOutputWriter` in `dotty/consolidation`; the JSON schema | 717 → 278 |
| MillCoexistence | `AddingThePluginSpec`, `HeaderPluginSpec`, `MillOwnTestTasksSpec`, `MillBspServerSpec`, each whole | `TestCommandModule`, the `plugin` section of `mill-plugin/build.mill` (dependencies, publishing, `Versions.mill`), the fixtures' plugin headers | 469 → 469 (about 409 with P4) |

Two tags split further, along lines where their files do not overlap:

- **Modules:** selection (`ModuleSelectorSpec`, `SeveralModulesSpec` Feature 1, `ModulesTheTestCommandCannotRunSpec` #10), 416 → 229; checks (`ModulesTheTestCommandCannotRunSpec` #1–#9, `TestCommandResultSpec` Feature 5), 310 → 92; independence of the modules (`SeveralModulesSpec` Features 2–3), 187 → 153.
- **Workers:** layout (`EveryWorkerSpec`, `TestCommandResultSpec` Feature 3); environment and module folder (`TestCommandArgumentsIntegrationSpec` Features 3–4, `CapturedOutputSpec` #8, `SeveralModulesSpec` #11).

**Core set.** Every build task runs these eight scenarios, tagged Core, whatever it changes. All are kept by P5. Together they cost about 169 solo-seconds, an estimated 2–3 minutes of wall clock plus about 20 s of compiling; this was not measured.

| Scenario | Proves | Solo s |
|---|---|---|
| `AddingThePluginSpec` #1 | the plugin header alone runs a module, on Scala 3.3 | 23.6 |
| `JsonOutputRootSpec` #1 | the JSON output, end to end | 27.3 |
| `SeveralModulesSpec` #5 | several module selectors run; a module selected twice runs once | 16.4 |
| `SeveralModulesSpec` #12 | a failing test fails the test command, and the other module still runs | 19.6 |
| `TestCommandArgumentsIntegrationSpec` #22 | an argument is rejected before any worker starts | 9.7 |
| `CapturedOutputSpec` #9 | console output reaches the console only through the human-readable output | 30.7 |
| `EveryWorkerSpec` #2 | the parallel queue | 21.6 |
| `HeaderPluginSpec` #4 | Mill's own `testForked` still passes with the plugin | 20.4 |

**Run everything** when any of these change: `TestCommand.scala` or `TestCommandModule.scala` as a whole; the versions in `mill-plugin/build.mill`; the shared test helpers `FixtureBuilds`, `FixtureBuild`, `TestCommandResult`, `JsonNode`, `JsonOutputFixture`, `IntegrationEnvironment` and `MillConsole`; the `Consolidation` API or the emitter's event-log format; the JSON output's keys or shape, because `EveryWorkerSpec`, `SeveralModulesSpec`, `ModulesTheTestCommandCannotRunSpec` and `TestCommandArgumentsIntegrationSpec` assert through `-fJ` output; and a wide change to how the console tree is rendered.

**Hub files.** 24 of the 40 commits that touch `mill-plugin/plugin/src` changed `TestCommand.scala` (`[history]`), so selecting by file would run everything most of the time. Map the two hub files by method instead:

| File | Method | Tags |
|---|---|---|
| `TestCommand.scala` | `run` | Arguments, Modules, Json |
| | `checkAndRunTests` | Modules |
| | `consolidateSelectedModules` | Json, HumanReadable, Capture |
| | `moduleRunTask`, `clearedModuleFolder` | Workers |
| `JvmTestModuleRun.scala` | worker layout and launchers | Workers |
| | discovery, `startWorker` | Capture |
| | `reportNoTestResultForClassGlobs` | Arguments |

**Overlaps.** A scenario that belongs to two areas takes its second tag through `taggedAs`:

- `TestCommandArgumentsIntegrationSpec` #7, #8 and #13: Arguments and Workers. #45 and #47: Arguments and Capture.
- `SeveralModulesSpec` #19: Modules and Workers. #18 and #30: Modules or Capture, and HumanReadable.
- `ModulesTheTestCommandCannotRunSpec` #9: Modules and Arguments.
- `JsonOutputTreeSpec` #1: Json and HumanReadable. #2: Json and Arguments.
- `JsonOutputArgumentSpec` #15: Json and Arguments.

`JsonOutputArgumentSpec` #5 is listed under both Arguments and Modules in the table, so the rows sum to slightly more than 4449 today.

**Splits.** With P1, `TestCommandArgumentsIntegrationSpec` and `SeveralModulesSpec` become one suite per Feature, so most tags become whole classes. Without it, a tag inside one class runs serially on one worker: Arguments would be about 237 solo-seconds in one class. Beyond P1:

- `SeveralModulesSpec`'s "Class globs and framework arguments" scenarios #28–#30 into their own Capture suite.
- `EveryWorkerSpec` Features 1 and 4–6 (Workers) apart from Features 2–3 (Capture). After P1 and P5, `EveryWorkerSpec` is the largest single unit, at about 266 solo-seconds.
- Optionally, `TestCommandResultSpec` Feature 3 next to `EveryWorkerSpec`'s Workers part.

Tag `MillOwnTestTasksSpec`, `MillBspServerSpec` and `TestCommandOutputSpec` whole, never per scenario: each shares a `lazy val` that puts all its cost on whichever scenario runs first.

**Selection.** Each tag is a Java runtime-retained `@TagAnnotation` on the suite classes, plus a Scala `Tag` object of the same name for `taggedAs` (`edc-bestpractice/scala/scalatest.md`, "Tagging"), in a package such as `org.scalatest.mill.tags`. Mill compiles Java in a Scala test module, as the fixture `class-globs-across-modules/noisy/Noisy.java` shows. A build task then runs, for example:

```
cd mill-plugin && ./mill --no-daemon plugin.integration.testForked -n org.scalatest.mill.tags.Core -n org.scalatest.mill.tags.Workers
```

Several `-n` select their union. `-l` excludes a tag, and `-l` over every tag gives the untagged remainder. To narrow by class as well, use `plugin.integration.testOnly 'org.scalatest.mill.EveryWorker*' -- -n …`; `testOnly` splits its arguments at `--` (`[mill]` `TestModule.scala` lines 240–246).

Why it should work:

- `testForked(args: String*)` passes `testArgsDefault() ++ args` to ScalaTest (`[mill]` `TestModule.scala` lines 160–166), as `.docs/learnings/mill-build-tooling.md` (2026-11-20) also records. mainargs reads a `T*` parameter as the leftover arguments (`[mainargs]` `Macros.scala` line 127).
- `-n samples.Fast` already selects by tag through Mill's runner, in the nested test command of `TestCommandArgumentsIntegrationSpec` #15 and `SeveralModulesSpec` #24.
- Every fixture is built in a scenario's body or in a `lazy val` (`FixtureBuilds.scala` lines 8–19), so a scenario filtered out builds nothing.

**Further use.** The same tags can shard the full run across parallel CI jobs: in `.github/workflows/ci.yml`, a matrix over groups of tags plus one `-l` remainder job brings the CI integration time to about 1/N plus each job's setup. It saves nothing on one machine, where shards compete for the same cores (§ 1.3).

**Saving.** None on the full run: every scenario still runs in it, and `test-all.sh`, `test-all.ps1` and CI keep running all of them. The saving is in a build task's own runs: it runs the core set, about 169 solo-seconds, plus the tags its change selects, instead of about 2060 solo-seconds after P1, P5, P2 and P4.

**Risk.**

- A task whose change selects too few tags misses a fault until the full run. The core set, the run-everything list and the method map for the hub files bound this.
- Every subset still pays `publishLocalTestRepo` and the compile.
- The `-l` remainder must list every tag. A guard test that fails on any untagged scenario keeps it honest.
- Tags drift as scenarios are added or moved; the guard test catches an untagged scenario but not a wrongly tagged one.
- `testForked -n` was not run for this note. `./mill --no-daemon plugin.test.testForked -n NoSuchTag` should run zero tests, and is a cheap check.

**Touches.** One Java annotation and one Scala `Tag` object per tag, an annotation on each suite, `taggedAs` on the overlapping scenarios, the guard test, and the splits above. Optionally `.github/workflows/ci.yml` for the shards.

**Considered and not recommended.**

| Alternative | Why not |
|---|---|
| One tag per epic feature | Coming features cross two or three stages: `--halt-on-failure` touches Arguments and Modules, and filtering the JSON output touches Arguments and Json. Maintenance edits belong to no feature. |
| `testOnly` with class globs only | Class names do not line up with areas (Workers spans five suites), and a glob cannot pick single scenarios. Fine as a stopgap that needs no code. |
| A Mill module per area | The shared helpers and resources would move into a common module; tags need only an annotation. |
| One tag per production file | Too fine: most scenarios pass through five or more files. |
| Selection driven by coverage | The plugin runs in nested Mill JVMs, which are hard to instrument, and every scenario loads `TestCommand`. |
| Excluding only the slow suites | It cuts time but says nothing about which area a change needs. |

### P7. Cache the Mill and Coursier downloads on CI

**What.** The `test-mill-plugin` job's `setup-java` step caches with `cache: 'sbt'`, whose key does not depend on `mill-plugin/build.mill` or the fixtures. Add an explicit cache keyed on `mill-plugin/build.mill`, `mill-plugin/mill` and the fixtures' `build.mill` files, covering the Coursier cache and Mill's own download folder.

**Saving.** Unknown: there is no CI run to measure. On a cold cache, the first nested builds download Mill's libraries, the Scala 3.3 and 3.9 compilers and the fixtures' dependencies, all under Coursier's locks.

**Risk.** Low. A stale cache cannot change resolution for the fixed versions these builds use.

**Touches.** `.github/workflows/ci.yml`.

### Considered and not recommended

| Change | Why not |
|---|---|
| Run Mill in-process or share one output folder (`useInMemory`, `MILL_TEST_SHARED_OUTPUT_DIR`; `[testkit] IntegrationTester.scala` line 242, `IntegrationTesterBase.scala` line 28) | It removes the process boundary the plugin's behaviour depends on: console streams, `MILL_PARAMS`, the environment workers inherit, `--no-daemon` against the daemon. That weakens testing. |
| Run shared-sandbox groups under a Mill daemon | A warm daemon would cut each later eval to about 2–3 s, but the daemon keeps the environment it started in and its in-memory caches between scenarios. Several scenarios test exactly that difference (`TestCommandArgumentsIntegrationSpec` lines 453–547; `CapturedOutputSpec` lines 41–72). Using it elsewhere would make most scenarios test daemon mode only. |
| Run scenarios of one suite in parallel (`ParallelTestExecution`) | Two testers in one JVM can pick the same `run-<n>` folder: the tester takes the first that does not exist and creates it later (`[testkit] IntegrationTesterBase.scala` lines 42–46). It would also deepen the oversubscription in § 1.3. P1 gives the same balance safely. |
| Copy a compiled `out/` into each new sandbox | Mill's cached task results hold absolute paths into the sandbox (for example `out/mill-build/compile.json` holds `…\sandbox\run-59\out\mill-build\compile.dest\classes`). A copy under another path is either recompiled or points at the template. |
| Change the number of workers | By § 1.3, throughput still rises from 8 to 16 workers (1.9 to 2.9), so fewer workers would be slower. More are not available. |
| Tune the nested JVMs (fewer GC and JIT threads, C1 only, class-data sharing) | It might reduce the contention in § 1.3, but its effect cannot be predicted from these data. It would need `.mill-jvm-opts` in every fixture, and `JAVA_TOOL_OPTIONS` prints a line that console assertions would see. Worth one measured experiment, not a recommendation. |

## 3. Recommended set

Apply in this order. Each step is independently useful, and each is measured before the next.

| Step | Change | Integration time predicted (s) | Saving vs today (s) |
|---|---|---|---|
| – | Today | 2335 (measured 2339–2364) | – |
| 1 | P1, split `TestCommandArgumentsIntegrationSpec` and `SeveralModulesSpec` by Feature | 1740–1820 | about 550 |
| 2 | P5, remove or simplify the 104 scenarios other tests cover | 1060–1080 | about 1260 |
| 3 | P2, share sandboxes among the remaining identical build states | 940–970 | about 1380 |
| 4 | P4, share the remaining same-command evals | 900–930 | about 1420 |
| 5 | P6, tag the suites by area | full run unchanged; a build task runs the core set plus the tags its change selects | none on the full run |
| CI | P7 the CI cache; optionally P6's tags as parallel CI jobs | development machine unchanged; CI integration time about 1/N with N jobs | – |

Steps 1–4 bring the full `plugin.test.testForked + plugin.integration.testForked` from 2388 s to **about 930–950 s (−60 %)**. Steps 1 and 2 alone bring it to about 1090–1100 s (−54 %). P3 is left out: after P5 it saves about 16 solo-seconds. Splitting every suite by Feature as well would save about 50–70 s more.

- **Step 1 comes first** because it is cheap and low-risk, and because the critical path hides every other saving until it is fixed: P5 alone predicts 1290–1300 s, against 1060–1080 s with P1.
- **P5 comes before P2** because it removes most of the groups P2 would need resets for, so P2 becomes a smaller and safer change. P5 can be done one suite at a time, and each suite's removals must keep the scenarios that the coverage file names as cover.
- **P2–P4 without P5** predict 1290–1320 s after P1.
- **P6 is a separate step after steps 1–4.** Its purpose is a build task's own runs, not the full run, to which it adds no saving. After P1 most tags are whole classes, so a tag no longer runs serially in one class; after P5 the scenarios that would otherwise need tagging are gone.
- **Two counts bound the estimate.** In the model, the end point does not depend on the order of the steps. A second count assumes P2 first, so that a removed scenario then saves only a warm eval, about 4 s (`integration-test-coverage.md` § Summary). It puts the end point at 1100–1130 s instead of 900–930 s. The two figures bound the estimate (§ 4).

## 4. The model and its limits

- **Model.** Each suite (or each Feature, once split) is a unit of solo-equivalent work (§ 1.3). Up to 16 workers claim units in name order. When *a* workers are active, each progresses at 1/slowdown(*a*), using the slowdown column of § 1.3. Replaying today's run this way gives 2335 s against the measured 2339 s. Each prediction above is the result for name order, and the median of 200 random claim orders, as the new suites' names are not chosen yet.
- **Limits.**
  - The slowdown curve comes from one task (build.mill compiles), and from one run. The high-concurrency samples are early in the run, when caches were also cold, so the curve may overstate the slowdown at 16 workers.
  - Solo-equivalents are estimates, not measured solo times. The scenarios measured alone at the end of the run (§ 1.4) agree with them.
  - The P2 and P4 savings assume 7–9 s per removed sandbox and 3–12 s per removed eval, from § 1.4. Groups whose scenarios swap source files save less, as the test module still recompiles.
  - P5 removes each scenario's solo-equivalent, taken per scenario from `[report]` and `[claim]` (the active workers at the scenario's midpoint), and the predictions with P5 apply P2 and P4 only to the groups and evals that remain. The removed scenarios average about 18 solo-seconds, more than the 10–15 s measured for a fresh scenario alone (§ 1.4), so P2's 7–9 s per sandbox and the 3–5 s of a warm eval do not add up to them. Counting a removal after P2 as only a warm eval instead gives 1100–1130 s for the recommended set rather than 900–930 s; 1100–1130 s is the more cautious figure.
  - The model was rebuilt from the same inputs for P5's predictions. It replays today's run at 2270 s rather than 2335 s, and its predictions are scaled by 1.03 to the measured run. Scaled, it reproduces the first pass's P1–P4 predictions within 2 %.
  - Two runs of the same tests differed by 15 % (2053 s and 2388 s).
- **Measuring each step.** Read `test-report.xml`, `worker-NN/claim.log` and each sandbox's `out/mill-chrome-profile.json` after the run, as this note does.

## Open questions

- How much of the slowdown at 16 workers is memory pressure or on-access scanning, rather than CPU? One run with Windows performance counters (paging, CPU queue length), or with the sandbox folder excluded from Defender, would answer it. An exclusion is a machine setting, not a test change.
- How long does the CI job take today? No workflow run exists to read.
- Does `plugin.integration.testForked -n <tag>` select by tag? The sources say it should (P6), but it was not run. `./mill --no-daemon plugin.test.testForked -n NoSuchTag`, which should run zero tests, would answer it.
- How long does the core set take? The 2–3 minutes in P6 is an estimate from solo-seconds, not a measurement.
