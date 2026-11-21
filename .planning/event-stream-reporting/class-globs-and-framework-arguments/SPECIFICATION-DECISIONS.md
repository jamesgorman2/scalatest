# Decisions: class globs and framework arguments

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## Where class globs are given, the test command fails as Mill's `testOnly` fails for want of a matching test, even though no test, suite or run failed.

**Alternatives**

- Failing only where a test, suite or run failed, as the consolidation decides every other failure of the test command (`../ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, **Read the consolidation state**), lets a mistyped class glob pass with no test run.
- Failing only where no test class matches, before any test starts, lets class globs and framework arguments that together select no test pass where `testOnly` fails.

**Reasoning** `../SPECIFICATION.md` R-053a has the test command run a module as `testOnly` runs it, so a user who replaces `testOnly` with the test command expects the same command lines to pass and fail. Mill 1.3.1's `testOnly` fails both before any worker starts, where no discovered class matches, and after its workers end, where they reported no result (`mill.javalib.TestModuleUtil.runTests` in `com.lihaoyi:mill-libs-javalib_3:1.3.1`). Each failure is attached to the root, so the consolidation state still decides the result (`SPECIFICATION.md` R-006, R-007).

**Costs** A build whose tag or test-name filters select no test of the matched classes fails, as under `testOnly`, and applying one set of class globs alike to several modules (`../SPECIFICATION.md` R-055) can fail on every module in which the globs match nothing. The test command finds that no test result was reported from the consolidated output, not from Mill's results, so where ScalaTest's `SuiteSortingReporter` drops a helper suite's events before they reach Mill, it can pass where `testOnly` fails.

**Settled by** [Plan: class globs and framework arguments](https://github.com/jamesgorman2/scalatest/issues/214).

---

## The test command takes class globs and framework arguments after its first `--`, in the form Mill's `testOnly` takes its own arguments.

**Alternatives**

- Taking ScalaTest arguments among the test command's own arguments, as `../SPECIFICATION.md` R-125a could be read, and class globs after `--`, puts framework arguments where `testOnly` never takes them, so a `testOnly` command line does not carry over unchanged.
- Class globs as further positional arguments cannot be told apart from module selectors: `samples.Alpha1` and `core.test` have the same form.

**Reasoning** `../SPECIFICATION.md` R-055 has the test command take class globs and framework arguments as `testOnly` takes them, while the test command's own positional arguments are module selectors, so a separator must end them. After the first `--`, the arguments are exactly what `mill <module>.testOnly` would take. Mill passes `--` to the command as an ordinary argument (`com.lihaoyi:mainargs_3:0.7.8`, `mainargs.TokenGrouping`).

**Costs** Framework arguments without class globs need two separators, as in `-- -- -n samples.Fast`. A ScalaTest argument written among the test command's own arguments fails the test command.

**Settled by** [Plan: class globs and framework arguments](https://github.com/jamesgorman2/scalatest/issues/214).

---
