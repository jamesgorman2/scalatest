# Decisions: a run that does not finish

Decisions behind `../SPECIFICATION.md` on what the consolidated output shows when a run or the test command does not finish, newest first.

## A run's expected tests come from the run, one suite at a time as it constructs each, not from the Mill plugin's list of classes.

**Alternatives**

- The plugin's list of classes as the authority gives class names only, with no scopes, tests or tags, and on the JVM lists `@DoNotDiscover` suites that ScalaTest's `Framework` drops unless named explicitly, so they would show falsely as not run.
- Each run sending its whole intended tree before running anything is impossible under Mill's test queue, where no worker knows which suites it will claim.

**Reasoning** Losing the rest of a module needs every worker of its fork group to die. That points at the harness rather than a test, and the user must rerun the module anyway. The test command still fails (`../SPECIFICATION.md` R-046), and the test classes left in the group's queue are still shown (R-068).

**Costs** A suite a dead worker was given but never constructed does not appear (`../SPECIFICATION.md` R-066), so a reader cannot tell from the output how much of a module is missing beyond the classes left in its queue.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See** `../../structured-test-reporting/research/mill-test-worker-args.md` § 1.2.

---

## An interrupted test command promises nothing beyond its progress output.

**Alternatives**

- A placeholder JSON file written when the test command starts, which structured test reporting needs only so each ScalaTest invocation can list its tests for its consolidator, is not needed where the test command is the only consolidator and already knows its runs.
- A JVM shutdown hook writing partial results would tell a user who pressed Ctrl-C only what they already know: they killed the tests.

**Reasoning** After Ctrl-C, Mill gives the test command's body no further control (`../SPECIFICATION.md` R-069; `../research/mill-command-lifetime.md` § 5.1, § 5.2).

**Costs** A CI timeout that sends SIGTERM gets no partial results.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/mill-command-lifetime.md` § 6.
- `../research/emitter-plugin-spike.md` § 8.3.

---

## Every runner constructed to execute tests is a run, whether or not it is given a task.

**Alternatives**

- A run beginning at its first task loses a worker that dies before its first task from the output.
- Every runner constructed while the test command executes tests also takes in another module's discovery runner (`../SPECIFICATION.md` R-056), which never calls `done()` and so would fail every test command (R-046, R-072).

**Reasoning** A worker that dies before its first task is still a failure the reader must see (`../SPECIFICATION.md` R-064, R-071), and defining a run by its purpose leaves out every discovery runner, whenever it is constructed.

**Costs** The Mill plugin must tell the discovery runner's channel from a run's (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../../structured-test-reporting/research/mill-test-worker-args.md` § 1.4.
- `../research/mill-worker-channels.md` § 3.2.
- `../research/emitter-plugin-spike.md` § 3.

---

## When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died.

**Alternatives** Best effort on death, showing only what the reader received, would on Windows often show the dying test, usually the cause, as not run instead of aborted.

**Reasoning** A single worker's death more often comes from a test (`System.exit`, running out of memory, a native crash) than from the harness, and that test is the fact the reader needs, so the output shows it (`../SPECIFICATION.md` R-073, R-075). Bytes still buffered inside the dying process are lost whatever the channel.

**Costs** The bound narrows the channel choice: a plain socket on Windows lost unread bytes on `Connection reset`, so it qualifies only if its reader can be shown never to lag.

**Settled by** [What does the consolidated output show when a run does not finish?](https://github.com/jamesgorman2/scalatest/issues/26).

**See**

- `../research/emitter-plugin-spike.md` § 4.
- `../research/mill-worker-channels.md` § 3.

---
