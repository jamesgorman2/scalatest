# Architecture decisions: event-stream test reporting for Mill

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), split by topic, each part newest first.

## Parts

- [decisions/architecture-library-and-modules.md](decisions/architecture-library-and-modules.md) — what the consolidation library takes, builds on and decides, and how the fork's artifacts are built and published.
- [decisions/architecture-test-structure.md](decisions/architecture-test-structure.md) — how each event's place in the test structure reaches the emitter, and where the test structure is assembled.
- [decisions/architecture-test-event-log.md](decisions/architecture-test-event-log.md) — the test event log's format and codec, what carries it on each runtime, how it is turned on and how its end is known.
- [decisions/architecture-running-tests.md](decisions/architecture-running-tests.md) — how the test command runs each module's tests under Mill, which processes it starts them through, and how it knows the test classes each took.
