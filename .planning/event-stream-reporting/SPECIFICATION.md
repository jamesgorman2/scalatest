# Event-stream test reporting for Mill

Scale: epic.

## Summary

Testing a Mill build can start many runs: several modules, parallel runs within a module, and the same suites on several runtimes. Each run prints for itself, so the output interleaves, loses the test structure, and cannot be read as one whole by people or by tools. This epic has each run send what the consolidated output needs over one channel, and adds a Mill plugin whose test command consolidates the channels of every run it started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this document, indexed by topic.
- [../structured-test-reporting/SPECIFICATION.md](../structured-test-reporting/SPECIFICATION.md) — the test structure and what each output shows, which this epic takes by default.
- [../structured-test-reporting/runner-json/SPECIFICATION.md](../structured-test-reporting/runner-json/SPECIFICATION.md) — the layout of one run's JSON object, which this epic takes.
- [../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md](../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md) — every key of that object, which this epic takes.
- [research/emitter-and-mill-channel.md](research/emitter-and-mill-channel.md) — what ScalaTest's events carry and lose, how events already leave a process, where a reporter attaches, and what attributing test output needs.
- [research/mill-worker-channels.md](research/mill-worker-channels.md) — on the JVM under Mill: how Mill wires a test worker's stdout and stderr, whether plugin code can take them, what a sandbox file, a socket and stdout can each rely on as a channel, and which APIs run plugin code in the test task after tests fail.
- [research/js-native-channels.md](research/js-native-channels.md) — on Scala.js on Node and on Native under Mill: what a socket, a file and the test-interface `Logger` can each rely on as a channel, where a run's stdout and stderr go, and which hooks let plugin code take them.
- [research/native-stdio-wrapper.md](research/native-stdio-wrapper.md) — what was observed on Windows and Linux when plugin code gave Scala Native's test adapter a launcher that redirects the test binary's stdout and stderr to files: what reaches the console and the plugin, the exit status, and behaviour under an emulator.
- [research/js-environment-channels.md](research/js-environment-channels.md) — for each JS environment Mill can select other than Node: whether the test adapter's com channel works, which other transports reach Mill's process, and what console output reaches the run's stdout and stderr.
- [research/mill-command-lifetime.md](research/mill-command-lifetime.md) — which Mill mechanisms run plugin code before the first test task and after the last, how that code learns what ran and how it ended, what Mill does to its console output, how the command's own output shares the console with Mill's prompt, and how it sets the exit status.
- [research/emitter-plugin-spike.md](research/emitter-plugin-spike.md) — observed on Windows and Linux: a stub socket emitter in each JVM test worker sending events to a minimal exclusive command that executed several modules' test tasks. Covers the command's lifetime and results, live events, a killed worker, worker streams, and what Mill's prompt does to the command's console output.
- [research/mill-nested-keep-going.md](research/mill-nested-keep-going.md) — at Mill `1.1.10`: which routes let the test command's nested evaluation keep going without `--keep-going`, whether each starts a module's tests on its own compile, and how the Mill API each uses is marked.
- [research/nested-prompt-console.md](research/nested-prompt-console.md) — observed on Windows and Linux under a pseudo-terminal: whether the test command's summary through `Task.log.ticker` and its lines through `Task.log` hold on the console through the fail-fast-off nested evaluation, and which logger keeps the command's prompt line shown.
- [research/mill-parallel-worker-capture.md](research/mill-parallel-worker-capture.md) — observed on Windows and Linux: plugin code taking each JVM worker's stdout and stderr under Mill's fork groups and `testParallelism`, by two routes (supplying a `TaskCtx`, and driving the test queue itself). Covers what reached the console, what the plugin received, how each route matched Mill's own `testForked`, and the Mill API each relies on.
- [research/emission-gap-analysis.md](research/emission-gap-analysis.md) — for each need of the consolidated output, whether a run's events already provide it on each runtime, lose it when serialised, leave it derivable, or need new code at the point of emission.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and the output it captures over a single channel.
- A consolidation library that takes the following and writes each consolidated output to a file, pipe or writer its caller supplies:
  - many runs' event streams, each with the output *captured off* the stdout and stderr of its run's processes, that is, read from those streams from outside the processes;
  - each process that ended before any run existed in it (R-076), with its test run label and the output captured off its stdout and stderr;
  - each module whose tests did not start, with its test run label and, where a failure stopped them, that failure;
  - each test class its caller knows was to run but that no run reported;
  - each module selector, with the test modules it resolved to.
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
- A dry-run option on the test command, listing each module selector's test structure without running tests; a possible later feature.

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

