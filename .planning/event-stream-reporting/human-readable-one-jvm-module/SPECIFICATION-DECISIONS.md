# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A test that a `structure` event marks ignored, and that has no execution, is ignored, not *not run*.

**Alternatives**

- Reporting such a test as *not run*, as `../SPECIFICATION.md` R-095a reads literally, shows a red `*** NOT RUN ***` line for a test that would not have run had its suite run. The summary line would then count as ignored a test whose line says not run, or count ignored tests of suites that never started in the tests expected.
- Taking a test's ignored state from `TestIgnored` alone leaves the tests expected unknown until each suite has run: the count falls as each `TestIgnored` arrives, and never falls for a suite that does not start.

**Reasoning** ScalaTest's filter says which tests a suite ignores before the suite runs, so the `structure` event can record that outcome with the test (`specification/test-event-log-structure.md` AC-190 to AC-193). `../SPECIFICATION.md` R-098a gives every test a recorded outcome and R-046a does not fail an ignored test. R-095a is read as covering the tests whose outcome nothing records. The summary line can then exclude every ignored test from the tests run and the tests expected whether or not the test's suite started, and each count agrees with the lines above it (`specification/layout-lines.md` AC-194, AC-195).

**Costs** A suite that completes without sending `TestIgnored` for a test its `structure` marks ignored, as one whose `runTests` override skips the test, shows the test as ignored and does not fail the test command. The mark is the filter's answer when the structure was read: where a suite's `tags` change afterwards, the mark is wrong until an event for the test arrives.

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## A suite, scope or test aborted with reason *not run* fails the test command, even where its run ends completed.

**Alternatives** Failing on *not run* only where the node's run did not complete needs an exception for completed runs that `../SPECIFICATION.md` does not state, and a test that silently did not run would not fail the test command.

**Reasoning** `../SPECIFICATION.md` R-095a reports a registered suite, scope or test with no events as aborted, R-046c fails every node that does not complete, and R-137 returns a failure where any node has failed. None of the three distinguishes the reason for an abort or how the node's run ended, so every aborted node fails, and the consolidation state agrees with each `*** NOT RUN ***` line the human-readable output shows. An attributed node has no outcome, so it fails nothing.

**Costs** A build that passes on ScalaTest 3.2.20 fails the test command where a suite registers a test that the instance that runs does not run. A suite run through `ConfigMapWrapperSuite` whose first registration holds a test the second drops is one such suite (`specification/test-command.md` AC-182).

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## A registered test with no execution is not *not run* where a test with the same suite ID and the same test name ran anywhere in the same event log.

**Alternatives** Attributing by suite ID alone, so that no registered test of a suite ID is *not run* once any test of that suite ID ran, hides a test skipped inside a suite that otherwise ran, as when the suite aborts part-way, which defeats `../SPECIFICATION.md` R-095a.

**Reasoning** `../SPECIFICATION.md` R-130f exempts a registered test that can be attributed to an executed test, without saying what attributes one to the other. Sibling suites sharing a suite ID take their places in the order they start (`specification/test-event-log-executions.md` AC-168), so a test can run at a place other than the one that registered it, and without the exemption each place shows the other's test as not run. Suite ID with test name is the narrowest key that still matches a test across places. ScalaTest keeps a test name unique within a suite, so the key never attributes a test to another test of its own suite.

**Costs**

- A suite registered at two places and run at only one shows nothing for the other place, so that place's not-run tests are hidden (`specification/runs-and-test-structure-structures.md` AC-178).
- A test that only a first registration contained, and whose test name never ran, is still shown as not run, and so fails the test command, as for a suite run through `ConfigMapWrapperSuite` whose tests depend on its config map (`specification/runs-and-test-structure-structures.md` AC-146).

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## A run's line is `Run <n>:`, numbered by the order the module's runs started, and the root's line is the fixed text `Consolidated tests:`.

**Alternatives** Naming the run by its process, as `Run (pid 12345):`, gives a line whose text differs on every run, so tests must mask the line and the outputs of two runs cannot be compared.

**Reasoning** `../SPECIFICATION.md` R-082 shows a run and the root only where the node failed or has output attached, and R-106a prints each node's name, but the epic names neither node. A number taken from start order and a fixed text are the same every time the same runs are consolidated, so a criterion can give each line exactly. The root's line needs nothing from the caller, so the library prints the same line with and without Mill (R-113).

**Costs** `Run 1:` does not say which worker process the run was. Where the root's line appears, every other line apart from the summary line gains a level of indentation.

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## The summary line has its full format in this feature, ahead of progress output, which may redefine it.

**Alternatives** A minimal placeholder line until progress output is designed (`../ROADMAP.md` § 9) tells a reader less than the full line, which is useful now.

