# Decisions: every worker of a JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## In the human-readable output's list of the suites of runs it does not show, suites that share a name are ordered by the numbers of the runs they ran in, as this feature's reading of `../SPECIFICATION.md` R-105's "execution order".

**Alternatives**

- Ordering suites that share a name by start time makes their order depend on scheduling between workers, which differs between runs of the same build.

**Reasoning** R-105 does not say what "execution order" means for siblings that ran in different workers at once. Run numbers are fixed by fork group and worker index, so ordering by run number gives the same order on every run of the build.

**Costs** A suite that started later can be listed before one that started earlier.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---

## The JSON output keeps each run's suites in that run's node.

**Alternatives**

- Merging the runs in the JSON output, as the human-readable output merges the runs it does not show, loses which suites shared a worker, which a script or HTML view needs to show the module as it executed.

**Reasoning** The JSON output is read by scripts, which can flatten runs away to compare results by suite but cannot recover runs that were never written.

**Costs** The two outputs nest a module's suites differently.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---

## The human-readable output merges the suites of every run it does not show into one list under the module.

**Alternatives**

- Listing each hidden run's suites in turn, in run order, splits the module's suites into chunks that depend on which worker claimed each class, so a suite's place changes between runs of the same build.

**Reasoning** The human-readable output is read by people, who need to see a run only where the run has output attached, the run aborted or stopped, or the run ran none of its assigned test classes. Elsewhere the module reads as one list of suites, in an order that does not depend on which worker ran each suite.

**Costs** The human-readable output does not say which suites of a hidden run shared a worker.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---
