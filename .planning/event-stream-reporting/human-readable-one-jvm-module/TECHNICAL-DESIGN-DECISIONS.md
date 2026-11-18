# Decisions: consolidated human-readable output for one JVM module, technical design

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## A registration takes a suite's `suiteId` and `suiteName` from the first reads 3.2.20 makes of them in the run.

**Alternatives**

- Reading `suiteId` and `suiteName` where the instance registers calls overridable members, which user code can make throw or depend on state, at a point where 3.2.20 does not call them, which can change the test-interface events the build tool receives (`../SPECIFICATION.md` R-051).
- Reading both at the first engine site of the run, `runImpl`, to write the `structure` there, reads them before 3.2.20 reads either: 3.2.20's first `suiteName` read is `Suite.run`'s thread rename, and its first `suiteId` read can be `Suite.runTests`' filter.

**Reasoning** 3.2.20 reads both before the instance's first scope or test event:

- `suiteName` in a `SuiteStarting`, `Suite.run`'s thread rename or a send helper;
- `suiteId` in `Framework`'s task for a top-level suite, in the `SuiteStarting` that `runNestedSuites` or the distributor's `SuiteRunner` builds, in `Suite.runTests`' filter, or as a send helper builds an event.

Under `Runner`, a top-level suite's wrapper reads `suiteId` first, in the suite's structure. Taking each value at that first read calls nothing that 3.2.20 and the wrapper do not already call. The `structure`, written where the last of the two is read, still precedes the node's scope and test events.

**Costs** Each core site that makes such a read must pass the value to the registration, and send sites must build an origin against a registration whose node path is not yet known. Where an event is handed off before both reads, the emitter reads the missing value itself.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A wrapper tells an event the emitter already has by the event's identity in a set, kept by the emitter until the top-level suite's run completes and by that suite's wrapper after.

**Alternatives**

- A thread-local flag, set while a wrapper forwards, breaks where a reporter between wrappers withholds events and releases them later, as ScalaTest's `TestSortingReporter` or a user's buffering reporter does.
- Skipping an event by its `suiteId` and `testName` fails for an event with no test name, such as the `SuiteAborted` of a `DistributedTestRunnerSuite` running one test, and drops an event that bypassed its test's wrapper instead of letting the next wrapper it reaches hand it off.

**Reasoning** The structure wrapper changes no event (`../ARCHITECTURE.md` § Modules § ScalaTest core, Structure wrapper), and the reporters between a send site and the wrappers can withhold events and release them on other threads. An event's identity tells it apart whichever reporter withholds it and whichever thread releases it.

**Costs** Each event costs one insert and one remove under the emitter's write lock, and an event that never reaches its top-level suite's wrapper stays in a set, the emitter's and then the wrapper's, until the wrapper is unreachable. Where a reporter forwards a copy of an event rather than the event, the copy is handed off again and duplicated in the event log.

**Settled by**

