# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A suite run through `ConfigMapWrapperSuite` has the structure its class registers when constructed with an empty config map.

**Alternatives**

- Constructing the running instance from the run's config map before `SuiteStarting`, and reusing it to run, moves the user's constructor and its side effects before `SuiteStarting`; a throwing constructor, caught and rethrown when the suite runs, still aborts with stack frames that differ from 3.2.19's, risking `specification/compatibility-and-library.md` AC-056 and `../SPECIFICATION.md` R-002.
- Constructing a second instance from the run's config map, only for its structure, runs the user's constructor once more than 3.2.19 does, before `SuiteStarting`.
- A structure of the suite's test names alone, as for a suite with no registration tree, drops a wrapped style's scopes and labels, against `specification/test-event-log-structure.md` AC-103's Rule.

**Reasoning** 3.2.19 already constructs the class with an empty config map before `SuiteStarting`, as `ConfigMapWrapperSuite`'s `wrappedSuite`: `Framework`'s task reads its `suiteId`, `runSuite` its `suiteName` for `SuiteStarting`, and `Runner` its `expectedTestCount` for `RunStarting`. Reading what that instance registers runs no user code earlier than 3.2.19 does, so nothing a reporter receives changes.

**Costs** Where a suite's tests depend on its config map, its structure disagrees with what runs: a test only the run's config map registers has no node, so its events are tagged with the suite (`specification/test-event-log-structure.md` AC-131), and a test only the empty config map registers never starts.

**Settled by** [Which instance supplies a ConfigMapWrapperSuite-wrapped suite's structure, and which class name does it carry?](https://github.com/jamesgorman2/scalatest/issues/70).

---

## A suite run through `ConfigMapWrapperSuite` carries the class name of the class annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`.

**Alternatives**

- Carrying `org.scalatest.ConfigMapWrapperSuite`, as 3.2.19's events do, shows the same class on every wrapped suite's abort line (`specification/layout.md` AC-012).

**Reasoning** The abort line names the class that aborted so a reader can find it, and only the annotated class holds the code that ran.

**Costs** The event log's class name for such a suite differs from the one 3.2.19's reporters receive.

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
