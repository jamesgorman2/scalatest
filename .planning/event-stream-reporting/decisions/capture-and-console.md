# Decisions: capture and the console

Decisions behind `../SPECIFICATION.md` on what is captured from each run and how the console is shared with Mill, newest first.

## Progress output is a summary of the number of suites and tests expected to run and their current completion state, redrawn in place (`../SPECIFICATION.md` R-090a, R-090b, R-090c, R-090d, R-090e, R-089).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Test output and worker output are told apart by their source, not by the route or thread of the write that captures them.

**Alternatives** Defining test output by write route and thread makes a test's `println` test output, but not the same line from a pool thread the test uses, or through a C `printf` on Native.

**Reasoning** A reader means by test output what the suite, scope or test wrote, however it was written. Routes and thread rules then decide only which node output is captured against (`../SPECIFICATION.md` R-097b).

**Costs** A C `printf` in a Native test, and the output of concurrent async tests on Scala.js, are test output yet land on a broader node than the one that wrote them.

**Settled by** [What do the capture requirements hold where a write cannot be caught, tied to one node, or kept off the console?](https://github.com/jamesgorman2/scalatest/issues/33).

**See** `../research/js-native-structure-and-capture.md` § 4.2.

---

## `../SPECIFICATION.md` R-040, R-097a and R-044 hold on every runtime, though meeting them needs Mill API that Mill does not keep stable.

**Alternatives**

- Holding only what Mill's stable API allows survives Mill releases, but its one route into a JVM worker's streams, a shim `testFramework`, takes only writes made through `System.out` and `System.err`, so JVM warnings, native code and child processes still write to the console.
- Relaxing R-040, R-097a and R-044 on the runtimes where taking a run's output is hardest leaves that output interleaved on the console, which is the problem this epic addresses.
- Running Mill's JVM test runner under a plugin `TaskCtx` with Mill's own logger takes each worker's streams but still shows that runner's progress (R-044).
- Passing the public `Logger.DummyLogger` as that `TaskCtx`'s `log` keeps that runner's progress off the console, but still evaluates Mill's own JVM test task, which passes the runner argument to Mill's discovery runner (R-072; `architecture-running-tests.md` "The plugin runs a module's test discovery itself…").

**Reasoning** Without them the plugin adds a consolidated report beside the console output it was meant to replace. On the JVM with the module unchanged (R-092), the plugin drives Mill's test queue itself (`architecture-running-tests.md` "The plugin runs a module's test discovery itself…"), so none of Mill's code that draws the runner's progress runs (R-044).

**Costs** The plugin needs Mill API marked `@experimental`, with `@internal` API and reflection on the JVM, and on Scala.js and Native evaluates Mill's test task under a `TaskCtx` Mill did not build; a new Mill release may break it without a compile error and need a code change and a release of the fork. On Native it compiles a launcher for each module (`architecture-running-tests.md` "Every Native test binary starts through a launcher…").

**Settled by**

- [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).
- [Can the plugin take a JVM worker's stdout and stderr when Mill runs a module's tests in parallel workers?](https://github.com/jamesgorman2/scalatest/issues/42).
- [Can the test command hold R-044 on the JVM when the module mixes in no plugin trait?](https://github.com/jamesgorman2/scalatest/issues/48).

**See**

- `../research/mill-worker-channels.md` § 2, § 5.
- `../research/emitter-plugin-spike.md` § 6.
- `../research/mill-parallel-worker-capture.md` § 3–6, § 8.
- `../research/js-native-channels.md` § 5, § 6.
- `../research/native-stdio-wrapper.md` § 3, § 5.
- `../research/mill-test-without-module-trait.md` § 5–6.
- `../research/mill-runner-progress-without-trait.md` § 3–7.

---

## Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output.

**Alternatives** Leaving Mill's list of failed tests, its test progress and ScalaTest's end-of-run summary on the console needs no change to Mill's test task, but each speaks for one run and misleads when several runs are active.

**Reasoning** Progress output and human-readable output report every run of the command at once, so a second per-run account of the same results adds only repetition in another format (`../SPECIFICATION.md` R-044).

**Costs** The test command's result does not list the failed tests, so a tool reading Mill's failure report sees only that the test command failed. A tool that reads Mill's JUnit XML report finds none written by the test command, at Mill's usual path or in the module folder (`../SPECIFICATION.md` R-047).

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---

## What a run's processes write to stdout and stderr is captured and shown with the run's results.

**Alternatives**

- Streaming it to the console shows it as it is written, but it interleaves across runs, which is the problem this epic addresses.
- Writing it to a file for each run keeps the consolidated output to what came in the test event log, but separates a crash message from the run it explains.
- Discarding it loses the only account of some runs that did not complete.

**Reasoning** A JVM or native-code message is often the only explanation of a run that did not complete, and the run's results are where a reader looks for it. Capturing everything also means no run prints for itself, so nothing interleaves.

**Costs**

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---
