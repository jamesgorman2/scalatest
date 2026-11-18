# Consolidated JSON output

Scale: feature.

## Summary

The test command shows its consolidated output only as human-readable text on its stdout, which an agent must parse back into a test structure. This feature adds `-fJ <path>`, which writes the whole node tree as JSON to the file at that path once every run has ended, for one JVM test module run in one worker.

## Plan

[Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140)

## Spec documents

- [specification/test-command.md](specification/test-command.md) — acceptance criteria for the `-fJ` argument: which arguments the test command accepts and rejects, when it deletes and writes the file, and its result.
- [specification/json-output.md](specification/json-output.md) — acceptance criteria for what the JSON output holds: the root, module and run, suites, scopes and tests, their order, messages and captured output, throwables, encoding, the library without Mill, and the format's documentation.
- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this specification.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this specification are the epic's.
- [../JSON-LAYOUT.md](../JSON-LAYOUT.md) — the layout of the JSON output, which this feature writes.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture, including the consolidation library's operations (§ APIs § The Mill plugin and the consolidation library).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- The test command's output argument `-fJ`, whose next argument is the path of the file the JSON output is written to. `-fJ` may stand beside `-oH`, a bare `-o` or no `-o`, which keep the meaning they have without it.
- The test command takes at most one `-f`, and only as `-fJ`. It rejects every other `-f` before any test starts, as it rejects an output argument it does not take.
- Once its arguments are accepted, and before it resolves its module selectors, the test command deletes any file at the path. It writes the JSON output to the path once every run has ended. A failure to delete or write the file fails the test command.
- The consolidation library's JSON output, laid out as `../JSON-LAYOUT.md` gives, written with or without Mill. Under the test command it holds the root, with the release of this fork, Mill's name and version, and the module selector with its module; the one module; the module's one run node; and every suite, scope and test of the run, with their messages, throwables and locations, and the output captured against the run and the root.
- The test command times the root and the module.
- `mill-plugin/JSON.md`, which documents the JSON output's format for the agents that read it, linked from `mill-plugin/README.md`.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- More than one run in a module, and the order of a module's run nodes (§ 3).
- More than one module, and a module's outcome (§ 5).
- An unsupported Mill release, and the root recording whether the running Mill release is supported (§ 6).
- A module's outcome reasons under `--halt-on-failure` (§ 5, § 7).
- Runs that do not finish, suites scheduled to run that have no events, and processes that end before any run exists in them (§ 8).
- Output captured against a suite, scope or test, and captured output whose stream is not known (§ 10).
- A run that has no run node, and every runtime other than the JVM (§ 11–15).
- `-fH`, `-fP`, more than one `-f`, and formatting and filtering characters on an `-f` (§ 9, § 16, § 18).

## Epic requirements met

This feature meets each requirement below for JSON output, for one module selector, one JVM test module and one worker, and with the consolidation library called directly. Where a requirement carries a qualifier, this feature meets only the part the qualifier names.

- R-028 (with no filtering characters), R-084, R-020 (human-readable output on stdout and JSON output in one file).
- R-096a (the root, one module, one forked run, and the run's suites, scopes and tests), R-104, R-105, R-130a, R-130b, R-130c, R-130d, R-130e, R-130f.
- R-097a (`info`, `alert`, `note` and `markup` messages, and the stdout and stderr captured from the run's process and given for the root), R-097b (as the human-readable output attaches them), R-043, R-011, R-063.
- R-098a, R-098b, R-098c, R-098d, R-098f, R-095a (suites, scopes and tests recorded in a structure event, in runs that finish), R-095b (runs that finish), R-103, R-074a.
- R-055 (one file receiving JSON output), R-049a (`-fJ`), R-077 and R-059 (the `-fJ` file), R-027 (JSON output).
- R-113 (JSON output).

## Acceptance criteria

The criteria are in the documents listed in § Spec documents. Each Feature states in its own Background, description or steps the module, run or fixture its scenarios need. The definitions below give a reader the terms and notation the criteria share:

- The module is a JVM test module at module path `core.test`, whose ScalaTest dependency is `com.edc::scalatest` at the release under test. Its build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill::<release>` of the `build.mill` header's `mvnDeps`, without changing the module's definition. Every JVM test module of a criterion's build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset. A criterion's test command is invoked as `mill scalatest/test core.test`, followed by the criterion's arguments, unless the criterion gives another invocation.
- `<report>` is an absolute path, in a folder that exists, at which nothing exists unless the criterion says otherwise. `<other>` is a second such path.
- *The JSON output* is the JSON value parsed from the file at `<report>`. A criterion gives a JSON value as JSON text, with these placeholders, each standing for one whole JSON value:
  - `<time>`: any integer, an epoch time in milliseconds;
  - `<ms>`: any non-negative integer;
  - `<thread>`: any string;
  - `<release>`: a string, the version of the release of this fork under test, such as `3.2.20`;
  - `<mill>`: a string, the version of the Mill release running the test command;
  - `<line n of F>`: a location object holding `lineNumber` `n` and either `filePath`, a path whose last segment is `F`, or `fileName` `F`, and no other key;
  - `<class C in F>`: a location object holding `className` `C` and either `filePath`, a path whose last segment is `F`, or `fileName` `F`, and no other key;
  - `<frames>`: a non-empty array of stack frames.
- A JSON object given *in full* has exactly the keys written, in that order. An object that *holds* keys has those keys with those values, in that order, and may have others. A JSON value given for a node, message or item that a criterion names by its `kind` and `name` or `label` is the one such value in the JSON output.
- *A run fed directly to the library* is a run given to the consolidation library without Mill, as its test event log's events, in the order the criterion lists them, and as the lines captured from its process's stdout and stderr, each with its line terminator. Unless a criterion says otherwise, the library is given module `core.test` for the run, the module selector `core.test` resolving to `core.test`, no build tool, and no start or end events for the root or the module. A criterion names an event by its `org.scalatest.events` class, or as `runStarted`, `structure` or `runEnded`, the events this fork adds, with the fields the criterion fixes.
- The console is what the `mill` command writes to its stdout and stderr. *The human-readable output* is the consolidated human-readable output the test command writes there.
- *The run's test event log* is the test event log the module's one worker writes.
