# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## Test output is captured on Scala.js and Scala Native as well as the JVM.

**Alternatives**

- JVM only leaves a Scala.js or Native run under sbt, several worker processes with interleaved, unattributed stdout, as unreadable as the fork exists to stop.
- Scala Native only would leave Scala.js, whose stdout already reaches the user, unattributed under parallel workers.

**Reasoning** Both runtimes can capture what tests print through their own language-level streams, as prior art has done, and output that capture cannot pin to a test falls back to its suite or the run (`SPECIFICATION.md` R-040, R-067).

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

## Capture hooks streams inside the process, never file descriptors.

**Alternatives**

- `console` alone on Scala.js misses code that writes to `process.stdout` directly, a gap closed cheaply on Node, where nearly every build-tool run executes.
- Redirecting file descriptors 1 and 2 on Scala Native catches C code and child processes, but cannot attribute by thread, needs a thread to drain the pipe and a flush of C stdio at every boundary, and is POSIX only.

**Reasoning** Each runtime captures what Scala tests print through the language's own streams, the overwhelming case; the descriptor-level output Native gives up could only attach to an ancestor anyway.

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

**Reasoning** Not capturing where nothing reads the output loses nothing, and on the JVM and Scala.js, 3.2-compatible configurations run exactly as ScalaTest 3.2.19 does; on Native they run under a pass-through stream.

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

## Completion is the end of one run, not of the whole build.

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
