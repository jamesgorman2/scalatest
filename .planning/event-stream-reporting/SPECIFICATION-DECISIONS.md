# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, newest first.

## In consolidated output a run is not a node: its own state is a run record on its test run label, named by each of its suites.

**Alternatives**

- A run node between each label and its suites, as in the single-run layout, splits one module's suites across as many nodes as Mill's test queue started workers, a split that changes from one test command to the next.
- Inlining the run's state on every suite duplicates it and leaves no place for a run with no suites (`SPECIFICATION.md` R-071).
- Rolling the runs' state up onto their label loses which worker died and which process wrote which worker output.

**Reasoning** The hierarchy then describes what the user configured, modules and their suites, while how the tests were scheduled is held in properties, as a suite's thread name already is (`SPECIFICATION.md` R-078, R-080). Each suite naming its run record keeps which worker ran it.

**Costs** The consolidated JSON departs from the single-run layout above the suites, so a tool reading both handles two layouts, and listing one run's suites means filtering its label's suites by run record.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

---

## Human-readable output shows each run's own output and abnormal end on its test run label, without naming the run.

**Alternatives**

- Leaving runs to the JSON output lets a test command fail with no visible cause, as when a worker given no task dies.
- Showing only how a run ended abnormally drops worker output, often the only explanation of that end, from the human-readable output.

**Reasoning** A reader at the console needs every cause of failure and the message that explains it, but which worker ran which suites is scheduling they did not choose (`SPECIFICATION.md` R-082). One block for each run keeps each run's stdout and stderr in order (R-043).

**Costs** The human-readable output cannot tell a reader which suites shared a process with a crash; only the JSON output can.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

---

## A test run label whose module's tests did not run is aborted, with reason *not run* where it was skipped and *could not start* where a failure stopped it.

**Alternatives**

- One reason for both leaves the reader to infer from the presence of a failure whether the module was skipped or broken.
- A status of the label's own, beside the outcome every other node uses, adds a second way to say how a node ended.

**Reasoning** The two call for different remedies: a skipped module needs a rerun, one that could not start needs a fix (`SPECIFICATION.md` R-058). Aborted with a reason is how the test structure already shows a node that never ran.

**Costs** A label carries an outcome even though it is not rolled up from its runs (`SPECIFICATION.md` R-083), so a label whose runs all failed shows none.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

---

## Module selectors are recorded at the JSON output's root, not as nodes.

**Alternatives** A node for each module selector, above its modules' labels, duplicates every module that two selectors name, such as `__.test` and `foo.test`, or places it under one by an arbitrary tie-break, and adds a level to every path.

**Reasoning** Selectors overlap, while each test module runs once, so recording each selector with the modules it resolved to keeps the hierarchy free of duplicates and still lets a tool see what was asked for (`SPECIFICATION.md` R-081).

**Costs** The human-readable output does not show which selector named a module.

