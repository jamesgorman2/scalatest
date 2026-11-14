# Consolidated human-readable output for one JVM module: technical design

Its decisions are in [TECHNICAL-DESIGN-DECISIONS.md](TECHNICAL-DESIGN-DECISIONS.md).

## Summary

- The emitter, in a scalatest-core that compiles on JDK 8 without `java.lang.ProcessHandle`, must give the Mill plugin:
  - the PID and start time Mill's JVM reads from `ProcessHandle`;
  - each label before any test runs, though no event carries the labels;
  - each test's events assigned to its node, though core and user code replace the reporter a test is given;
  - the scopes and tests of an instance that runs, though the instance first read may register others and user code runs instances core never sees;
  - each execution of a node told from its others, though no event says which run of its suite or call of `runTest` sent it;
  - each test's messages in the order the test called them, though no event carries that order.
- The test command, reached as `mill scalatest/test` with nothing in the build referring to it, must:
  - split a module's own ScalaTest arguments as ScalaTest's JVM runner parses them;
  - turn a worker's stdout and stderr byte chunks into lines;
  - read Mill's colour decision through `private[mill]` API;
  - take its result from library state that another thread changes, not from what Mill's test-runner subprocess call returns.
- The human-readable writer must reuse `StringReporter`'s formatting without changing what `StringReporter` prints, though in ScalaTest 3.2.20 that formatting reads a live `Throwable` the decoded event stream cannot rebuild and returns lines already indented and coloured.
- Every artifact must move to group `com.edc` while MiMa checks each one upstream also publishes against upstream's. Upstream's build sets the check on few of those projects and names the right artifact on two.

## Parts

- [technical-design/test-command.md](technical-design/test-command.md) — how the test command is reached, splits a module's arguments, captures a worker's output, takes its result and writes to stdout.
- [technical-design/test-event-log.md](technical-design/test-event-log.md) — how the emitter reads the run's PID and start time, which nodes and executions it gives each event, and the event log's format.
- [technical-design/consolidation-library.md](technical-design/consolidation-library.md) — how assembly places messages and executions in the test structure, and how the human-readable writer reuses `StringReporter`'s formatting.
- [technical-design/testing.md](technical-design/testing.md) — how the criteria's runs are built and compared, what Mill's console output tests can assert, and how the fork is compared with ScalaTest 3.2.20.
- [technical-design/publishing.md](technical-design/publishing.md) — how every artifact is built and published under `com.edc`, and how MiMa checks them against ScalaTest 3.2.20.
