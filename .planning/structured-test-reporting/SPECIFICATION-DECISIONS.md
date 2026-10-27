# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A node left unfinished has outcome aborted, not a new outcome.

**Alternatives** A new outcome, incomplete, ranked worst or level with aborted, adds no information: why the node never finished is unknown either way, and the only evidence is the truncated output.

**Reasoning** ScalaTest 3.2.19's `org.scalatest.events.RunAborted` already means "a runner encountered an error while attempting to run a suite of tests" (its scaladoc), which fits a node whose process died or whose run gave up on it, so the six outcomes of `SPECIFICATION.md` R-015 stand (R-074).

**Costs** A suite that threw and a suite whose process died read alike at node level; only the run's label tells them apart (R-077).

**Settled by** [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9).

---

## A run that finishes with nodes unfinished ends in `RunAborted` for every reporter.

**Alternatives** Recording the abort only in results output and the JSON, while existing reporters keep 3.2.19's `RunCompleted`, tells two stories of one run, and puts in the JSON a `RunAborted` no reporter received (`SPECIFICATION.md` R-031).

**Reasoning** A node is left unfinished only when its process was lost or the wait for it gave up (`SPECIFICATION.md` R-074): a suite that aborts closes the tests and scopes open inside it, so a single-process sequential run in which a test throws a `VirtualMachineError` still ends in `RunCompleted`, as in 3.2.19 (R-002). Where nodes are left unfinished, tests have silently vanished, and 3.2.19's `RunCompleted`, with partial counts, reports the run as complete (`ARCHITECTURE.md` § Data flow, failure path). 3.2.19 already ends a run in `RunAborted` when sbt's forked JVM is lost, so R-075 extends that to the cases it misses.

**Costs** The first deliberate departure from 3.2.19's event stream (R-002, R-057): a `-C` reporter, IntelliJ's among them, sees `RunAborted` where 3.2.19 sent `RunCompleted`.

**Settled by** [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9).

---

## The consolidator's caller passes each module's test-task result, and a failed task marks a module that has runs, none of them failed or aborted, as test process failed.

**Alternatives**

- Accepting the gap lets a module show passed while its build task failed, whenever a process died before writing any JSON, as a Mill test-worker JVM can.
- Passing the expected number of runs is not possible: Mill decides its worker count at run time, and sbt folds forked groups into one run.
- Marking every module whose task failed as "test process failed" mislabels modules whose tests failed or that did not compile, because the task result cannot tell a crash from either.

**Reasoning** Only the build sees a process that died before writing JSON, and Mill and sbt both fail the task for it; neither tells a crash from failing tests (`ARCHITECTURE.md` § External systems). Pending, ignored and canceled tests never fail a task, so the result decides only for a module that has runs and none of them failed or aborted (`SPECIFICATION.md` R-050, R-078).

**Costs** A dead process in a module whose other runs failed shows "failed", not "test process failed", and the consolidator's input becomes partly the build's verdict. A module whose only process died before writing any JSON reads "did not run".

**Settled by** [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9).

---

## Runs that share a JSON destination write one file each, through a new reporter argument naming a directory.

**Alternatives**

- Making `-fJ` append changes an existing spelling's meaning (`SPECIFICATION.md` R-003), puts many runs in one file, needs atomic whole-line appends from concurrent JVMs, and leaves a rerun appending to stale output.
- Leaving callers to keep paths distinct, through Mill's per-worker sandbox working directory with a relative path or a per-project sbt path, fails silently when a user turns off Mill's `testSandboxWorkingDir`, and leaves a bare `Runner` user the same risk.

**Reasoning** Runs given identical arguments are normal: each Mill test-worker JVM is its own run (`ARCHITECTURE.md` § External systems), several sbt projects can share one path, and so can a module's platforms. A file named for its run ID (`SPECIFICATION.md` R-033, R-071) cannot collide whatever the caller passes, and `-fJ <file>` keeps its 3.2.19 truncating meaning as a single-run destination.

**Costs** One new reporter spelling.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## Every JSON object carries its run's run ID, not only a file's first object.

