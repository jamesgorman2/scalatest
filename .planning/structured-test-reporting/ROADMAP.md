# Roadmap - Structured test reporting

Features of the epic [Structured test reporting](SPECIFICATION.md), in intended implementation order. The JVM `Runner` comes first, then the JVM `Framework`, then consolidation and build integration for JVM modules, then Scala.js and Scala Native. Spec document paths are relative to `.planning/structured-test-reporting/`; code paths are relative to the repo root. "Review of X" means the review comment on that closed design issue.

## 1. Structured JSON from `Runner`

Under `Runner` on the JVM, every `J` spelling writes JSON from which the test structure can be rebuilt, in every execution mode. This covers `-fJ`, `-oJ` and `-eJ`. It covers `Runner`'s paths only; the `Framework`'s come in feature 4.

- Structure wrappers on every path `Runner` takes that builds `Args`.
- Node IDs and declared positions.
- The run node and run ID.
- Envelope dispatch placed ahead of the sorting reporters.
- The first version of the documented format.

Existing reporters receive exactly what 3.2.19 sends them.

Enables:

- `SPECIFICATION.md` § Scope (JVM on Scala 2.11, 2.12, 2.13 and 3.3), § Constraints (R-001, R-002, R-057), R-010 to R-014 (within one process), R-016 to R-018 (JVM), § JSON output (R-030 to R-033, R-035, R-064; R-036 and R-037 for `Runner`)
- `ARCHITECTURE.md` § Modules § ScalaTest core (structure wrapper, envelope dispatch, JSON writer; the Dotty source generators, such as `project/GenScalaTestDotty.scala`), § The compatibility boundary, § Data flow (the in-process path), § External systems (IntelliJ forked mode, `-C`)

Settles:

- Emission-point wrapping coverage and the JSON schema policy ([Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1), first review).
- The JSON format version ([Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6), review).
- How a throwable's class and cause chain survive the JSON ([Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3), review).

Risks:

- `ARCHITECTURE-DECISIONS.md` § Structure is attached where each event is emitted, not reconstructed after the merge (Costs)
- `ARCHITECTURE.md` § The compatibility boundary (no second envelope; suites whose own `run` or `runNestedSuites` bypasses the wrappers)
- `SPECIFICATION.md` § Constraints § IntelliJ (forked-mode ordering needs)

## 2. Progress-then-results output from `Runner`

Plain `-o` and `-e` under `Runner` show progress output, then one contiguous results block assembled from the tree: declared order, outcome roll-up, durations. Progress and results can each be sent to stdout, stderr or both. 3.2-format spellings stay byte-for-byte the same.

Enables:

- `SPECIFICATION.md` R-011, R-015, R-074 (outcome only), § Human-readable output (R-020, R-022, R-058 for `Runner`), R-003
- `ARCHITECTURE.md` § Modules § ScalaTest core (tree assembly, human renderer)

Settles:

- How the independent destinations of R-022 are spelled under `Runner`.
- Top-level suite order ([Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6), review).

Risks:

- `SPECIFICATION.md` R-003 (`-oDU`)
- `SPECIFICATION-DECISIONS.md` § Progress-then-results is the default for plain `-o`, `-e` and the `Framework`, not an opt-in (Costs)

## 3. Test output capture under `Runner`

Under `Runner` on the JVM:

- Test output is captured when a consuming reporter is configured.
- It is attributed to a test, a suite or the run node.
- It is withheld from the console unless a `-C` or 3.2-format reporter is configured.
- It appears in results output for failed or aborted nodes, and in all JSON.
- TeamCity lines pass through unaltered.

Enables:

- `SPECIFICATION.md` § Test output (R-061, R-065 JVM, R-069, R-070 single run, R-040, R-067, R-068, R-062, R-063, R-007), R-024, R-034, R-037 (chunk objects)
- `ARCHITECTURE.md` § Modules § ScalaTest core (test output capture, the thread-reporting part of the structure wrapper)

Settles:

