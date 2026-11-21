# Decisions: the root, runs and modules in the output

Decisions behind `../SPECIFICATION.md` on how the root, runs and modules appear in the consolidated output, and when the human-readable output shows the root or a run, newest first.

---

## Human-readable output shows a run only where the run has output attached, the run aborted or stopped, or the run was assigned test classes and ran none of them (`../SPECIFICATION.md` R-144).

**Alternatives**

- Showing every run puts a `Run <n>:` line over each worker's suites, so a module run in parallel reads as a list of workers though which worker ran a suite rarely matters.
- Showing a run whose tests failed moves a suite out of the module's list whenever one of its tests fails, though the failure is the test's and the run did nothing unusual.
- Hiding a run that was assigned test classes and ran none leaves nothing in the output to tie the missing classes to a worker.

**Reasoning** Runs are recorded so that a reader can see when suites may have run in parallel, and human-readable output hides that unless the run itself matters: its own output, how it ended, or work it accepted and did not do. A worker that was started but did no work is not interesting; a worker that accepted work and did none is.

**Costs** The human-readable output does not show which suites shared a worker unless the run is shown; a reader who needs that reads the JSON output.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---

## Human-readable output shows the root only where the root failed or has output attached (`../SPECIFICATION.md` R-143).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## A forked JVM run is a node, and a non-forked run is reported as part of its module (`../SPECIFICATION.md` R-096a, R-135).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## A module has an outcome only when the module has an error (`../SPECIFICATION.md` R-098e).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---