**Reasoning** `../SPECIFICATION.md` R-141 ends the human-readable output with the last progress output, whether or not progress output is enabled, so this feature must print a line before any progress output exists. The line gives the counts R-090a names, in ScalaTest's vocabulary, each count in the colour of a line of its kind (`specification/layout-lines.md` § The summary line). The format is this feature's choice for now: § 9 is free to redefine it, and the summary line then follows § 9's.

**Costs**

- This feature's output and tests change if § 9 redefines the line. The line is long for a narrow console.
- An ignored test counts in `ignored` alone, in neither the tests run nor the tests expected, so the tests expected is not the number of tests the suites register.

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## The criteria use the test event log as a fixture, and assert what the emitter writes to it.

**Alternatives** Expressing every criterion through the test command cannot produce a torn test event log, concurrent events and similar edges, and does not pin the interface that the emitter and the consolidation library are built against.

**Reasoning** The test event log is the internal API between the emitter and the consolidation library. No caller outside this fork may rely on it, but within one release of this fork the consolidation library reads every test event log that the release's emitter writes (`../SPECIFICATION.md` § Scope). Criteria that feed the library an event log, and criteria that assert what the emitter writes, therefore test each side of that API on its own.

**Costs** A change to the events a test event log carries changes the criteria on both sides of it, even where the test command's output does not change.

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## A suite is a node at each place it runs, under the suite or test that ran it.

**Alternatives**

- Assigning an event whose node its suite's structure lacks to the nearest ancestor that has one puts a test's events on its suite, so the test is never shown as a test (`../SPECIFICATION.md` R-130c).
- A node for each instance that runs gives a test declared by a wrapper and run by the instance it wraps two nodes, one of which never starts and has nothing to show, though it is not *not run* (R-130f).
- One node for every suite sharing a `suiteId` under the innermost enclosing suite merges the helper suites of different tests.

**Reasoning** A node is a place in the test structure, and the times that place ran are its executions. A suite is known by the suite or test that ran it, by its suite ID, and by its place among the siblings under that suite or test that share its suite ID. A suite instance that a suite runs outside its tests, with the running suite's own suite ID, is therefore the running suite. A suite that one test runs each time the test runs is therefore one suite.

**Costs** A helper suite that two tests run is shown twice. A suite that user code runs on a thread or `ExecutionContext` of its own shows its tests directly under that suite, without the scopes that contain them. Such a suite is merged with any other such suite that shares its suite ID.

**Settled by**

- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A test is one node in its suite, whichever scope declares it.

**Alternatives** Knowing a test by its scope path as well gives a test declared in a wrapper's flat list and in the wrapped instance's scoped tree two nodes, one of which never starts and has nothing to show, though it is not *not run* (`../SPECIFICATION.md` R-130f).

**Reasoning** A test is known by its suite and its test name, which ScalaTest keeps unique within a suite, so it is the same node whichever declaration the latest structure contains.

**Costs** A test's scope comes only from the latest structure containing it, so every execution of the test shows under the scope that structure gives.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A suite run through `ConfigMapWrapperSuite` carries the class name of the class annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`.

**Alternatives** Carrying `org.scalatest.ConfigMapWrapperSuite`, as 3.2.20's events do, shows the same class on every wrapped suite's abort line (`specification/layout-lines.md` AC-012).

**Reasoning** The abort line names the class that aborted so a reader can find it, and only the annotated class contains the code that ran.

**Costs** The event log's class name for such a suite differs from the one 3.2.20's reporters receive.

**Settled by** [Which instance supplies a ConfigMapWrapperSuite-wrapped suite's structure, and which class name does it carry?](https://github.com/jamesgorman2/scalatest/issues/70).

---

## The test command is invoked under the plugin's own `scalatest/` prefix, outside every module the build defines.

**Alternatives**

- A command on a trait or module the build defines needs a build change beyond adding the plugin (`../SPECIFICATION.md` R-092), and puts a `test` command where `mill __.test` reaches it (R-060).
- A `def` alias in `build.mill` adds a line to every build beyond the plugin's, and may put a `test` command where `mill __.test` reaches it (R-060).

**Reasoning** The plugin ships the command on an `ExternalModule`, which nothing in the build names. Mill reaches an external module only through an explicit `<prefix>/` selector, never through a wildcard resolved from the build's root module, so the command can be named `test` without breaking R-060, and the build need not mention it.

**Costs** The plugin claims the top-level name `scalatest` on the meta-build's classpath, and a build wanting a shorter name of its own waits for R-126's preset module (`../ROADMAP.md` § 17).

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

**See** `research/mill-plugin-conventions.md` § 3, § 8.3.

---