- [How does a test's structure wrapper find its test's node ID?](https://github.com/jamesgorman2/scalatest/issues/69).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A suite run through `ConfigMapWrapperSuite` has the structure its class registers with an empty config map until the instance that runs registers another.

**Alternatives**

- Keeping the empty config map's structure for the whole run leaves a test only the run's config map registers without a node.
- Constructing the running instance from the run's config map before `SuiteStarting` moves the user's constructor earlier than 3.2.20: one that throws given that config map then throws before `SuiteStarting`, where 3.2.20 reports `SuiteStarting` and then `SuiteAborted`, so the build tool would no longer receive the test-interface events 3.2.20 sends (`../SPECIFICATION.md` R-051).
- Constructing a second instance from the run's config map, only for its structure, runs the user's constructor once more than 3.2.20 does, before `SuiteStarting`.
- A structure of the suite's test names alone, as for a suite with no registration tree, drops a wrapped style's scopes and labels, against `specification/test-event-log-structure.md` AC-103's Rule.

**Reasoning** 3.2.20 already constructs the class with an empty config map before `SuiteStarting`, as `ConfigMapWrapperSuite`'s `wrappedSuite`: `Framework`'s task reads its `suiteId`, `runSuite` its `suiteName` for `SuiteStarting`, and `Runner` its `expectedTestCount` for `RunStarting`. Reading what that instance registers runs no user code earlier than 3.2.20 does, so the test-interface events the build tool receives do not change (`../SPECIFICATION.md` R-051). The instance that runs is built after `SuiteStarting`, as in 3.2.20, and where it registers a different tree a further `structure` follows the first (`specification/test-event-log-structure.md` AC-131).

**Costs** Where a suite's tests depend on its config map, the event log first contains a structure that disagrees with what runs: a test only the empty config map registers is shown as not run (`specification/runs-and-test-structure-structures.md` AC-146), and so fails the test command (`specification/test-command.md` AC-182). A run that dies between the suite's `SuiteStarting` and the further `structure` leaves the empty config map's structure as the only one shown.

**Settled by**

- [Which instance supplies a ConfigMapWrapperSuite-wrapped suite's structure, and which class name does it carry?](https://github.com/jamesgorman2/scalatest/issues/70).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## Each event of a run's test event log is a JSON object.

**Alternatives** A positional tab-separated format is shorter and simpler to parse, but needs its own escaping rules, specified and tested separately, and is unreadable where a field is absent.

**Reasoning** JSON's escaping is already specified, and the event log then shares the JSON output's encoding (`../JSON-LAYOUT.md` § Encoding). JSON leaves lossless round-tripping of every Java string to core's codec: receivers of a lone surrogate's `\uXXXX` escape behave unpredictably (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2), so core's reader decodes it back to the same `char` itself.

**Costs** Core carries a hand-written JSON reader and writer, and keys repeated on every line lengthen the event log.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## The consolidation library's tests build each run with a test-only builder, not through core's codec.

**Alternatives** Encoding each run with core's codec and feeding the library its decoded events makes every library test depend on the event log's format.

**Reasoning** The library takes decoded events (`../decisions/architecture-library-and-modules.md` "The consolidation library takes each run's event stream…"), and each side of the codec is tested against the format, so the library's tests need only the events. A drift between builder and decoder still fails a test (`technical-design/testing.md` § Testing § Runs the criteria describe).

**Costs** The builder must yield what the decoder yields, and only those comparisons keep the two in step.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## A worker's stdout and stderr are read as bytes and split into lines before decoding, with the charset Mill's JVM names in `native.encoding`.

**Alternatives** `os.ProcessOutput.Readlines` decodes with the default charset of Mill's JVM and ends a line at a lone `\r`.

**Reasoning** A worker writes in the platform's native encoding, which need not be the default charset of Mill's JVM. Only raw bytes reach the splitter with the worker's encoding and line ends intact (`technical-design/test-command.md` § Critical code paths § Capturing a JVM worker's stdout and stderr).

**Costs** Where a module's `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`, its non-ASCII output is mis-decoded.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

**See** `../research/mill-worker-channels.md` § 1.2, § 2.3.

---

## Core's argument splitter keeps its own table of each flag's arity, kept in step with `ArgsParser.parseArgs` by a test.

**Alternatives** Sharing `parseArgs`'s arities means rewriting its inline `if` chain as a table, in the parser every run goes through, emitter off included (`../SPECIFICATION.md` R-001).

**Reasoning** The split needs each flag's arity (`technical-design/test-command.md` § Critical code paths § A module's own ScalaTest arguments). A table of the splitter's own leaves the parser every run uses as it is, apart from the one branch that accepts the runner argument.

**Costs** The splitter's table repeats the arities `parseArgs` encodes, and only that test keeps the two in step.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## The test command keeps Mill's `Running Test Class` lines out of captured output by running the worker at log level `Info` or higher.

**Alternatives** Dropping captured lines that match `Running Test Class <name>` also drops a test's own line of the same text.

**Reasoning** At `Info` Mill never writes the line. ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which `../SPECIFICATION.md` R-002 already silences, so nothing else is lost. This feature meets `../SPECIFICATION.md` R-053b only for the worker's working directory and environment (`SPECIFICATION.md` § Epic requirements met), so the worker need not run at the module's own log level.

**Costs** Under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

**Settled by** [Does a run's block show the worker's own output?](https://github.com/jamesgorman2/scalatest/issues/57).

---