**Alternatives** The run ID in a file's opening object alone saves about 40 bytes a line, but leaves `-oJ` lines from parallel runs in one log inseparable, and makes a file's format differ from `-oJ`'s.

**Reasoning** An object read alone names its run, so every `J` spelling writes one format (`SPECIFICATION.md` R-030, R-033), and the objects of runs sharing a log can be told apart.

**Costs** About 40 bytes on every object.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## ScalaTest's JSON names no module; the consolidator's caller names each module and where its JSON is.

**Alternatives** A new ScalaTest argument stamping a module name into the JSON would make the JSON self-describing and let `-oJ` lines in one build log be sorted by module, but, as a new spelling under `SPECIFICATION.md` R-003, it is a flag every launch path must pass per module, and bare `Runner` and IntelliJ would not.

**Reasoning** A module is a build-tool concept, and the callers that consolidate, the Mill plugin and the sbt recipe, already know each module and where its JSON goes (`SPECIFICATION.md` R-050).

**Costs** A third party reading JSON gets runs but no module names, and `-oJ` lines from several modules in one build log cannot be assigned to their modules.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## Under the `Framework`, a run's results output is one block per run, printed wherever the build tool prints the test framework's summary.

**Alternatives**

- One block per suite, written through that suite's task loggers, needs no traffic between processes and keeps partial results after a crash, but reproduces the per-suite blocks sbt's default `logBuffered := true` already prints, which this epic exists to replace.
- Writing the block at `done()` through a task logger kept from an earlier `execute`, on the JVM only, would give it a log level, but no such logger exists under sbt's forked mode, on a run that executes no task, or on Scala.js and Native, so the level would vary by launch path; and it relies on undocumented sbt behaviour.

**Reasoning** A run's whole result first exists at `done()`, and the string `done()` returns is the one output every build tool prints once per run, on every platform, as soon as that run's tests finish (`ARCHITECTURE.md` § External systems), which meets `SPECIFICATION.md` R-023.

**Costs** Under Mill, results output and progress output cannot be separated (`SPECIFICATION.md` R-022). A run whose `done()` never runs shows no results output (`ARCHITECTURE.md` § Data flow, failure path); any other dead worker's suites appear only as far as that worker reported them.

**Settled by** [Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6).

**See** `research/js-native-structure-and-capture.md` § 3.2.

---

## Where a Scala.js run cannot write files, `-fJ` and the JSON directory argument are configuration errors and `-oJ` is the route for JSON.

**Alternatives**

- Falling back silently from a file argument to `-oJ`'s logger output puts the JSON in build-tool logs, where neither the Mill plugin nor the sbt recipe looks, so the module drops out of consolidation unexplained.
- Relaying the JSON out over another channel collapses into that fallback: from jsdom or a browser the build tool's loggers are the only way out, since no ScalaTest code runs on the build tool's JVM and an sbt plugin is out of scope.

**Reasoning** A user who asks for a file that cannot be written is told so by the run itself, not by a consolidation that comes up short. `-oJ` reaches the build tool from every environment that has one, so the JSON stays available to whoever extracts it.

**Costs** jsdom and browser users who want consolidation extract `-oJ` lines from build logs themselves.

**Settled by** [Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6).

**See** `research/js-native-structure-and-capture.md` § 2.3 and § 3.1.

---

## A write belongs to the run whose node or own code is running on the writing thread, and output belonging to no run is never captured.

**Alternatives**

- Giving output on other threads to the process's only active run, passing it through when several are active, makes behaviour depend on what else the process is running, and captures the build tool's and other frameworks' output while one module runs.
- Giving a thread to the run whose node started it misattributes pooled threads, which the first run to need them creates and later runs reuse.

**Reasoning** Only a thread running a run's node or own code identifies a run without depending on what else the process is running (`SPECIFICATION.md` R-067, R-070). It keeps ScalaTest from capturing or withholding the build tool's output, other frameworks' output, and callbacks a finished run leaves in a shared Scala.js or Native process that fire while no run's code is running.

**Costs** On the JVM and Native, output from a test's own `Future` on a shared pool, or from a thread the test starts, reaches the console live and interleaved even when other test output is withheld, and is absent from results output and JSON. On Scala.js, a late callback from a finished run attaches to whichever test is running.

