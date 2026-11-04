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

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- correctly outputting human-readable output on all runtimes
- producing non-lossy JSON output
- clear progress output
- a multi-run consolidation executable, that consolidates multiple JSON files into ScalaTest output
- mill plugins and sbt recipes
- maintaining Intellij test runner support

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

## Constraints

### Compatibility with ScalaTest 3.2.x

- The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19, the latest 3.2.x release the fork builds on.
- For a sequential run of the same suites, every reporter built against the 3.2.x `Reporter` API — ScalaTest's own and any passed with `-C` — receives the same sequence of event types and field values as ScalaTest 3.2.19 does, apart from timestamps, durations and thread names, and except where explicitly noted below.
- Existing command-line flags keep their ScalaTest 3.2.19 meanings, but human and JSON output changes.
- `-oDU` remains unchanged
- Under every execution mode in § Test structure, every reporter built against the 3.2.x `Reporter` API receives the events ScalaTest 3.2.19 delivers in that mode, in an order 3.2.19 could deliver them, except where explicitly noted below.
- The `Framework` sends build tools the same test-interface events as ScalaTest 3.2.19 does.
- Failure messages and throwables are preserved exactly: message text, throwable class, cause chain and location.

### IntelliJ

IntelliJ's Scala plugin runs ScalaTest in three modes:

- **Forked**: the IDE's `ScalaTestRunner.main` calls `Runner.run` with only `-C <its reporter>`, a reporter compiled against ScalaTest 3.2.19 as `Provided`. That reporter writes TeamCity service messages (`##teamcity[…]`) to stdout, inferring each node's parent from `Ordinal` prefixes and `suiteId`; it needs each `SuiteStarting` and `ScopeOpened` before the tests inside it and every open closed, and silently drops event types it does not know.
- **sbt shell**: the IDE forces `-oDU` and parses the text with regular expressions
- **BSP**: the build server runs `Framework` and forwards its test-interface events.

## Test structure

A node is any suite or test (Something about nested suites and test descriptions).

- `info`, `alert`, `note` and `markup` attach to the node that emitted them. 
- An event whose emitting node cannot be identified attaches to the first identifiable ancestor of that node, and is never lost or reordered.
-  When a run finishes — at `done()` under the `Framework`, at the end of the run under `Runner` — with any nodes unfinished, and has not already ended in `RunAborted` or `RunStopped`, every reporter, `-C` reporters included, receives `RunAborted` in place of `RunCompleted`.
- every node has a duration
- A suite that aborts, including one whose constructor throws, is reported as aborted on every platform.
- nodes that share a name are never consolidated

## Human-readable output

- human-readable form:
  - is emitted in the ScalaTest 3.2.19 format, except, if necessary, additional suite hierarchy indentation is added
  - is consolidated and reported as a single block at the end of each invocation of scalatest
  - test output is streamed out as it is received, not part of the consolidated results
  - reports run aborted if printed before receiving a valid completion event
  - nodes appear in declared order where available, alphanumeric descending where not

## Progress output

- progress output:
  - is emitted to any or `-e`, `-o` or `-f`
  - streams test results / suite failures as single characters
  - reports current progress summary of the entire invocation
  - is best effort on JS/Native
  - may be run on its own, or with human-readable on the same output 
  - may not be run with HTML, XML or JSON

## JSON output

- JSON form:
  - is emitted as a single structured JSON object
  - represents the full node hierarchy of the invocation. In JS/Native this includes all sub-processes 
  - is consolidated and reported at the end of each invocation of scalatest
  - writes stderr and stdout output as properties on the closest recognisable node
  - reports run aborted if printed before receiving a valid completion event 

## Test output

Test outputs are the writes to stderr/stdout via `println`, `System.out` and `System.err`; on Scala.js also through `console`, and on Node also through `process.stdout` and `process.stderr` made while running a test suite.

Other outputs include, but are not limited to, output to stderr/stdout from:
- a thread pool or background thread a test uses
- the build tool's own output
- other test frameworks' output
- native code

- test output is emitted only when a reporter that consumes it is configured
- test output is allocated to the node writing it. If the code performing a write is instantiated in an ancestor node, the test output will be attributed to that ancestor.
- other output is streamed directly to the appropriate stderr/stdout
- all `-C` reporters have test output reach the process stderr/stdout

## Mill

- mill plugin
  - does not guarantee consolidated output when not using the consolidation plugin - concurrent test invocations may interleave their output

## Multi-run consolidation plugin

Is exposed as an executable and a mill plugin

- executable 
  - expects one or more pairs of a test run label and a list of ScalaTest JSON files as input
    - test run label must be unique per consolidation
  - outputs a consolidated files for one or more of:
    - JSON
    - human-readable
    - JUnit XML
  - outputs use the same flags as ScalaTest
    - best effort dependent on the data in the input JSON
  - distinguishes test files that did not run (one whose JSON location holds no run, whatever its test-task result), test files whose test processes failed, and test files whose tests failed.
  - reports as non-successful if any test file is missing or not successful.
  - suites and tests with the same name across files are not collapsed

- mill plugin provides a command that 
  - runs the tests of every selected module
    - full JSON output, all data
    - ensures no test run label or file collision
  - consolidates the JSON of the runs it started

- sbt
  - a recipe will be provided to run scalatest with JSON output for each module it under test, then consolidate the output
  - runs the tests of every selected module
    - full JSON output, all data
    - ensures no test run label or file collision
  - consolidates the JSON of the runs it started

- R-073 A Scala.js module whose runs cannot write files cannot be consolidated; neither the Mill plugin nor the sbt recipe turns on JSON output to files for it.
