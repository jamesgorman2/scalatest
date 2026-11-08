# Decisions: capture and the console

Decisions behind `../SPECIFICATION.md` on what is captured from each run and how the console is shared with Mill, newest first.

## On the console, progress output is a one-line summary rewritten in place plus whole lines, both sharing Mill's prompt, rather than streamed single characters or output that needs `--ticker false`.

**Alternatives**

- Streaming single characters, as structured test reporting does, needs raw writes, because `Task.log` passes only whole lines; the prompt's redraws overwrite raw writes.
- Pausing Mill's prompt while the tests run, and writing raw, needs the `private[mill]` `withPromptPaused`.
- Requiring Mill's `--ticker false` makes the user pass it on every command, since the plugin cannot set it.
- Whole lines alone give no live sense of a long run's progress.

**Reasoning** `Task.log.ticker` and `Task.log` are public with no annotation: Mill rewrites the ticker's detail in place on the test command's own prompt line, and clears and redraws its prompt around `Task.log` lines, which left no stale prompt. The summary holds through the nested evaluation, the test command's one `execute` call with one goal per selected test module, at any console height and nested-task count only when that evaluation logs through the command's `Task.log`. That logger keys every nested task's prompt line to sort after the command's, and with it the summary was current from half a second in, at 24 and 40 rows and with one nested task, except in single MinTTY snapshots that `pyte` drew garbled. Logged otherwise, nested tasks' lines hid the summary for seconds at 24 rows, and a nested evaluation of fewer than ten tasks took the command's line, against `../SPECIFICATION.md` R-089 and R-091.

**Costs** Progress output streams no single characters; its summary is cut to the console width and gone once the nested evaluation returns, while event streams may still be ending; and Mill has lost a `Task.log` line in some runs under Windows pseudo-consoles, for a cause not found, against `../SPECIFICATION.md` R-091. R-089 rests on prompt ordering in `mill.internal`, which has no compatibility promise, so a Mill change there would break it silently; logging the nested evaluation through `Task.log` also prefixes its tasks' console lines `1-<n>]` instead of `<n>]` (R-050).

**Settled by**

