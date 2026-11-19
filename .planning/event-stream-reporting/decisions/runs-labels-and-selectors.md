# Decisions: runs and modules in the output

Decisions behind `../SPECIFICATION.md` on when the human-readable output shows a run, newest first.

---

## Human-readable output shows a run only where the run has output attached, aborted or stopped, or was assigned test classes and ran none of them (`../SPECIFICATION.md` R-144).

**Alternatives**

- Showing every run puts a `Run <n>:` line over each worker's suites, so a module run in parallel reads as a list of workers though which worker ran a suite rarely matters.
- Showing a run whose tests failed moves a suite out of the module's list whenever one of its tests fails, though the failure is the test's and the run did nothing unusual.
- Hiding a run that was assigned test classes and ran none leaves nothing in the output to tie the missing classes to a worker.

**Reasoning** Runs are recorded so that a reader can see when suites may have run in parallel, and human-readable output hides that unless the run itself matters: its own output, how it ended, or work it accepted and did not do. A worker that was started but did no work is not interesting; one that accepted work and did none is.

**Costs** The human-readable output does not show which suites shared a worker unless the run is shown; a reader who needs that reads the JSON output.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---