**Settled by** [Which run owns test output when one process hosts several runs?](https://github.com/jamesgorman2/scalatest/issues/8).

---

## Test output is captured on Scala.js and Scala Native as well as the JVM.

**Alternatives**

- JVM only leaves a Scala.js or Native run under sbt, several worker processes with interleaved, unattributed stdout, as unreadable as the fork exists to stop.
- Scala Native only would leave Scala.js, whose stdout already reaches the user, unattributed under parallel workers.

**Reasoning** Both runtimes can capture what tests print through their own language-level streams, as prior art has done, and output that capture cannot pin to a test falls back to its suite or its run, or is not captured (`SPECIFICATION.md` R-040, R-067, R-069).

**Costs** Each runtime needs its own capture code, and every Native run, 3.2-compatible or not, writes through a pass-through `System.out` and `System.err`.

**Settled by** [Does the epic capture test output on Scala.js and Scala Native?](https://github.com/jamesgorman2/scalatest/issues/7).

**See** `research/js-native-structure-and-capture.md` § 4.

---

## A test owns test output only while it is the only test running on the writing thread.

**Alternatives**

- Attaching output to the most recently started test misattributes it to a sibling whenever async tests overlap.
- Attaching all output to the suite gives up per-test output for serial tests, where attributing it is exact.

**Reasoning** A thread identifies its test only while no other test runs on it: always for synchronous tests on the JVM and Native, never for overlapping async tests, and on Scala.js, where one thread runs everything, only while tests do not overlap.

**Costs** Overlapping tests of an async `ParallelTestExecution` suite, on every platform, lose their per-test output to the suite.

**Settled by** [Does the epic capture test output on Scala.js and Scala Native?](https://github.com/jamesgorman2/scalatest/issues/7).

---

## Only output written through the runtime's own streams is captured; output written straight to file descriptors, from native code or by child processes is not.

**Alternatives**

- `console` alone on Scala.js misses code that writes to `process.stdout` directly, a gap closed cheaply on Node, where nearly every build-tool run executes.
- Redirecting file descriptors 1 and 2 on Scala Native catches C code and child processes, but cannot attribute by thread, needs a thread to drain the pipe and a flush of C stdio at every boundary, and is POSIX only.

**Reasoning** Each runtime captures what Scala tests print through the language's own streams, the overwhelming case; the descriptor-level output Native gives up could not be attributed to a node, or, where runs share a process, even to a run.

**Costs** Outside Node, Scala.js captures only `console`; on Native, output from C code and child processes is not captured and, under Mill's daemon, still never reaches the console.

**Settled by** [Does the epic capture test output on Scala.js and Scala Native?](https://github.com/jamesgorman2/scalatest/issues/7).

**See** `research/js-native-structure-and-capture.md` § 4.1 and § 4.2.

---

## Captured test output is withheld from stdout and stderr unless a `-C` reporter or a 3.2-format `-o` or `-e` reporter is configured.

**Alternatives**

- Always forwarding leaves the default console output under parallel runs as unreadable as today, the readable copy arriving only at the end.
- Forwarding whenever no reporter writes to the console, as with `-fJ` alone, would make enabling JSON also put raw test output on the console, which a CI configuration should have to request explicitly.

**Reasoning** Withheld output reappears, attributed, where it is rendered (`SPECIFICATION.md` R-024 and R-034), so the default console reads cleanly under parallelism. Forwarding exactly when a `-C` reporter or a 3.2-format `-o` or `-e` reporter is configured (`SPECIFICATION.md` R-062) keeps 3.2-format output and IntelliJ's forked and sbt-shell modes unchanged.

**Costs** `println` debugging of passing tests, and a hung or crashed run's test output, disappear from the default console; a run with only `-fJ` prints no test output. IDEs driving the `Framework` with no reporter argument (IntelliJ BSP, Metals, Bloop) no longer show test output live.

**Settled by** [What does the console show of test output, and when is capture on?](https://github.com/jamesgorman2/scalatest/issues/5).

---

## Test output is captured only when a reporter consumes it.

**Alternatives**

- Always on, every 3.2-compatible configuration, including IntelliJ's forked and sbt-shell modes, would run under replaced streams, so compliance with `SPECIFICATION.md` R-002 and R-003 would rest on capture being invisible.
- An opt-in flag would keep `SPECIFICATION.md` R-024's failed-test output out of the default output unless the flag was set.

**Reasoning** Not capturing where nothing reads the output loses nothing, and on the JVM and Scala.js, 3.2-compatible configurations run exactly as ScalaTest 3.2.19 does.

**Costs** Whether capture is on depends on the reporter set, so adding `J` beside a `-C` reporter turns capture on under it.

**Settled by** [What does the console show of test output, and when is capture on?](https://github.com/jamesgorman2/scalatest/issues/5).

---

## The consolidator ships as an executable and a Mill plugin; sbt users get a recipe.

**Alternatives**

- An sbt plugin costs a second plugin to build and maintain, and the executable already covers CI.
- A Mill recipe, as for sbt, must hand-maintain the module set and each module's JSON location, which the plugin knows, and cannot consolidate after failed tests, because Mill skips every task downstream of a module whose tests fail; the plugin owns a command that can.

**Reasoning** The executable runs anywhere a JSON file exists, which covers CI. The Mill plugin derives the module set and each module's JSON location from the build. sbt users get the same result through the recipe (`SPECIFICATION.md` R-060), so an sbt plugin would add only its cost.

**Costs** sbt users wire the executable into their build themselves.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The consolidator writes a fixed set of formats, and the documented JSON is the only extension point.

**Alternatives** A renderer plug-in API would be a second public contract to keep stable beside the JSON format.

**Reasoning** Given `SPECIFICATION.md` R-037, a renderer API adds a contract the consolidator must keep and no capability a third party lacks.

**Costs** A format outside the set is built and maintained outside this fork.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## JSON output is opt-in.

**Alternatives** On by default, files would appear in every build, and something would have to own the output directory.

**Reasoning** The human-readable default already makes runs readable without it; the JSON serves tools and the consolidator, which are configured deliberately.

**Costs** Consolidation needs every run configured to write JSON first.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## A run's results output is printed when that run completes, not when the whole build does.

**Alternatives** Waiting for every module would need a view of the build that ScalaTest does not have: it sees one `Runner` run, or one `Framework` runner up to `done()`.

**Reasoning** The consolidator already reads every run's JSON after the build, so a whole-build view belongs there.

**Costs** A multi-module build prints one results output per run; one view of the whole build needs the consolidator.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## Progress-then-results is the default for plain `-o`, `-e` and the `Framework`, not an opt-in.

**Alternatives** A new config character to opt in would bring readability only to those who ask, and the fork exists to make it normal.

**Reasoning** Under `SPECIFICATION.md` R-003, the tools that parse ScalaTest text, IntelliJ's sbt-shell mode among them, keep what they parse.

**Costs** Anyone parsing plain `-o` text sees a changed format.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The test structure gets no public API, not even an additive one.

**Alternatives**

- New `Event` subclasses are excluded by `SPECIFICATION.md` R-002, because precompiled matches on the sealed `Event` would break.
- A new reporter trait is rejected because it would be a second stable public contract beside the JSON format.

**Reasoning** The structure needs to reach only ScalaTest's own reporters and the JSON, and the documented JSON (`SPECIFICATION.md` R-037) gives third parties the structure without a public type the fork must keep stable.

**Costs** Third-party reporters cannot see the test structure in process; it reaches them only through the JSON.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The fork stays binary compatible with ScalaTest 3.2.x.

**Alternatives**

- New fields on the public `Event` classes, even source compatible, break IntelliJ's forked-mode reporter and every other precompiled `-C` reporter.
- Diverging freely would make every upstream merge manual and close the path to upstreaming.

**Reasoning** Precompiled reporters link against the 3.2.x event classes, so any change to them fails at run time in tools this fork cannot rebuild.

**Costs** No existing public type can change shape, so the test structure cannot ride on `Event`.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---