- R-010 Each run sends over one channel every event the run reports, the run's test structure and the output the run captures.
- R-063 What a run sends does not depend on its reporter configuration.
- R-011 Nothing the consolidated output shows or uses is lost or altered between the run and the channel's reader. This includes failure messages and the order of events within a node.
- R-013 A channel carries one run.
- R-074 On every runtime, each run's channel carries how the run ended: completed, aborted or stopped.
- R-073 When a run's process dies, R-010, R-011, R-021 and R-042 hold for everything the run had handed to the operating system. They do not cover what was still buffered inside the process.

## Behaviour taken from structured test reporting

- R-030 Where this document is silent, the test structure and what the human-readable, progress and JSON output show are as `../structured-test-reporting/SPECIFICATION.md` § Test structure, § Human-readable output, § Progress output and § JSON output state. Where the two differ, this document holds.
- R-031 No statement in `../structured-test-reporting/SPECIFICATION.md` or `../structured-test-reporting/runner-json/SPECIFICATION.md` about a reporter, a reporter's flags, or where and when a single run writes its output applies to this epic, unless a requirement here cites it. This excludes, among others:
  - what `-C` and other existing reporters receive;
  - event-filter characters;
  - which reporters progress output is written to;
  - test output streamed as it is received;
  - a block or file written at the end of each run.

Unlike `../structured-test-reporting/SPECIFICATION.md` § Test output, what test output is does not depend on the route or thread of the write, and none of a run's output is streamed directly to stdout or stderr; R-012 and R-040 replace that section.

## The test command

- R-054 In a Mill build, only a test command produces consolidated output.
- R-055 The test command's arguments include:
  - one or more module selectors, each a separate argument; `+` cannot join them, because Mill consumes it before the test command runs;
  - optional class globs and framework arguments, as Mill's `testOnly` takes them, applying alike to every selected test module;
  - `--halt-on-failure` (R-057);
  - an argument that turns progress output off, and one that turns human-readable output off (R-048);
  - an argument that turns JSON output on (R-085) and one that gives the JSON file's path (R-049), which may be the same argument.
- R-053 The test command runs each selected test module's tests as Mill's `testOnly` runs them with the test command's class globs and framework arguments, or as `testForked` runs them when no class globs are given:
  - the same tests, in the processes Mill's fork grouping, test parallelism and test queue start;
  - the same working directory, arguments and environment, plus only what turning the emitter on (with its channel) and turning off the reporter R-041 names require;
  - except that a path inside the task folder (`Task.dest`) of `testOnly` or `testForked` is instead at the same relative path inside a folder the test command owns for that test module alone, outside the test command's own task folder (R-049);
  - except that environment variables Mill's launcher sets from its own command line, such as `MILL_PARAMS` from `mill.bat`, hold the `mill` command line that started the test command.
- R-071 A run given no task, including one whose process dies before its first task, has a run record in the JSON output (R-080) and, where R-082 gives it one, a block in the human-readable output.
- R-072 A runner that is not a run, such as Mill's discovery runner, does not appear in the consolidated output and does not affect whether the test command fails.
- R-056 By default, the test command runs the tests of every selected test module, whatever happens to other modules' runs and whether or not Mill's `--keep-going` is given. Tests start without waiting for every selected test module to compile.
- R-061 IF a task Mill must complete before starting the test command fails, THEN the test command does not run and produces no consolidated output. A selected test module's compile and test tasks are not among them; R-046 and R-058 cover their failure.
- R-062 IF another goal on the same `mill` command line fails before the test command starts and Mill's `--keep-going` is not given, THEN the test command does not run and produces no consolidated output.
- R-059 IF a module selector resolves to a test module that does not test with the release of this fork whose channels the Mill plugin reads, THEN the test command fails before any test starts, naming each such module, and produces no consolidated output. Examples are a module that tests with MUnit, uTest, upstream ScalaTest 3.2.19 or another release of this fork. The JSON file stays as R-077 left it.
- R-058 The consolidated output shows the test run label of each selected test module whose tests did not run as aborted, with:
  - reason *not run* where `--halt-on-failure` skipped them (R-057);
  - reason *could not start*, and the failure that stopped them, where they could not start.
