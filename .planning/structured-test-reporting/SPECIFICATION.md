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
- R-002 For a sequential run of the same suites, every reporter built against the 3.2.x `Reporter` API — ScalaTest's own and any passed with `-C` — receives the same sequence of event types and field values as ScalaTest 3.2.19 does, apart from timestamps, durations and thread names, and except as R-075 provides.
- R-057 Under every execution mode in § Test structure, every reporter built against the 3.2.x `Reporter` API receives the events ScalaTest 3.2.19 delivers in that mode, in an order 3.2.19 could deliver them, except as R-075 provides.
- R-003 Existing command-line flags keep their ScalaTest 3.2.19 meanings, including `-h` and the streaming output of a 3.2-format `-o` or `-e` reporter: one carrying a 3.2 config character other than `J`, and no `J` (R-030). For `-oDU` on a sequential run, the output is byte-for-byte what ScalaTest 3.2.19 prints, apart from timestamps, durations and thread names. The only exceptions are the default format of the spellings § Human-readable output names, test output withheld from stdout and stderr under R-063, and R-075's `RunAborted`. No new behaviour reuses an existing spelling with a new meaning.
- R-004 The `Framework` sends build tools the same test-interface events as ScalaTest 3.2.19 does.

### IntelliJ

IntelliJ's Scala plugin runs ScalaTest in three modes:

- **Forked**: the IDE's `ScalaTestRunner.main` calls `Runner.run` with only `-C <its reporter>`, a reporter compiled against ScalaTest 3.2.19 as `Provided`. That reporter writes TeamCity service messages (`##teamcity[…]`) to stdout, inferring each node's parent from `Ordinal` prefixes and `suiteId`; it needs each `SuiteStarting` and `ScopeOpened` before the tests inside it and every open closed, and silently drops event types it does not know.
- **sbt shell**: the IDE forces `-oDU` and parses the text with regular expressions, relying on R-003.
- **BSP**: the build server runs `Framework` and forwards its test-interface events.

## Test structure

These requirements govern the results output in § Human-readable output, the JSON output and the outputs in § Consolidation, and R-075 also every reporter, on every launch path and whatever the execution mode: sequential, `-P`, `ParallelTestExecution`, async suites, `RandomTestOrder`, or sbt's parallel tasks.

- R-010 Every node appears under the node that contains it, except as R-013 allows and in JUnit XML (R-055).
- R-011 In results output and in every consolidated output other than merged JSON, siblings appear in the order their parent declares them, whatever order they ran in. Within a node, events keep the order the node emitted them.
- R-012 `info`, `alert`, `note` and `markup` attach to the node that emitted them.
- R-013 An event whose emitting node cannot be identified attaches to an ancestor of that node, and is never lost or reordered.
- R-014 Every node has an ID unique within its run, including where `suiteId`s and scope texts repeat and where the run spans several processes. In consolidated output, every node's ID is unique across all runs.
- R-015 A node's outcome is the worst of its own and its descendants'. From worst to best: aborted, failed, canceled, pending, ignored, passed.
- R-074 A test, scope or suite whose start was reported but whose end never was has its own outcome aborted, and existing reporters receive no event for its end. It is unfinished when, in addition, no suite containing it ended and its run reported no `RunAborted` or `RunStopped`.
- R-075 When a run finishes — at `done()` under the `Framework`, at the end of the run under `Runner` — with any node unfinished (R-074), and has not already ended in `RunAborted` or `RunStopped`, every reporter, `-C` reporters included, receives `RunAborted` in place of `RunCompleted`.
- R-016 Failure messages and throwables are preserved exactly: message text, throwable class, cause chain and location.
- R-017 Every test and suite carries its duration.
- R-018 A suite that aborts, including one whose constructor throws, is reported as aborted on every platform.

## Human-readable output

