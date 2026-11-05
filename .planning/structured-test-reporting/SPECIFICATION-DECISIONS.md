# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## Every `J` reporter writes one JSON object per run, holding the run's whole test structure, in place of ScalaTest 3.2.19's one JSON line per event.

**Alternatives** Keeping 3.2.19's per-event JSON lines, which a structure-aware reader could reassemble, cannot carry the run's whole tree, the marker an unfinished run leaves, or nodes that emitted no event, such as suites aborted *not run*.

**Reasoning** `SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x keeps the `J` spellings' meaning as JSON output but lets that output change, and only an object written once the structure is known can hold every node `SPECIFICATION.md` § Test structure requires, including those no event names.

**Costs** Every consumer of 3.2.19's per-event `J` output breaks, and no JSON appears until the run ends or writes its marker.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## After a stop, a node that never ran is aborted with reason *not run*; only the run node is stopped.

**Alternatives** Marking every skipped test and never-started suite stopped reads as a deliberate outcome rather than work missing from the run.

**Reasoning** Stopping is exceptional. Aborted, *not run* shows that the node should have run but did not, as for any other early termination.

**Costs** A reader cannot tell from a skipped node alone that the run was stopped rather than lost; only the run node's outcome says so.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## On a `J` reporter, filter characters drop only events' data; the JSON's test structure is always whole.

**Alternatives**

- Ignoring filter characters on a `J` spelling changes an existing flag's meaning (`SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x).
- Dropping a filtered event's node, as `H` dropping `SuiteStarting` would drop its suite, leaves that node's children with no parent.

**Reasoning** A node exists in the test structure whether or not its events are written, so a filter can remove what the JSON says about a node, never the node. The root's filter characters tell a reader, the consolidator among them, which data is missing rather than empty, and the consolidator renders best effort from what remains.

**Costs** A filtered JSON holds every node with some fields absent, and a reader must check the root's filter characters to tell missing data from none.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## The JSON format has no version of its own: its root records the fork's ScalaTest version, and once the epic is complete the format changes only as that version's semantic versioning allows.

**Alternatives** An independent integer format version tells a reader when the format changed without consulting release history, but is a second number to keep in step with a consolidator already released in lockstep with core (`ARCHITECTURE-DECISIONS.md` § Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on).

**Reasoning** The version that wrote a file already names the core, and so the consolidator, that understands it. Under semantic versioning a patch never changes the format and a minor only adds to it, so a reader of the documented JSON (`SPECIFICATION.md` § JSON output) that ignores unknown fields reads every file of its major version.

**Costs** A breaking format change forces a major release of the fork, and a reader cannot tell from the version alone whether a minor release added to the format.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## A run's JSON carries no run ID; in consolidation, a run is identified by its test run label and its file.

**Alternatives** A run ID minted by each run and written in its object identifies the run wherever its JSON lands, but nothing needs it: the consolidator's caller names each file and its label.

**Reasoning** Consolidation needs only which files belong to which label, which its caller supplies (`ARCHITECTURE-DECISIONS.md` § Each consolidating caller gives every test run label fresh JSON files each time its command runs …), and each file holds one run.

**Costs** An `-oJ` object in a log cannot be matched to the same run's `-fJ` file, and the objects of two identical runs cannot be told apart outside consolidation.

**Settled by** the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## The consolidator's exit code does not depend on test results.

**Alternatives**

- Exiting non-zero when any run failed or aborted makes the consolidator judge results the build's test task already judges, and a failed consolidation could no longer be told from failing tests.
- Exiting non-zero only when a listed file is not run still judges results, and only one kind of them.

**Reasoning** The consolidator reports; it does not judge. Its exit code says only whether it consolidated, so twenty JSON files from runs whose test processes all failed still consolidate successfully.

**Costs** CI must read the results, or rely on the build tool's own test task, to fail on test failures.

**Settled by** [What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16).

---

## A node left unfinished has outcome aborted, not a new outcome.

**Alternatives** A new outcome, incomplete, ranked worst or level with aborted, adds no information: why the node never finished is unknown either way, and the only evidence is the truncated output.

**Reasoning** ScalaTest 3.2.19's `org.scalatest.events.RunAborted` already means "a runner encountered an error while attempting to run a suite of tests" (its scaladoc), which fits a node whose process died or whose run gave up on it, so the outcomes of `.docs/ubiquitous-language.md` § Test structure stand.

**Costs** A suite that threw and a suite whose process died read alike at node level; only the run's `RunAborted` reason, *process failed*, tells them apart.

**Settled by** [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9).

---

## A run that finishes with nodes unfinished ends in `RunAborted` for every reporter.

**Alternatives** Recording the abort only in results output and the JSON, while existing reporters keep 3.2.19's `RunCompleted`, tells two stories of one run, and puts in the JSON a `RunAborted` no reporter received.