- [How do the consolidated progress output and Mill's own prompt share the console?](https://github.com/jamesgorman2/scalatest/issues/36).
- [Does Task.log.ticker keep the test command's summary, and Task.log its lines, through the nested evaluation?](https://github.com/jamesgorman2/scalatest/issues/47).

**See**

- `../research/mill-command-lifetime.md` § 4.2.
- `../research/mill-nested-keep-going.md` § 2.1, § 3.3.
- `../research/emitter-plugin-spike.md` § 8.2, § 8.4.
- `../research/nested-prompt-console.md` § 2–6.

---

## Test output and worker output are told apart by their source, not by the route or thread of the write that captures them.

**Alternatives** Structured test reporting defines test output by write route and thread (`../../structured-test-reporting/SPECIFICATION.md` § Test output): a test's `println` is test output, but the same line from a pool thread the test uses, or through a C `printf` on Native, is not.

**Reasoning** A reader means by test output what the suite, scope or test wrote, however it was written. Routes and thread rules then decide only which node output is captured against (`../SPECIFICATION.md` R-012).

**Costs** A C `printf` in a Native test, and the output of concurrent async tests on Scala.js, are test output yet land on a broader node than the one that wrote them.

**Settled by** [What do the capture requirements hold where a write cannot be caught, tied to one node, or kept off the console?](https://github.com/jamesgorman2/scalatest/issues/33).

**See** `../../structured-test-reporting/research/js-native-structure-and-capture.md` § 4.2.

---

## Output captured only against the run is shown without claiming its source.

**Alternatives** Labelling run-level output as test output or worker output asserts what no capture route can tell: on Native, the process's descriptors carry a test's C write, a crash message and the code under test's own stdout alike.

**Reasoning** Worker output is never captured against a suite, scope or test, so only output captured against the run is of uncertain source (`../SPECIFICATION.md` R-086, R-087).

**Costs** A JSON reader cannot separate worker output from test output captured only against the run.

**Settled by** [What do the capture requirements hold where a write cannot be caught, tied to one node, or kept off the console?](https://github.com/jamesgorman2/scalatest/issues/33).

---

## `../SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime, though meeting them needs Mill API that Mill does not keep stable.

**Alternatives**

- Holding only what Mill's stable API allows survives Mill releases, but its one route into a JVM worker's streams, a shim `testFramework`, takes only writes made through `System.out` and `System.err`, so JVM warnings, native code and child processes still write to the console.
- Relaxing R-040, R-042 and R-044 on the runtimes where taking a run's output is hardest leaves that output interleaved on the console, which is the problem this epic addresses.
- Supplying a `TaskCtx` to Mill's own JVM test runner takes each worker's streams but, as observed, still shows that runner's progress, so it does not meet R-044.

**Reasoning** Without them the plugin adds a consolidated report beside the console output it was meant to replace.

**Costs** The plugin needs Mill API marked `@experimental`, and either `@internal` API or an evaluation of Mill's own test command that Mill does not document, so it may need a release for each Mill release; on Native it must compile its launcher where an emulator cannot load a script. On the JVM, no route that needs no change to the module (R-092) has been observed to meet R-044, and without that change a run's stdout and stderr have been taken only on Windows and Linux, on Native only on Windows, and never with a killed worker.

**Settled by**

- [May the Mill plugin run a module's tests in place of Mill's test task?](https://github.com/jamesgorman2/scalatest/issues/38).
- [Can the plugin take a JVM worker's stdout and stderr when Mill runs a module's tests in parallel workers?](https://github.com/jamesgorman2/scalatest/issues/42).

**See**

- `../research/mill-worker-channels.md` § 2, § 5.
- `../research/emitter-plugin-spike.md` § 6.
- `../research/mill-parallel-worker-capture.md` § 3–6, § 8.
- `../research/js-native-channels.md` § 5, § 6.
- `../research/native-stdio-wrapper.md` § 3, § 5.
- `../research/mill-test-without-module-trait.md` § 5–6.

---

## Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output.

**Alternatives**

- Leaving Mill's list of failed tests, its test progress and ScalaTest's end-of-run summary on the console needs no change to Mill's test task, but each speaks for one run and misleads when several runs are active.
- Also turning off Mill's JUnit XML report for each module removes the last per-run result, but that report is a file read by tools, not by the person at the console, and the consolidation writes no JUnit XML to take its place (`../SPECIFICATION.md` R-047).

**Reasoning** Progress output and human-readable output report every run of the command at once, so a second per-run account of the same results adds only repetition in another format (`../SPECIFICATION.md` R-044).

**Costs** The test command's result does not list the failed tests (`../SPECIFICATION.md` R-046), so a tool reading Mill's failure report sees only that the test command failed. Mill's XML report omits failures outside tests, so a tool reading it sees less than the console shows.

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---

## What a run's processes write to stdout and stderr is captured and shown with the run's results.

**Alternatives**

- Streaming it to the console, as `../../structured-test-reporting/SPECIFICATION.md` § Test output does with output that is not test output, keeps the two epics alike, but it interleaves across runs, which is the problem this epic addresses.
- Writing it to a file for each run keeps the consolidated output to what came over the channel, but separates a crash message from the run it explains.
- Discarding it loses the only account of some runs that did not complete.

**Reasoning** A JVM or native-code message is often the only explanation of a run that did not complete, and the run's results are where a reader looks for it. Capturing everything also means no run prints for itself, so nothing interleaves.

**Costs** This output arrives apart from the channel, so its place among the run's events is not kept (`../SPECIFICATION.md` R-043). What a reporter the user configured prints appears in the consolidated output as text repeating the events.

**Settled by** [Under the plugin, what happens to each run's own reporter output and raw worker output?](https://github.com/jamesgorman2/scalatest/issues/23).

---
