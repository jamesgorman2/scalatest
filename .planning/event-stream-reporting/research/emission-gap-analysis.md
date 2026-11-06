# What the consolidated outputs need that a run does not emit

For each need of the consolidated progress, human-readable and JSON output: what ScalaTest's events carry in memory on the JVM, Scala.js and Native, what `Event.toJson` keeps of it, and whether new code is needed at the point of emission. Facts only; no design is chosen or recommended.

The needs are read from `.planning/event-stream-reporting/SPECIFICATION.md` and from the parts of the structured-test-reporting epic and its `runner-json` feature that its R-012, R-028 and R-030 take.

`.planning/event-stream-reporting/research/emitter-and-mill-channel.md`, cited as `EMC`, already lists what serialisation loses; this note does not repeat it.

## Short answer

- **Needing new code at the point of emission on every runtime:** test output, a scope's or test's name as written in the source, a test's label before it closes, the reasons *not run* and *process failed*, every node that never produces an event, and the ScalaTest version (§ 2, § 4, § 6, § 7, § 9, § 10).
- **Needing new code on Scala.js and Native only:** every run-level event and what it carries, and a suite whose constructor throws (§ 4, § 9).
- **Needing new code under every `Framework`:** the expected test count, which the JVM `Framework` sends as 0 and Scala.js and Native never send (§ 9).
- **In memory but lost by `toJson`:** the order of events within a run, a throwable's cause chain, suppressed exceptions and failed-code path, `TestFailed.analysis`, and the payload (§ 3, § 4).
- **Derivable by the reader, under conditions that concurrent runs break:** a nested suite's parent, a scope's parent, a test's scope, declared order, and a scope's identity (§ 1, § 2, § 3).
- **Provided:** suite and test identity, outcomes of tests, scopes and running suites, test and JVM suite durations, times, thread names, locations, scope labels and messages (§ 2, § 4, § 5, § 7, § 8).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[this]` | this repo | `47f849e7d` |
| `EMC` | `.planning/event-stream-reporting/research/emitter-and-mill-channel.md` | as committed at `47f849e7d` |
| `ESR` | `.planning/event-stream-reporting/SPECIFICATION.md` | as committed with this note |
| `STR` | `.planning/structured-test-reporting/SPECIFICATION.md` | the same |
| `RJ` | `.planning/structured-test-reporting/runner-json/SPECIFICATION.md` | the same |
| `TD` | `.planning/structured-test-reporting/runner-json/TECHNICAL-DESIGN.md` | the same |
| `ADR` | `.planning/structured-test-reporting/ARCHITECTURE-DECISIONS.md` | the same |
| `JSN` | `.planning/structured-test-reporting/research/js-native-structure-and-capture.md` | read from the sources its § Sources lists |

`[this]` paths are relative to the repo root. Abbreviations:

- `CORE/` is `jvm/core/src/main/scala/org/scalatest/`.
- `EV/` is `CORE/events/`; `TOOLS/` is `CORE/tools/`.
- `JS/` is `js/core/src/main/scala/org/scalatest/tools/`; `NAT/` is `native/core/src/main/scala/org/scalatest/tools/`.

No experiment was run. Every `[this]` citation was opened and confirmed unless marked *unverified*. Statements marked *inference* follow from the code read and were not observed. Mill and sbt behaviour is cited from `EMC` and `JSN`, and no build-tool source was read.

`EV/`, `CORE/Engine.scala`, `CORE/AsyncEngine.scala`, `CORE/Suite.scala` and `CORE/Tracker.scala` are the sources of all three runtimes: `project/GenScalaTestJS.scala` lines 126–212 and `project/GenScalaTestNative.scala` line 132 skip none of them, and `EV/Event.scala` holds no runtime-specific marker. Only the `tools` runners differ. A JVM citation for those files therefore covers Scala.js and Native.

## Summary

Verdicts: **P** provided; **L** provided but lost when serialised; **D** derivable by the reader; **A** absent and needing new code at the point of emission. "—" means the need does not arise.

| § | Need | JVM | Scala.js | Native |
|---|---|---|---|---|
| 1 | Nested suite's parent | D | D | D |
| 1 | Scope's parent scope | D | D | D |
| 1 | Test's scope | D | D | D |
| 1 | Message's suite or test | P | P | P |
| 1 | Message's scope | D | D | D |
| 2 | Run an event belongs to | — | — | — |
| 2 | Runtime of a run | — | — | — |
| 2 | Process within a run | — | — | — |
| 2 | Suite identity | P | P | P |
| 2 | Test identity | P | P | P |
| 2 | Scope identity | D | D | D |
| 3 | Order of events within a run | L | L | L |
| 3 | Declared order of a node's children | D | D | D |
| 4 | Test outcome; scope pending | P | P | P |
| 4 | Suite aborted while running | P | P | P |
| 4 | Suite whose constructor throws | P | A | A |
| 4 | Run completed, aborted or stopped | P | A | A |
| 4 | Reasons *not run* and *process failed* | A | A | A |
| 4 | Failure message, throwable class and stack | P | P | P |
| 4 | Cause chain, suppressed, failed-code path, analysis, payload | L | L | L |
| 5 | Test duration; start and end times; thread name | P | P | P |
| 5 | Top-level suite duration | P | D | D |
| 5 | Scope duration | D | D | D |
| 5 | Run duration | P | A | A |
| 6 | Test output, attributed to its node | A | A | A |
| 7 | Suite name, suite ID, class name | P | P | P |
| 7 | Scope's or test's name as written in the source | A | A | A |
| 7 | Scope label; test label once the test closes | P | P | P |
| 7 | Test label before the test closes | A | A | A |
| 7 | Scope, test and message location | P | P | P |
| 7 | Suite's source file | D | D | D |
| 8 | Messages, including those recorded in a test | P | P | P |
| 9 | Expected test count | A | A | A |
| 9 | Config map | P | A | A |
| 9 | Summary counts | P | D | D |
| 9 | ScalaTest version | A | A | A |
| 10 | Top-level suites and tests that never start | A | A | A |

The JVM column is the JVM `Framework`, the path Mill drives. Where `Runner` differs, the evidence says so.

## 1. Structure

**Need.** The test structure: the run node, suites, nested suites under the suite that nests them, scopes and tests (`STR` § Test structure; `RJ` R-002; `ESR` R-010, R-022). Messages attach to the node that emitted them (`STR` § Test structure, Events).

**In memory.**

- No event has a field naming a parent node. A suite event carries `suiteName`, `suiteId` and `suiteClassName` (`EV/Event.scala` lines 776–787). A scope event carries a `message` and a `NameInfo` whose `testName` is `None` (lines 1602–1611; `CORE/Suite.scala` lines 1903–1908). A test event carries the joined `testName` and `testText` (lines 268–281).
- Depth is carried: a scope's `ScopeOpened` and a test's closing event hold an `IndentedText` with `indentationLevel` (`CORE/Suite.scala` lines 1538–1549, 1571–1597, 1907). `TestStarting` holds `MotionToSuppress` and no level (line 1642).
- A message emitted in a scope carries `NameInfo(suite…, testName = None)`; one emitted in a test carries the test's name (`CORE/Suite.scala` lines 1695–1701; `CORE/Engine.scala` lines 276, 282, 289, 416).
- `Engine` emits a scope's events as a bracket on one thread: `ScopeOpened`, the scope's sub-nodes, then `ScopeClosed` or `ScopePending` (`CORE/Engine.scala` lines 385–394).
- A nested suite run without a distributor emits `SuiteStarting` … `SuiteCompleted` on its parent's thread and tracker, inside its parent's bracket (`CORE/Suite.scala` lines 1173–1190, 1221–1225). With a distributor it gets `tracker.nextTracker()` (line 1228), whose ordinals sort between the parent's earlier and later ones (`CORE/Tracker.scala` lines 88–94).

**`toJson`.** Writes `suiteId`, `nameInfo`, `testName`, `testText` and the whole `IndentedText`, level included (`EV/Event.scala` lines 102–113, 153–154, 297, 1623). It writes no parent, and nothing of the ordinal that orders events (`EMC` § 1.1).

**Verdict: derivable by the reader**, from the order of one suite's events and, for nested suites, the position of `SuiteStarting` among the parent's events. It stops being derivable when:

- **The JVM `Framework`'s `SuiteSortingReporter` is upstream.** It is always in the chain (`TOOLS/Framework.scala` lines 678–682, 263, 312). It queues each suite's events by `suiteId` and releases a later slot's events only after the head slot's done event (`TOOLS/SuiteSortingReporter.scala` `doApply`, `handleTestEvents`, `fireReadyEvents`, lines 50, 148 and 167). *Inference:* a nested suite's `SuiteStarting` leaves after its parent's `SuiteCompleted`, so the bracket is gone for every reporter behind it, a `-C` reporter included (`EMC` § 3.2).
- **Suites run concurrently on one tracker.** Every `Framework` task shares one `Tracker` (`TOOLS/Framework.scala` lines 672, 718; `JS/MasterRunner.scala` lines 129, 176), so another top-level suite's events fall inside a parent's bracket (`ADR` § Structure is attached where each event is emitted, not reconstructed after the merge).
- **Two running suites share a `suiteId`.** Scope and test events are grouped by `suiteId` alone.
- **A suite's tests run apart from its scope events.** Under `ParallelTestExecution` each test runs through `DistributedTestRunnerSuite` on a new tracker (`CORE/ParallelTestExecution.scala`, `runTest`; `TOOLS/DistributedTestRunnerSuite.scala`). `TestSortingReporter` puts the events back in order until its timeout (`TOOLS/TestSortingReporter.scala`, `doApply`, `handleSuiteEvent`).
- **An async suite closes a scope early.** `AsyncEngine` reports `ScopeClosed` synchronously after building the scope's chain of statuses, while every test after the first is deferred with `thenRun` (`CORE/AsyncEngine.scala` lines 430–437, 478–484). The order reporters then receive is not established (§ 11).

Scala.js and Native have no sorting reporter and no distributor in the chain: `TaskRunner` passes `SbtReporter` over `SbtLogInfoReporter` in `Args` with distributor `None` (`JS/TaskRunner.scala` lines 141–146; `NAT/TaskRunner.scala` lines 146–151). The bracket holds within one task. Lines of different tasks interleave under sbt (`JSN` § 2.1, § 3.2).

## 2. Identity

**Need.** No node holds another run's events (`ESR` R-022). A channel carries one run (`ESR` R-013), and the consolidation library's caller names its test run label (`ESR` R-052), which tells apart runs on different runtimes (`.docs/ubiquitous-language.md` **Test run label**). No event therefore needs to name its run, its runtime or its process. Nodes that share a name are never merged (`STR` § Test structure, Durations and names).

**In memory and `toJson`.**

- **Run.** The only run field is `ordinal.runStamp`, which is 0 under every `Framework` runner (`EMC` § 5; on Scala.js and Native, `JS/MasterRunner.scala` line 129 and `NAT/MasterRunner.scala` line 131). `Runner` uses a run stamp it is given (`TOOLS/Runner.scala` line 1076). `toJson` writes it. Absent under every `Framework`; not needed.
- **Runtime.** No event field names the runtime (`EV/Event.scala` lines 268–1814, every case class). Absent; not needed.
- **Process within a run.** Scala.js and Native workers each build `new Tracker` (`JS/SlaveRunner.scala` line 115; `NAT/SlaveRunner.scala` line 116), and no event names its process (`JSN` § 1.4). Absent; not needed.
- **Suite.** `suiteId` is on every suite, test, scope and in-suite message event, and `toJson` writes it. **Provided**, unique only while no two suites of a run share a `suiteId`; `SuiteSortingReporter` assumes the same (`TOOLS/SuiteSortingReporter.scala`, `slotMap`).
- **Test.** `suiteId` with `testName`; `Engine` rejects a duplicate test name (`CORE/Engine.scala` lines 668–674). **Provided.**
- **Scope.** No identifier; a scope is known only by its `message`, level and position in its suite's events. **Derivable**, under § 1's conditions.

## 3. Order

**Need.** The order of events within a node is kept (`ESR` R-011). Children appear in declared order, and each message keeps its place among them (`RJ` R-003; `STR` § Human-readable output).

**In memory.** Every event holds an `Ordinal`; `Event.compare` orders by it (`EV/Event.scala` line 80; `EV/Ordinal.scala` lines 172, 232, 242). One tracker gives a total order of one process's events (`CORE/Tracker.scala` lines 64–70). Recorded messages are an ordered `IndexedSeq`, each with its own ordinal and `timeStamp` (`EV/Event.scala` line 358; `CORE/Suite.scala` line 1696).

**`toJson`.** Drops the ordinal's ordering stamps (`EMC` § 1.1) and writes `timeStamp` in milliseconds (`EV/Event.scala` line 297).

**Verdicts.**

- **Order of events: provided but lost when serialised.** A reader of `toJson` has arrival order and millisecond timestamps. Arrival order is emission order only where nothing upstream reorders (§ 1).
- **Declared order: derivable** from the arrival order of a node's children in a sequential run (`.docs/ubiquitous-language.md` **Declared order**). It is not derivable for a concurrent run, for a suite mixing in `RandomTestOrder`, whose registration order no event carries, or for a node that never starts (§ 10).

## 4. Outcome

**Need.** Every test has an outcome; a scope when pending or aborted; a suite when aborted; the run node when aborted or stopped. A suite whose constructor throws is aborted on every runtime. Never-run nodes are aborted with reason *not run*, and a dead process gives *process failed* (`STR` § Test structure, Outcomes; `TD` § Nodes, `outcome`, `reason`). Messages and throwables are exact, with cause chain and suppressed exceptions (`RJ` R-006; `ESR` R-011).

**In memory and `toJson`.**

- **Test and scope outcomes** are the event type, written as `eventType` (`EV/Event.scala` lines 383, 477, 559, 635, 722, 1743). **Provided.**
- **Suite aborted.** `SuiteAborted` carries message and throwable (lines 935–949, 966). The JVM `Framework` turns a throwing constructor into a `DeferredAbortedSuite` and reports it (`TOOLS/Framework.scala` lines 443–458, 334, 358). **Provided** on the JVM.
- **Constructor throws on Scala.js and Native.** `TaskRunner` instantiates the suite before it builds a reporter, outside any `try` (`JS/TaskRunner.scala` lines 79, 141, 149; `NAT/TaskRunner.scala` lines 79–83). No event is created. **Absent.**
- **Run outcome.** The JVM `Framework` sends `RunCompleted` in `done()` and forwards a fork's `RunAborted` and `RunStopped` (`TOOLS/Framework.scala` lines 794, 921–924, 941). Scala.js and Native send no run-level event (`EMC` § 5). **Provided** on the JVM; **absent** on Scala.js and Native.
- **Reasons.** No event has a reason field; `ADR` § An aborted node may carry an explicit reason holds it in the structure, never in an `Event`. **Absent.**
- **Throwable.** The event holds the `Throwable` itself. Of it, `toJson` keeps class, message and stack, which are **provided**, and drops the cause chain and suppressed exceptions, which are **lost** (`EMC` § 1.1). Also lost is a `StackDepthException`'s `failedCodeFilePathname`, which `TD` § Throwables needs as `filePath`.
- **`analysis` and payload.** `toJson` writes neither (`EMC` § 1.1). **Lost.** The baseline keeps neither (`RJ` R-004); `ESR` R-011 covers both.

## 5. Duration

**Need.** A node that ran has a start time, end time and duration; suites and tests a thread name (`RJ` R-004; `TD` § Nodes).

- **Tests, times, thread names.** Closing test events carry `Some(duration)`; every event carries `timeStamp` and `threadName` (`CORE/Suite.scala` lines 1636, 1647, 1671, 1675; `EV/Event.scala` lines 63–70). `toJson` writes all three. `TestIgnored` has no duration, as `TD` § Nodes expects. **Provided.**
- **Top-level suite, JVM.** `Some(duration)` on `SuiteCompleted` and `SuiteAborted` (`TOOLS/Framework.scala` lines 325, 334–337). **Provided.**
- **Top-level suite, Scala.js and Native.** On completion `TaskRunner` passes `Platform.currentTime`, the current epoch time and not an elapsed time, as the duration (`JS/TaskRunner.scala` lines 154–161; `NAT/TaskRunner.scala` lines 159–166). Only the path that catches a throw from `suite.run` subtracts the start time (lines 171, 176). **Derivable** from the `timeStamp`s of `SuiteStarting` and the closing event. Nested suites carry a true duration (`CORE/Suite.scala` lines 1182, 1204).
- **Scope.** No duration field; **derivable** from the `timeStamp`s of `ScopeOpened` and `ScopeClosed`, as `TD` § Nodes computes it.
- **Run.** JVM: `RunCompleted` carries `Some(duration)` (`TOOLS/Framework.scala` lines 790–794). `toJson` does not tell a missing run duration from zero (`EMC` § 1.1). Scala.js and Native compute it only for the `done()` string (`JS/MasterRunner.scala` lines 133–149). **Absent** there as an event.

## 6. Test output

**Need.** Each piece of test output is attributed to the node running when it was written, and keeps its position among that node's events (`ESR` R-010, R-012; `STR` § Test output; `TD` § Nodes, `output`).

- No event type carries output (`EV/Event.scala`), and ScalaTest captures none (`EMC` § 4; the search was re-run for this note).
- **Absent** on every runtime. Capture mechanisms per runtime: `JSN` § 4.

## 7. Names, labels and locations

**Need.** A suite's name, suite ID and location; a scope's or test's name as written in the source, its label, and its location; a suite's source file (`RJ` R-004, R-013; `TD` § Nodes, § Locations, § Source names and a suite's file).

- **Suite name, ID, class.** On every suite event and in `toJson`. **Provided.**
- **Name as written.** `ScopeOpened.message` is the description with its parent's child prefix applied (`CORE/Engine.scala` lines 387–389, 435–439). A test's `testName` is the joined name. Its `testText` is the text the style trait registered, without the parent's child prefix, on `TestStarting` and the closing events (lines 85, 267, 315) but with it on `TestIgnored` (lines 408–410). Whether each style trait registers the source text or text already joined to a verb was not opened; `TD` § Source names says some join it. **Absent** as a field that always holds the source text.
- **Label.** `ScopeOpened` and a test's closing event carry `IndentedText.formattedText`, and `toJson` writes it (`EV/Event.scala` line 109). **Provided.** `TestStarting` carries `MotionToSuppress` (`CORE/Suite.scala` line 1642), so a test that never closes has no label, and its parent's child prefix is in no event. **Absent** before the test closes.
- **Location.** Scopes and tests carry a `LineInFile` with file name, path and line where the style passes a `Position` (`CORE/Engine.scala` lines 589–597, 676–684). Suites carry `TopOfClass` (`TOOLS/Framework.scala` line 310; `JS/TaskRunner.scala` line 144). `toJson` writes each (`EV/Event.scala` lines 115–132). **Provided.**
- **Suite's file.** No suite event carries a file. **Derivable** from the `LineInFile`s of the suite's scope and test events where they share one, which is `TD`'s definition; not for a suite with none or one that never ran.

## 8. Messages

**Need.** `info`, `alert`, `note` and `markup` attach to their node and are never lost or reordered (`STR` § Test structure, Events; `RJ` R-003, R-005).

- Notes and alerts are reported at once; info and markup inside a test arrive late (`EMC` § 5; `CORE/Engine.scala` lines 272–297, 310–315). `toJson` writes `recordedEvents` in full (`EV/Event.scala` line 383). **Provided.**
- A test that never closes never sends its recorded messages. This is the unfinished-node case of § 10.

## 9. Run-level facts

**Need.** The run node holds the ScalaTest version, expected test count, config map and summary counts (`RJ` R-004, R-007; `TD` § The run node). Progress output reports a summary of the whole run (`STR` § Progress output).

- **Expected test count.** The JVM `Framework` sends `RunStarting(…, 0, configMap)` (`TOOLS/Framework.scala` line 687); `Runner` sends the real count (`TOOLS/Runner.scala` line 1235). Scala.js and Native send no `RunStarting` (`EMC` § 5). **Absent** under every `Framework`.
- **Config map.** In the JVM `RunStarting`. Scala.js and Native pass `ConfigMap.empty` to every suite and send no run event (`JS/TaskRunner.scala` line 146). **Provided** on the JVM; **absent** elsewhere.
- **Summary.** JVM: `RunCompleted` carries the `Summary` (`TOOLS/Framework.scala` lines 791–794; `EV/Event.scala` lines 145–151). Scala.js and Native keep the counts for the `done()` string only (`JS/MasterRunner.scala` lines 132–150), counted from event class names (lines 192–213). **Derivable** there by counting event types, given every process's events.
- **ScalaTest version.** No event carries it. **Absent.**
- **Discovery.** Only `Runner` sends `DiscoveryStarting` and `DiscoveryCompleted` (`TOOLS/Runner.scala` lines 1109, 1150); the baseline needs them only "when the run sent them" (`TD` § The run node).

## 10. Nodes that never produce an event

**Need.** Every node the run was to execute that never ran is aborted, *not run*: top-level suites given a task that never started, and tests registered in a constructed suite that never ran. A run that ends with nodes unfinished ends in `RunAborted` (`STR` § Test structure, Outcomes; `ESR` R-021).

- **Top-level suites.** Each runner learns them in `tasks(taskDefs)` and reports nothing (`TOOLS/Framework.scala` lines 750–767; `JS/MasterRunner.scala` lines 166–185). A suite's first event is its `SuiteStarting`, sent when its task executes (`TOOLS/Framework.scala` line 310).
- **Tests and scopes.** `Engine` holds every registered test and scope from construction (`CORE/Engine.scala` lines 82–103, 688–689). A test's first event is its `TestStarting` (line 267).
- **Unfinished nodes at the end.** Nothing compares what started with what finished; the JVM `done()` sends `RunCompleted` unless a fork ended the run (`TOOLS/Framework.scala` lines 793–794).
- **Absent** on every runtime.

## 11. What could not be established

- The order in which reporters receive a serial async suite's `ScopeClosed` relative to the scope's later tests (§ 1). The emission was read; `TestSortingReporter`'s effect on it was not traced, and no run was observed.
- Whether a nested async suite's `SuiteCompleted` can precede its tests' events. `callExecuteOnSuite` reports it when `run` returns, without waiting on the `Status` (`CORE/Suite.scala` lines 1177–1190).
- Whether the *inference* in § 1 about `SuiteSortingReporter` and nested suites holds in a run.
- What each style trait passes `Engine` as `testText` and as a scope's description (§ 7).
- Event order and content for suites not built on `Engine`: a hand-written `Suite`, `RefSpec`, and the JUnit and TestNG wrappers.
- Whether Mill executes a JVM worker's tasks one at a time, which decides whether § 1's shared-tracker condition arises there. `JSN` § 1.3 establishes it for Scala.js and Native only.
- What a `Throwable` holds on Scala.js and Native: stack frames, cause and suppressed exceptions.
- What the progress output needs beyond `STR` § Progress output, which fixes no fields.

## 12. A correction to an earlier note

- `JSN` § 1.4 says no `SuiteSortingReporter` is compiled for Scala.js or Native. The line it cites, `project/GenScalaTestJS.scala` line 129, skips `DispatchReporter.scala` only; neither skip list names `SuiteSortingReporter.scala` or `TestSortingReporter.scala`, and `CORE/AsyncEngine.scala` line 38 imports both. Neither is in `TaskRunner`'s chain.