- How JVM `println` reaches capture ([Does the epic capture test output on Scala.js and Scala Native?](https://github.com/jamesgorman2/scalatest/issues/7), review; [Which run owns test output when one process hosts several runs?](https://github.com/jamesgorman2/scalatest/issues/8), review).
- Whether output written during suite construction attaches to the suite or the run node ([Which run owns test output when one process hosts several runs?](https://github.com/jamesgorman2/scalatest/issues/8), review).

Risks:

- `SPECIFICATION-DECISIONS.md` § Captured test output is withheld from stdout and stderr unless a `-C` reporter or a 3.2-format `-o` or `-e` reporter is configured (Costs), and § Test output is captured only when a reporter consumes it (Costs)
- `ARCHITECTURE.md` § The compatibility boundary (reporter writes on `DispatchReporter`'s thread)
- `SPECIFICATION.md` R-064 (JSON lines against captured output on the same stream)

## 4. Structured output under the JVM `Framework`, in process

Under sbt unforked, Mill, Metals, Bloop and IntelliJ BSP:

- Progress output goes through the task loggers. Run-level output is held for the next task or for `done()`.
- Each run returns one results block as its `done()` string, without waiting for other runs.
- `-oJ` and `-fJ` work.
- Test-interface events are unchanged.

Enables:

- `SPECIFICATION.md` R-002, R-057, R-003, R-010 to R-013, R-014 (within one process), R-015, R-016 to R-018, R-031 to R-033, R-064 and R-035 (a Mill test-worker JVM dying), all for the JVM `Framework`
- `SPECIFICATION.md` R-004, R-020, R-022 (build tools), R-023, R-058, R-030 (`-oJ` passed to the `Framework`), R-036 (JVM `Framework`)
- `ARCHITECTURE.md` § Modules § ScalaTest core (structure wrapper, including the run node's wrapper for the `Framework`'s runner; JSON writer, including the run ID the `Framework`'s runner mints and the file created only once the run executes; human renderer, for the `Framework`), § External systems (IntelliJ sbt-shell and BSP modes, sbt unforked, Mill, Metals and Bloop)

Risks:

- `SPECIFICATION.md` § Constraints § IntelliJ (sbt-shell regex parsing)
- `ARCHITECTURE.md` § Modules § ScalaTest core, human renderer (loggers exist only during `execute`)
- `ARCHITECTURE.md` § External systems (Mill prints line by line; sbt unforked runs several runs, possibly under several loads of ScalaTest)
- `SPECIFICATION-DECISIONS.md` § Under the `Framework`, a run's results output is one block per run, printed wherever the build tool prints the test framework's summary (Costs)

## 5. Structured output under sbt's forked mode

With `Test / fork := true`:

- Forked JVMs send envelopes over the existing socket to the runner in sbt's JVM.
- That runner passes the run ID to the forks in `remoteArgs`.
- Node IDs carry a component unique to each process.
- The runner writes the JSON and the results block once every fork's envelopes have arrived.

Enables:

- `SPECIFICATION.md` R-002, R-057 and R-033 (sbt forked), R-014 (across processes), R-035 (fork death), R-036 (sbt forked), R-037 (the per-process component of node IDs)
- `ARCHITECTURE.md` § Modules § ScalaTest core (envelope forwarding, sbt branch; JSON writer, run ID), § External systems (sbt forked)

Settles:

- Where the fork's own `done()` string goes ([What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9), review).

Risks:

- `ARCHITECTURE.md` § Modules, envelope forwarding (the 15 s wait; envelopes arriving late are dropped)
- `ARCHITECTURE.md` § Modules, tree assembly (the fork's run-level events are dropped or redispatched)
- `ARCHITECTURE.md` § External systems (every `testGrouping` group belongs to one run)

## 6. Runs that lose nodes end in `RunAborted`

- Open-node tracking runs in every run.
- A run that finishes with unfinished nodes emits `RunAborted` instead of `RunCompleted` to every reporter. This applies under `Runner`, the JVM `Framework`, and an sbt fork's shutdown-hook `done()`.
- The JSON and its documentation let a reader recognise a run with no closing event and a run whose test process failed.

Enables:

- `SPECIFICATION.md` R-074 (unfinished), R-075, R-076, R-037 (R-076 and R-077 recognition)
- `ARCHITECTURE.md` § Modules § ScalaTest core, tree assembly (open-node tracking, forked JVM), § Data flow (failure path)

Settles:

- Whether the shutdown-hook `done()` can block on a still-running async suite ([What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9), review).
- How the JSON marks the `RunAborted` of R-075 apart from other `RunAborted` events.

Risks:

- `SPECIFICATION-DECISIONS.md` § A run that finishes with nodes unfinished ends in `RunAborted` for every reporter (Costs: the first departure from 3.2.19; IntelliJ)
- `SPECIFICATION-DECISIONS.md` § A run that finishes with nodes unfinished ends in `RunAborted` for every reporter (Reasoning: a `VirtualMachineError` under `SuiteAborted` is not unfinished)
- `ARCHITECTURE.md` § Modules, envelope forwarding (a slow fork ranks as a test process failure)

## 7. Test output capture under the JVM `Framework`

Capture extends to every JVM `Framework` path:

- Run ownership by thread, with the `Framework`'s runner and tasks marking their own code.
- Each run's own configuration applies where runs share a JVM.
- Captures from separately loaded copies of ScalaTest chain.
- In forked mode, captured output travels as envelopes.

Enables:

- `SPECIFICATION.md` R-070 (several runs), R-061 to R-063, R-069, R-040, R-067, R-068, R-007, R-024 and R-034 (`Framework`)
- `ARCHITECTURE.md` § Modules § ScalaTest core, test output capture (marking, `Console` per thread, chaining)

Settles:

- The mechanism by which captures from separately loaded copies of ScalaTest chain ([Which run owns test output when one process hosts several runs?](https://github.com/jamesgorman2/scalatest/issues/8), review).

Risks:

- `SPECIFICATION-DECISIONS.md` § A write belongs to the run whose node or own code is running on the writing thread, and output belonging to no run is never captured (Costs), and § Captured test output is withheld from stdout and stderr unless a `-C` reporter or a 3.2-format `-o` or `-e` reporter is configured (Costs: BSP, Metals and Bloop lose live output)

## 8. Consolidator: merged JSON and results output

A standalone executable takes one or more test run labels, unique within the consolidation, each with a list of JSON files and, where the caller has it, the test-task result behind them. It merges each label's files under that label, without collapsing suites or tests that share a name across files, and writes merged JSON and a results output of the whole build. For each label, it distinguishes "did not run", "test process failed" and "tests failed".

Enables:

- `SPECIFICATION.md` R-050 (merged JSON, results output), R-051, R-059, R-077, R-078, R-014 (across runs), R-011, R-016
- `ARCHITECTURE.md` § Modules § Consolidator

Settles (all from [Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3), review):

- Where JSON reading lives.
- The consolidator's Scala versions and platforms, and how it is packaged and invoked.
- The merged JSON format.
- Test run label order in consolidated output ([Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6), review).

Risks:

- `ARCHITECTURE-DECISIONS.md` § Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on (Costs: lockstep release, core's dependencies)

## 9. Consolidated JUnit XML and Markdown

The consolidator also writes JUnit XML, with each test's containment flattened into its path in the test and suite names, and a Markdown summary.

Enables:

- `SPECIFICATION.md` R-050 (JUnit XML, Markdown), R-055, R-010 (the JUnit XML exception), R-011
- `ARCHITECTURE.md` § External systems (CI JUnit XML readers)

Settles:

- Whether test output goes into JUnit XML and Markdown ([What does the console show of test output, and when is capture on?](https://github.com/jamesgorman2/scalatest/issues/5), review).

## 10. Mill plugin

The plugin provides a command that:

- turns on JSON for each selected JVM module through a relative `-fJ` path, so that each test-worker JVM writes its own file in its own sandbox, `Task.dest[/<group>][/worker-<i>]/sandbox`, and fails the build for a module whose `testSandboxWorkingDir` is off;
- runs the tests and consolidates only the runs it started, even when tests fail, under one test run label per module covering all its worker files, with that module's task result, so that no label collides.

Enables:

- `SPECIFICATION.md` R-053, R-056
- `ARCHITECTURE.md` § Modules § Mill plugin, § External systems (Mill)

Settles:

- The Mill versions supported, and whether the consolidator runs in process or as a subprocess ([Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3), review).
- A consolidated module's console output ([Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6), review).
- Mill plugin constraints ([Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1), review).

Risks:

- `SPECIFICATION-DECISIONS.md` § The consolidator is an executable, with a Mill plugin whose command runs the tests and consolidates; sbt users get a recipe (Alternatives: Mill skips downstream tasks when tests fail)
- `ARCHITECTURE.md` § External systems (Mill: a dead worker JVM never calls `done()`)
- `ARCHITECTURE-DECISIONS.md` § The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file: it passes a relative `-fJ` path, which resolves inside each worker's own sandbox, and fails the build when the sandbox is off (Costs: a user who turns off `testSandboxWorkingDir` cannot use the command)

## 11. sbt recipe

A documented recipe that:

- gives each JVM module its own `-fJ` path and deletes the file before `test`;
- after `test`, consolidates under one test run label per module, with that module's task result, even when tests fail.

Enables:

- `SPECIFICATION.md` R-060
- `ARCHITECTURE.md` § Modules § Consolidator, § External systems (sbt, failure)

Settles:

- The sbt versions supported ([Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6), review).

Risks:

- `ARCHITECTURE-DECISIONS.md` § Each consolidating caller gives every test run label fresh JSON files each time its command runs; neither ScalaTest nor the consolidator tells one use of the command from another (Costs: a dropped delete consolidates stale runs silently)

## 12. Structured output on Scala.js

- Workers forward envelopes to the controller through `notifyServer`.
- The controller writes all console output, the results block and the JSON. `-fJ` uses Node's `fs`.
- Where files cannot be written, `-fJ` is a configuration error; `testHtml` produces no JSON.
- Suites whose constructor throws are reported as aborted.
- `MasterRunner` runs open-node tracking and applies R-075 when its `done()` runs with nodes unfinished. A dead Scala.js worker never reaches the controller's `done()`, so it leaves JSON with no closing run event instead.

Enables:

- `SPECIFICATION.md` R-002, R-057, R-003, R-004, R-010 to R-013, R-014, R-015 to R-017, R-018, R-020 and R-022 (late progress), R-023, R-030 to R-033, R-035 and R-036, R-058, R-064, R-074, R-075 (all for Scala.js)
- `ARCHITECTURE.md` § Modules § ScalaTest core (the structure wrapper in `TaskRunner`'s `Args`; envelope dispatch with no `DispatchReporter`, ahead of `TestSortingReporter`; envelope forwarding; JSON writer; tree assembly's open-node tracking on `MasterRunner`; human renderer for Scala.js; the source generators `project/GenScalaTestJS.scala`, `project/GenScalaTestNative.scala` and their Dotty equivalents), § The compatibility boundary (the one `-o` reporter writes through loggers), § Data flow (the Scala.js and Native bullet), § External systems (Scala.js and Native test interface)

Risks:

- `research/js-native-structure-and-capture.md` § 2.1 (loggers detached when `execute` returns), § 2.2 (ordering inference; asynchronous-only replies; Encoding, unpaired surrogates), § 2.3 (Node version floor for ES modules), § 5 items 1, 2 and 6
- `ARCHITECTURE-DECISIONS.md` § On Scala.js and Native, the controller writes all of a run's console output, only through its tasks' loggers or the `done()` string; workers only forward envelopes (Costs)
- The constructor throws outside the `try` today (`js/core/src/main/scala/org/scalatest/tools/TaskRunner.scala` line 79), so R-018 is a real gap.

## 13. Structured output on Scala Native

The same shape as feature 12 on Native, plus:

- File writes check partial writes.
- Every `send` goes through one lock per process.
- After a worker dies, the controller's `done()` applies R-075.

Enables:

- As feature 12, for Native.
- `SPECIFICATION.md` R-075 (dead Native worker)
- `ARCHITECTURE.md` § Modules, envelope forwarding (Native `send` lock)

Risks:

- `research/js-native-structure-and-capture.md` § 2.1 (stdout is an inherited file descriptor), § 2.2 (Native thread safety; Encoding, unpaired surrogates), § 2.3 (Native partial writes), § 5 items 3 to 5
- The constructor throws outside the `try` today (`native/core/src/main/scala/org/scalatest/tools/TaskRunner.scala` lines 78–82), so R-018 is a real gap.

## 14. Build integrations for Scala.js and Native modules

The Mill plugin (feature 10) and the sbt recipe (feature 11) turn on JSON for Scala.js and Scala Native modules and consolidate their runs alongside JVM modules, each module under its own test run label, so the same suites run on several platforms stay apart. They skip the Scala.js modules that cannot write files.

Enables:

- `SPECIFICATION.md` R-073, R-050 (runs across platforms), R-053, R-056 and R-060 (Scala.js and Native modules)
- `ARCHITECTURE.md` § External systems (Scala.js and Native test interface)

Settles:

- How the plugin and the recipe recognise a Scala.js module whose runs cannot write files.

Risks:

- `SPECIFICATION-DECISIONS.md` § Where a Scala.js run cannot write files, `-fJ` is a configuration error and `-oJ` is the route for JSON (Costs)
- `research/js-native-structure-and-capture.md` § 2.3 (jsdom, browser, ES modules without `process.getBuiltinModule`), § 1.3 (both plugins reject forking; Mill drives a run on one thread), § 2.1 (Native stdout under the Mill daemon), § 5 item 6
- `ARCHITECTURE.md` § External systems, Scala.js and Native test interface (a dead Scala.js worker means the controller's `done()` never runs, leaving JSON with no closing run event)

## 15. Test output capture on Scala.js

`console.*`, and on Node also `process.stdout` and `process.stderr`, are replaced so that output is attributed under the single-thread rule. TeamCity lines pass through. The capture is safe in processes that serve several runs.

Enables:

- `SPECIFICATION.md` R-065 (Scala.js), R-040 (Scala.js), R-061 to R-063, R-067 to R-070, R-007, R-024 and R-034 (Scala.js)
- `ARCHITECTURE.md` § Modules § ScalaTest core, test output capture

Risks:

- `research/js-native-structure-and-capture.md` § 4.1 (partial-line buffer; overlapping tasks), § 1.1 (VMs outlive runs), § 2.2 (Encoding, unpaired surrogates in captured output), § 5 item 8
- `SPECIFICATION-DECISIONS.md` § A test owns test output only while it is the only test running on the writing thread (Costs)

## 16. Test output capture on Scala Native

A permanent delegating `System.out` and `System.err` is installed when the `Framework` loads, with routing by thread.

Enables:

- `SPECIFICATION.md` R-065 (Native), R-040, R-061 to R-063, R-067 to R-070, R-007, R-024 and R-034 (Native)
- `ARCHITECTURE.md` § Modules § ScalaTest core, test output capture (Native)

Risks:

- `research/js-native-structure-and-capture.md` § 4.2
- `SPECIFICATION-DECISIONS.md` § Test output is captured on Scala.js and Scala Native as well as the JVM (Costs: every Native run writes through a pass-through stream)

## Deferred

- **Per-test attribution of overlapping async tests on Node** through `AsyncLocalStorage`. Unverified; the accepted limitation is in `research/js-native-structure-and-capture.md` § 4.1 and `SPECIFICATION-DECISIONS.md` § A test owns test output only while it is the only test running on the writing thread.
- **Attributing output from a test's own `Future` or the threads it starts on the JVM and Native**, by propagating the owning node. Today this output passes through live (`SPECIFICATION-DECISIONS.md` § A write belongs to the run whose node or own code is running on the writing thread, and output belonging to no run is never captured, Costs).
- **A tool that pulls `-oJ` lines out of build logs** so jsdom and browser runs can be consolidated (`SPECIFICATION-DECISIONS.md` § Where a Scala.js run cannot write files, `-fJ` is a configuration error and `-oJ` is the route for JSON, Costs).
- **A configurable wait for sbt's fork socket**, in place of the fixed 15 s (`ARCHITECTURE.md` § Modules, envelope forwarding).
