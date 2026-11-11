# Decisions: a run that does not finish

Decisions behind `../SPECIFICATION.md` on what the consolidated output shows when a run or the test command does not finish, newest first.

## A test class no run reported is a suite node on its test run label, aborted with reason *not run*.

**Alternatives**

- A separate list on the label, outside the suite hierarchy, needs a new JSON shape and ordering rule, and tools that count suites miss its classes.
- Leaving the placement to the feature that implements it leaves to a feature the shape of the JSON output, which carries a compatibility promise (`../SPECIFICATION.md` R-084).

**Reasoning** `../../structured-test-reporting/SPECIFICATION.md` § Test structure already shows a top-level suite that never started as aborted, *not run*, so a reader of either output sees one shape. Classes left in a queue and classes a dead process took are shown alike (`../SPECIFICATION.md` R-095).

**Costs** A suite that names no run record is a new case for readers of the JSON output. Progress output need not show these classes, which `../SPECIFICATION.md` R-068 had required of classes left in a queue.

**Settled by** The user, in conversation on 2026-11-11, under no sub-issue of the plan. The spec review of that day's test manifest amendment found that `../SPECIFICATION.md` R-078 gave a test run label only the top-level suites of its runs as children, so a test class shown as not run had no stated place, order or JSON shape.

---

## A suite's scopes and tests come from the run, one suite at a time as it constructs each, and the test manifest supplies only the test classes no run reported.

**Alternatives**

- Taking the test manifest as the only authority gives class names only, with no scopes, tests or tags.
- Each run sending its whole intended tree before running anything is impossible under Mill's test queue, where no worker knows which suites it will claim.
- Showing nothing for a suite no run constructed leaves no record of the classes a process was to run when it dies before reporting them.

**Reasoning** Only a run knows a suite's scopes and tests, and only once it constructs the suite; the test command knows every test class before any test starts. A test class no run reported is therefore shown by name, as not run (`../SPECIFICATION.md` R-066, R-094). The user must rerun it anyway, so its structure would tell them little.

**Costs** A test class no run reported shows no nested suites, scopes or tests, so a reader cannot tell from the output how many tests were not run. The test command must compile a test manifest to supply those classes (`../ARCHITECTURE-DECISIONS.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…").

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26), which settled that structure comes from the run and showed nothing for a suite no run constructed. The user, in conversation on 2026-11-11 and under no sub-issue of the plan, added the test classes no run reported, after a review flagged that a test process failing before it writes its test event log left no record of the suites it was to run.

**See** `../../structured-test-reporting/research/mill-test-worker-args.md` § 1.2.

---

## An interrupted test command promises nothing beyond its progress output.

**Alternatives**

- A placeholder JSON file written when the test command starts is not needed: structured test reporting needs one only so each run can list its tests for its consolidator, and here the test command is the only consolidator and already knows its runs.
- A JVM shutdown hook writing partial results would tell a user who pressed Ctrl-C only what they already know: they killed the tests.

**Reasoning** After Ctrl-C, Mill gives the test command's body no further control (`../research/mill-command-lifetime.md` § 5.1, § 5.2).

**Costs** A CI timeout that sends SIGTERM gets no partial results.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/mill-command-lifetime.md` § 6.
- `../research/emitter-plugin-spike.md` § 8.3.

---

## Every runner constructed to execute tests is a run, whether or not it is given a task.

**Alternatives**

- Starting a run at its first task drops from the output a worker that dies before its first task.
- Counting every runner constructed while the test command executes tests also counts another module's discovery runner, against `../SPECIFICATION.md` R-072.

**Reasoning** A worker that dies before its first task is still a failure the reader must see (`../SPECIFICATION.md` R-064, R-071), and defining a run by its purpose leaves out every discovery runner, whenever it is constructed.

**Costs** The Mill plugin must keep Mill's JVM discovery runner from opening a test event log by running discovery itself without the runner argument (`../ARCHITECTURE-DECISIONS.md` "The plugin runs a module's test discovery itself…").

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../../structured-test-reporting/research/mill-test-worker-args.md` § 1.4.
- `../research/mill-worker-channels.md` § 3.2.
- `../research/emitter-plugin-spike.md` § 3.

---

## When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died.

**Alternatives** Best effort on death, showing only what the reader received, would on Windows often show the dying test as not run instead of aborted.

**Reasoning** A single worker's death more often comes from a test (`System.exit`, running out of memory, a native crash) than from the harness, and that test is the fact the reader needs, so the output shows it (`../SPECIFICATION.md` R-073, R-075). Bytes still buffered inside the dying process are lost whatever the channel.

**Costs** This narrows every run's test event log, and the capture of every Scala.js VM's stdout and stderr, to routes that keep what their reader has not yet received (`../ARCHITECTURE-DECISIONS.md` "…a run's test event log is one file per run." and "Every Scala.js VM starts through a launcher…").

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/emitter-plugin-spike.md` § 4.
- `../research/mill-worker-channels.md` § 3.

---
