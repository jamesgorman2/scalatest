# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces, how its human-readable output is coloured, formatted, filtered and collapsed, how it shows a node that ran more than once, and what its JSON output records, newest first. The JSON output's layout has its own decisions, in `../JSON-LAYOUT-DECISIONS.md`.

## The test command writes no JUnit XML report: neither one consolidated report nor Mill's report for each module (`../SPECIFICATION.md` R-047).

**Alternatives**

- Consolidated JUnit XML gives CI tools that read only JUnit XML one report for the whole test command, but loses failures outside tests.
- Keeping Mill's report for each module in the module folder gives CI tools that read only JUnit XML one report per module, but it holds only test-case results, so it loses failures outside tests.
- CTRF (Common Test Report Format) is a more plausible standard for CI readers, but loses failures outside tests.

**Reasoning** No JUnit XML or CTRF report can hold a failure outside a test, so any of them would report a run as cleaner than the human-readable and JSON output show it (`../SPECIFICATION.md` R-106d, R-028).

**Costs** CI tools that read JUnit XML find no report from the test command, at Mill's usual path or in the module folder.

**Settled by**

- [Class globs and framework arguments - Whether the test command writes JUnit XML](https://github.com/jamesgorman2/scalatest/issues/217).
- [Is consolidated JUnit XML in scope?](https://github.com/jamesgorman2/scalatest/issues/34).

---

## The JSON output's format carries no compatibility promise across releases of this fork (`../SPECIFICATION.md` § Scope).

**Alternatives** Semantic versioning of the format, under which a patch release never changes it and a minor release only adds to it, binds every later release to additive change for third-party readers the goal does not name.

**Reasoning** The JSON output's readers are agents that started the test command. Each agent reads the output against the documentation of the release that wrote it, so a change between releases costs it nothing.

**Costs** A tool that parses the JSON output across releases may break on any release.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## The JSON output records the release of this fork that wrote it, and has no format version of its own (`../SPECIFICATION.md` R-084).

**Alternatives** A format version of its own beside the release version records nothing the release version does not, since only a release changes the format.

**Reasoning** The release version tells a reader which release's documentation describes the layout, so a second version adds nothing a reader can act on.

**Costs** A reader that wants to know whether the layout changed between two releases must compare those releases' documentation.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## The test command times the root and each module.

**Alternatives** Leaving the root and modules untimed gives a reader no time for the test command or for a module, though only the test command can observe either.

**Reasoning** No run's events record when the test command or a module's tests start and end. Only the test command observes those times, so only it can give the root and each module a measured time.

**Costs** The root and modules have no times when the consolidation library is called by a caller that gives none.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## A node's duration is measured from that node's own start and end, never derived from its children's (`../SPECIFICATION.md` R-103).

**Alternatives** Falling back to the sum of a node's children's durations overstates the time of a node whose children ran concurrently, such as a module with parallel runs, and gives the node a value nothing measured.

**Reasoning** A duration that nothing measured is a guess a reader cannot tell from a measurement.

**Costs** A node whose start or end was not measured, such as an ignored test, has no duration.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## A suite, scope or test that was executed more than once appears as many times as it was executed, in the structure of its execution (`../SPECIFICATION.md` R-130e).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## The human-readable output shows each execution of a suite, scope or test with what that execution alone produced.

**Alternatives**

- Showing only the last execution hides a failure that a later running of the same test followed with a success (`../SPECIFICATION.md` R-130e).
- One line with a rolled-up outcome gives a node an outcome no event carries, which R-098a–R-098f leave to readers; it is deferred (`../ROADMAP.md` § Deferred).

**Reasoning** ScalaTest 3.2.20 prints every running of a test with its own outcome, failure and messages, so showing each execution keeps that output's content (R-130e).

**Costs** A test run many times prints as many lines.

**Settled by** [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## Where a subtree contains identical aborted messages, the human-readable output prints only the subtree's root (`../SPECIFICATION.md` R-106c).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Each `-o` and `-f` argument takes the formatting character `w` for human-readable and progress output, the formatting character `d` for human-readable output, and the filtering characters `c`, `x`, `e`, `o`, `m` and `r` for human-readable and JSON output (`../SPECIFICATION.md` R-090e, R-106f, R-106g, R-127a, R-127b, R-127c, R-127d, R-138, R-139).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Human-readable output prints in colour unless the `w` formatting character is given (`../SPECIFICATION.md` R-106f).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---
