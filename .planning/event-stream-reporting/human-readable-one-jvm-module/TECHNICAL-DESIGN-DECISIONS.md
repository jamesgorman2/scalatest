# Decisions: consolidated human-readable output for one JVM module, technical design

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## An event's node is found from the suite instance that sent it, recorded beside the event in the emitter, never through `args.reporter`.

**Alternatives**

- Keying by `suiteId` and test name alone is ambiguous where two distinct instances of one class run at once, as when the class is nested twice under `-P`.
- Unwrapping known reporter chains fails outright for `RandomTestOrder`, which runs each deferred per-test instance with the suite-level `Args` rather than those given to `runTest`, and on an unknown reporter silently falls back to the suite's node.
- A thread-local fails where the distributor runs the test on another thread.
- Carrying the node ID in `Args.configMap` shows it to user code, risking `../SPECIFICATION.md` R-002.
- Tracking a wrapper's open scopes by the order of `ScopeOpened` and `ScopeClosed` cannot tell sibling scopes of one text apart, and holds only while every scope's events arrive in nesting order.

**Reasoning** No reporter chain reliably carries a node's wrapper, since core and user code replace `args.reporter` before a test runs. The suite instance reaches every engine site that sends an event, together with the test name or branch the event is for. The site therefore records them against the event, and the wrapper that hands the event off needs only to name a node table (`TECHNICAL-DESIGN.md` § The test event log on the JVM § An event's node). An instance no core site created registers itself when its run starts.

**Costs** Each site that creates or first runs a suite instance must register it and later remove it, and every engine site that sends an event must record its origin. The origin of an event that reaches no wrapper stays in the emitter's state until the run ends.

**Settled by**

- [How does a test's structure wrapper find its test's node ID?](https://github.com/jamesgorman2/scalatest/issues/69).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## A wrapper tells an event another wrapper has handed to the emitter by the event's identity in a set the emitter keeps until the run's top-level registration is removed, and that registration keeps after.

**Alternatives**

- A thread-local flag, set while a wrapper forwards, breaks where a reporter between wrappers holds events back and releases them later, as ScalaTest's `TestSortingReporter` or a user's buffering reporter does.
- Skipping an event by its `suiteId` and `testName` fails for an event with no test name, such as the `SuiteAborted` of a `DistributedTestRunnerSuite` running one test, and drops an event that bypassed its test's wrapper instead of letting the next wrapper it reaches hand it off.

**Reasoning** `../SPECIFICATION.md` R-002 forbids changing an event, and the reporters between wrappers can hold events back and release them on other threads. An event's identity tells it apart whichever reporter holds it and whichever thread releases it.

**Costs** Each event costs one insert and one remove under the emitter's write lock, and a handed-off event that never reaches its top-level suite's wrapper stays in the emitter's set until that suite's registration is removed, and then in the registration's set while the suite's wrapper is reachable. Where a reporter between wrappers forwards a copy of an event rather than the event, the copy is handed off again and duplicated in the event log.

**Settled by** [How does a test's structure wrapper find its test's node ID?](https://github.com/jamesgorman2/scalatest/issues/69).

---

## Each event of a run's test event log is a JSON object.

**Alternatives** A positional tab-separated format is shorter and simpler to parse, but needs its own escaping rules, specified and tested separately, and is unreadable where a field is absent.

**Reasoning** JSON's escaping is already specified, and the event log then shares the JSON output's encoding (`../JSON-LAYOUT.md` § Encoding). JSON leaves lossless round-tripping of every Java string to core's codec: receivers of a lone surrogate's `\uXXXX` escape behave unpredictably (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2), so core's reader decodes it back to the same `char` itself.

**Costs** Core carries a hand-written JSON reader and writer, and keys repeated on every line lengthen the event log.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## The consolidation library's tests build each run with a test-only builder, not through core's codec.

**Alternatives** Encoding each run with core's codec and feeding the library its decoded events makes every library test depend on the event log's format.

**Reasoning** The library takes decoded events, not an event log (`../decisions/architecture-library-and-modules.md` "The consolidation library takes each run's event stream, not its test event log."), and each side of the codec is tested against the format, so the library's tests need only the events. A drift between builder and decoder still fails a test (`TECHNICAL-DESIGN.md` § Testing § Runs the criteria describe).

**Costs** The builder must yield what the decoder yields, and only those comparisons keep the two in step.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## A worker's stdout and stderr are read as bytes and split into lines before decoding, with the charset Mill's JVM names in `native.encoding`.

**Alternatives** `os.ProcessOutput.Readlines` decodes with the default charset of Mill's JVM and ends a line at a lone `\r`.

**Reasoning** A worker writes in the platform's native encoding, which need not be the default charset of Mill's JVM. Only raw bytes reach the splitter with the worker's encoding and line ends intact (`TECHNICAL-DESIGN.md` § Critical code paths § Capturing a JVM worker's stdout and stderr).

**Costs** Where a module's `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`, its non-ASCII output is mis-decoded.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

**See** `../research/mill-worker-channels.md` § 1.2, § 2.3.

---

## Core's argument splitter keeps its own table of each flag's arity, kept in step with `ArgsParser.parseArgs` by a test.

**Alternatives** Sharing `parseArgs`'s arities means rewriting its inline `if` chain as a table, in the parser every run goes through, emitter off included (`../SPECIFICATION.md` R-001).

**Reasoning** The split needs each flag's arity (`TECHNICAL-DESIGN.md` § Critical code paths § A module's own ScalaTest arguments). A table of the splitter's own leaves the parser every run uses as it is, apart from the one branch that accepts the runner argument.

**Costs** A second arity table, kept in step with `parseArgs` only by that test.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## The test command keeps Mill's `Running Test Class` lines out of captured output by running the worker at log level `Info` or higher.

**Alternatives** Dropping captured lines that match `Running Test Class <name>` also drops a test's own line of the same text.

**Reasoning** At `Info` Mill never writes the line. ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which `../SPECIFICATION.md` R-041 already silences, so nothing else is lost. R-053 does not require the worker to run at the module's own log level.

**Costs** Under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

**Settled by** [Does a run's block show the worker's own output?](https://github.com/jamesgorman2/scalatest/issues/57).

---
