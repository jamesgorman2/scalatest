# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest end-to-end path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the test command's stdout.

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
- The Mill plugin's test command. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to its stdout.
- The test command's output arguments `-oH` and a bare `-o` (R-048). With no `-o`, stdout holds human-readable output only.
- The test command fails before any test starts, producing no consolidated output, where:
  - an `-o` holds an output letter other than `H`;
  - an `-o` holds any lowercase character;
  - an `-f` is given;
  - the module's own ScalaTest arguments hold one whose flag ScalaTest's parser does not recognise. The failure names that argument.
- The test command fails where:
  - it is given more than one module selector, or one that resolves to anything other than one JVM test module;
  - the worker dies (R-046 first bullet, "does not finish");
  - the module tests with another framework or another release of this fork (R-059).
- The Mill plugin is built against Mill 1.3.1, which is its one supported Mill release.

### Out of scope

Each is left to the named feature of `../ROADMAP.md`.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters (R-127, R-128), and `-f` for human-readable output (R-049) (§ 16).
- A module that presets a test command's arguments (R-126) (§ 17).
- More than one worker for a module, and a run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, and the module's JUnit XML report (§ 4).
- Running more than one module selector, or a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet), and what the test command shows for a module that tests with another framework or another release of this fork (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- Scala.js and Native (§ 11–15).

## Epic requirements met

Each holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-060, R-004.
- R-010, R-063, R-011, R-074.
- R-096, R-097, R-098, R-099, R-100, R-103, R-104, R-105, R-106 (with no formatting or filtering character), R-107 and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout holds human-readable output); R-053 (second and third bullets, for one worker), R-125; R-092, R-072.
- R-020 (human-readable output), R-027, R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-101, R-021, R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046 (first bullet: a failure, aborted, stopped).
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.
- `../SPECIFICATION.md` § Scope: the JVM's Scala versions, and no compatibility promise on the test event log's format, the consolidation library's API or the switch.

## Acceptance criteria

```gherkin
Feature: A run's block

  Rule: Mill's per-class lines give a run no block (R-129, R-082)
    Scenario: AC-001 A silent run at Mill's default test log level shows no block
      Given a JVM test module at Mill's default `testLogLevel`, whose tests and suites write nothing to stdout or stderr and send no message outside a suite, and whose run completes
      When the test command runs the module
      Then the human-readable output shows no block for the run
      And no line of the human-readable output reads "Running Test Class " followed by one of the module's test class names
```
