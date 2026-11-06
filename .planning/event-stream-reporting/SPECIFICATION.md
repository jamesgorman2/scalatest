# Event-stream test reporting for Mill

Scale: epic.

## Summary

One Mill command can start many runs: several selectors, several modules, parallel runs within a module, and the same suites on several runtimes. Each run prints for itself, so the command's output interleaves, loses the test structure, and cannot be read as one whole by people or by tools. This epic has each run send everything it knows over one channel, and has a Mill plugin consolidate the channels of every run the command started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this document.
- [../structured-test-reporting/SPECIFICATION.md](../structured-test-reporting/SPECIFICATION.md) — the test structure, what each output shows, and how test output is attributed, which this epic takes by default.
- [../structured-test-reporting/runner-json/SPECIFICATION.md](../structured-test-reporting/runner-json/SPECIFICATION.md) — the layout of one run's JSON object, which this epic takes.
- [research/emitter-and-mill-channel.md](research/emitter-and-mill-channel.md) — what ScalaTest's events carry and lose, how events already leave a process, where a reporter attaches, what attributing test output needs, and what is known about a channel out of a Mill test-worker JVM.
- [research/emission-gap-analysis.md](research/emission-gap-analysis.md) — for each need of the consolidated output, whether a run's events already provide it on each runtime, lose it when serialised, leave it derivable, or need new code at the point of emission.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and test output over a single channel.
- A consolidation library that reads the channels of many runs and produces their consolidated output.
- A Mill plugin that runs a module's or a build's tests with the emitter on, and hosts the consolidation library.

All three cover runs on the JVM, Scala.js and Native, on the Scala versions `../structured-test-reporting/SPECIFICATION.md` § Scope lists for each. Every requirement below, including those taken from structured test reporting, holds on each runtime; none is best effort on Scala.js or Native.

Terms this document adds:

- A *Mill command* is one execution of Mill from the command line, whatever number of selectors, modules, runtimes and runs it covers.
- A *selector* is the part of a Mill command that names test tasks to run; one command may carry several.
- A *channel* is the one ordered stream a run's emitter writes and the consolidation library reads.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- Changing any existing ScalaTest reporter.
- A consolidated HTML report; a follow-up epic.
- Consolidated JUnit XML.
- Build tools other than Mill.

## Constraints

### Compatibility with ScalaTest 3.2.x

*Receives what 3.2.19 sends* has the meaning `../structured-test-reporting/runner-json/SPECIFICATION.md` § Constraints gives it.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.19 uses, each receives what 3.2.19 sends, and the build tool receives the test-interface events 3.2.19 sends.
- R-002 With the emitter on, every existing ScalaTest reporter, and any reporter passed with `-C`, still receives what 3.2.19 sends.
- R-003 Test runs launched by Metals, Bloop and IntelliJ, in the forked, sbt shell and BSP modes of `../structured-test-reporting/SPECIFICATION.md` § Constraints § IntelliJ, behave as they do against ScalaTest 3.2.19, including in a build that uses the Mill plugin.
- R-004 The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19.

## The emitter

- R-010 Each run sends over one channel every event the run reports, the run's test structure and the run's test output.
- R-011 Nothing a run reports is lost or altered between the run and the channel's reader. This includes failure messages, throwables with their cause chains, and the order of events within a node.
- R-012 Each piece of test output is attributed to a node as `../structured-test-reporting/SPECIFICATION.md` § Test output states, and keeps its position among that node's events.
- R-013 A channel carries one run, and the consolidation library's caller names the test run label it belongs to.

## Behaviour taken from structured test reporting

- R-030 Where this document is silent, the test structure and what the human-readable, progress and JSON output show are as `../structured-test-reporting/SPECIFICATION.md` § Test structure, § Human-readable output, § Progress output and § JSON output state. Where the two differ, this document holds.
- R-031 No statement in `../structured-test-reporting/SPECIFICATION.md` or `../structured-test-reporting/runner-json/SPECIFICATION.md` about a reporter, a reporter's flags, or where and when a single run writes its output applies to this epic, unless a requirement here cites it. This excludes, among others, what `-C` and other existing reporters receive, event-filter characters, the reporters progress output is written to, test output streamed as it is received, and a block or file written at the end of each run.

## Consolidation

- R-020 For one Mill command, the Mill plugin produces one set of consolidated output covering every run the command started: progress output, human-readable output and JSON output. Each is a separate output.
- R-026 Progress output is written while the runs execute, from the events received so far.
- R-027 The human-readable and JSON output are written together, once every run has completed or halted.
- R-028 The JSON output is one object for the Mill command. Its root wraps each run's test structure in the layout `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object gives one run, each node holding its final state.
- R-021 Each output receives everything every run sent; no event, node or test output is dropped.
- R-022 In the human-readable and JSON output, no node of one run's test structure holds an event or test output of another run.
- R-023 Given the same channels, the consolidation library produces the same human-readable and JSON output when used without Mill.
- R-025 By default, the Mill plugin gives each module one test run label.
