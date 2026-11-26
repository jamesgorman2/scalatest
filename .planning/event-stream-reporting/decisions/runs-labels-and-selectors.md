# Decisions: the root, runs and modules in the output

Decisions behind `../SPECIFICATION.md` on how the root, runs and modules appear in the consolidated output, which node an error or a discovery runner's output is attached to, and when the human-readable output shows the root or a run, newest first.

---

## The output of a runner that is not a run, such as Mill's discovery runner, is attached to the module the runner ran for (`../SPECIFICATION.md` R-097b).

**Alternatives**

- Attaching that output to the root mixes several modules' discovery output, unlabelled, on one node, and makes the human-readable output show the root (`../SPECIFICATION.md` R-143).
- Leaving a module's output to a later feature keeps discovery output on the root, and no feature in `../ROADMAP.md` is planned to move it.

**Reasoning** A discovery runner runs for one module, so that module is the most specific node its output can be attributed to. A module's output then sits beside the module's failures, where a reader looks for why that module's tests behaved as they did.

**Costs** A module gains an `output` key in the JSON output and output lines in the human-readable output. The test command must capture what Mill's discovery runner writes for each module.

**Settled by** [Many modules in one test command - How a module's error is shown](https://github.com/jamesgorman2/scalatest/issues/241).

---

## An error that no run, suite, scope or test records, and that the test command can attribute to one selected test module, is attached to that module's node, not to the root (`../SPECIFICATION.md` R-072b).

**Alternatives**

- Keeping such errors on the root, each naming its module in its message, leaves a tool parsing messages to learn which module failed.
- Moving only the failures of a module's tests not starting or of its class globs matching no test leaves the module's other failures, such as a worker failing to start, on the root.

**Reasoning** This fork exists so that tools read test output without parsing its text. The tree already names each module, so a module's node is where a reader looks for that module's failures.

**Costs** A reader collecting every failure must walk the modules as well as the root.

**Settled by** [Many modules in one test command - How a module's error is shown](https://github.com/jamesgorman2/scalatest/issues/241).

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

## A module has an outcome only when an error is attached to it (`../SPECIFICATION.md` R-098e).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---
