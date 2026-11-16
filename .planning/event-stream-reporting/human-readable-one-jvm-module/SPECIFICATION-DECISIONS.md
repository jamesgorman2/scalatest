# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The criteria use the test event log as a fixture, and assert what the emitter writes to it.

**Alternatives** Expressing every criterion through the test command cannot produce a torn test event log, concurrent events and similar edges, and does not pin the interface that the emitter and the consolidation library are built against.

**Reasoning** The test event log is the internal API between the emitter and the consolidation library. No caller outside this fork may rely on it, but within one release of this fork the consolidation library reads every test event log that the release's emitter writes (`../SPECIFICATION.md` § Scope). Criteria that feed the library an event log, and criteria that assert what the emitter writes, therefore test each side of that API on its own.

**Costs** A change to the events a test event log carries changes the criteria on both sides of it, even where the test command's output does not change.

**Settled by** [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54).

---

## A suite is a node at each place it runs, under the suite or test that ran it.

**Alternatives**

- Assigning an event whose node its suite's structure lacks to the nearest ancestor that has one puts a test's events on its suite, so the test is never shown as a test (`../SPECIFICATION.md` R-021).
- A node for each instance that runs gives a test declared by a wrapper and run by the instance it wraps two nodes, one of which never starts and is shown as not run (R-100).
- One node for every suite sharing a `suiteId` under the innermost enclosing suite merges the helper suites of different tests.

**Reasoning** A node is a place in the test structure, and the times that place ran are its executions. A suite is known by the suite or test that ran it, by its suite ID, and by its place among the siblings under that suite or test that share its suite ID. A suite instance that a suite runs outside its tests, with the running suite's own suite ID, is therefore the running suite. A suite that one test runs each time the test runs is therefore one suite.

**Costs** A helper suite that two tests run is shown twice. A suite that user code runs on a thread or `ExecutionContext` of its own shows its tests directly under that suite, without the scopes that contain them. Such a suite is merged with any other such suite that shares its suite ID.

**Settled by**

- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A test is one node in its suite, whichever scope declares it.

**Alternatives** Knowing a test by its scope path as well gives a test declared in a wrapper's flat list and in the wrapped instance's scoped tree two nodes, one of which never starts and is shown as not run (`../SPECIFICATION.md` R-100).

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
