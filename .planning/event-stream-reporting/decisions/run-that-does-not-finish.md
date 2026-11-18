# Decisions: a run that does not finish

Decisions behind `../SPECIFICATION.md` on what the consolidated output shows when a run or the test command does not finish, newest first.

## A suite scheduled to run, or a suite, scope or test recorded in a structure event, that has no subsequent test events is aborted with reason *not run* (`../SPECIFICATION.md` R-095a).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## A suite's scopes and tests come from the run, one suite at a time as it constructs each, and the test manifest supplies only the test classes no run reported.

**Alternatives**

- Taking the test manifest as the only authority gives class names only, with no scopes, tests or tags.
- Each run sending its whole intended tree before running anything is impossible under Mill's test queue, where no worker knows which suites it will claim.
- Showing nothing for a suite no run constructed leaves no record of the classes a process was to run when it dies before reporting them.

**Reasoning** Only a run knows a suite's scopes and tests, and only once it constructs the suite; the test command knows every test class before any test starts. A test class no run reported is therefore shown by name, as not run (`../SPECIFICATION.md` R-095a). The user must rerun it anyway, so its structure would tell them little.

**Costs** A test class no run reported shows no nested suites, scopes or tests, so a reader cannot tell from the output how many tests were not run. The test command must compile a test manifest to supply those classes (`architecture-running-tests.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…").

**Settled by**

- [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).
- The user, on 2026-11-11, under no sub-issue of the plan.

**See** `../research/mill-test-worker-args.md` § 1.2.

---

## The test command has no special behaviour when interrupted (`../SPECIFICATION.md` R-069).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Every runner constructed to execute tests is a run, whether or not it is given a task.

**Alternatives**

- Starting a run at its first task drops from the output a worker that dies before its first task.
- Counting every runner constructed while the test command executes tests also counts another module's discovery runner, against `../SPECIFICATION.md` R-072.

**Reasoning** A worker that dies before its first task is still a failure the reader must see (`../SPECIFICATION.md` R-102), and defining a run by its purpose leaves out every discovery runner, whenever it is constructed.

**Costs** The Mill plugin must keep Mill's JVM discovery runner from opening a test event log by running discovery itself without the runner argument (`architecture-running-tests.md` "The plugin runs a module's test discovery itself…").

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/mill-test-worker-args.md` § 1.4.
- `../research/mill-worker-channels.md` § 3.2.
- `../research/emitter-plugin-spike.md` § 3.

---

## When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died.

**Alternatives** Best effort on death, showing only what the reader received, would on Windows often show the dying test as not run instead of aborted.

**Reasoning** A single worker's death more often comes from a test (`System.exit`, running out of memory, a native crash) than from the harness, and that test is the fact the reader needs, so the output shows it (`../SPECIFICATION.md` R-073). Bytes still buffered inside the dying process are lost whatever the channel.

**Costs** This narrows every run's test event log, and the capture of every Scala.js VM's stdout and stderr, to routes that keep what their reader has not yet received (`architecture-test-event-log.md` "…a run's test event log is one file per run." and `architecture-running-tests.md` "Every Scala.js VM starts through a launcher…").

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/emitter-plugin-spike.md` § 4.
- `../research/mill-worker-channels.md` § 3.

---
