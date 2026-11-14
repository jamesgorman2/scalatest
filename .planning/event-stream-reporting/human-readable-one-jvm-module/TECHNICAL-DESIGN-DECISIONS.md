# Decisions: consolidated human-readable output for one JVM module, technical design

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## A registration takes a suite's `suiteId` and `suiteName` from the first reads 3.2.20 makes of them in the run, and the suite's `structure` is written where the last of them is read.

**Alternatives**

- Reading `suiteId` and `suiteName` where the instance registers calls overridable members, which user code can make throw or depend on state, at a point where 3.2.20 does not call them (`../SPECIFICATION.md` R-002).
- Writing the `structure` at the first engine site of the run, `runImpl`, needs both before 3.2.20 reads either, since its first `suiteName` read is `Suite.run`'s thread rename and its first `suiteId` read can be `Suite.runTests`' filter.

**Reasoning** 3.2.20 reads `suiteName` before the instance's first scope or test event, in a `SuiteStarting`, `Suite.run`'s thread rename or a send helper, and reads `suiteId` there too: in `Framework`'s task for a top-level suite, in the `SuiteStarting` `runNestedSuites` or the distributor's `SuiteRunner` builds, in `Suite.runTests`' filter, or as a send helper builds an event. Under `Runner`, a top-level suite's wrapper reads it first, in the suite's structure. Taking each there calls nothing 3.2.20 and the wrapper do not, and still writes the `structure` before the node's scope and test events.

**Costs** Each core site that makes such a read must pass the value to the registration, and send sites must build an origin against a registration whose node path is not yet known. Where an event is handed off before both reads, the emitter reads the missing value itself.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A wrapper tells an event the emitter already has by the event's identity in a set, kept by the emitter until the top-level suite's run completes and by that suite's wrapper after.

**Alternatives**

- A thread-local flag, set while a wrapper forwards, breaks where a reporter between wrappers holds events back and releases them later, as ScalaTest's `TestSortingReporter` or a user's buffering reporter does.
- Skipping an event by its `suiteId` and `testName` fails for an event with no test name, such as the `SuiteAborted` of a `DistributedTestRunnerSuite` running one test, and drops an event that bypassed its test's wrapper instead of letting the next wrapper it reaches hand it off.

**Reasoning** `../SPECIFICATION.md` R-002 forbids changing an event, and the reporters between a send site and the wrappers can hold events back and release them on other threads. An event's identity tells it apart whichever reporter holds it and whichever thread releases it.

**Costs** Each event costs one insert and one remove under the emitter's write lock. An event that never reaches its top-level suite's wrapper stays in the emitter's set until that suite's run completes, then in the wrapper's set while the wrapper is reachable. Where a reporter forwards a copy of an event rather than the event, the copy is handed off again and duplicated in the event log.

**Settled by**

- [How does a test's structure wrapper find its test's node ID?](https://github.com/jamesgorman2/scalatest/issues/69).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## Each event of a run's test event log is a JSON object.

**Alternatives** A positional tab-separated format is shorter and simpler to parse, but needs its own escaping rules, specified and tested separately, and is unreadable where a field is absent.

**Reasoning** JSON's escaping is already specified, and the event log then shares the JSON output's encoding (`../JSON-LAYOUT.md` § Encoding). JSON leaves lossless round-tripping of every Java string to core's codec: receivers of a lone surrogate's `\uXXXX` escape behave unpredictably (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2), so core's reader decodes it back to the same `char` itself.

**Costs** Core carries a hand-written JSON reader and writer, and keys repeated on every line lengthen the event log.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## The consolidation library's tests build each run with a test-only builder, not through core's codec.

**Alternatives** Encoding each run with core's codec and feeding the library its decoded events makes every library test depend on the event log's format.

**Reasoning** The library takes decoded events, not an event log (`../decisions/architecture-library-and-modules.md` "The consolidation library takes each run's event stream, not its test event log."), and each side of the codec is tested against the format, so the library's tests need only the events. A drift between builder and decoder still fails a test (`technical-design/testing.md` § Testing § Runs the criteria describe).

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

**Costs** A second arity table, kept in step with `parseArgs` only by that test.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## The test command keeps Mill's `Running Test Class` lines out of captured output by running the worker at log level `Info` or higher.

**Alternatives** Dropping captured lines that match `Running Test Class <name>` also drops a test's own line of the same text.

**Reasoning** At `Info` Mill never writes the line. ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which `../SPECIFICATION.md` R-041 already silences, so nothing else is lost. R-053 does not require the worker to run at the module's own log level.

**Costs** Under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

**Settled by** [Does a run's block show the worker's own output?](https://github.com/jamesgorman2/scalatest/issues/57).

---
