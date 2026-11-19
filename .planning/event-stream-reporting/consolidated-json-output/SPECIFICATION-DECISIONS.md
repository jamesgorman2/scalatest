# Decisions: consolidated JSON output

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The JSON output's format is documented in `mill-plugin/JSON.md`, linked from `mill-plugin/README.md`, which documents `-fJ <path>`.

**Alternatives**

- Documenting the format in `mill-plugin/README.md` buries the test command's usage under a key-by-key reference that only readers of the file need.
- Pointing readers at `../JSON-LAYOUT.md` fails once the epic closes, since close-out deletes `.planning/`.

**Reasoning** The format's readers are agents that read the file a test command wrote. A separate document beside the README, shipped in the repository with each release, gives them the format for the release that wrote the file, as `../decisions/outputs.md` requires.

**Costs** `mill-plugin/JSON.md` restates the layout, and must change with each release that changes the JSON writer.

**Settled by** [Consolidated JSON output - The JSON output's documentation](https://github.com/jamesgorman2/scalatest/issues/147).

---

## The test command deletes the `-fJ` file once its arguments are accepted and before it resolves its module selectors, and leaves the file untouched when it rejects its arguments.

**Alternatives**

- Deleting the file before the arguments are checked loses the previous test command's JSON output to a mistyped argument, though the test command then does nothing else.
- Deleting the file only just before the first test starts leaves the previous test command's JSON output in place when a module selector fails to resolve, and an agent reading the file takes it for the output of the test command that just failed.
- Emptying the file, which `../SPECIFICATION.md` R-077 also allows, leaves an empty file that a reader cannot parse as JSON and may take for a write that failed part-way.

**Reasoning** The file's readers are agents that started the test command. Once the arguments name the file, the file at that path must be either this test command's JSON output or absent, so a file present after a test command is never an earlier test command's output.

**Costs** A mistyped module selector deletes the previous test command's JSON output, though no test then runs.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---
