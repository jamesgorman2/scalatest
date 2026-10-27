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

- ScalaTest's runtime carries the test structure on every launch path and platform below.
- The default human-readable output changes (§ Human-readable output).
- ScalaTest's JSON output changes (§ JSON output).
- Where test output is captured, it is attributed to nodes and may be withheld from stdout and stderr (§ Test output).
- A new consolidator, shipped as an executable and a Mill plugin, with a documented recipe for sbt.

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

- R-001 The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19, the latest 3.2.x release the fork builds on.
- R-002 For a sequential run of the same suites, every reporter built against the 3.2.x `Reporter` API — ScalaTest's own and any passed with `-C` — receives the same sequence of event types and field values as ScalaTest 3.2.19 does, apart from timestamps, durations and thread names.
- R-057 Under every execution mode in § Test structure, every reporter built against the 3.2.x `Reporter` API receives the events ScalaTest 3.2.19 delivers in that mode, in an order 3.2.19 could deliver them.
- R-003 Existing command-line flags keep their ScalaTest 3.2.19 meanings, including `-h` and the streaming output of a 3.2-format `-o` or `-e` reporter: one carrying a 3.2 config character other than `J`, and no `J` (R-030). For `-oDU` on a sequential run, the output is byte-for-byte what ScalaTest 3.2.19 prints, apart from timestamps, durations and thread names. The only exceptions are the default format of the spellings § Human-readable output names, and test output withheld from stdout and stderr under R-063. No new behaviour reuses an existing spelling with a new meaning.
- R-004 The `Framework` sends build tools the same test-interface events as ScalaTest 3.2.19 does.

### IntelliJ

IntelliJ's Scala plugin runs ScalaTest in three modes:

- **Forked**: the IDE's `ScalaTestRunner.main` calls `Runner.run` with only `-C <its reporter>`, a reporter compiled against ScalaTest 3.2.19 as `Provided`. That reporter writes TeamCity service messages (`##teamcity[…]`) to stdout, inferring each node's parent from `Ordinal` prefixes and `suiteId`; it needs each `SuiteStarting` and `ScopeOpened` before the tests inside it and every open closed, and silently drops event types it does not know.
- **sbt shell**: the IDE forces `-oDU` and parses the text with regular expressions, relying on R-003.
- **BSP**: the build server runs `Framework` and forwards its test-interface events.

## Test structure

These requirements govern the results output in § Human-readable output, the JSON output and the outputs in § Consolidation, on every launch path and whatever the execution mode: sequential, `-P`, `ParallelTestExecution`, async suites, `RandomTestOrder`, or sbt's parallel tasks.

- R-010 Every node appears under the node that contains it, except as R-013 allows and in JUnit XML (R-055).
- R-011 In results output and in every consolidated output other than merged JSON, siblings appear in the order their parent declares them, whatever order they ran in. Within a node, events keep the order the node emitted them.
- R-012 `info`, `alert`, `note` and `markup` attach to the node that emitted them.
- R-013 An event whose emitting node cannot be identified attaches to an ancestor of that node, and is never lost or reordered.
- R-014 Every node has an ID unique across all consolidated output, including where `suiteId`s and scope texts repeat.
- R-015 A node's outcome is the worst of its own and its descendants'. From worst to best: aborted, failed, canceled, pending, ignored, passed.
- R-016 Failure messages and throwables are preserved exactly: message text, throwable class, cause chain and location.
- R-017 Every test and suite carries its duration.
- R-018 A suite that aborts, including one whose constructor throws, is reported as aborted on every platform.

## Human-readable output

By default — plain `-o`, plain `-e`, and the `Framework` given no reporter argument (sbt's loggers):

- R-020 A run shows progress output while it runs, followed by its results output.
- R-022 A user can send progress output and results output each to stdout, stderr or both, independently. Under a build tool, the user chooses a build-tool log level for each output instead of a stream.
- R-023 A run's results output never waits for other runs or modules.
- R-024 Where test output is captured (R-061), results output shows the test output of every failed test.
- R-058 A run's results output appears as one contiguous block, never interleaved with its own progress output or with other output ScalaTest writes.

## JSON output

- R-030 JSON output is written only when asked for with the `J` presentation config character. Every `J` spelling — `-fJ <file>`, `-oJ`, `-eJ`, and `-oJ` passed to the `Framework` — produces the JSON output this section describes.
- R-031 It stays one JSON object per event or chunk of test output, and every field ScalaTest 3.2.19 writes keeps its name and meaning.
- R-064 Where JSON output shares stdout or stderr with other output, each JSON object occupies whole lines that no other output splits.
- R-032 JSON events appear in the order they arrive. From a run's JSON alone, a reader can rebuild its test structure, each node's event order and its siblings' declared order.
- R-033 Every JSON file identifies its run — module, platform and JVM — distinctly from every other run.
- R-034 Where test output is captured (R-061), JSON output holds all test output, attributed or not.
- R-035 If any process of a run dies, the run's JSON holds, as complete objects, every event and chunk of test output that process reported before it died; at most the final object is truncated.
- R-036 JSON output is available on every launch path in § Scope.
- R-037 The JSON format is documented well enough for a third party to render from it.

## Test output

- R-061 On the JVM, test output is captured when, and only when, a reporter that consumes it is configured: the default output of § Human-readable output, or any `J` reporter (R-030). Otherwise a run handles test output as ScalaTest 3.2.19 does.

On the JVM, when test output is captured:

- R-040 Output written on a test's thread while that test runs attaches to that test. Output written on a suite's thread outside any test attaches to that suite. Output not tied to a running node attaches to the run as unattributed. Every chunk attaches to exactly one node or to the run. Output written by ScalaTest's reporters, including `-C` reporters, is not test output: it is never captured, attributed or withheld.
- R-062 When any `-C` reporter or any 3.2-format `-o` or `-e` reporter (R-003) is configured, test output reaches the process's stdout and stderr live and unaltered, whatever `-f` reporters are configured.
- R-063 When R-062 does not apply, test output is withheld from stdout and stderr: it is not passed to them as it is written, and appears only within results output (R-024) and JSON output (R-034).
- R-007 Under R-063, TeamCity service-message lines in test output still reach stdout unaltered.

## Consolidation

- R-050 The consolidator is given runs' JSON — across modules, forked JVMs and platforms — and the set of modules expected to have run, and writes:
  - merged JSON;
  - results output, rendered as ScalaTest's own results output is;
  - JUnit XML;
  - Markdown.
- R-051 Consolidated output distinguishes a module that did not run, and a module whose test process failed, from a module whose tests failed.
- R-059 A module's outcome is the worst of its runs' outcomes (R-015); "test process failed" and "did not run" rank with aborted.
- R-053 The Mill plugin provides a command that runs the tests of every selected module, then consolidates their runs' JSON, including when tests fail.
- R-055 JUnit XML keeps each test's containment as its path in the test and suite names.
- R-056 The Mill plugin turns on JSON output for each module it consolidates.
- R-060 The documented sbt recipe turns on JSON output for each module and consolidates every selected module's runs' JSON after `test`, including when tests fail.
