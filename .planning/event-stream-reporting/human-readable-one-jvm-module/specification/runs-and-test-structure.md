# Consolidated human-readable output for one JVM module: runs and test structure

Acceptance criteria for what the human-readable output shows of a run: which suites, scopes, tests, failures and captured output appear, how often and in what order, and the run's block. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them; [layout.md](layout.md) gives where each line sits and its text and colour.

## Parts

- [runs-and-test-structure-nodes.md](runs-and-test-structure-nodes.md) — the order of suites, scopes and tests, outcomes, nodes that share a name, filtered tests, and failures.
- [runs-and-test-structure-executions.md](runs-and-test-structure-executions.md) — a suite, scope or test that ran more than once.
- [runs-and-test-structure-structures.md](runs-and-test-structure-structures.md) — a suite whose structure the event log holds more than once.
- [runs-and-test-structure-runs.md](runs-and-test-structure-runs.md) — a run's block, the output captured against the run, and runs that abort or stop.
