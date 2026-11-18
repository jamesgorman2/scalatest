# Consolidated human-readable output for one JVM module: runs and test structure

This document indexes the acceptance criteria for what the human-readable output shows of a run: which suites, scopes, tests, failures and captured output appear, how often and in what order, a run's own line and output, and the root. It is part of [../SPECIFICATION.md](../SPECIFICATION.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## Parts

- [runs-and-test-structure-nodes.md](runs-and-test-structure-nodes.md) — the order of suites, scopes and tests, outcomes, nodes that share a name, filtered tests, and failures.
- [runs-and-test-structure-executions.md](runs-and-test-structure-executions.md) — a suite, scope or test that ran more than once.
- [runs-and-test-structure-structures.md](runs-and-test-structure-structures.md) — a suite that registers its scopes and tests more than once, and a registered test that ran at another place.
- [runs-and-test-structure-runs.md](runs-and-test-structure-runs.md) — a run's line, the output captured against the run, runs that abort or stop, and the root's line.
