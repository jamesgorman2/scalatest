# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, newest first.

## Only an explicit test command produces consolidated output, leaving Mill's test tasks unchanged.

**Alternatives**

- A trait overriding each module's test tasks keeps `mill __.test` working, but each task consolidates only its own module's runs, so modules still interleave, and IDE and BSP launches reach the replacement (`SPECIFICATION.md` R-003).
- Offering both the trait and the command doubles the surface, while only the command consolidates across modules.

**Reasoning** Of the Mill mechanisms examined, only an exclusive command that takes the `Evaluator` has a start, an end and the failure path (`research/mill-command-lifetime.md` § 2, § 3). Confining the plugin's replacement of a module's test task to that command keeps `SPECIFICATION.md` R-003 and R-060 without the replacement having to stand aside for other launches.

**Costs** Users run a command other than `mill __.test`, built on evaluator commands that Mill documents as experimental. `+` cannot join module selectors, one set of class globs and framework arguments applies to every module (`SPECIFICATION.md` R-055), and nothing covers `testLocal` or `testCached`.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## The test command runs every selected test module by default; `--halt-on-failure` opts into skipping modules not yet started.

**Alternatives**

- Following Mill's `--keep-going`, failing fast by default, suits a build, but with modules running unordered and in parallel, which modules report depends on timing.
- Showing only the runs that started, as Mill does, hides that modules were skipped.
- Always running every module, with no opt-in, loses fast feedback on a large build.

**Reasoning** A test report's coverage should not vary with scheduling (`SPECIFICATION.md` R-056). Where the user trades coverage for speed, the output still shows which modules did not run (R-057, R-058).

**Costs** The default differs from Mill's, and the test command ignores Mill's `--keep-going`. Unless the user opts in, a failing build takes as long as a passing one.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A selected test module that does not test with this fork of ScalaTest fails the test command before any test starts.

**Alternatives**

- Running it as Mill does, showing only its worker output and whether it passed, supports mixed builds, but every requirement on test structure, failures and test output would need an exemption for it.
- Skipping it with a warning yields a report that looks complete when it is not.

**Reasoning** Every requirement then holds for every run in the consolidated output, and the failure comes before any time is spent on tests (`SPECIFICATION.md` R-059).

**Costs** A build mixing test frameworks cannot use a bare `__.test` and must narrow its module selectors.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## Under the Mill plugin, a module's tests run, and pass or fail, as they do without it, except that a run that did not complete always fails.

**Alternatives** Holding only which tests run and their results, and leaving their processes to each feature, is cheaper to build but breaks a build whose tests rely on Mill's fork grouping, parallelism or sandbox working directory.

**Reasoning** A user who adds the plugin expects the output to change and nothing else, save that a run that did not complete is reported as a failure (`SPECIFICATION.md` R-053, R-046).

**Costs** The plugin must reproduce Mill's fork grouping, parallelism and sandbox working directory, rather than choose processes whose output is easier to take, such as one worker for every module. A build whose Native test binary crashes passes under Mill and fails under the plugin (`research/native-stdio-wrapper.md` § 4).

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

---

## `SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime, though meeting them needs Mill API that Mill does not keep stable.

**Alternatives**

- Holding only what Mill's stable API allows survives Mill releases, but that API gives the plugin neither a run's stdout and stderr nor the test task's result after tests fail, so worker output and Mill's list of failed tests reach the console.
- Relaxing R-040, R-042 and R-044 on the runtimes where taking a run's output is hardest leaves that output interleaved on the console, which is the problem this epic addresses.

**Reasoning** Without them the plugin adds a consolidated report beside the console output it was meant to replace.

**Costs** The plugin must run each module's tests itself instead of calling Mill's test task, using Mill API marked `@experimental` and re-implementing logic Mill keeps `@internal`, so it may need a release for each Mill release. On Native it takes a run's stdout and stderr only by putting a launcher between Scala Native's test adapter and the test binary. That route was observed on Windows and Linux only, and the launcher must be compiled where an emulator cannot load a script.

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

**See**

- `research/mill-worker-channels.md` § 2, § 5.
- `research/js-native-channels.md` § 5, § 6.
- `research/native-stdio-wrapper.md` § 3, § 5.

---

## Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output.

**Alternatives**

- Leaving Mill's list of failed tests, its test progress and ScalaTest's end-of-run summary on the console needs no change to Mill's test task, but each speaks for one run and misleads when several runs are active.
- Also turning off Mill's JUnit XML report for each module removes the last per-run result, but that report is a file read by tools, not by the person at the console, and the consolidation writes no JUnit XML to take its place (`SPECIFICATION.md` R-047).

**Reasoning** Progress output and human-readable output report every run of the command at once, so a second per-run account of the same results adds only repetition in another format (`SPECIFICATION.md` R-044).

**Costs** A failed test task's result no longer lists the failed tests (`SPECIFICATION.md` R-053), so a tool reading Mill's failure report sees only that the task failed. Mill's XML report omits failures outside tests, so a tool reading it sees less than the console shows.

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---

## Worker output is captured and shown on its run.

**Alternatives**

- Streaming it to the console as `../structured-test-reporting/SPECIFICATION.md` § Test output does keeps the two epics alike, but it interleaves across runs, which is the problem this epic addresses.
- Writing it to a file for each run keeps the consolidated output to what came over the channel, but separates a crash message from the run it explains.
- Discarding it loses the only account of some runs that did not complete.

**Reasoning** A JVM or native-code message is often the only explanation of a run that did not complete, and the run's node is where a reader looks for it. Capturing everything also means no run prints for itself, so nothing interleaves.

**Costs** Worker output arrives apart from the channel, so its place among the run's events is approximate (`SPECIFICATION.md` R-043). What a reporter the user configured prints appears in the consolidated output as text repeating the events.

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---

## The consolidation produces no JUnit XML.

**Alternatives**

- Consolidated JUnit XML, as `../structured-test-reporting/SPECIFICATION.md` § Multi-run consolidation plugin writes it, keeps the two epics' outputs the same, but loses failures outside tests.
- CTRF (Common Test Report Format) is a more plausible standard for CI readers, but loses the same failures.

**Reasoning** Neither format can hold a failure outside a test, so either would report a run as cleaner than the human-readable and JSON output show it (`SPECIFICATION.md` R-021).

**Costs** CI tools that read only JUnit XML get nothing from the consolidation. The two epics differ by one output, an exception to reuse by default that consolidating many runs does not force.

**Settled by** [Is consolidated JUnit XML in scope?](https://github.com/jamesgorman2/scalatest/issues/34).

---

## This epic's test structure and outputs behave as structured test reporting's by default; each departure is a requirement or decision of this epic.

**Alternatives** Designing the test structure and the JSON, human-readable and progress output afresh frees this epic from choices shaped by reporting inside one run, but the two epics' outcomes would then differ in ways their architectures do not force.

**Reasoning** The two epics are to be compared for ease of implementation, maintenance and appropriateness of outcome. Holding the reported behaviour common leaves one difference to compare: reporting inside each run, or in a plugin reading every run's channel. Of what is reused, only the JSON layout of `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object is designed in depth (`SPECIFICATION.md` R-028).

**Costs** A flaw found in the reused documents, which this plan may not edit, is carried here as a stated difference. Because each JSON node holds its final state, a tool that reads only the JSON cannot recover a payload or the order of events across nodes.

**Settled by** [What does this epic reuse from structured test reporting?](https://github.com/jamesgorman2/scalatest/issues/25).

---
