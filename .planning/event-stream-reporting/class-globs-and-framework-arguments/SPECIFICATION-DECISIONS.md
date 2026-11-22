# Decisions: class globs and framework arguments

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## A framework `-P`, `-F`, `-T`, `-S` or `-W` replaces the module's `testArgsDefault` arguments with the same flag, rather than following them.

**Alternatives**

- Passing both on, as Mill's `testOnly` and `testForked` do, fails every run where the flag is `-F`, `-T` or `-S`: ScalaTest's `ArgsParser.parseDoubleArgument` and `parseLongArgument` throw `Only one -F can be specified.` Where the flag is `-P` or `-W`, the runner keeps the first, so the module's argument takes effect.
- Replacing only `-F`, `-T` and `-S` keeps Mill's behaviour for `-P` and `-W`, but leaves a framework argument that the test command accepts with no effect.

**Reasoning** A user types a framework argument for this test command alone, so it takes effect over the module's standing setting for every flag (`SPECIFICATION.md` R-005). For every other listed flag, ScalaTest takes the later argument or adds to the earlier, so passing both on in order already gives the framework argument effect.

**Costs** For these five flags the test command departs from Mill's `testOnly` and `testForked`: a command line that fails or keeps the module's setting under Mill takes the framework argument's setting under the test command.

**Settled by** [Class globs and framework arguments - Framework arguments in every run](https://github.com/jamesgorman2/scalatest/issues/224).

---

## A framework argument that is a ScalaTest flag that `../SPECIFICATION.md` R-125a does not list fails the test command before any test starts, rather than being suppressed.

**Alternatives**

- Suppressing such a flag, as `../SPECIFICATION.md` R-142 suppresses it in a module's `testArgsDefault`, keeps one rule for both sources and lets a `testOnly` command line with reporter flags carry over, but silently ignores a flag the user typed, against `../SPECIFICATION.md` R-125b.
- Passing such a flag on to ScalaTest's runner conflicts with the consolidation: `-o` and `-f` add reporters beside it, and `-s` selects suites beside the class globs.

**Reasoning** A flag in a module's `testArgsDefault` is a standing setting that also serves Mill's own test tasks, while a flag the user types after the test command's first `--` is meant for this test command alone. Failing, and naming the flag, tells the user before any test runs that the flag has no effect under the test command (`SPECIFICATION.md` R-002).

**Costs** A `testOnly … -- -oD` command line does not carry over unchanged: its unlisted ScalaTest flags must be removed first.

**Settled by** [Class globs and framework arguments - Unlisted ScalaTest flags as framework arguments](https://github.com/jamesgorman2/scalatest/issues/219).

---

## Where class globs are given, the test command fails as Mill's `testOnly` fails for want of a matching test, even though no test, suite or run failed.

**Alternatives**

- Failing only where a test, suite or run failed lets a mistyped class glob pass with no test run.
- Failing only where no test class matches, before any test starts, lets class globs and framework arguments that together select no test pass where `testOnly` fails.

**Reasoning** `../SPECIFICATION.md` R-053a has the test command run a module as `testOnly` runs it, so a user who replaces `testOnly` with the test command expects the same command lines to pass and fail. Mill 1.3.1's `testOnly` fails both before any worker starts, where no discovered class matches, and after its workers end, where they reported no result (`mill.javalib.TestModuleUtil.runTests` in `com.lihaoyi:mill-libs-javalib_3:1.3.1`). The test command attaches each of these failures to the root of the consolidated output, as a failure of the test command (`SPECIFICATION.md` R-006, R-007).

**Costs** A test command whose tag or test-name filters select no test of the matched classes fails, as under `testOnly`. Applying one set of class globs alike to several modules (`../SPECIFICATION.md` R-055) can fail the test command for each module in which the globs match nothing.

**Settled by** [Class globs and framework arguments - Failing when class globs select no test](https://github.com/jamesgorman2/scalatest/issues/216).

**See** `../decisions/architecture-library-and-modules.md` "The consolidation library, not Mill's per-worker results, tells the test command whether a test result reached a module."

---

## The test command takes class globs and framework arguments after its first `--`, in the form Mill's `testOnly` takes its own arguments.

**Alternatives**

- Taking ScalaTest arguments among the test command's own arguments, as `../SPECIFICATION.md` R-125a could be read, and class globs after `--` puts framework arguments where `testOnly` never takes them, so no `testOnly` command line with framework arguments carries over unchanged.
- Class globs as further positional arguments cannot be told apart from module selectors: `samples.Alpha1` and `core.test` have the same form.

**Reasoning** `../SPECIFICATION.md` R-055 has the test command take class globs and framework arguments as `testOnly` takes them, while the test command's own positional arguments are module selectors, so a separator must end the module selectors. After the first `--`, the arguments take the form that `mill <module>.testOnly` takes. Mill passes `--` to the command as an ordinary argument (`com.lihaoyi:mainargs_3:0.7.8`, `mainargs.TokenGrouping`).

**Costs** Framework arguments without class globs need two separators, as in `-- -- -n samples.Fast`. A ScalaTest argument written among the test command's own arguments fails the test command.

**Settled by** [Class globs and framework arguments - Where class globs and framework arguments go](https://github.com/jamesgorman2/scalatest/issues/215).

---
