# Structured test reporting

Scale: epic.

## Summary

ScalaTest events do not carry the test structure: no event names a suite's parent, scopes have no identity, and a test's name is its scopes' text joined. Once suites or tests run in parallel or across modules, reporters cannot recover the nesting, and the output stops being readable. This epic makes the fork carry the test structure through every run and use it to keep output readable, by people and by tools, on parallel and multi-module runs, without breaking reporters, build tools or IDE integrations built against ScalaTest 3.2.x.

## Plan

[Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the contested behaviour choices and the alternatives they beat.
- [ARCHITECTURE.md](ARCHITECTURE.md) — modules, how the test structure travels through a run, the compatibility boundary and the data flow.
- [research/js-native-structure-and-capture.md](research/js-native-structure-and-capture.md) — what one Scala.js or Scala Native run consists of, the channels out of it, and how test output can be captured on each runtime.
- [research/mill-test-worker-args.md](research/mill-test-worker-args.md) — how Mill passes arguments to its test-worker JVMs, and whether each can be given its own JSON file.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- correctly outputting human-readable output on all runtimes
- producing non-lossy JSON output
- clear progress output
- a multi-run consolidation executable that consolidates multiple JSON files into ScalaTest output
- mill plugins and sbt recipes
- maintaining IntelliJ test runner support

Launch paths:

- `org.scalatest.tools.Runner`;
- the JVM `org.scalatest.tools.Framework`, as driven by sbt, Mill, Metals, Bloop, and IntelliJ's sbt and BSP modes;
- the Scala.js and Scala Native `org.scalatest.tools.Framework`.

Platforms: JVM on Scala 2.11, 2.12, 2.13 and 3.3; Scala.js and Scala Native on 2.12, 2.13 and 3.3.

### Out of scope

- Consolidated HTML; a follow-up epic.
- An sbt plugin for the consolidator.
- A renderer plug-in API for the consolidator.
- A public in-process API for the test structure.
- Changing `Runner`'s default of opening the GUI reporter when given no reporter argument.
- A JSON Schema for the JSON format.

## Constraints

### Compatibility with ScalaTest 3.2.x

- The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19, the latest 3.2.x release the fork builds on.
- For a sequential run of the same suites, every reporter built against the 3.2.x `Reporter` API — ScalaTest's own and any passed with `-C` — receives the same sequence of event types and field values as ScalaTest 3.2.19 does, apart from timestamps, durations and thread names, and except where explicitly noted below.
- Existing command-line flags keep their ScalaTest 3.2.19 meanings, but human-readable and JSON output change.
- `-oDU` remains unchanged.
- Under every execution mode, every reporter built against the 3.2.x `Reporter` API receives the events ScalaTest 3.2.19 delivers in that mode, in an order 3.2.19 could deliver them, except where explicitly noted below.
- The `Framework` sends build tools the same test-interface events as ScalaTest 3.2.19 does.
- Failure messages and throwables are preserved exactly: message text, throwable class, cause chain, suppressed exceptions and location.

### IntelliJ

IntelliJ's Scala plugin runs ScalaTest in three modes:

- **Forked**: the IDE's `ScalaTestRunner.main` calls `Runner.run` with only `-C <its reporter>`, a reporter compiled against ScalaTest 3.2.19 as `Provided`. That reporter writes TeamCity service messages (`##teamcity[…]`) to stdout, inferring each node's parent from `Ordinal` prefixes and `suiteId`; it needs each `SuiteStarting` and `ScopeOpened` before the tests inside it and every open closed, and silently drops event types it does not know.
- **sbt shell**: the IDE forces `-oDU` and parses the text with regular expressions.
- **BSP**: the build server runs `Framework` and forwards its test-interface events.

## Test structure

The test structure's nodes are:

- the run node, at the root;
- suites; a nested suite sits under the suite that nests it. `DiscoverySuite` is not a node, so the suites it discovers are top-level;
- scopes;
- tests.

Events:

- `info`, `alert`, `note` and `markup` attach to the node that emitted them.
- An event whose emitting node cannot be identified attaches to the first identifiable ancestor of that node, and is never lost or reordered.

Outcomes:

- Every test has an outcome; in JSON, a filter character that drops a test's closing event leaves that outcome absent (§ JSON output). A scope has one only when it is pending or aborted; a suite only when it is aborted; the run node only when it is aborted or stopped. No node is given an outcome rolled up from its children; readers roll them up.
- When a suite aborts with scopes open inside it, those scopes are aborted in the test structure; existing reporters still receive only `SuiteAborted`.
- A suite that aborts, including one whose constructor throws, is reported as aborted on every platform.
- When a run finishes — at `done()` under the `Framework`, at the end of the run under `Runner` — with any nodes unfinished, and has not already ended in `RunAborted` or `RunStopped`, every reporter, `-C` reporters included, receives `RunAborted` in place of `RunCompleted`. In the test structure that `RunAborted` has reason *process failed*, as does the `RunAborted` of a run that loses one of its processes.
- Every node the run was to execute, known before its run ends, that never ran is aborted with reason *not run*, in JSON as well as human-readable output. This includes a top-level suite discovered or given a task that never started, and a test registered in a constructed suite that never ran. A node excluded by a filter, such as a tag or a test-name selector, is reported as ScalaTest 3.2.19 reports it.
- After `RunStopped`, only the run node is stopped; the tests it skipped and the suites it never started are aborted, *not run*.
- Where a run halts early, as when a suite's constructor throws under `Runner`, every top-level suite the run was to execute still appears: the one that threw aborted, every other one that never ran aborted, *not run*.

Durations and names:

- A node that ran has a duration, except where the events that time it carry none or the reporter's filters drop them; a node that never ran has none.
- Nodes that share a name are never merged, within a run or across the files of a consolidation.

## Human-readable output

- human-readable form:
  - is emitted in the ScalaTest 3.2.19 format, except that:
    - additional suite hierarchy indentation is added where necessary
    - by default, a subtree whose nodes all share one error is collapsed to its root; `-oDU` output is never collapsed
  - is consolidated and reported as a single block at the end of each run, on every human-readable reporter other than `-oDU`
  - test output is streamed out as it is received, not part of the consolidated results
  - reports run aborted if printed before receiving a valid completion event
  - nodes appear in declared order where available, alphanumeric descending where not

## Progress output

- progress output:
  - is emitted to any of `-e`, `-o` or `-f`
  - streams test results / suite failures as single characters
  - reports a current progress summary of the entire run
  - is best effort on JS/Native
  - followed by the human-readable block, is the default for plain `-o` and `-e` and for the `Framework`
  - may be run on its own, or with the human-readable form on the same output
  - may not be run with HTML, XML or JSON

## JSON output

- JSON form:
  - is emitted as a single structured JSON object
  - holds the run's full test structure, whatever the reporter's filters, with nothing collapsed. In JS/Native this includes all sub-processes
  - on a `J` reporter, the event-filter characters (`N C X E H L O P Q R M`) keep their ScalaTest 3.2.19 meaning, but a filtered event drops only its data from its node, never the node; dropped data is absent
  - records, at its root, the reporter's active filter characters
  - is consolidated and reported at the end of each run
  - writes stderr and stdout output as properties on the closest recognisable node
  - reports run aborted if printed before receiving a valid completion event
- JSON files:
  - a `-fJ` reporter that can write its file leaves it once its run starts executing suites: until the run's end replaces it, the file holds a valid object for a run aborted with reason *process failed*, listing the top-level suites known so far and no results
  - where a run's process ends early but can still run code as it exits, as on `System.exit` or SIGTERM on the JVM, the file holds every node known, with the results reached, in a run aborted with reason *process failed*, unless another run has written the file since
  - every write replaces the file whole, so a reader never sees part of an object
  - where a Scala.js run cannot write files, `-fJ` is a configuration error; `-oJ` still writes the object
- JSON compatibility:
  - the root records the version of the fork's ScalaTest that wrote it; the format has no version of its own
  - once this epic is complete, changes to the format follow ScalaTest's semantic versioning: a patch release never changes it, a minor release only adds to it, and only a major release removes or changes anything. Until then, any feature may change it
  - the format is documented for third-party readers

## Test output

Test output reaches stdout and stderr through `println`, `System.out` and `System.err`; on Scala.js also through `console`, and on Node also through `process.stdout` and `process.stderr`.

Other output, which is not test output, includes, but is not limited to, output to stderr/stdout from:
- a thread pool or background thread a test uses
- the build tool's own output
- other test frameworks' output
- native code

- test output is emitted only when a reporter that consumes it is configured
- test output belongs to the run whose node or own code is running on the writing thread
- test output is allocated to the node writing it. If the code performing a write is instantiated in an ancestor node, the test output will be attributed to that ancestor.
- a write is allocated to a test only while that test is the only test running on the writing thread; otherwise it goes to the innermost suite running there, else to the run node
- other output is streamed directly to the appropriate stderr/stdout
- all `-C` reporters have test output reach the process stderr/stdout

## Mill

- mill plugin
  - does not guarantee consolidated output when not using the consolidation plugin - concurrent test invocations may interleave their output

## Multi-run consolidation plugin

It is exposed as an executable and a mill plugin.

- executable
  - expects one or more pairs of a test run label and a list of JSON files written by ScalaTest as input
    - each test run label must be unique within a consolidation
  - outputs consolidated files for one or more of:
    - JSON
    - human-readable
    - JUnit XML
  - outputs use the same flags as ScalaTest
    - best effort, dependent on the data in the input JSON
  - shows a listed JSON file that does not exist as not run
  - shows each file's run with its closing run event and abort reason, so a file a run left unfinished shows a run aborted, *process failed*
  - its exit code does not depend on test results: input in which no run succeeded still consolidates successfully

- mill plugin provides a command that
  - runs the tests of every selected module
    - full JSON output, all data
    - ensures no test run label or file collision
  - consolidates the JSON of the runs it started

- sbt
  - a recipe will be provided to run ScalaTest with JSON output for each module under test, then consolidate the output
  - runs the tests of every selected module
    - full JSON output, all data
    - ensures no test run label or file collision
  - consolidates the JSON of the runs it started

- A Scala.js module whose runs cannot write files cannot be consolidated; neither the Mill plugin nor the sbt recipe turns on JSON output to files for it.
