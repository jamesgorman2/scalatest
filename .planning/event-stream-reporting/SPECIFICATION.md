# Event-stream test reporting for Mill

Scale: epic.

## Summary

One Mill command can start many runs: several test-worker JVMs per module, several modules, and several selectors. Each run prints for itself, so the command's output interleaves, loses the test structure, and cannot be read as one result by people or by tools. This epic has each run send everything it knows over one channel, and has a Mill plugin consolidate the channels of every run the command started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [research/emitter-and-mill-channel.md](research/emitter-and-mill-channel.md) — what ScalaTest's events carry and lose, the existing ways events leave a process, what capturing test output needs, and how Mill launches its test workers.

Written against:

- `.planning/structured-test-reporting/research/mill-test-worker-args.md` — how Mill passes arguments to its test-worker JVMs.
- `.planning/structured-test-reporting/research/js-native-structure-and-capture.md` — what one Scala.js or Scala Native run consists of, the channels out of it, and how test output can be captured on each runtime.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and test output over a single channel.
- A consolidation library that reads the channels of many runs and produces their consolidated output. It does not depend on Mill.
- A Mill plugin that runs a module's or a build's tests with the emitter on, and hosts the consolidation library.

A *Mill command* is one execution of Mill from the command line, whatever number of modules, selectors and test-worker JVMs it covers.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- Changing any existing ScalaTest reporter.
- A consolidated HTML report; a follow-up epic.
- Build tools other than Mill.

## Constraints

### Compatibility with ScalaTest 3.2.x

- R-001 A run that does not use the Mill plugin behaves as ScalaTest 3.2.x does: the same reporters, the same output and the same test-interface events.
- R-002 Every existing ScalaTest reporter, and any reporter passed with `-C`, receives the same events with the emitter on as with it off.
- R-003 IDE and BSP test runs behave as they do against ScalaTest 3.2.x.

## The emitter

- R-010 Each run sends over one channel: every event the run reports, the test structure those events belong to, and the run's test output.
- R-011 Nothing a run reports is lost or altered between the run and the channel's reader. This includes failure messages, throwables with their cause chains, and the order of events within a node.
- R-012 Each piece of test output is attributed to the node that was running when it was written, and keeps its position among that node's events.
- R-013 A reader can tell apart the channels of different runs started by one Mill command.

## Consolidation

- R-020 For one Mill command, the Mill plugin produces one set of output covering every run the command started:
  - progress output while the runs execute;
  - human-readable results;
  - JSON results.
- R-021 The consolidated results hold everything every run sent; no event, node or test output is dropped.
- R-022 Output from different runs is never interleaved within a node's results.
- R-023 The consolidation library produces the same results from the same channels when used without Mill.
- R-024 The consolidated results hold what a consolidated HTML report needs, so that report can be added without changing the emitter.
