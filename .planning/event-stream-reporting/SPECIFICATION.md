# Event-stream test reporting for Mill

Scale: epic.

## Summary

Testing a Mill build can start many runs: several modules, parallel runs within a module, and the same suites on several runtimes. Each run prints for itself, so the output interleaves, loses the test structure, and cannot be read as one whole by people or by tools. This epic has each run send what the consolidated output needs over one channel, and adds a Mill plugin whose test command consolidates the channels of every run it started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this document.
- [../structured-test-reporting/SPECIFICATION.md](../structured-test-reporting/SPECIFICATION.md) — the test structure, what each output shows, and how test output is attributed, which this epic takes by default.
- [../structured-test-reporting/runner-json/SPECIFICATION.md](../structured-test-reporting/runner-json/SPECIFICATION.md) — the layout of one run's JSON object, which this epic takes.
- [../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md](../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md) — every key of that object, which this epic takes.
- [research/emitter-and-mill-channel.md](research/emitter-and-mill-channel.md) — what ScalaTest's events carry and lose, how events already leave a process, where a reporter attaches, and what attributing test output needs.
- [research/mill-worker-channels.md](research/mill-worker-channels.md) — on the JVM under Mill: how Mill wires a test worker's stdout and stderr, whether plugin code can take them, what a sandbox file, a socket and stdout can each rely on as a channel, and which APIs let plugin code run inside the test task after tests fail.
- [research/js-native-channels.md](research/js-native-channels.md) — on Scala.js on Node and on Native under Mill: what a socket, a file and the test-interface `Logger` can each rely on as a channel, where a run's stdout and stderr go, and which hooks let plugin code take them.
- [research/native-stdio-wrapper.md](research/native-stdio-wrapper.md) — what was observed on Windows and Linux when plugin code gave Scala Native's test adapter a launcher that redirects the test binary's stdout and stderr to files: what reaches the console and the plugin, the exit status, and behaviour under an emulator.
- [research/js-environment-channels.md](research/js-environment-channels.md) — for each JS environment Mill can select other than Node: whether the test adapter's com channel works, which other transports reach Mill's process, and what console output reaches the run's stdout and stderr.
- [research/mill-command-lifetime.md](research/mill-command-lifetime.md) — which Mill mechanisms run plugin code before the first test task and after the last, how that code learns what ran and how it ended, what Mill does to its console output, and how it sets the exit status.
- [research/emitter-plugin-spike.md](research/emitter-plugin-spike.md) — what was observed on Windows and Linux when a stub socket emitter in each JVM test worker sent events to a minimal exclusive command that executed several modules' test tasks: the command's lifetime and results, live events, a killed worker, worker streams, and what Mill's prompt does to the command's console output.
- [research/mill-nested-keep-going.md](research/mill-nested-keep-going.md) — at Mill `1.1.10`: which routes let the test command's nested evaluation keep going without `--keep-going`, whether each starts a module's tests on its own compile, and how the Mill API each uses is marked.
- [research/mill-parallel-worker-capture.md](research/mill-parallel-worker-capture.md) — what was observed on Windows and Linux when plugin code took each JVM worker's stdout and stderr under Mill's fork groups and `testParallelism`, by two routes (supplying a `TaskCtx`, and driving the test queue itself): what reached the console, what the plugin received, how each route matched Mill's own `testForked`, and the Mill API each relies on.
- [research/emission-gap-analysis.md](research/emission-gap-analysis.md) — for each need of the consolidated output, whether a run's events already provide it on each runtime, lose it when serialised, leave it derivable, or need new code at the point of emission.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and test output over a single channel.
- A consolidation library that takes the following and writes each consolidated output to a file, pipe or writer its caller supplies:
  - many runs' event streams, each with its run's test run label and worker output;
  - each module whose tests did not start, with its test run label and, where a failure stopped them, that failure.
- A Mill plugin that hosts the consolidation library, turns the channel of each run it starts into that run's event stream, and adds the test command, which runs the tests of every test module its module selectors name, with the emitter on.

All three cover runs on the JVM, Scala.js and Native, on the Scala versions `../structured-test-reporting/SPECIFICATION.md` § Scope lists for each. Every requirement below, including those taken from structured test reporting, holds on each runtime; none is best effort on Scala.js or Native.

A channel's format, the consolidation library's API and the switch by which the Mill plugin turns the emitter on carry no compatibility promise across releases of this fork: any release may change them, and no caller outside this fork may rely on them.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- A supported way to turn the emitter on outside Mill.
- Carrying an event's payload, or any other part of an event or throwable that the consolidated output neither shows nor uses.
- Changing any existing ScalaTest reporter.
- A consolidated HTML report; a follow-up epic.
- Consolidated JUnit XML.
- A consolidated CTRF (Common Test Report Format) report; a possible later feature.
- Build tools other than Mill.
- A `--local` option on the test command, running each module as `testLocal` does, in Mill's JVM.
- A `--cached` option on the test command, returning cached test results, each flagged as cached (newer than the last edit) or missing.

## Constraints

### Compatibility with ScalaTest 3.2.x

*Receives what 3.2.19 sends* has the meaning `../structured-test-reporting/runner-json/SPECIFICATION.md` § Constraints gives it.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.19 uses. Each receives what 3.2.19 sends, and the build tool receives the test-interface events 3.2.19 sends.
- R-002 With the emitter on, every existing ScalaTest reporter, and any reporter passed with `-C`, still receives what 3.2.19 sends, except as R-041 states.
- R-051 With the emitter on, the build tool still receives the test-interface events 3.2.19 sends.
- R-003 Test runs launched by Metals, Bloop and IntelliJ, in each IntelliJ mode `../structured-test-reporting/SPECIFICATION.md` § Constraints § IntelliJ lists, behave as they do against ScalaTest 3.2.19, including in a build that uses the Mill plugin.
- R-060 In a build that uses the Mill plugin, Mill's test tasks (`testForked`, `testOnly`, `testCached` and `testLocal`), including when run as `mill __.test`, behave as they do without the plugin.
- R-004 The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19.

