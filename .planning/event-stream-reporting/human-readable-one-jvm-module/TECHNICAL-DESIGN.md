# Consolidated human-readable output for one JVM module: technical design

## Summary

The emitter lives in scalatest-core, which is compiled for Java 8 and cross-built for four Scala versions, yet must give the Mill plugin values that match what Mill's JVM reads from `ProcessHandle`.

## Critical code paths

### The run's PID and process start time

Item 1 of the test event log carries the run's PID and process start time (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin). The plugin treats the process as exited unless that start time equals `ProcessHandle.of(pid).info().startInstant()` in Mill's JVM, so the two must be equal, not close.

- The emitter reads both from `ProcessHandle.current()` by reflection, because core's Java 8 class files cannot name `ProcessHandle`.
- It looks each method up on the interfaces `java.lang.ProcessHandle` and `java.lang.ProcessHandle.Info`, never on the handle's runtime class, whose methods are not accessible (`research/jvm-pid-and-start-time.md` § 2).
- `RuntimeMXBean.getStartTime()` is not a fallback: it records when the JVM finished initialising and never matched (`research/jvm-pid-and-start-time.md` § 3).
- On a JVM with no `ProcessHandle`, a runner given the runner argument fails its construction. Mill 1.x cannot start a test worker there (`research/jvm-pid-and-start-time.md` § 1), so no run under the test command reaches this.
