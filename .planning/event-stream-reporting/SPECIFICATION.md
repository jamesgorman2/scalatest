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
- [research/emitter-and-mill-channel.md](research/emitter-and-mill-channel.md) — what ScalaTest's events carry and lose, how events already leave a process, where a reporter attaches, and what attributing test output needs.
- [research/mill-worker-channels.md](research/mill-worker-channels.md) — on the JVM under Mill: how Mill wires a test worker's stdout and stderr, whether plugin code can take them, what a sandbox file, a socket and stdout can each rely on as a channel, and which APIs let plugin code run inside the test task after tests fail.
- [research/js-native-channels.md](research/js-native-channels.md) — on Scala.js on Node and on Native under Mill: what a socket, a file and the test-interface `Logger` can each rely on as a channel, where a run's stdout and stderr go, and which hooks let plugin code take them.
- [research/js-environment-channels.md](research/js-environment-channels.md) — for each JS environment Mill can select other than Node: whether the test adapter's com channel works, which other transports reach Mill's process, and what console output reaches the run's stdout and stderr.
- [research/mill-command-lifetime.md](research/mill-command-lifetime.md) — which Mill mechanisms run plugin code before the first test task and after the last, how that code learns what ran and how it ended, what Mill does to its console output, and how it sets the exit status.
- [research/emission-gap-analysis.md](research/emission-gap-analysis.md) — for each need of the consolidated output, whether a run's events already provide it on each runtime, lose it when serialised, leave it derivable, or need new code at the point of emission.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and test output over a single channel.
- A consolidation library that reads the channels of many runs, takes each run's test run label and worker output, and produces their consolidated output.
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
- A consolidated CTRF (Common Test Report Format) report; a possible later feature.
- Build tools other than Mill.

## Constraints

### Compatibility with ScalaTest 3.2.x

*Receives what 3.2.19 sends* has the meaning `../structured-test-reporting/runner-json/SPECIFICATION.md` § Constraints gives it.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.19 uses. Each receives what 3.2.19 sends, and the build tool receives the test-interface events 3.2.19 sends.
- R-002 With the emitter on, every existing ScalaTest reporter, and any reporter passed with `-C`, still receives what 3.2.19 sends, except as R-041 states.
- R-051 With the emitter on, the build tool still receives the test-interface events 3.2.19 sends.
- R-003 Test runs launched by Metals, Bloop and IntelliJ, in the forked, sbt shell and BSP modes of `../structured-test-reporting/SPECIFICATION.md` § Constraints § IntelliJ, behave as they do against ScalaTest 3.2.19, including in a build that uses the Mill plugin.
- R-004 The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19.

## The emitter

- R-010 Each run sends over one channel every event the run reports, the run's test structure and the run's test output.
- R-011 Nothing a run reports is lost or altered between the run and the channel's reader. This includes failure messages, throwables with their cause chains, and the order of events within a node.
- R-012 Each piece of test output is attributed to a node as `../structured-test-reporting/SPECIFICATION.md` § Test output states, and keeps its position among that node's events.
- R-013 A channel carries one run.

## Behaviour taken from structured test reporting

- R-030 Where this document is silent, the test structure and what the human-readable, progress and JSON output show are as `../structured-test-reporting/SPECIFICATION.md` § Test structure, § Human-readable output, § Progress output and § JSON output state. Where the two differ, this document holds.
- R-031 No statement in `../structured-test-reporting/SPECIFICATION.md` or `../structured-test-reporting/runner-json/SPECIFICATION.md` about a reporter, a reporter's flags, or where and when a single run writes its output applies to this epic, unless a requirement here cites it. This excludes, among others:
  - what `-C` and other existing reporters receive;
  - event-filter characters;
  - which reporters progress output is written to;
  - test output streamed as it is received;
  - a block or file written at the end of each run.

## Consolidation

- R-020 For one Mill command, the Mill plugin produces one set of consolidated output covering every run the command started: each of progress output, human-readable output and JSON output that is turned on. Each is a separate output.
- R-026 Progress output is written while the runs execute, from the events received so far. It is not held until a run or the command completes.
- R-027 The human-readable and JSON output are written together, once every run has completed or has ended without completing.
- R-028 The JSON output is one object for the Mill command. Its root wraps each run's test structure in the layout `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object gives one run, each node holding its final state.
- R-021 The human-readable and JSON output show every node, every failure and every piece of test output of every run, except what the layouts this document takes (R-028, R-030) leave out.
- R-022 In the human-readable and JSON output, no node of one run's test structure holds an event or test output of another run.
- R-023 Given the same channels, test run labels and worker output, the consolidation library produces the same human-readable and JSON output when used without Mill.
- R-052 The consolidation library's caller names the test run label each channel belongs to.
- R-025 By default, the Mill plugin gives each module one test run label.

## How tests run under the Mill plugin

- R-053 Where the Mill plugin runs the tests of a test task that a selector names, they run as that task runs them without the plugin:
  - the same tests, in the same processes, with the same working directory;
  - with the same arguments and environment, plus only what turning the emitter on with its channel, and turning off the reporter R-041 names, require;
  - with a result that succeeds or fails as the task's does and holds the same test results, but does not carry Mill's list of failed tests (R-044).

## Each run's own output under the Mill plugin

These hold for every run the Mill plugin starts.

- R-040 Nothing the run writes to stdout or stderr reaches the console except through the consolidated output.
- R-041 The run's events are not printed by the reporter ScalaTest uses when no reporter is configured.
- R-042 The human-readable and JSON output show the run's worker output on the run's node, and drop none of it except as R-045 allows. This differs from `../structured-test-reporting/SPECIFICATION.md` § Test output.
- R-043 Worker output written to stdout keeps its order, as does worker output written to stderr. Its position among the run's events, and the order of stdout relative to stderr, are not guaranteed.
- R-044 Outside the consolidated output, the console does not show Mill's list of failed tests, Mill's own progress for the test task, or ScalaTest's end-of-run summary for the run.
- R-045 The plugin may show as worker output Mill's own progress for the test task and what Mill's own test runner writes for the run. It does not show Mill's list of failed tests or ScalaTest's end-of-run summary.
- R-046 The Mill command fails if any run it started reports a failure or does not complete.
- R-047 Mill writes its JUnit XML report for each module as it does without the plugin.

Where the consolidated output goes:

- R-048 Progress output and human-readable output are each written to the console when turned on.
- R-049 JSON output is written to a file, never to the console.
- R-050 Output written by the Mill command's other tasks, and Mill's own output other than what R-044 and R-045 name, is neither captured nor changed. On the console it may interleave with the consolidated output.
