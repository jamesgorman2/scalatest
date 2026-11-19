# Decisions: every worker of a JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The human-readable output merges the suites of every run it does not show into one list under the module, while the JSON output keeps each run's suites in that run's node.

In the merged list, suites are ordered by name, and suites that share a name are ordered by the numbers of the runs they ran in. This is the reading this feature takes of `../SPECIFICATION.md` R-105's "execution order" for siblings from different runs.

**Alternatives**

- Listing each hidden run's suites in turn, in run order, splits the module's suites into chunks that depend on which worker claimed each class, so a suite's place changes between runs of the same build.
- Merging the runs in the JSON output too loses which suites shared a worker, which a script or HTML view needs to show the module as it executed.
- Ordering suites that share a name by start time makes their order depend on scheduling between workers, which differs between runs of the same build.

**Reasoning** Runs are recorded so that a reader can see when suites may have run in parallel. The human-readable output is read by people, who need that only where a run matters, so it reads as one module of suites. The JSON output is read by scripts, which can flatten runs away to compare results by suite but cannot recover runs that were never written. Run numbers are fixed by fork group and worker index, so ordering same-name suites by run number gives the same order on every run of the build.

**Costs** The human-readable output does not say which suites of a hidden run shared a worker, and the two outputs nest a module's suites differently.

**Settled by** [Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159).

---
