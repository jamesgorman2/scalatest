# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest end-to-end path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the console.

## Plan

[Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this document are the epic's.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture: the modules this feature starts, their parts (§ Modules), and the boundaries between them.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the feature's non-obvious technical choices.
- [research/jvm-pid-and-start-time.md](research/jvm-pid-and-start-time.md) — which JDKs a Mill 1.x test worker can run on, and which readings of a process's PID and start time match `ProcessHandle`'s.
- [research/mill-plugin-conventions.md](research/mill-plugin-conventions.md) — how Mill 1.x plugins are named, loaded and tested under testkit, and how the Mill internals the plugin drives differ between Mill 1.1.10 and 1.3.1.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- A ScalaTest runner argument on the JVM that turns the emitter on, so the run writes its test event log to a file.
- The consolidation library, producing human-readable output, including each run's block (R-082).
- The Mill plugin's test command. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to the console.
- The Mill plugin is built against Mill 1.3.1, which is its one supported Mill release.

### Out of scope

Each is left to the named feature of `../ROADMAP.md`.

- JSON output (§ 2) and progress output (§ 9).
- More than one worker for a module, and a run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, and the module's JUnit XML report (§ 4).
- More than one module selector, and a module selector that resolves to anything other than one JVM test module: the test command fails on these (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet), and a module that tests with another framework or another release of this fork (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- Scala.js and Native (§ 11–15).

## Epic requirements met

Each holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-060, R-004.
- R-010, R-063, R-011, R-074.
- R-096–R-107 and R-112, for human-readable output.
- R-054; R-055 (one module selector; the argument that turns human-readable output off); R-053 (working directory and environment, for one worker); R-092, R-072.
- R-020, R-027, R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-021, R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-043, R-044, R-045.
- R-046 (first bullet: a failure, aborted, stopped). The test command also fails where the worker dies and where the module tests with another framework or another release of this fork; what it then shows is out of scope.
- R-048 (human-readable output), R-050.
- `../SPECIFICATION.md` § Scope: the JVM's Scala versions, and no compatibility promise on the test event log's format, the consolidation library's API or the switch.

Under Mill, a JVM `Framework` run ends completed unless its process dies. The aborted and stopped parts of R-082 and R-046 are therefore tested by feeding event streams to the consolidation library directly.
