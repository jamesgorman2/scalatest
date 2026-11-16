# Consolidated human-readable output for one JVM module: the test event log

## Summary

This document indexes the acceptance criteria for what a run on the JVM writes to its test event log, event by event, when given the ScalaTest runner argument that turns the emitter on, and for the ScalaTest behaviour that stays unchanged. It is part of [../SPECIFICATION.md](../SPECIFICATION.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Parts

- [test-event-log-structure.md](test-event-log-structure.md) — the run's start and how it ended, each suite's structure, the labels of its scopes and tests, and its class name.
- [test-event-log-executions.md](test-event-log-executions.md) — a suite that runs another instance of itself, a suite run from a test, nested suites run in other ways, and a test run more than once.
- [test-event-log-events.md](test-event-log-events.md) — messages, what the event log keeps of each event, captured output, reporters, and the ScalaTest behaviour that stays unchanged.
