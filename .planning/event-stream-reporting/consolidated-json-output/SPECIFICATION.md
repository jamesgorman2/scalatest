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

- The test command's output argument `-fJ <path>`, which names the file the JSON output is written to. `-fJ` may stand beside `-oH`, a bare `-o` or no `-o`. A relative path resolves against the Mill workspace root, the folder holding the build's `build.mill`, as Mill resolves its own path arguments.
- The rejection, before any test starts, of every `-f` other than one `-fJ`.
- Deleting any file at the path before the module selectors are resolved, and writing the JSON output there once every run has ended, creating any missing parent folder. A failure to delete or write the file fails the test command.
- The consolidation library's JSON output, in the layout `../JSON-LAYOUT.md` gives, with or without Mill, for the root, one module, the module's one run node, and the run's suites, scopes and tests.
- The start and end times of the root and the module, which the test command measures by its own clock.
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

This feature meets each requirement below for JSON output, for one module selector, one JVM test module and one worker, and with the consolidation library called directly. Where a requirement is followed by a parenthesis, this feature meets only the part the parenthesis names.

- R-028 (with no filtering characters), R-084, R-020 (human-readable output on stdout and JSON output in one file).
- R-096a (the root, one module, one forked run, and the run's suites, scopes and tests), R-104, R-105, R-130a, R-130b, R-130c, R-130d, R-130e, R-130f.
- R-097a (`info`, `alert`, `note` and `markup` messages, and the stdout and stderr captured from the run's process and given for the root), R-097b (each piece of output on the node the human-readable output shows it under), R-043, R-011, R-063.
- R-098a, R-098b, R-098c, R-098d, R-098f, R-095a (suites, scopes and tests recorded in a structure event, in runs that finish), R-095b (runs that finish), R-103, R-074a.
- R-055 (one file receiving JSON output), R-049a (`-fJ`), R-077 (the `-fJ` file), R-027 (JSON output).
- R-113 (JSON output).

## Acceptance criteria

The criteria are in the documents listed in § Spec documents. Each Feature states, in its description and Background, the terms, placeholders and fixture its scenarios use.