## The emitter

- R-010 Each run sends over one channel every event the run reports, the run's test structure and the run's test output.
- R-063 What a run sends does not depend on its reporter configuration.
- R-011 Nothing the consolidated output shows or uses is lost or altered between the run and the channel's reader. This includes failure messages and the order of events within a node.
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

## The test command

- R-054 In a Mill build, only a test command produces consolidated output.
- R-055 The test command's arguments include:
  - one or more module selectors, each a separate argument; `+` cannot join them, because Mill consumes it before the test command runs;
  - optional class globs and framework arguments, as Mill's `testOnly` takes them, applying alike to every selected test module;
  - `--halt-on-failure` (R-057).
- R-053 The test command runs each selected test module's tests as Mill's `testOnly` runs them with the test command's class globs and framework arguments, or as `testForked` runs them when no class globs are given:
  - the same tests, in the processes Mill's fork grouping, test parallelism and test queue start;
  - the same working directory, arguments and environment, plus only what turning the emitter on (with its channel) and turning off the reporter R-041 names require;
  - except that a path inside the task folder (`Task.dest`) of `testOnly` or `testForked` is instead at the same relative path inside a folder the test command owns;
  - except that environment variables Mill's launcher sets from its own command line, such as `MILL_PARAMS` from `mill.bat`, hold the `mill` command line that started the test command.
- R-056 By default, the test command runs the tests of every selected test module, whatever happens to other modules' runs and whether or not Mill's `--keep-going` is given. Tests start without waiting for every selected test module to compile.
- R-061 IF a task Mill must complete before starting the test command fails, THEN the test command does not run and produces no consolidated output. A selected test module's compile and test tasks are not among them; R-046 and R-058 cover their failure.
- R-062 IF another goal on the same `mill` command line fails before the test command starts and Mill's `--keep-going` is not given, THEN the test command does not run and produces no consolidated output.
- R-058 The consolidated output shows each selected test module whose tests did not run as not run. For a module whose tests could not start, it also shows the failure that stopped it.
- R-059 IF a module selector resolves to a test module that does not test with the release of this fork whose channels the Mill plugin reads, THEN the test command fails before any test starts, naming each such module. For example, such a module tests with MUnit, uTest, upstream ScalaTest 3.2.19 or another release of this fork.

WHERE `--halt-on-failure` is given:

- R-057 Once anything has failed the test command (R-046), the test command starts no further selected test module's tests.

## Consolidation

- R-020 For one test command, the Mill plugin produces one set of consolidated output covering every run the test command started: each of progress output, human-readable output and JSON output that is turned on. Each is a separate output. Any combination of them can be on together, progress output with JSON output included, unlike `../structured-test-reporting/SPECIFICATION.md` § Progress output.
- R-026 Progress output is written while the runs execute, from the events received so far. It is not held until a run or the test command completes.
- R-027 The human-readable and JSON output are written together, once every run has completed or has ended without completing.
- R-028 The JSON output is one object for the test command. Its root wraps each run's test structure in the layout that `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object and `../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md` § APIs give one run, key names included, each node holding its final state. Unlike that layout, the JSON output records no event-filter characters and filters no value by them.
- R-021 The human-readable and JSON output show every node, every failure and every piece of test output of every run, except what the layouts this document takes (R-028, R-030) leave out.
- R-022 In the human-readable and JSON output, no node of one run's test structure holds an event or test output of another run.
- R-023 The consolidation library is published and depends on no Mill artifact. Given the same inputs (§ Scope), it produces the same human-readable and JSON output when called without Mill as when the Mill plugin calls it.
- R-052 The consolidation library's caller names the test run label for each event stream.
- R-025 By default, the Mill plugin gives each module one test run label.

## Each run's own output under the test command

These hold for every run a test command starts.

- R-040 Nothing the run writes to stdout or stderr reaches the console except through the consolidated output.
- R-041 The run's events are not printed by the reporter ScalaTest uses when no reporter is configured.
- R-042 The human-readable and JSON output show the run's worker output on the run's node, and drop none of it except as R-045 allows. This differs from `../structured-test-reporting/SPECIFICATION.md` § Test output.
- R-043 Worker output written to stdout keeps its order, as does worker output written to stderr. Its position among the run's events, and the order of stdout relative to stderr, are not guaranteed.
- R-044 Outside the consolidated output, the console does not show Mill's list of failed tests, Mill's own progress for the tests of the run's module, or ScalaTest's end-of-run summary for the run.
- R-045 The plugin may show as worker output Mill's own progress for the tests of the run's module and what Mill's own test runner writes for the run. It does not show Mill's list of failed tests or ScalaTest's end-of-run summary.
- R-046 The test command fails if:
  - any run it started reports a failure or does not complete; or
  - the tests of any selected test module cannot start, for example because it or a module it depends on fails to compile.

  Its failure does not list the failed tests.
- R-047 The test command writes each selected test module's JUnit XML report as Mill's `testOnly` or `testForked`, whichever R-053 names, writes it.

Where the consolidated output goes:

- R-048 Progress output and human-readable output are each written to the console when turned on.
- R-049 JSON output is written to a file, never to the console.
- R-050 Output written by other tasks named on the same `mill` command line, and Mill's own output other than what R-044 and R-045 name, is neither captured nor changed. On the console it may interleave with the consolidated output.
