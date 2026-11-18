# Decisions: consolidated JSON output

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The test command deletes the `-fJ` file once its arguments are accepted and before it resolves its module selectors, and leaves the file untouched when it rejects its arguments.

**Alternatives**

- Deleting the file before the arguments are checked loses the previous test command's JSON output to a mistyped argument, though the test command then does nothing else.
- Deleting the file only just before the first test starts leaves the previous test command's JSON output in place when a module selector fails to resolve, and an agent reading the file takes it for the output of the test command that just failed.
- Emptying the file, which `../SPECIFICATION.md` R-077 also allows, leaves an empty file that a reader cannot parse as JSON and may take for a write that failed part-way.

**Reasoning** The file's readers are agents reading the test command that wrote it (`../JSON-LAYOUT.md` § Compatibility). Once the arguments name the file, the file at that path must be either this test command's JSON output or absent, so a file present after a test command is never an earlier one's.

**Costs** A mistyped module selector deletes the previous test command's JSON output, though no test then runs.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---