By default — plain `-o`, plain `-e`, and the `Framework` given no reporter argument (the build tool's loggers):

- R-020 A run shows progress output while it runs, followed, after all of its progress output, by its results output. On Scala.js and Native, progress output may arrive late, as late as with the results output.
- R-022 Under `Runner`, a user can send progress output and results output each to stdout, stderr or both, independently. Under a build tool, results output goes wherever the build tool prints the test framework's summary, and where the build tool has log levels, as sbt does, the user chooses one for progress output, except that on Scala.js and Native progress output that arrives with the results output (R-020) goes where the results output goes. Under Mill, which prints every line with no level, neither output can be separated by level.
- R-023 A run's results output never waits for other runs or modules.
- R-024 Results output shows the captured test output attached to every node whose outcome (R-015) is failed or aborted.
- R-058 A run's results output appears as one contiguous block that no other output ScalaTest writes splits, except that a build tool printing the block line by line may interleave other runs' output printed at the same moment.

## JSON output

- R-030 JSON output is written only when asked for by a `J` spelling: `-fJ <file>`, `-oJ`, `-eJ`, `-oJ` passed to the `Framework`, or the directory argument of R-071. Each produces the JSON output this section describes, wherever R-036 makes it available.
- R-031 It stays one JSON object per event or chunk of test output, and every field ScalaTest 3.2.19 writes keeps its name and meaning.
- R-064 Within one run's JSON output, each JSON object occupies whole lines that no other output ScalaTest writes or captures splits; an object may span several lines. Where several runs' JSON output shares one stream, nothing is guaranteed about how it interleaves.
- R-032 JSON events appear in the order they arrive. From a run's JSON alone, a reader can rebuild its test structure, each node's event order and its siblings' declared order.
- R-033 Every JSON object carries its run's run ID, which distinguishes the run from every other run, including runs that share a module, platform or process.
- R-071 A reporter argument new under R-003, given a directory, writes each run's JSON to `<dir>/<run ID>.json`, so runs given the same directory, from one process or many, never overwrite each other's JSON.
- R-034 JSON output holds all captured test output, whichever node it attaches to.
- R-035 If any process of a run dies, the run's JSON holds, as complete objects, every event and chunk of test output that process reported before it died, except that the last may be truncated or missing.
- R-076 A run whose JSON ends with no closing run event — `RunCompleted`, `RunAborted` or `RunStopped` — is aborted.
- R-036 JSON output is available on every launch path in § Scope, with two exceptions. Where a Scala.js run cannot write files, the run reports `-fJ` and R-071's directory argument as configuration errors, and `-oJ` remains available. Under `testHtml`, no `J` spelling produces JSON.
- R-037 The JSON format is documented well enough for a third party to render from it, including how a reader recognises a run with no closing run event (R-076) and a run whose test process failed (R-077).

## Test output

- R-061 Test output is captured when, and only when, a reporter that consumes it is configured: the default output of § Human-readable output, or any `J` spelling (R-030).
- R-070 A write belongs to the run whose node, or whose own code, is running on the writing thread; where several runs are, to the one started most recently. A run's own code is ScalaTest's code that starts, discovers, loads and finishes the run's suites, on every launch path. Where several runs share a process, each run's own configuration alone decides whether its writes are captured, withheld or passed through.
- R-065 Capture catches test output written through `println`, `System.out` and `System.err`; on Scala.js also through `console`, and on Node also through `process.stdout` and `process.stderr`. Output written any other way, such as straight to a file descriptor, from native code, by a child process or through a stream reference obtained before capture began, is never captured.
- R-069 Output that is not captured — because R-061 does not hold for its run, R-065 does not catch it, or it belongs to no run under R-070 — reaches stdout and stderr as under ScalaTest 3.2.19. Output belonging to no run includes output from a thread pool or background thread a test uses, the build tool's own output, and other test frameworks' output.

When test output is captured:

- R-040 Output written on a thread while exactly one test runs on that thread attaches to that test. Other output written on a thread while a suite runs on it attaches to the innermost such suite. On Scala.js, every test and suite of a process runs on its one thread.
- R-067 Output that belongs to a run but to none of its suites under R-040 attaches to the run's node, except that output written while a suite is constructed attaches to that suite or to the run's node. Every captured chunk attaches to exactly one node.
- R-068 Output ScalaTest itself writes, including its reporters' and any `-C` reporter's, is not test output: it is never captured, attributed or withheld.
- R-062 When any `-C` reporter or any 3.2-format `-o` or `-e` reporter (R-003) is configured, captured test output reaches the process's stdout and stderr live and unaltered, whatever `-f` reporters are configured.
- R-063 When R-062 does not apply, captured test output is withheld from stdout and stderr: it is not passed to them as it is written, and appears only within results output (R-024) and JSON output (R-034), except as R-007 provides.
- R-007 Under R-063, TeamCity service-message lines in captured test output still reach stdout unaltered.

## Consolidation

- R-050 The consolidator is given each module expected to have run, with the location of that module's JSON and, where the caller has it, whether the module's test task failed. Module names come only from this caller. It reads every run found in those locations, across forked JVMs and platforms, and writes:
  - merged JSON;
  - results output, rendered as ScalaTest's own results output is;
  - JUnit XML;
  - Markdown.
- R-051 Consolidated output distinguishes a module that did not run — one whose JSON location holds no run, whatever its test-task result — and a module whose test process failed, from a module whose tests failed.
- R-077 A run's test process failed when its JSON has no closing run event (R-076), or it ended in R-075's `RunAborted`, including one a forked JVM of the run emits, or in the `RunAborted` raised when a forked JVM of the run is lost. A run that ends in any other `RunAborted`, such as one raised for a suite that cannot be loaded or instantiated, is aborted.
- R-059 A module's outcome is the worst of its runs' outcomes (R-015); "test process failed" and "did not run" rank with aborted.
- R-078 A module that has at least one run, none of them failed or aborted, and whose test task failed (R-050) has test process failed.
- R-053 The Mill plugin provides a command that runs the tests of every selected module, then consolidates the JSON of the runs it started and of no earlier runs, with each module's test-task result (R-050), including when tests fail.
- R-055 JUnit XML keeps each test's containment as its path in the test and suite names.
- R-056 The Mill plugin turns on JSON output for each module it consolidates.
- R-060 The documented sbt recipe turns on JSON output for each module it consolidates and, after `test`, consolidates the JSON of every selected module's runs that `test` started and of no earlier runs, with each module's test-task result (R-050), including when tests fail.
- R-073 A Scala.js module whose runs cannot write files (R-036) cannot be consolidated; neither the Mill plugin nor the sbt recipe turns on JSON output to files for it.
