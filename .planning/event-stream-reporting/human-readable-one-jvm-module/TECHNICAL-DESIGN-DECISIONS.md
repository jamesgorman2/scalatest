# Decisions: consolidated human-readable output for one JVM module, technical design

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## The consolidation library's tests build each run with a test-only builder, not through core's codec.

**Alternatives**

- Encoding each run with core's codec and feeding the library its decoded items makes every library test depend on the event log's format.

**Reasoning** The criteria state an event log as its items, never as bytes (`../decisions/event-log-contract.md`), so the library's tests need only those items. The criteria of `specification/test-event-log.md` compare what core's codec decodes from real runs with the builder's items, so a drift between builder and decoder in what a criterion fixes still fails a test.

**Costs** The builder must yield what the decoder yields, and only those comparisons keep the two in step.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## A worker's stdout and stderr are read as bytes and split into lines before decoding, with the charset Mill's JVM names in `native.encoding`.

**Alternatives**

- `os.ProcessOutput.Readlines` decodes with the default charset of Mill's JVM and ends a line at a lone `\r`.

**Reasoning** A worker writes in the platform's native encoding, which need not be the default charset of Mill's JVM, and only raw bytes reach the splitter with the worker's encoding and line ends intact (`TECHNICAL-DESIGN.md` § Critical code paths § Capturing a JVM worker's stdout and stderr).

**Costs** Where a module's `forkArgs` set `file.encoding`, `stdout.encoding` or `stderr.encoding`, its non-ASCII output is mis-decoded.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

**See** `../research/mill-worker-channels.md` § 1.2, § 2.3.

---

## Core's argument splitter keeps its own table of each flag's arity, kept in step with `ArgsParser.parseArgs` by a test.

**Alternatives**

- Sharing `parseArgs`'s arities means rewriting its inline `if` chain as a table, in the parser every run goes through, emitter off included (`../SPECIFICATION.md` R-001).

**Reasoning** A flag cannot be told from a value by its leading `-`, so the split needs each flag's arity. A table of the splitter's own leaves the parser every run uses as it is, apart from the one branch that accepts the runner argument.

**Costs** A second arity table, kept in step with `parseArgs` only by that test.

**Settled by** [How does the human-readable output look?](https://github.com/jamesgorman2/scalatest/issues/58).

---

## The test command keeps Mill's `Running Test Class` lines out of captured output by running the worker at log level `Info` or higher.

**Alternatives**

- Dropping captured lines that match `Running Test Class <name>` also drops a test's own line of the same text.

**Reasoning** At `Info` Mill never writes the line. ScalaTest calls the test-interface `Logger` only at `info`, from `org.scalatest.tools.Framework`'s `SbtLogInfoReporter`, which `../SPECIFICATION.md` R-041 already silences, so nothing else is lost. R-053 does not require the worker to run at the module's own log level.

**Costs** Under the test command, Debug-level worker lines from a later Mill release are hidden even where the module sets `Debug`.

**Settled by** [Does a run's block show the worker's own output?](https://github.com/jamesgorman2/scalatest/issues/57).

---