**Settled by** [How do modules, module selectors and runs appear in the consolidated output?](https://github.com/jamesgorman2/scalatest/issues/29).

---

## A run's expected tests come from the run, one suite at a time as it constructs each, not from the Mill plugin's list of classes.

**Alternatives**

- The plugin's list of classes as the authority gives class names only, with no scopes, tests or tags, and on the JVM lists `@DoNotDiscover` suites that ScalaTest's `Framework` drops unless named explicitly, so they would show falsely as not run.
- Each run sending its whole intended tree before running anything is impossible under Mill's test queue, where no worker knows which suites it will claim.

**Reasoning** Losing the rest of a module needs every worker of its fork group to die. That points at the harness rather than a test, and the user must rerun the module anyway. The test command still fails (`SPECIFICATION.md` R-046), and the test classes left in the group's queue are still shown (R-068).

**Costs** A suite a dead worker was given but never constructed does not appear (`SPECIFICATION.md` R-066), so a reader cannot tell from the output how much of a module is missing beyond the classes left in its queue.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See** `../structured-test-reporting/research/mill-test-worker-args.md` § 1.2.

---

## An interrupted test command promises nothing beyond its progress output.

**Alternatives**

- A placeholder JSON file written when the test command starts exists in structured test reporting solely so each ScalaTest invocation can list its tests for its consolidator. The test command is the only consolidator and already knows its runs.
- A JVM shutdown hook writing partial results would tell a user who pressed Ctrl-C only what they already know: they killed the tests.

**Reasoning** A user who interrupts the tests knows they killed them (`SPECIFICATION.md` R-069, R-046). After Ctrl-C, Mill gives the test command's body no further control (`research/mill-command-lifetime.md` § 5.1, § 5.2).

**Costs** A CI timeout that sends SIGTERM gets no partial results.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `research/mill-command-lifetime.md` § 6.
- `research/emitter-plugin-spike.md` § 8.3.

---

## Every runner constructed to execute tests is a run, whether or not it is given a task.

**Alternatives**

- A run beginning at its first task loses a worker that dies before its first task from the output.
- Every runner constructed while the test command executes tests also takes in another module's discovery runner (`SPECIFICATION.md` R-056), which never calls `done()` and so would fail every test command (R-046, R-072).

**Reasoning** A worker that dies before its first task is still a failure the reader must see (`SPECIFICATION.md` R-064, R-071), and defining a run by its purpose leaves out every discovery runner, whenever it is constructed.

**Costs** The plugin must tell the discovery runner's channel from a worker's, which is left to the architecture.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../structured-test-reporting/research/mill-test-worker-args.md` § 1.4.
- `research/mill-worker-channels.md` § 3.2.
- `research/emitter-plugin-spike.md` § 3.

---

## When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died.

**Alternatives** Best effort on death, showing only what the reader received, would on Windows often show the dying test, usually the cause, as not run instead of aborted.

**Reasoning** A single worker's death more often comes from a test (`System.exit`, running out of memory, a native crash) than from the harness, and that test is the fact the reader needs, so the output shows it (`SPECIFICATION.md` R-073, R-075). Bytes still buffered inside the dying process are lost whatever the channel.

**Costs** The bound narrows the channel choice before the architecture is written. A plain socket on Windows lost unread bytes on `Connection reset`, so it qualifies only if its reader can be shown never to lag, which must be re-confirmed when the channel is chosen.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `research/emitter-plugin-spike.md` § 4.
- `research/mill-worker-channels.md` § 3.

---

## The consolidation library's API carries no compatibility promise across releases and is not for callers outside this fork.

**Alternatives**

- A public API with a compatibility promise, checked by MiMa as `SPECIFICATION.md` R-004 checks the fork, serves callers the goal does not name.
- A documented API marked experimental invites outside callers but gives them no more to rely on.

**Reasoning** sbt and command-line front ends are out of scope and this epic gives no supported way to produce a channel outside Mill, so the only caller is this fork's own Mill plugin, shipped in the same release. Any later front end in this fork ships likewise. The epic then adds no public programmatic API; tools read only the JSON output, as in structured test reporting (`../structured-test-reporting/SPECIFICATION.md` § Scope § Out of scope).

**Costs** A third party that builds on the library, such as another build tool's plugin, may break on any release of the fork.

**Settled by** [Is the consolidation library's event-stream input a public API, and under what compatibility promise?](https://github.com/jamesgorman2/scalatest/issues/46).

---

## The consolidation library takes each run's event stream, not its channel.

**Alternatives** Reading channels, live or as recorded files, ties the library to the transport, and recording a channel outside Mill needs a supported way to turn the emitter on there: new public surface on `Runner` and the `Framework`, bound by `SPECIFICATION.md` R-001 and R-004.

**Reasoning** Separating decoding from consolidation lets any front end in this fork, live under Mill or reading files, feed the same library, so the library never knows whether a channel was live or recorded.

**Costs** Every front end must turn channels into event streams. This epic gives no way outside Mill to produce a channel, so `SPECIFICATION.md` R-023 is exercised only by feeding event streams directly.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## A channel carries what the consolidated output shows or uses, not the whole event.

**Alternatives**

- Carrying the payload as its `toString` sends unbounded text that no output reads.
- Carrying the whole event, with the payload kept where serialisable, needs a rule for each runtime because Scala.js and Native have no general serialisation, and nothing reads the payload.

**Reasoning** The emitter then makes no judgement at runtime about what can be sent (`SPECIFICATION.md` R-011, R-063). Because the channel's format is private, a later epic that needs the payload can add it without breaking a reader.

**Costs** A later output that needs more of an event, such as its payload or a custom exception's fields, needs a change to the emitter in ScalaTest itself, not only to the consolidation library.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## A channel is read only by the release of this fork that wrote it.

**Alternatives** A private format versioned across releases lets one plugin read test modules on several releases of the fork, but each reader must keep decoding every older format, which the goal does not need.

**Reasoning** The emitter and its reader ship in the same release, so the format can change in any release without any reader decoding more than one.

**Costs**

- A test module on another release of the fork fails the test command (`SPECIFICATION.md` R-059).
- The Mill plugin needs a release for each pairing of fork release and Mill release that it supports.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## The channel's format is private to this fork.

**Alternatives** A documented format that third parties may read is a second public contract beside the JSON output (`SPECIFICATION.md` R-028), needing its own specification and compatibility policy, and the goal names no reader for it.

**Reasoning** Tools already have a documented account of every run in the JSON output, so the channel need serve only this fork's own reader.

**Costs** No consumer other than this fork's own can read a channel.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## Only an explicit test command produces consolidated output, leaving Mill's test tasks unchanged.

**Alternatives**

- A trait overriding each module's test tasks keeps `mill __.test` working, but each task consolidates only its own module's runs, leaving modules interleaved, and IDE and BSP launches reach the replacement (`SPECIFICATION.md` R-003).
- Offering both the trait and the command doubles the surface, while only the command consolidates across modules.

**Reasoning** Of the Mill mechanisms examined, only an exclusive command that takes the `Evaluator` has a start, an end and the failure path (`research/mill-command-lifetime.md` § 2, § 3). Confining the plugin's replacement of a module's test task to that command keeps `SPECIFICATION.md` R-003 and R-060 without the replacement having to stand aside for other launches.

**Costs**

- Users run a command other than `mill __.test`, built on evaluator commands that Mill documents as experimental.
- No consolidated output appears when an earlier goal on the command line fails without `--keep-going` (`SPECIFICATION.md` R-062).
- `+` cannot join module selectors, and one set of class globs and framework arguments applies to every module (`SPECIFICATION.md` R-055).
- Nothing covers `testLocal` or `testCached`.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A selected test module whose tests cannot start is a failed run: shown with the failure that stopped it, failing the test command and triggering `--halt-on-failure`.

**Alternatives**

- Leaving it to Mill's own console error omits it from the report (`SPECIFICATION.md` R-058).
- Showing it without failing the test command lets a build whose tests never ran look green.

**Reasoning** A module that did not run is a gap in coverage whatever stopped it, so the test command fails (R-046) and the consolidated output shows both that it did not run and why (R-058). Counting it as a failure makes `--halt-on-failure` stop on it as on any other (R-057).

**Costs** One module that fails to compile fails the whole test command, and under `--halt-on-failure` it also skips the modules not yet started.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## Each selected test module's tests start once that module compiles, without waiting for other selected modules to compile.

**Alternatives** Compiling every selected module before any test starts gives an all-or-nothing start, but delays the first test until the slowest compile finishes.

**Reasoning** Mill's test task starts each module's tests on its own compile, so waiting for every compile would make the test command slower than the task it replaces (`SPECIFICATION.md` R-056).

**Costs** Of the routes examined, only one both starts each module's tests on its own compile and runs every selected module without `--keep-going`. It reaches Mill internals that carry no compatibility promise, so a Mill change to them breaks it at run time, not compile time (`research/mill-nested-keep-going.md` § 3, § 5).

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## The test command runs every selected test module by default; `--halt-on-failure` opts into skipping modules not yet started.

**Alternatives**

- Failing fast unless `--keep-going` is given, as Mill does, suits a build; but modules run unordered and in parallel, so which modules report depends on timing.
- Showing only the runs that started, as Mill does, hides that modules were skipped.
- Always running every module, with no opt-in, loses fast feedback on a large build.

**Reasoning** A test report's coverage should not vary with scheduling (`SPECIFICATION.md` R-056). Where the user trades coverage for speed, the output still shows which modules did not run (R-057, R-058).

**Costs** The default differs from Mill's: `--keep-going` no longer decides which selected modules run. Unless the user opts in, a failing build takes as long as a passing one.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A selected test module that does not test with the release of this fork whose channels the Mill plugin reads fails the test command before any test starts.

**Alternatives**

- Running it as Mill does, showing only its worker output and whether it passed, supports mixed builds, but every requirement on test structure, failures and test output would need an exemption for it.
- Skipping it with a warning yields a report that looks complete when it is not.

**Reasoning** Every requirement then holds for every run in the consolidated output, and the failure comes before any time is spent on tests (`SPECIFICATION.md` R-059).

**Costs** A build mixing test frameworks cannot use a bare `__.test` and must narrow its module selectors.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A run that did not complete fails the test command, even where Mill's test task passes it.

**Alternatives** Passing or failing it as Mill's test task does keeps the two results alike, but lets a build whose tests never finished look green.

**Reasoning** A run that did not complete leaves tests unrun, a gap in coverage whatever ended it (`SPECIFICATION.md` R-046).

**Costs** A build whose Native test binary crashes passes under Mill's test task and fails under the test command (`research/native-stdio-wrapper.md` § 4).

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

---

## Under the test command, each selected module's tests run as Mill's `testOnly` or `testForked` would run them.

**Alternatives** Holding only which tests run and their results, and leaving their processes to each feature, is cheaper to build but breaks a build whose tests rely on Mill's fork grouping, parallelism or sandbox working directory.

**Reasoning** A user who runs the test command in place of Mill's test task expects the output to change and nothing else (`SPECIFICATION.md` R-053).

**Costs** The plugin must reproduce Mill's fork grouping, parallelism and sandbox working directory, rather than choose processes whose output is easier to take, such as one worker for every module.

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

---

## `SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime, though meeting them needs Mill API that Mill does not keep stable.

**Alternatives**

- Holding only what Mill's stable API allows survives Mill releases, but its one route into a JVM worker's streams, a shim `testFramework`, takes only writes made through `System.out` and `System.err`, so JVM warnings, native code and child processes still write to the console.
- Relaxing R-040, R-042 and R-044 on the runtimes where taking a run's output is hardest leaves that output interleaved on the console, which is the problem this epic addresses.

**Reasoning** Without them the plugin adds a consolidated report beside the console output it was meant to replace.

**Costs**

- The plugin must run each module's tests through Mill API marked `@experimental` and `@internal` instead of calling Mill's test task, so it may need a release for each Mill release.
- On Native, the launcher must be compiled where an emulator cannot load a script.
- Taking a JVM parallel worker's streams and a Native run's stdout and stderr have each been observed only on Windows and Linux. The JVM's have never been observed with class globs, with test parallelism off, with a killed worker or in a command running several modules.

**Settled by**

- [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).
- [Can the plugin take a JVM worker's stdout and stderr when Mill runs a module's tests in parallel workers?](https://github.com/jamesgorman2/scalatest/issues/42).

**See**

- `research/mill-worker-channels.md` § 2, § 5.
- `research/emitter-plugin-spike.md` § 6.
- `research/mill-parallel-worker-capture.md` § 3–6, § 8.
- `research/js-native-channels.md` § 5, § 6.
- `research/native-stdio-wrapper.md` § 3, § 5.

---

## Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output.

**Alternatives**

- Leaving Mill's list of failed tests, its test progress and ScalaTest's end-of-run summary on the console needs no change to Mill's test task, but each speaks for one run and misleads when several runs are active.
- Also turning off Mill's JUnit XML report for each module removes the last per-run result, but that report is a file read by tools, not by the person at the console, and the consolidation writes no JUnit XML to take its place (`SPECIFICATION.md` R-047).

**Reasoning** Progress output and human-readable output report every run of the command at once, so a second per-run account of the same results adds only repetition in another format (`SPECIFICATION.md` R-044).

**Costs** The test command's result does not list the failed tests (`SPECIFICATION.md` R-046), so a tool reading Mill's failure report sees only that the test command failed. Mill's XML report omits failures outside tests, so a tool reading it sees less than the console shows.

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---

## Worker output is captured and shown with its run's results.

**Alternatives**

- Streaming it to the console as `../structured-test-reporting/SPECIFICATION.md` § Test output does keeps the two epics alike, but it interleaves across runs, which is the problem this epic addresses.
- Writing it to a file for each run keeps the consolidated output to what came over the channel, but separates a crash message from the run it explains.
- Discarding it loses the only account of some runs that did not complete.

**Reasoning** A JVM or native-code message is often the only explanation of a run that did not complete, and the run's results are where a reader looks for it. Capturing everything also means no run prints for itself, so nothing interleaves.

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

**Reasoning** The two epics are to be compared for ease of implementation, maintenance and appropriateness of outcome. Holding the reported behaviour common leaves one difference to compare: reporting inside each run, or in a plugin reading every run's channel. Of what is reused, only the JSON layout, key names included, is designed in depth, so this epic takes it whole for suites and every node below them (`SPECIFICATION.md` R-028).

**Costs** A flaw found in the reused documents, which this plan may not edit, is carried here as a stated difference. Because each JSON node holds its final state, a tool that reads only the JSON cannot recover the order of events across nodes.

**Settled by** [What does this epic reuse from structured test reporting?](https://github.com/jamesgorman2/scalatest/issues/25).

---
