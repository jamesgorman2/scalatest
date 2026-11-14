# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A suite is a node at each place it runs, under the suite or test that ran it.

**Alternatives**

- Assigning an event whose node its suite's structure lacks to the nearest ancestor that has one puts a test's events on its suite, so the test is never shown as a test (`../SPECIFICATION.md` R-021).
- A node for each instance that runs gives a test declared by a wrapper and run by the instance it wraps two nodes, one of which never starts and is shown as not run (R-100).
- One node for every suite sharing a `suiteId` under the innermost enclosing suite merges the helper suites of different tests.

**Reasoning** A node is a place in the test structure, and the times that place ran are its executions. A suite is known by the suite or test that ran it, its suite ID and its place among the siblings there sharing that ID. So an instance a suite runs outside its tests with the suite's own ID is that suite, and a suite that one test runs each time the test runs is one suite.

**Costs** A helper suite that two tests run is shown twice. A suite user code runs on a thread or `ExecutionContext` of its own shows its tests directly under it, merged with any other such suite sharing its ID.

**Settled by**

- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A test is one node in its suite, whichever scope declares it.

**Alternatives** Knowing a test by its scope path as well gives a test declared in a wrapper's flat list and in the wrapped instance's scoped tree two nodes, one of which never starts and is shown as not run (`../SPECIFICATION.md` R-100).

**Reasoning** A test is known by its suite and its test name, which ScalaTest keeps unique within a suite, so it is the same node whichever declaration the latest structure holds.

**Costs** A test's scope comes only from the latest structure holding it, so every execution of the test shows under the scope that structure gives.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A suite run through `ConfigMapWrapperSuite` has the structure its class registers with an empty config map until the instance that runs registers another.

**Alternatives**

- Keeping the empty config map's structure for the whole run leaves a test only the run's config map registers without a node, and shows a test only the empty config map registers as not run.
- Constructing the running instance from the run's config map before `SuiteStarting` moves the user's constructor earlier than 3.2.20: one that throws given that config map then throws before `SuiteStarting`, where 3.2.20 reports `SuiteStarting` and then `SuiteAborted` (`specification/compatibility-and-library.md` AC-056, `../SPECIFICATION.md` R-002).
- Constructing a second instance from the run's config map, only for its structure, runs the user's constructor once more than 3.2.20 does, before `SuiteStarting`.
- A structure of the suite's test names alone, as for a suite with no registration tree, drops a wrapped style's scopes and labels, against `specification/test-event-log-structure.md` AC-103's Rule.

**Reasoning** 3.2.20 already constructs the class with an empty config map before `SuiteStarting`, as `ConfigMapWrapperSuite`'s `wrappedSuite`: `Framework`'s task reads its `suiteId`, `runSuite` its `suiteName` for `SuiteStarting`, and `Runner` its `expectedTestCount` for `RunStarting`. Reading what that instance registers runs no user code earlier than 3.2.20 does, so nothing a reporter receives changes. The instance that runs is built after `SuiteStarting`, as in 3.2.20, and where it registers a different tree a further `structure` replaces the first (`specification/test-event-log-structure.md` AC-131).

**Costs** Where a suite's tests depend on its config map, the event log first holds a structure that disagrees with what runs: a run that dies between the suite's `SuiteStarting` and the further `structure` leaves the empty config map's structure as the one shown.

**Settled by**

- [Which instance supplies a ConfigMapWrapperSuite-wrapped suite's structure, and which class name does it carry?](https://github.com/jamesgorman2/scalatest/issues/70).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## A suite run through `ConfigMapWrapperSuite` carries the class name of the class annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`.

**Alternatives**

- Carrying `org.scalatest.ConfigMapWrapperSuite`, as 3.2.20's events do, shows the same class on every wrapped suite's abort line (`specification/layout-lines.md` AC-012).

**Reasoning** The abort line names the class that aborted so a reader can find it, and only the annotated class holds the code that ran.

**Costs** The event log's class name for such a suite differs from the one 3.2.20's reporters receive.

**Settled by** [Which instance supplies a ConfigMapWrapperSuite-wrapped suite's structure, and which class name does it carry?](https://github.com/jamesgorman2/scalatest/issues/70).

---

## The test command is invoked under the plugin's own `scalatest/` prefix, outside every module the build defines.

**Alternatives**

- A command on a trait or module the build defines needs a build change beyond adding the plugin (`../SPECIFICATION.md` R-092), and puts a `test` command where `mill __.test` reaches it (R-060).
- A `def` alias in `build.mill` adds a line to every build beyond the plugin's, and may put a `test` command where `mill __.test` reaches it (R-060).

**Reasoning** The plugin ships the command on an `ExternalModule`, which nothing in the build names. Mill reaches an external module only through an explicit `<prefix>/` selector, never through a wildcard resolved from the build's root module, so the command can be named `test` while R-060 holds and the build need not mention it.

**Costs** The plugin claims the top-level name `scalatest` on the meta-build's classpath, and a build wanting a shorter name of its own waits for R-126's preset module (`../ROADMAP.md` § 17).

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

**See** `research/mill-plugin-conventions.md` § 3, § 8.3.

---
