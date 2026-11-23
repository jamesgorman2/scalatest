# Decisions: class globs and framework arguments

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The test command adds no `Test selector does not match any test` failure to the root where a worker's JVM ends without writing its result and the test command already fails.

**Alternatives**

- Adding the failure after a worker's JVM ends without writing its result, wherever no run of the module reported a test result, as a departure from Mill's `testOnly`.
- Skipping the failure after every worker's JVM that ends without writing its result. A JVM that ends after its run has ended, but before it writes its result, leaves no aborted run or class. Where no run of the module reported a test result, the test command would then succeed where Mill's `testOnly` fails, against `SPECIFICATION.md` R-003.

**Reasoning** A JVM that ends before its run ends leaves the run, or a class it claimed, aborted, so the test command already fails (`../SPECIFICATION.md` R-095a, R-095b, R-137). The class globs did match a test class, so "does not match any test" points the reader at the class globs rather than at the dead JVM. Mill 1.3.1's `testOnly` adds no such failure in this case. Such a worker does not throw. `callTestRunnerSubprocess` gives `Result.Failure("Test reporting Failed: … does not exist")` for its missing `out.json`. The scheduler then gives a `Result.Failure`, which `runTests` returns before its no-match check (`mill.javalib.TestModuleUtil.runTests` in `com.lihaoyi:mill-libs-javalib_3:1.3.1`). Skipping the failure keeps R-007 consistent with a worker that fails to start. The skip applies only where the test command already fails, so it never turns a failing test command into a passing one. R-007 therefore holds only where no worker of the module had its JVM end without writing its result while the test command already fails (`SPECIFICATION.md` R-007).

**Costs**

- R-007 has one more condition.
- Where a JVM ends after its run has ended, but before it writes its result, and no run of the module reported a test result, the root holds `Test selector does not match any test`, whereas Mill's `testOnly` fails with `Test reporting Failed: … does not exist`.
- The skip reads whether the whole test command already fails, not whether the module does. The two agree only while a test command runs one module. Once a test command runs several modules (`../ROADMAP.md` § 5 Many modules in one test command), the skip must read whether the module already fails, which needs a per-module query the consolidation library does not yet have.

**Settled by** [Class globs and framework arguments - Skip the no-test-result failure where a worker's JVM ends without its result](https://github.com/jamesgorman2/scalatest/issues/238).

---

## The test command adds no `Test selector does not match any test` failure to the root where a fork group fails to lay out or a worker fails to start or run.

**Alternatives**

- Adding the failure after a fork group fails to lay out or a worker fails to start or run, wherever no run of the module reported a test result.

**Reasoning** The fork group's or worker's failure on the root already explains the missing test result. The class globs did match a test class, so "does not match any test" misleads. Mill 1.3.1's `testOnly` adds none after either. It raises the failure only once every worker has given a result, and a failure writing a fork group's `test-classes` queue, like a failing worker, throws before then (`mill.javalib.TestModuleUtil.runTests` in `com.lihaoyi:mill-libs-javalib_3:1.3.1`). R-007 therefore holds only where no fork group of the module failed to lay out and no worker of it failed to start or run (`SPECIFICATION.md` R-007).

**Costs** R-007 has one more condition.

**Settled by** [Class globs and framework arguments - Skip the no-test-result failure where a worker failed to start](https://github.com/jamesgorman2/scalatest/issues/237).

---

## A framework `-P` without a thread count fails the test command before any test starts, rather than being passed on.

**Alternatives**

- Passing a bare `-P` on, as Mill's `testOnly` does, and documenting that the test command then fails.

**Reasoning** ScalaTest's sbt `Framework` rejects `-P` without a thread count (`jvm/core/src/main/scala/org/scalatest/tools/Framework.scala`, `Framework.runner`), so passing a bare `-P` on fails the test command either way:

- where the module's `testBatchFrameworkTasks` is off, the test command's own discovery throws, and the test command writes no consolidated output;
- where it is on, every worker fails before a run starts.

Rejecting a bare `-P` instead fails the test command before any test starts, with a message naming it (`SPECIFICATION.md` R-002).

**Costs** The test command departs from Mill's `testOnly` and `testForked`, which pass a bare `-P` on.

**Settled by** [Class globs and framework arguments - Reject a bare -P before any test starts](https://github.com/jamesgorman2/scalatest/issues/236).

---

## A test command clears the module folder only once it has resolved the module, so one that fails on its arguments leaves the folder as it was.

**Alternatives**

- Resolving the module selector before failing on any other argument. The test command would then clear the module folder even where it fails on its framework arguments, so every test command whose module selector names the module would clear it.

**Reasoning** A test command that fails on its arguments fails before any test starts and writes no consolidated output (`SPECIFICATION.md` R-002, R-013), so it should change nothing on disk: it leaves both the file at its `-fJ` path and the module folder as they were (`SPECIFICATION.md` R-012).

**Costs** After such a failure, the module folder can still hold an earlier test command's files. They are never read as a run, because the next test command that resolves the module clears the module folder before it starts any test.

**Settled by** [Class globs and framework arguments - The module folder](https://github.com/jamesgorman2/scalatest/issues/227).

---

## A framework `-P`, `-F`, `-T`, `-S` or `-W` replaces the module's `testArgsDefault` arguments that have the same flag, rather than following them.

**Alternatives**

- Passing both on, as Mill's `testOnly` and `testForked` do:
  - fails every run where the flag is `-F`, `-T` or `-S`: ScalaTest's `ArgsParser.parseDoubleArgument` and `parseLongArgument` throw, for example, `Only one -F can be specified.`;
  - gives the module's argument effect where the flag is `-W`, because the runner keeps the first `-W`;
  - gives a module `-P` holding a thread count effect, because the runner keeps the first `-P` holding one.
- Replacing only `-F`, `-T` and `-S` keeps Mill's behaviour for `-P` and `-W`, but leaves a framework argument that the test command accepts with no effect.

**Reasoning** A user types a framework argument for this test command alone, so it should take effect over the module's standing setting (`SPECIFICATION.md` R-005). For the other flags R-005 lists, ScalaTest takes the later argument or adds to the earlier, so passing both on in order already gives the framework argument effect. Only these five flags need replacing.

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
