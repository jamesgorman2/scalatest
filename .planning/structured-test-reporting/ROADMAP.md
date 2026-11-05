# Roadmap - Structured test reporting

Features of the epic [Structured test reporting](SPECIFICATION.md), in intended implementation order: the JVM `Runner`, then the JVM `Framework`, then JVM test output capture, then consolidation and build integration for JVM modules, then Scala.js and Scala Native. Spec document paths are relative to `.planning/structured-test-reporting/`; code paths are relative to the repo root. `SPECIFICATION.md` bullets carry no IDs, except R-073 (§ Multi-run consolidation plugin), so the rest are cited by section and a short quotation. "Review of #N" means the review comment on that closed design issue; "#N question" means the question of an issue closed without an answer.

## 2. Results block from `Runner`

Under `Runner`, every human-readable `-o`, `-e` and `-f` reporter other than `-oDU` prints one human-readable block at the end of the run, rendered from tree assembly:

- the 3.2.19 format, plus suite-hierarchy indentation where needed;
- a subtree whose nodes share one error collapsed to its root;
- declared order where known, otherwise alphanumeric;
- no test output, which streams live as today.

`-oDU` stays byte-for-byte 3.2.19. Until feature 3, these reporters print nothing during the run.

Enables:

- `SPECIFICATION.md` § Human-readable output (all but "reports run aborted"), § Constraints § Compatibility with ScalaTest 3.2.x ("`-oDU` remains unchanged"; flags keep their meanings), § Scope ("correctly outputting human-readable output"), all under `Runner`
- `ARCHITECTURE.md` § Modules § ScalaTest core (human renderer: the block, written to `Runner`'s stdout, stderr or file)

Settles:

- What counts as "one error" for collapsing, and how a user turns collapsing off (review of #16: hiding rules left to features).
- Where hierarchy indentation is "necessary".
- How existing configuration characters (`D`, `S`, `F`, `W`, `U` and the rest) apply to the block.

Risks:

- `SPECIFICATION-DECISIONS.md` § Progress-then-results is the default for plain `-o`, `-e` and the `Framework`, not an opt-in (Costs)
- `SPECIFICATION.md` § Constraints § IntelliJ (sbt-shell regex parsing of `-oDU`)

## 3. Progress output from `Runner`

Under `Runner`, `-o`, `-e` and `-f` stream progress output as results arrive: one character per test result or suite failure, and a running summary of the run. It runs alone or ahead of the block on the same output, never on a reporter writing HTML, XML or JSON.

Enables:

- `SPECIFICATION.md` § Progress output (all but "best effort on JS/Native"), under `Runner`
- `ARCHITECTURE.md` § Modules § ScalaTest core (human renderer: progress output and its per-reporter Assumption; envelope dispatch ahead of the sorting reporters)

Settles:

- How a reporter selects progress only, progress then block, or block only, given that existing flags keep their 3.2.19 meanings.
- The character set and the summary's content.
- Whether progress on an HTML, XML or JSON reporter is a configuration error or ignored.

Risks:

- `ARCHITECTURE.md` § Modules § ScalaTest core, human renderer (Assumption: progress is barred per reporter, not per invocation)
- Live test output (`SPECIFICATION.md` § Human-readable output, "streamed out as it is received") interleaves with progress characters on the same stream

## 4. Structured output under the JVM `Framework`, in process

Under sbt unforked, Mill test-worker JVMs, Metals, Bloop and IntelliJ BSP:

- the `Framework`'s runner gets the run node's wrapper; each task wraps its suite;
- progress output goes through each task's loggers while it executes; run-level output is held for the next task or the head of the `done()` string;
- each run returns its block, and any `-oJ` object, as its `done()` string;
- each `-f` reporter's block is written to its file;
- `-fJ` is written at `done()`; Mill's discovery runner, which never executes, leaves no file;
- test-interface events and `-oDU` are unchanged.

Enables:

- `SPECIFICATION.md` § Scope (JVM `Framework` launch path), § Constraints § Compatibility with ScalaTest 3.2.x ("same test-interface events"; "every execution mode"), § Constraints § IntelliJ (sbt shell, BSP), § Test structure ("attach to the node that emitted them"; "first identifiable ancestor"; "every node has a duration"; "A suite that aborts"; "nodes that share a name are never consolidated"), § Human-readable output, § Progress output, § JSON output, § Mill ("concurrent test invocations may interleave"), all for the in-process JVM `Framework`
- `ARCHITECTURE.md` § Modules § ScalaTest core (structure wrapper for the `Framework`'s runner and tasks; human renderer: loggers, held output, `done()` string, the `-f` block in its file; JSON writer: `-fJ` at `done()`, `-oJ` in the `done()` string, no file from Mill's discovery runner), § External systems (sbt unforked, Mill, Metals and Bloop, IntelliJ)

Settles:

- Where `-e` progress output, the `-e` block and `-eJ` go under the `Framework`, which accepts `-e` (`jvm/core/src/main/scala/org/scalatest/tools/Framework.scala` lines 1060–1063; `ARCHITECTURE.md` § Modules § ScalaTest core, human renderer, is silent).

Risks:

- `ARCHITECTURE.md` § External systems, Mill (a discovery runner constructed and dropped): today the runner's constructor builds its reporters and fires `RunStarting` (`jvm/core/src/main/scala/org/scalatest/tools/Framework.scala` lines 676 and 687), and a `-f` reporter opens its file there (`jvm/core/src/main/scala/org/scalatest/tools/PrintReporter.scala` lines 129–130)
- `ARCHITECTURE.md` § Modules § ScalaTest core, human renderer (loggers exist only during `execute`)
- `ARCHITECTURE.md` § External systems (Mill prints line by line; unforked sbt runs several runs, possibly under several loads of ScalaTest)
- `SPECIFICATION-DECISIONS.md` § Under the `Framework`, a run's results output is one block per run, printed wherever the build tool prints the test framework's summary (Costs)

## 5. Runs that end early leave evidence and lose no node

Under `Runner` and the in-process JVM `Framework`:

- open-node tracking runs in every run, whatever reporters are configured;
- every node the run was to execute that has no result is aborted, in JSON and the block, including unstarted top-level suites and tests registered in a constructed suite that never ran; filter-excluded nodes are reported as 3.2.19 reports them;
- a run that finishes with unfinished nodes emits `RunAborted` with reason *process failed* in place of `RunCompleted`, to every reporter;
- a `RunAborted` reason is held beside the `Event` and written to the JSON;
- the block and the JSON report the run aborted when produced without a valid completion event;
- `-fJ` writes a marker at the run's first execution, rewrites it as top-level suites become known (Mill's queue path) and replaces it at the end;
- a JVM shutdown hook replaces the marker with the full tree on `System.exit` or SIGTERM, never over a later run's file.

Enables:

- `SPECIFICATION.md` § Test structure ("any nodes unfinished … `RunAborted`"; "Every node the run was to execute … reported as aborted"), § Human-readable output and § JSON output ("reports run aborted if printed before receiving a valid completion event"), § Constraints § Compatibility with ScalaTest 3.2.x ("except where explicitly noted"), § Constraints § IntelliJ (forked mode sees `RunAborted`)
- `ARCHITECTURE.md` § Modules § ScalaTest core (tree assembly: a top-level suite as a node from `Runner` discovery or task creation, open-node tracking, aborted outcome, the `RunAborted` rule, the *process failed* reason; JSON writer: first execution, marker, rewrite, full object at the end, atomic replacement, JVM shutdown hook, never over another run's file), § Data flow (failure path, `Runner` and Mill)

Settles:

- How the shutdown hook detects that another run has written the file since its run's last write (`ARCHITECTURE.md` § Modules § ScalaTest core, JSON writer, states the rule, not the means).

Risks:

- `SPECIFICATION-DECISIONS.md` § A run that finishes with nodes unfinished ends in `RunAborted` for every reporter (Costs: the first departure from 3.2.19's event stream; IntelliJ's `-C` reporter)
- `SPECIFICATION-DECISIONS.md` § A node left unfinished has outcome aborted, not a new outcome (Costs)
- `ARCHITECTURE-DECISIONS.md` § Every run with a `-fJ` reporter that can write its file leaves that file once it starts executing … (Costs: a hard death loses results) and § On a soft death, a shutdown hook replaces a run's marker … (Costs: best effort)
- No 3.2.19 event lists a suite's registered tests, so tests that never ran must be learnt from the constructed suite
- `research/mill-test-worker-args.md` § 1.2 (queue path) and § 1.3 (each worker calls `System.exit(0)` after `done()`, so the hook must be deregistered first)

## 6. Structured output under sbt's forked mode

With `Test / fork := true`:

- forked JVMs send envelopes over the existing socket; node IDs carry a per-process component;
- the runner in sbt's JVM owns `-f`, `-u`, `-h`, `-C` and the JSON; each fork keeps `-o` through its task loggers;
- that runner renders the block and writes the JSON once the socket closes, or once its 15 s wait gives up, ending the run in `RunAborted` if nodes are unfinished;
- a lost fork's `RunAborted` carries *process failed*; the fork's own shutdown-hook `done()` applies the unfinished rule to its nodes;
- the marker is rewritten when a later `testGrouping` group's fork creates its tasks.

Enables:

- `SPECIFICATION.md` § Scope (sbt via the JVM `Framework`, forked), § Constraints § Compatibility with ScalaTest 3.2.x, § Test structure, § Human-readable output, § Progress output, § JSON output, all under sbt's forked mode
- `ARCHITECTURE.md` § Modules § ScalaTest core (structure wrapper: per-process ID component; envelope dispatch: open-node tracking in the forked JVM; envelope forwarding: sbt branch; tree assembly: forked JVM and the fork's run-level events; JSON writer: rewrite for later `testGrouping` groups; human renderer: `-f` from the owning process), § Data flow (failure path, sbt), § External systems (sbt forked, `System.exit`, failure)

Settles:

- Where a forked JVM gets its per-process ID component (`ARCHITECTURE.md` § Modules § ScalaTest core, structure wrapper, requires one, not its source).
- How a `RunAborted` reason crosses the fork socket (review of #16).
- Where the fork's own `done()` string goes (review of #9).
- The whole-run progress summary under forked mode (review of #15).
- Whether the fork's shutdown-hook `done()` can block on a still-running async suite (review of #9).

Risks:

- `ARCHITECTURE.md` § Modules § ScalaTest core, envelope forwarding (the 15 s wait, `jvm/core/src/main/scala/org/scalatest/tools/Framework.scala` lines 785–786; a slow fork ends as *process failed*; late envelopes are dropped)
- `ARCHITECTURE.md` § Modules § ScalaTest core, tree assembly (the fork's run-level events are dropped or redispatched; the lost-fork `RunAborted` is emitted in `Skeleton.React.tryReact`, `Framework.scala` lines 928–941)
- `ARCHITECTURE.md` § External systems, sbt (every forked `testGrouping` group belongs to one run)

## 7. Test output in `Runner`'s JSON

Under `Runner` on the JVM, a run with a JSON reporter captures test output: every write still reaches stdout and stderr live, and each is attached to the closest node in the JSON (the test, when it is the only test running on the thread, else the innermost suite, else the run node). Output on threads running no node or run code, and reporters' own writes, are not captured.

Enables:

- `SPECIFICATION.md` § Test output (all bullets), § JSON output ("writes stderr and stdout output as properties on the closest recognisable node"), § Human-readable output ("test output is streamed out as it is received"), under `Runner`
- `ARCHITECTURE.md` § Modules § ScalaTest core (test output capture: its Assumption, thread marking by `Runner`, routing, `Console` per thread; structure wrapper: thread reporting), § The compatibility boundary (no reporter's writes are test output)

Settles:

- Where output written during suite construction attaches: the suite or the run node (review of #8).
- How JVM `println` reaches capture (reviews of #7 and #8).
- How output is placed in the JSON relative to events and which stream wrote it.

Risks:

- `SPECIFICATION-DECISIONS.md` § A write belongs to the run whose node or own code is running on the writing thread, and output belonging to no run is never captured (Costs), and § A test owns test output only while it is the only test running on the writing thread (Costs)
- `SPECIFICATION-DECISIONS.md` § Test output is captured only when a reporter consumes it (Costs: `J` beside `-C` turns capture on under it)
- `-oJ` and `-eJ` write the JSON to a stream that also carries live test output (`ARCHITECTURE.md` § Modules § ScalaTest core, JSON writer)

## 8. Test output in JSON under the JVM `Framework`

Capture extends to every JVM `Framework` path:

- the `Framework`'s runner and tasks mark the threads running their code;
- several runs in one JVM each own their writes, under their own configuration;
- captures from separately loaded copies of ScalaTest chain;
- under sbt's forked mode, captured output travels to sbt's JVM as envelopes.

Enables:

- `SPECIFICATION.md` § Test output, § JSON output (test output properties), for the JVM `Framework`
- `ARCHITECTURE.md` § Modules § ScalaTest core (test output capture: marking by the `Framework`'s runner and tasks, routing to the owning run, chaining; envelope forwarding: output envelopes over the fork socket), § External systems (unforked sbt runs several runs, possibly under several loads)

Settles:

- How captures from separately loaded copies of ScalaTest chain (review of #8).

Risks:

- `SPECIFICATION-DECISIONS.md` § A write belongs to the run whose node or own code is running on the writing thread, and output belonging to no run is never captured (Costs)
- `ARCHITECTURE.md` § Modules § ScalaTest core, test output capture (no capture is removed while another sits above it)

## 9. Consolidator: JSON and human-readable output

A standalone executable takes one or more test run labels, each with a list of JSON files, and writes consolidated JSON and a human-readable block, chosen with ScalaTest's own reporter flags. It:

- merges each file under its label's node, keeping each file's closing run event and reason, and never collapses suites or tests that share a name across files;
- shows an absent file as `RunAborted` with reason *not run*, and a label with no files as an empty label node;
- classifies nothing, and exits independently of test results;
- rejects duplicate labels;
- rebuilds `Event`s from the JSON and renders with core's tree assembly and human renderer.

Enables:

- `SPECIFICATION.md` § Scope (multi-run consolidation executable), § Multi-run consolidation plugin ("executable" bullets, except JUnit XML), § Test structure ("nodes that share a name are never consolidated"), § Constraints ("throwables are preserved exactly")
- `ARCHITECTURE.md` § Multi-run consolidation executable, § Modules § ScalaTest core (tree assembly: the *not run* reason; aborted outcome for nodes with no result at input end), § Data flow (consolidator)

Settles (from review of #3 unless stated):

- Where JSON reading lives.
- The consolidator's Scala versions and platforms, packaging and invocation.
- The consolidated JSON format (also review of #15).
- Test run label and top-level suite order (review of #6).
- Whether a run is itself a rendered node (`ARCHITECTURE.md` § Multi-run consolidation executable), and whether human output lists runs or top-level suites for a marker ([What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16), answer item 8).
- Empty or unparseable input files, and the consolidator's own failure conditions (review of #16).
- Which of ScalaTest's flags it accepts, and how it reads JSON of another format version (feature 1).

Risks:

- `ARCHITECTURE-DECISIONS.md` § Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on (Costs: lockstep release; core's dependencies)
- `SPECIFICATION-DECISIONS.md` § The consolidator's exit code does not depend on test results (Costs) and § ScalaTest's JSON carries no test run label; the consolidator's caller gives each label and its JSON files (Costs)

## 10. Consolidated JUnit XML

The consolidator also writes JUnit XML, flattening each node's containment and its test run label into suite and test names, for CI readers.

Enables:

- `SPECIFICATION.md` § Multi-run consolidation plugin ("JUnit XML")
- `ARCHITECTURE.md` § External systems (CI JUnit XML readers)

Settles:

- Whether test output goes into JUnit XML (review of #5).
- The flattening scheme, and how a *not run* or *process failed* `RunAborted` appears (review of #15: producers of consolidated JSON and JUnit XML).

## 11. Mill plugin

The plugin's command, for each selected JVM module:

- passes the forked test command a relative `-fJ` path, so each test-worker JVM writes its own file in its own sandbox;
- fails the build when `testSandboxWorkingDir` is off;
- runs the tests and, even when they fail, consolidates the runs it started, one test run label per module, named for its path, covering every JSON file under its sandboxes in the command's `Task.dest`.

Enables:

- `SPECIFICATION.md` § Scope ("mill plugins"), § Mill, § Multi-run consolidation plugin ("exposed as … a mill plugin"; "mill plugin provides a command"), for JVM modules
- `ARCHITECTURE.md` § Mill plugin, § Multi-run consolidation executable (the Mill plugin's labels and files), § External systems (Mill)

Settles:

- The Mill versions supported, and whether the consolidator runs in process or as a subprocess (review of #3).
- A consolidated module's console output (review of #6).
- The command's build outcome when tests fail (review of #16).
- Mill plugin constraints (review of #1).

Risks:

- `research/mill-test-worker-args.md` I-4 (a `testTask` override that collects after `super.testTask` never runs when tests fail; consolidating needs the `@experimental` `TestModuleUtil.runTests`), I-1 (a reused `processFolder` fails the task), § 3 (`testLocal` runs in Mill's process, outside any sandbox)
- `SPECIFICATION-DECISIONS.md` § The consolidator is an executable, with a Mill plugin whose command runs the tests and consolidates; sbt users get a recipe (Alternatives: Mill skips downstream tasks when tests fail)
- `ARCHITECTURE-DECISIONS.md` § The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file … (Costs: sandbox off)

## 12. sbt recipe

A documented recipe that gives each JVM module its own `-fJ` path, deletes the file before `test`, and after `test`, even when tests fail, consolidates one test run label per module.

Enables:

- `SPECIFICATION.md` § Scope ("sbt recipes"), § Multi-run consolidation plugin ("sbt" bullets), for JVM modules
- `ARCHITECTURE.md` § Multi-run consolidation executable (the sbt recipe's labels and delete), § External systems (sbt, failure)

Settles:

- The sbt versions supported (review of #6).
- The recipe's build outcome when tests fail, and a module with no tests (review of #16).

Risks:

- `ARCHITECTURE-DECISIONS.md` § Each consolidating caller gives every test run label fresh JSON files each time its command runs … (Costs: a dropped delete consolidates stale runs silently)
- `SPECIFICATION-DECISIONS.md` § The consolidating caller keeps runs that share arguments from colliding on one JSON file … (several sbt projects can share one path)

## 13. Structured output on Scala.js

- Structure wrappers in `TaskRunner`'s `Args`; envelope dispatch with no `DispatchReporter`, ahead of any `TestSortingReporter`; node IDs with a per-process component.
- Workers forward envelopes to the controller through `notifyServer`.
- The controller writes all console output (progress best effort, held between tasks), the block and any `-oJ` object as its `done()` string, and `-fJ` through Node's synchronous `fs`. Where `fs` cannot load, `-fJ` is a configuration error; `testHtml` produces no JSON.
- A suite whose constructor throws is reported as aborted.
- `MasterRunner` runs open-node tracking and ends a run with unfinished nodes in `RunAborted`; the marker and a `process.on('exit')` hook cover a run whose `done()` never runs, as after a worker dies.

Enables:

- `SPECIFICATION.md` § Scope (Scala.js `Framework`; 2.12, 2.13, 3.3), § Constraints § Compatibility with ScalaTest 3.2.x, § Test structure (including "whose constructor throws … on every platform"), § Human-readable output, § Progress output ("best effort on JS/Native"), § JSON output ("In JS/Native this includes all sub-processes"), all for Scala.js
- `ARCHITECTURE.md` § Modules § ScalaTest core (every part, for Scala.js: the source generators; envelope forwarding through `notifyServer`; JSON writer through Node `fs`, its configuration error and the Node exit listener; human renderer from the controller), § The compatibility boundary (the one `-o` reporter writes through loggers), § Data flow (the Scala.js and Native bullet; failure path), § External systems (Scala.js and Native test interface)

Settles:

- How a worker gets its per-process ID component when nothing in the protocol identifies it (`research/js-native-structure-and-capture.md` § 1.4).

Risks:

- `ARCHITECTURE.md` § Modules § ScalaTest core, JSON writer (assumes, unverified, that sbt passes all of a run's tasks to the controller in one `tasks` call, so the controller plans no marker rewrite; review of #16)
- `research/js-native-structure-and-capture.md` § 2.1 (loggers detached when `execute` returns), § 2.2 (ordering inference; asynchronous-only replies; unpaired surrogates), § 2.3 (ES modules need `process.getBuiltinModule`), § 5 items 1, 2, 6 and 7
- `ARCHITECTURE-DECISIONS.md` § On Scala.js and Native, the controller writes all of a run's console output; workers only forward envelopes (Costs) and § On Scala.js and Native, the controller writes console output only through its tasks' loggers or the `done()` string … (Costs)
- The suite is constructed outside the `try` (`js/core/src/main/scala/org/scalatest/tools/TaskRunner.scala` line 79; the `try` opens at line 149), so a throwing constructor is not reported as aborted today
- The Scala.js `Framework` carries no file reporter today (`ARCHITECTURE.md` § External systems), so `-fJ` is new there

## 14. Structured output on Scala Native

As feature 13 on Native, with these differences:

- `-fJ` writes through `FileOutputStream`'s channel, checking partial writes;
- every `send`, of events, logger lines and envelopes, goes through one lock per process;
- the exit handler is `Runtime.addShutdownHook`;
- after a worker dies, the controller's `done()` still runs and ends the run in `RunAborted`.

Enables:

- As feature 13, for Native.
- `ARCHITECTURE.md` § Modules § ScalaTest core, envelope forwarding (Native `send` lock), § External systems (Native's `RunnerAdapter.done` skips closed processes)

Risks:

- `research/js-native-structure-and-capture.md` § 2.1 (stdout is an inherited file descriptor), § 2.2 (Native thread safety; unpaired surrogates), § 2.3 (partial writes), § 5 items 3 to 5
- The suite is constructed outside the `try` (`native/core/src/main/scala/org/scalatest/tools/TaskRunner.scala` lines 79–83; the `try` opens at line 154)

## 15. Build integrations for Scala.js and Native modules

The Mill plugin (feature 11) and the sbt recipe (feature 12) turn on `-fJ` for Scala.js and Native modules and consolidate their runs beside JVM modules, each module under its own test run label. They leave out Scala.js modules whose runs cannot write files.

Enables:

- `SPECIFICATION.md` R-073, § Multi-run consolidation plugin ("mill plugin" and "sbt" bullets, for Scala.js and Native modules)
- `ARCHITECTURE.md` § External systems (Scala.js and Native test interface)

Settles:

- How the plugin and the recipe recognise a Scala.js module that cannot write files.
- How the Mill plugin gives Scala.js and Native modules their files and labels (review of #15; `research/mill-test-worker-args.md` covers only the JVM `TestModule`).

Risks:

- `SPECIFICATION-DECISIONS.md` § Where a Scala.js run cannot write files, `-fJ` is a configuration error and `-oJ` is the route for JSON (Costs)
- `research/js-native-structure-and-capture.md` § 1.3 (both plugins reject forking; Mill drives a run on one thread), § 2.3 (jsdom, browser, ES modules), § 5 item 6
- `research/mill-test-worker-args.md` (opening scope note: Scala.js and Native override `testTask`)

## 16. Test output capture on Scala.js

`console.*`, and on Node `process.stdout` and `process.stderr`, are replaced so that test output is attributed under the single-thread rule, passed through live, and forwarded from workers as envelopes. TeamCity lines pass through unaltered, and output from no run's code, as in a process shared across runs, is not captured.

Enables:

- `SPECIFICATION.md` § Test output (Scala.js `console`; Node `process.stdout` and `process.stderr`), § JSON output (test output properties), for Scala.js
- `ARCHITECTURE.md` § Modules § ScalaTest core (test output capture; envelope forwarding of output)

Settles:

- How test output reaches the console live when process stdout is unordered and may be dropped near `done()` (review of #15, against `ARCHITECTURE-DECISIONS.md` § On Scala.js and Native, the controller writes console output only through its tasks' loggers or the `done()` string …).

Risks:

- `research/js-native-structure-and-capture.md` § 4.1 (partial-line buffer; overlapping tasks), § 1.1 (VMs outlive runs), § 2.2 (unpaired surrogates in captured output), § 5 item 8
- `SPECIFICATION-DECISIONS.md` § A test owns test output only while it is the only test running on the writing thread (Costs) and § A write belongs to the run whose node or own code is running on the writing thread … (Costs: late callbacks)

## 17. Test output capture on Scala Native

A permanent delegating `System.out` and `System.err`, installed when the `Framework` loads and before `scala.Console` initialises, routes writes by thread to the owning run, passes every write through, and forwards captured output from workers to the controller as envelopes.

Enables:

- `SPECIFICATION.md` § Test output, § JSON output (test output properties), for Native
- `ARCHITECTURE.md` § Modules § ScalaTest core (test output capture, Native; envelope forwarding of output)

Settles:

- How test output reaches the console live under Mill's daemon, where the inherited descriptor does not (review of #15).

Risks:

- `research/js-native-structure-and-capture.md` § 4.2, § 2.1
- `SPECIFICATION-DECISIONS.md` § Test output is captured on Scala.js and Scala Native as well as the JVM (Costs: every Native run writes through a pass-through stream) and § Only output written through the runtime's own streams is captured … (Costs)

## Deferred

- **An sbt plugin for the consolidator**, in place of the recipe (`SPECIFICATION.md` § Out of scope; `SPECIFICATION-DECISIONS.md` § The consolidator is an executable …).
- **A renderer plug-in API for the consolidator** (`SPECIFICATION.md` § Out of scope; `SPECIFICATION-DECISIONS.md` § The consolidator writes a fixed set of formats …).
- **A public in-process API for the test structure** (`SPECIFICATION.md` § Out of scope; `SPECIFICATION-DECISIONS.md` § The test structure gets no public API …).
- **`Runner` without a reporter argument writing to the console** instead of opening the GUI (`SPECIFICATION.md` § Out of scope).
- **Per-test attribution of overlapping async tests on Node** through `AsyncLocalStorage`; unverified (`research/js-native-structure-and-capture.md` § 4.1).
- **Attributing output from a test's own `Future` or threads it starts** on the JVM and Native, by propagating the owning node (`SPECIFICATION-DECISIONS.md` § A write belongs to the run …, Costs).
- **Capturing descriptor-level output on Native** (C code, child processes) with `dup2` (`SPECIFICATION-DECISIONS.md` § Only output written through the runtime's own streams is captured …).
- **A tool that extracts `-oJ` objects from build logs**, so jsdom and browser runs can be consolidated (`SPECIFICATION-DECISIONS.md` § Where a Scala.js run cannot write files …, Costs).
- **A configurable wait for sbt's fork socket**, in place of the fixed 15 s that `ARCHITECTURE.md` § Modules § ScalaTest core, envelope forwarding, keeps from today; deferred by this roadmap.
- **A shim `testFramework` for the Mill plugin**, so users with `testSandboxWorkingDir` off can use its command (`ARCHITECTURE-DECISIONS.md` § The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file …, Alternatives).
