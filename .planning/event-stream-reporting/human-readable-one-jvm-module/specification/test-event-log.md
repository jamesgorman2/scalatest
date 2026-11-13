# Consolidated human-readable output for one JVM module: the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM, event by event, and for the ScalaTest behaviour that stays unchanged. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

## Parts

- [test-event-log-structure.md](test-event-log-structure.md) — the run's start and how it ended, each suite's structure, the labels of its scopes and tests, and its class name.
- [test-event-log-executions.md](test-event-log-executions.md) — a suite that runs another instance of itself, a suite run from a test, nested suites run in other ways, and a test run more than once.
- [test-event-log-events.md](test-event-log-events.md) — messages, what the event log keeps of each event, captured output, reporters, and the ScalaTest behaviour that stays unchanged.
