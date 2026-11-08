# Decisions: the test command and running modules

Decisions behind `../SPECIFICATION.md` on which modules the test command runs, how, and when it fails, newest first.

## A test module needs no change to its definition for the test command to run it.

**Alternatives** A plugin trait mixed into each test module, exposing the protected `testTask` and `aheadOfTimeDiscoveredTestClassesIfNeeded`, needs every module definition changed; a selected module without it would be neither run, as `../SPECIFICATION.md` § Scope requires, nor failed by R-059.

**Reasoning** `../SPECIFICATION.md` § Scope and the entry "Only an explicit test command produces consolidated output…" have the test command run any test module its selectors name, so the plugin must reach each module's tests from outside it (R-092). Evaluating the module's public `testForked` or `testOnly` with the plugin's own `TaskCtx` did so on every runtime with no trait. It kept each run's output off the console, gave it to the plugin (R-040, R-042), and matched Mill's own task (R-053).

**Costs** Every route found relies on reflection, on `@internal` API, or on Mill's own test body running correctly under a `TaskCtx` it did not build; a Mill change can break the first and the last at run time, not compile time. On the JVM, meeting R-044 as well needs further Mill internals (`capture-and-console.md`, entry "`../SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime…"); the nested evaluation has run goals the plugin builds only one module at a time, so its fail-fast-off behaviour has been observed only with each module's own `testForked` as goals.

**Settled by**

- [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45).
- [Can the test command hold R-044 on the JVM when the module mixes in no plugin trait?](https://github.com/jamesgorman2/scalatest/issues/48).

**See**

- `../research/mill-test-without-module-trait.md` § 3.4, § 4, § 6.
- `../research/mill-runner-progress-without-trait.md` § 3–5, § 7.

---

## Only an explicit test command produces consolidated output, leaving Mill's test tasks unchanged.

**Alternatives**

- A trait overriding each module's test tasks keeps `mill __.test` working, but each task consolidates only its own module's runs, leaving modules interleaved, and IDE and BSP launches reach the replacement (`../SPECIFICATION.md` R-003).
- Offering both the trait and the command doubles the surface, while only the command consolidates across modules.

**Reasoning** Of the Mill mechanisms examined, only an exclusive command that takes the `Evaluator` has a start, an end and the failure path (`../research/mill-command-lifetime.md` § 2, § 3). Confining the plugin's replacement of a module's test task to that command keeps `../SPECIFICATION.md` R-003 and R-060 without the replacement having to stand aside for other launches.

**Costs** Users run a command other than `mill __.test`, built on evaluator commands that Mill documents as experimental, and get no consolidated output when an earlier goal on the command line fails without `--keep-going` (`../SPECIFICATION.md` R-062). `+` cannot join module selectors, one set of class globs and framework arguments applies to every module (R-055), and nothing covers `testLocal` or `testCached`.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A selected test module whose tests cannot start counts as a failure: shown with the failure that stopped it, failing the test command and triggering `--halt-on-failure`.

**Alternatives**

- Leaving it to Mill's own console error omits it from the report (`../SPECIFICATION.md` R-058).
- Showing it without failing the test command lets a build whose tests never ran look green.

**Reasoning** A module that did not run is a gap in coverage whatever stopped it, so the test command fails (R-046) and the consolidated output shows both that it did not run and why (R-058). Counting it as a failure makes `--halt-on-failure` stop on it as on any other (R-057).

**Costs** One module that fails to compile fails the whole test command, and under `--halt-on-failure` it also skips the modules not yet started.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## Each selected test module's tests start once that module compiles, without waiting for other selected modules to compile.

**Alternatives** Compiling every selected module before any test starts gives an all-or-nothing start, but delays the first test until the slowest compile finishes.

**Reasoning** Mill's test task starts each module's tests on its own compile, so waiting for every compile would make the test command slower than the task it replaces (`../SPECIFICATION.md` R-056).

**Costs** Only one route examined both starts each module's tests on its own compile and runs every selected module without `--keep-going`: a single `execute` call with one goal per selected test module, on an evaluator whose `Execution` has fail-fast off, observed only with each module's own `testForked` as the goals. It reaches Mill internals that carry no compatibility promise, so a Mill change to them breaks it at run time, not compile time (`../research/mill-nested-keep-going.md` § 3, § 5).

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## The test command runs every selected test module by default; `--halt-on-failure` opts into skipping modules not yet started.

**Alternatives**

- Failing fast unless `--keep-going` is given, as Mill does, suits a build; but modules run unordered and in parallel, so which modules report depends on timing.
- Showing only the runs that started, as Mill does, hides that modules were skipped.
- Always running every module, with no opt-in, loses fast feedback on a large build.

**Reasoning** A test report's coverage should not vary with scheduling (`../SPECIFICATION.md` R-056). Where the user trades coverage for speed, the output still shows which modules did not run (R-057, R-058).

**Costs** The default differs from Mill's: `--keep-going` no longer decides which selected modules run. Unless the user opts in, a failing build takes as long as a passing one.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A selected test module that does not test with the release of this fork whose channels the Mill plugin reads fails the test command before any test starts.

**Alternatives**

- Running it as Mill does, showing only what its processes wrote to stdout and stderr and whether it passed, supports mixed builds, but every requirement on test structure, failures and test output would need an exemption for it.
- Skipping it with a warning yields a report that looks complete when it is not.

**Reasoning** Every requirement then holds for every run in the consolidated output, and the failure comes before any time is spent on tests (`../SPECIFICATION.md` R-059).

**Costs** A build mixing test frameworks cannot use a bare `__.test` and must narrow its module selectors.

**Settled by** [Which runs does one set of consolidated output cover, and how does the user start it?](https://github.com/jamesgorman2/scalatest/issues/35).

---

## A run that did not complete fails the test command, even where Mill's test task passes it.

**Alternatives** Passing or failing it as Mill's test task does keeps the two results alike, but lets a build whose tests never finished look green.

**Reasoning** A run that did not complete leaves tests unrun, a gap in coverage whatever ended it (`../SPECIFICATION.md` R-046).

**Costs** A build whose Native test binary crashes passes under Mill's test task and fails under the test command (`../research/native-stdio-wrapper.md` § 4).

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

---

## Under the test command, each selected module's tests run as Mill's `testOnly` or `testForked` would run them.

**Alternatives** Holding only which tests run and their results, and leaving their processes to each feature, is cheaper to build but breaks a build whose tests rely on Mill's fork grouping, parallelism or sandbox working directory.

**Reasoning** A user who runs the test command in place of Mill's test task expects the output to change and nothing else (`../SPECIFICATION.md` R-053).

**Costs** The plugin must take each run's output from the processes Mill's fork grouping and parallelism start, in Mill's sandbox working directory, rather than from processes it chooses because their output is easier to take, such as one worker for every module.

**Settled by** [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).

---