**Reasoning** A node is left unfinished only when its process was lost or the wait for it gave up (`SPECIFICATION.md` § Test structure). A suite that aborts closes the tests and scopes open inside it, so a single-process run in which an asynchronous suite aborts with a test open, as when the test's future fails with a `VirtualMachineError`, still ends in `RunCompleted`, as in 3.2.19 (`SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x). Where nodes are left unfinished, tests have silently vanished, and 3.2.19's `RunCompleted`, with partial counts, reports the run as complete (`ARCHITECTURE.md` § Data flow, failure path). 3.2.19 already ends a run in `RunAborted` when sbt's forked JVM is lost, so `SPECIFICATION.md` § Test structure extends that to the cases it misses.

**Costs** The first deliberate departure from 3.2.19's event stream (`SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x): a `-C` reporter, IntelliJ's among them, sees `RunAborted` where 3.2.19 sent `RunCompleted`.

**Settled by** [What outcome does an unfinished node get, and how does a run's JSON record a dead process?](https://github.com/jamesgorman2/scalatest/issues/9).

---

## The consolidating caller keeps runs that share arguments from colliding on one JSON file; ScalaTest gains no directory argument, and `-fJ` keeps its meaning.

**Alternatives**

- Making `-fJ` append changes an existing spelling's meaning (`SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x), puts many runs in one file, needs atomic whole-line appends from concurrent JVMs, and leaves a rerun appending to stale output.
- A new reporter argument naming a directory, into which each run writes a file under a name unique to it, cannot collide whatever the caller passes, but adds a spelling to ScalaTest to prevent a collision the consolidating caller can prevent itself.

**Reasoning** Runs given identical arguments are normal: each Mill test-worker JVM is its own run (`ARCHITECTURE.md` § External systems), and several sbt projects can share one path. The callers that consolidate already choose each module's paths and test run label, so the Mill plugin ensures no test run label or file collision, failing the build where it cannot, and the sbt recipe gives each module its own path and label (`SPECIFICATION.md` § Multi-run consolidation plugin). `-fJ <file>` keeps its 3.2.19 truncating meaning as a single-run destination. The mechanism is in `ARCHITECTURE-DECISIONS.md` § The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file … and § The Mill plugin fails the build for a selected module whose `testSandboxWorkingDir` is off.

**Costs** Runs outside a consolidating caller that share one `-fJ` path, such as bare `Runner` users, overwrite one another's files. A Mill user who turns off `testSandboxWorkingDir` cannot use the plugin's command.

**Settled by** the user, without a sub-issue, in commit `df10bf6a4`.

---

## ScalaTest's JSON carries no test run label; the consolidator's caller gives each label and its JSON files.

**Alternatives** A new ScalaTest argument stamping a label, such as a module name, into the JSON would make the JSON self-describing and let `-oJ` lines in one build log be sorted by label, but, as a new spelling under `SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x, it is a flag every launch path must pass per module, and bare `Runner` and IntelliJ would not.

**Reasoning** Which files belong together is the caller's knowledge: the same suites run on the JVM, Scala.js and Native write JSON that only the caller can tell apart. The callers that consolidate, the Mill plugin and the sbt recipe, already know each module and where its JSON goes, so they give each module a label (`SPECIFICATION.md` § Multi-run consolidation plugin).

**Costs** A third party reading JSON gets runs but no labels, and `-oJ` lines from several modules in one build log cannot be assigned to their modules.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## Under the `Framework`, a run's results output is one block per run, printed wherever the build tool prints the test framework's summary.

**Alternatives**

- One block per suite, written through that suite's task loggers, needs no traffic between processes and keeps partial results after a crash, but reproduces the per-suite blocks sbt's default `logBuffered := true` already prints, which this epic exists to replace.
- Writing the block at `done()` through a task logger kept from an earlier `execute`, on the JVM only, would give it a log level, but no such logger exists under sbt's forked mode, on a run that executes no task, or on Scala.js and Native, so the level would vary by launch path; and it relies on undocumented sbt behaviour.

**Reasoning** A run's whole result first exists at `done()`, and the string `done()` returns is the one output every build tool prints once per run, on every platform, as soon as that run's tests finish (`ARCHITECTURE.md` § External systems), which meets `SPECIFICATION.md` § Human-readable output's one block at the end of each run.

**Costs** Under Mill, results output and progress output cannot be separated (`ARCHITECTURE.md` § External systems, Mill). A run whose `done()` never runs shows no results output (`ARCHITECTURE.md` § Data flow, failure path); any other dead worker's suites appear only as far as that worker reported them.

**Settled by** [Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6).

**See** `research/js-native-structure-and-capture.md` § 3.2.

---

## Where a Scala.js run cannot write files, `-fJ` is a configuration error and `-oJ` is the route for JSON.

**Alternatives**

- Falling back silently from `-fJ` to `-oJ`'s logger output puts the JSON in build-tool logs, where neither the Mill plugin nor the sbt recipe looks, so the module drops out of consolidation unexplained.
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

**Reasoning** Only a thread running a run's node or own code identifies a run without depending on what else the process is running (`SPECIFICATION.md` § Test output). It keeps ScalaTest from capturing or withholding the build tool's output, other frameworks' output, and callbacks a finished run leaves in a shared Scala.js or Native process that fire while no run's code is running.

**Costs** On the JVM and Native, output from a test's own `Future` on a shared pool, or from a thread the test starts, reaches the console live and interleaved even when other test output is withheld, and is absent from results output and JSON. On Scala.js, a late callback from a finished run attaches to whichever test is running.

**Settled by** [Which run owns test output when one process hosts several runs?](https://github.com/jamesgorman2/scalatest/issues/8).

---

## Test output is captured on Scala.js and Scala Native as well as the JVM.

**Alternatives**

- JVM only leaves a Scala.js or Native run under sbt, several worker processes with interleaved, unattributed stdout, as unreadable as the fork exists to stop.
- Scala Native only would leave Scala.js, whose stdout already reaches the user, unattributed under parallel workers.

**Reasoning** Both runtimes can capture what tests print through their own language-level streams, as prior art has done, and output that capture cannot pin to a test falls back to its suite or its run, or is not captured (`SPECIFICATION.md` § Test output).

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

**Reasoning** Withheld output reappears, attributed, where it is rendered (`SPECIFICATION.md` § Human-readable output and § JSON output), so the default console reads cleanly under parallelism. Forwarding exactly when a `-C` reporter or a 3.2-format `-o` or `-e` reporter is configured (`SPECIFICATION.md` § Test output) keeps 3.2-format output and IntelliJ's forked and sbt-shell modes unchanged.

**Costs** `println` debugging of passing tests, and a hung or crashed run's test output, disappear from the default console; a run with only `-fJ` prints no test output. IDEs driving the `Framework` with no reporter argument (IntelliJ BSP, Metals, Bloop) no longer show test output live.

**Settled by** [What does the console show of test output, and when is capture on?](https://github.com/jamesgorman2/scalatest/issues/5).

---

## Test output is captured only when a reporter consumes it.

**Alternatives**

- Always on, every 3.2-compatible configuration, including IntelliJ's forked and sbt-shell modes, would run under replaced streams, so compliance with `SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x would rest on capture being invisible.
- An opt-in flag would keep test output out of the JSON unless the flag was set beside the `J` spelling.

**Reasoning** Not capturing where nothing reads the output loses nothing, and on the JVM and Scala.js, 3.2-compatible configurations run exactly as ScalaTest 3.2.19 does.

**Costs** Whether capture is on depends on the reporter set, so adding `J` beside a `-C` reporter turns capture on under it.

**Settled by** [What does the console show of test output, and when is capture on?](https://github.com/jamesgorman2/scalatest/issues/5).

---

## The consolidator is an executable, with a Mill plugin whose command runs the tests and consolidates; sbt users get a recipe.

**Alternatives**

- An sbt plugin costs a second plugin to build and maintain, and the executable already covers CI.
- A Mill recipe, as for sbt, must hand-maintain each test run label and its JSON files, which the plugin derives from the build's modules, and cannot consolidate after failed tests, because Mill skips every task downstream of a module whose tests fail; the plugin owns a command that can.

**Reasoning** The executable runs anywhere a JSON file exists, which covers CI. The Mill plugin derives a test run label and its JSON files for each module from the build. sbt users get the same result through the recipe (`SPECIFICATION.md` § Multi-run consolidation plugin), so an sbt plugin would add only its cost.

**Costs** sbt users wire the executable into their build themselves.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The consolidator writes a fixed set of formats, and the documented JSON is the only extension point.

**Alternatives** A renderer plug-in API would be a second public contract to keep stable beside the JSON format.

**Reasoning** Given `SPECIFICATION.md` § JSON output, a renderer API adds a contract the consolidator must keep and no capability a third party lacks.

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

**Reasoning** Under `SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x, the tools that parse ScalaTest text, IntelliJ's sbt-shell mode among them, keep what they parse.

**Costs** Anyone parsing plain `-o` text sees a changed format.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The test structure gets no public API, not even an additive one.

**Alternatives**

- New `Event` subclasses are excluded by `SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x, because precompiled matches on the sealed `Event` would break.
- A new reporter trait is rejected because it would be a second stable public contract beside the JSON format.

**Reasoning** The structure needs to reach only ScalaTest's own reporters and the JSON, and the documented JSON (`SPECIFICATION.md` § JSON output) gives third parties the structure without a public type the fork must keep stable.

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
