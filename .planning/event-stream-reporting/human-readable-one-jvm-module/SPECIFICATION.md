# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest whole path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the console.

## Plan

[Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. Requirement IDs of the form `R-nnn` are the epic's.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture: the modules this feature starts and the boundaries between them.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the feature's non-obvious technical choices.
- [research/jvm-pid-and-start-time.md](research/jvm-pid-and-start-time.md) — which JDKs a Mill 1.x test worker can run on, and which readings of a process's PID and start time match `ProcessHandle`'s.
- [research/mill-plugin-conventions.md](research/mill-plugin-conventions.md) — how Mill 1.x plugins are named, loaded and tested under testkit, and how the Mill internals the plugin drives differ between Mill 1.1.10 and 1.3.1.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- In ScalaTest core, on the JVM: the structure wrapper, the emitter and the codec. A runner argument turns the emitter on and the default reporter off, and the run writes its test event log to a file.
- The consolidation library, published with no Mill dependency: assembly of one run's test structure, one test run label, and the human-readable writer, including a run's block.
- The Mill plugin, in its own Mill build with integration tests under Mill's testkit. Its test command takes one module selector that resolves to one JVM test module. It runs the module's tests in one worker, reads the run's test event log live, captures the worker's stdout and stderr against the run, and writes the human-readable output to the console once the run's event stream ends.
- The test command fails when the run reports a failure, aborts or stops.

### Out of scope

Each is left to a later feature of the epic (`../ROADMAP.md`).

- JSON output and progress output.
- More than one worker for a module.
- Class globs and framework arguments on the test command, and the module's JUnit XML report.
- More than one module selector, and a module selector that resolves to anything other than one JVM test module: the test command fails on these.
- What the consolidated output shows for a run that does not finish.
- Test output captured against a suite, scope or test.
- Scala.js and Native.