- R-068 The consolidated output shows as not run each test class left in a fork group's queue when the group's last worker ended. It may also show as not run any other test class the Mill plugin knows was to run but that no run reported.
- R-069 IF the test command is interrupted, as by Ctrl-C, THEN it writes no human-readable output and no JSON, not even a placeholder; its progress output may show runs the interrupt ended. The JSON file stays as R-077 left it.

WHERE `--halt-on-failure` is given:

- R-057 Once anything has failed the test command (R-046), the test command starts no further selected test module's tests.

## Consolidation

Output is *captured against* a suite, scope or test when the means that captured it ties it to that node, and *captured against* the run when it ties it only to the run.

- R-020 For one test command, the Mill plugin produces one set of consolidated output covering every run the test command started: each of progress output, human-readable output and JSON output that is turned on. Each is a separate output, and any combination can be on together, including progress output with JSON output, unlike `../structured-test-reporting/SPECIFICATION.md` § Progress output.
- R-026 Progress output is written while the runs execute, from the events received so far. It is not held until a run or the test command completes.
- R-027 Unless the test command is interrupted (R-069), the human-readable and JSON output are written together, once every run's event stream has ended.
- R-078 In the human-readable and JSON output, the consolidation is the root, one for each test command under Mill. Its children are its test run labels, and each label's children are the top-level suites of its runs. No run and no module selector is a node.
- R-079 In the human-readable and JSON output, test run labels appear in ascending lexicographic order, as do the top-level suites under each label by name, unlike the order `../structured-test-reporting/SPECIFICATION.md` § Human-readable output gives. Below a top-level suite, nodes keep the order R-028 and R-030 give them.
- R-028 The JSON output is one object for the consolidation. Each suite, and every node below it, takes the layout that `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object and `../structured-test-reporting/runner-json/TECHNICAL-DESIGN.md` § APIs give one run, key names included, each node holding its final state. Unlike that layout, the JSON output records no event-filter characters and filters no value by them. The `output` of each node and run record holds the output captured against it (R-012), not only test output, whatever its source and whether or not its stream is known.
- R-080 In the JSON output, each test run label holds a run record for every run under it, including a run with no suites (R-071). A run record holds what the layout R-028 cites gives the run node, key names included, except its suites, ScalaTest version and event-filter characters, and adds the run's identifier, unique within its label. Each suite a run reported names that run's record.
- R-081 The JSON output's root records the ScalaTest version, which every run shares (R-059), and each module selector with the test modules it resolved to. The human-readable output does not show module selectors.
- R-082 The human-readable output shows no run. On each test run label, without naming the run, it shows one block for each run that has any of:
  - output captured against the run;
  - messages attached to the run rather than to a suite;
  - how the run ended, where it aborted, stopped or did not finish.
- R-083 A test run label has an outcome only as R-058 gives it.
- R-021 The human-readable and JSON output show every suite, scope and test, every failure and every piece of captured output of every run, except what R-045 excludes and what the layouts this document takes (R-028, R-030) leave out.
- R-022 In the human-readable and JSON output, no suite, scope, test, run record or human-readable block (R-082) of one run holds an event or captured output of another run.
- R-012 Each piece of captured output is shown on the narrowest suite, scope or test it was captured against, else on its run (R-080, R-082), or, for a process in which no run existed, as R-076 gives. Each is shown with its stream, stdout or stderr, where that is known. In the human-readable output, output captured against a suite, scope or test keeps its position among that node's events.
- R-088 In each of the human-readable and JSON output, each piece of output a process wrote appears at most once, even where it was captured both against a node and off the process's stdout or stderr.
- R-086 Output captured only against the run is shown without claiming that it is test output or worker output.
- R-087 Worker output is never captured against a suite, scope or test.
- R-084 The JSON output is documented for third-party readers and carries the compatibility promise `../structured-test-reporting/SPECIFICATION.md` § JSON output gives the single-run JSON. The human-readable and progress output carry no compatibility promise.
- R-023 The consolidation library is published and depends on no Mill artifact. Given the same inputs (§ Scope), it produces the same human-readable and JSON output when called without Mill as when the Mill plugin calls it.
- R-052 The consolidation library's caller names the test run label for each event stream.
- R-025 The Mill plugin gives each selected test module exactly one test run label, named for its module path. Mill keeps module paths unique.

## A run that does not finish

A run *does not finish* when its event stream ends without how the run ended (R-074), as when its process dies. These hold for such a run unless the test command is interrupted (R-069).

- R-064 The run, and each suite, scope and test that started in it without an event ending it, are aborted.
- R-065 Every scope and test registered in a suite the run constructed appears, even where the run's process died before the scope or test started.
- R-066 A suite the run did not construct does not appear in the consolidated output, even where the run was given a task for it.
- R-075 Where a test ended the run's process, as a call to `System.exit` does, the consolidated output shows that test and every event the run reported before the test started.
- R-067 Progress output shows that the run aborted as soon as its event stream ends.

A process started to execute tests may end before any run exists in it, as when it dies before its runner is constructed:

- R-076 On its module's test run label, the human-readable and JSON output show that such a process ended before any run existed, with the output captured off that process's stdout and stderr. That output appears nowhere else, neither in a run record nor in a run's human-readable block (R-082).

## Each run's own output under the test command

These hold for every run a test command starts.

- R-040 Nothing the run writes to stdout or stderr reaches the console except through the consolidated output.
- R-041 The run's events are not printed by the reporter ScalaTest uses when no reporter is configured.
- R-042 Output captured off the stdout and stderr of the run's processes is captured against the run.
- R-043 Output captured off the run's processes' stdout keeps its order, as does output captured off their stderr, whatever its source. Its position among the run's events, and the order of stdout relative to stderr, are not guaranteed.
- R-044 Outside the consolidated output, the console does not show:
  - Mill's list of failed tests;
  - the progress Mill's test runner shows for the tests of the run's module, such as each worker's current test class and the count of classes completed;
  - ScalaTest's end-of-run summary for the run.

  Mill's prompt may still show a line for the test command and for each task it runs, without that progress. A Mill test task named as another goal on the same `mill` command line shows its own progress (R-050).
- R-045 The consolidated output does not show Mill's list of failed tests or ScalaTest's end-of-run summary for the run.
- R-046 The test command fails if:
  - any run reports a failure or does not complete: it ends aborted or stopped, or does not finish (§ A run that does not finish);
  - a process started to execute tests ends before any run exists in it (R-076); or
  - the tests of any selected test module cannot start, for example because it or a module it depends on fails to compile.

  Its failure does not list the failed tests. What R-068 shows is not itself a cause of failure. When the test command is interrupted (R-069), Mill's client and the operating system set its exit status instead.
- R-047 The test command writes each selected test module's JUnit XML report as Mill's `testOnly` or `testForked`, whichever R-053 names, writes it.

Where the consolidated output goes:

- R-048 Progress output and human-readable output are each on unless a test-command argument turns it off (R-055), and each is written to the console.
- R-090 On the console, progress output is written as whole lines, never as the single characters `../structured-test-reporting/SPECIFICATION.md` § Progress output streams. Whether or not the summary (R-089) is shown, the lines show every failure a run reports and each other cause of failure R-046 lists, including each run that aborts or does not finish (R-067).
- R-089 Where Mill redraws in place its prompt (the status lines at the foot of the console), progress output also shows on the console a current summary of the test command, on one line rewritten in place.
- R-091 Neither progress output's lines nor its summary erase Mill's console output, and Mill's console output erases neither.
- R-085 JSON output is off unless a test-command argument turns it on (R-055).
- R-049 JSON output is written to a file in the test command's task folder (`Task.dest`), or to the path a test-command argument gives, never to the console.
- R-077 When the test command starts, before any test starts, it deletes or empties each file it will write consolidated output to that already exists. However the test command ends, no such file holds the result of an earlier test command.
- R-050 Output written by other tasks named on the same `mill` command line, and Mill's own output other than what R-044 and R-045 name, is neither captured nor changed. On the console it may interleave with the consolidated output.
