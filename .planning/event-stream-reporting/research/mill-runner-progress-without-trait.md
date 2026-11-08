# Can the test command keep Mill's test-runner progress off the console when the module mixes in no plugin trait?

What was observed when an exclusive command ran a JVM test module's tests by three routes that leave the module unchanged, inside one `execute` on a fail-fast-off copy of its `Execution` with `logger = Task.log`, under a pseudo-terminal on Windows and Linux. Answers [Can the test command hold R-044 on the JVM when the module mixes in no plugin trait?](https://github.com/jamesgorman2/scalatest/issues/48). Observations only; no design is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`: the routes without a trait, `PluginCtx`, the filesystem checker, the missing `Task.dest`.
- `.planning/event-stream-reporting/research/mill-parallel-worker-capture.md`, cited as `MPC`: Route A, Route B and the `log` compile probe.
- `.planning/event-stream-reporting/research/nested-prompt-console.md`, cited as `NPC`: the pseudo-terminal harness, prompt keys and `copy-logger`.
- `.planning/event-stream-reporting/research/mill-nested-keep-going.md`, cited as `MNK`: the fail-fast-off copy and Mill's compatibility policy.

## Short answer

- **Two of the three routes held R-044, R-040, R-042, R-053 with class globs, and R-092, on every terminal and in both modes** (§ 3, § 5, § 6). Neither drew a worker prompt line, a worker ticker or an `N/M completed` line in any of 1 755 snapshots of 54 runs, and none of those strings reached the terminal at all.
  - **Route B driven from outside the module** (`queue`, § 3). The plugin calls `callTestRunnerSubprocess` itself, so no Mill code that sets progress runs. It needs `@experimental` `TestModuleUtil`, `@internal` `internalWorker`, `TestRunnerUtils.globFilter` and `TestResult`, `mill.javalib.api.internal.ZincOp`, and reflection on the protected discovery member.
  - **A plugin `Logger` compiled in package `mill.ntp`**, passed as the `TaskCtx`'s `log` to the `command` route (`command-quiet`) and to Route A from outside the module (`member-quiet`) (§ 5). It implements the `private[mill]` `Logger.prompt` and drops the ticker and every prompt line and detail below its own key. It compiled as a meta-build source, `mill-build/src/mill/ntp/QuietLogger.scala`, with no annotation or flag.
- **The `command` route with Mill's own logger did not hold R-044** (§ 4). In 18 of 18 runs its goal's line showed `N/M completed` and each worker had a line with its current test class, as under Mill's own `testForked`. It held R-040, R-042, R-053 and R-092.
- **`Logger.DummyLogger`, public and unannotated, kept progress off the prompt but broke R-053** (§ 5.3). Its prompt is never coloured, so workers got `colored: false` where Mill's own task passed `true`: in 4 of 4 Linux runs and in a Windows run with `--color true`.
- **Mill's own task, under the same harness,** showed worker lines, tickers and the completion line in 18 of 18 runs, its failed-test list in 6 of 6 failing runs, and ScalaTest's summary and worker output in 18 of 18 (§ 2.2).
- **R-053 variation** (§ 6). On Windows a class ran on two workers in 42 of 56 route runs and 9 of 12 runs of Mill's own task, and worker counts varied, as `MPC` § 5 records. On Linux no class ran twice; one `member-quiet` run started 3 workers in a group against 2.
- **Not run:** macOS; a terminal height other than 40; several modules in one command; a killed worker; the logger in a jar; more than one round per case (§ 9).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[proto]` | this repo, branch `prototype/no-trait-progress`, `.planning/event-stream-reporting/prototype-no-trait-progress/` | `e8f150cf6627e8f8ac1f9701c90bc3191d8698af` |
| `MTW`, `MPC`, `NPC`, `MNK` | the notes above | as committed |

Permalink base: `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/`. Paths are relative to the `[mill]` root. Abbreviations:

- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `EC` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `PFX` is `core/internal/src/mill/internal/PrefixLogger.scala`.
- `LOG` is `core/api/daemon/src/mill/api/daemon/Logger.scala`.
- `TCX` is `core/api/java11/src/mill/api/TaskCtx.scala`.
- `MBR` is `runner/meta/src/mill/meta/MillBuildRootModule.scala`.
- `MAIN` is `runner/daemon/src/mill/daemon/MillMain0.scala`.
- `B` is `[proto]` `build.mill`; `QL` is `[proto]` `mill-build/src/mill/ntp/QuietLogger.scala`; `R/` is `[proto]` `results/`.

A statement marked *inference* follows from the cited source or runs and was not observed directly. A statement marked *general* is Scala or JVM behaviour no source here was opened for. An unmarked statement with no `[mill]` citation was observed in the § 1 runs.

## 1. What was run

- **Fixture,** `jvm.test`: `MPC` § 1's `gamma` module, ten suites in two fork groups of five, `testParallelism` on, run with `-j 3`; each test now sleeps 400 ms so that the prompt has time to draw (`B` lines 47–56). It names nothing of the prototype's (R-092).
- **The command,** `ntp.run --route <route> [--globs '<globs>'] jvm.test`, is exclusive and takes the `Evaluator` (`B` lines 368–415).
  - It builds one goal per module and runs them in one `execute` on `MNK` § 3.1's fail-fast-off copy, with `logger = Task.log` (`B` lines 137–152, 396). This is `NPC` § 6's `copy-logger` route.
  - Each goal captures its run's streams into `out/ntp/modules/<route>/jvm.test/captured/`, keyed by worker label with an index of worker folders (`B` lines 61–106).
  - The command prints one `NTP module=… result=…` line per module and the logger's counters, never the failed tests; failure text goes to `out/ntp/run.dest/results.txt`.
- **Routes** (`B` lines 154–366):

  | Route | How the goal runs the tests | The `TaskCtx`'s `log` |
  |---|---|---|
  | `queue` | Route B (`MPC` § 1) from outside the module: Mill's glob filter and `ZincOp.GetTestTasks` with the globs, the queue folders, one `callTestRunnerSubprocess` per worker on a pool of `jobs` threads, `TestModule.handleResults` (`B` lines 204–318). Discovery from `aheadOfTimeDiscoveredTestClassesIfNeeded` by reflection (`B` lines 159–163) | the goal's own; Route B never passes it to Mill's scheduler |
  | `command` | the public `testForked` or `testOnly(globs*)`, its anonymous tasks and body evaluated by the plugin (`MTW` § 3.4; `B` lines 321–366) | the goal's own |
  | `command-quiet` | as `command` | a `mill.ntp.QuietLogger` over the goal's own (§ 5) |
  | `member-quiet` | Route A from outside the module: `TestModuleUtil` from the module's public tasks and the reflected discovery member, `runTests()` (`MTW` § 3.2; `B` lines 165–202) | a `QuietLogger` |
  | `command-dummy` | as `command` | `Logger.DummyLogger` |

- **Recording,** `NPC` § 1's harness at 160 by 40: ConPTY through `pywinpty` (`conpty-run.py`), Git Bash MinTTY with `--log` (`mintty-run.py`), and Linux `script` under WSL 2 piped to `tsrec.py` (`run.sh`, `run-wsl.ps1`).
  - In 3 MinTTY runs the log ended before the harness's exit line; the exit status was then taken from Mill's final header (`fix-exits.py`). A `sleep 1` after that line stopped it recurring.
- **Matrix** (`cases.tsv`), once per case, on each terminal, under the daemon and `--no-daemon`:
  - passing and with `SPIKE_FAIL` (`G03 t2` fails): Mill's `jvm.test.testForked` and the five routes;
  - with class globs: `jvm.test.testOnly gamma.G0*` and every route but `command-dummy`, with `--globs gamma.G0*`.

  That is 17 cases, 34 runs per terminal, 102 in all; 84 are route runs. The three terminals ran one after another.
- **Colour check.** On Windows, with output redirected to files, `--no-daemon --color true -j 3`: `testForked`, `command-dummy` and `command-quiet` (`R/color-testargs.txt`).
- **Analysis** (`analyse.py`, `tabulate.py`). Each recording was replayed through `pyte` and rendered every 250 ms.
  - In each snapshot, the prompt is the block from the last header row (`<n>/<m>] …`) to the foot of the screen. In it, a row whose key has three or more parts (`139-0-1]`, `1-139-0-1]`) is a worker prompt line; a row naming a test class is a worker ticker; a row holding `<n>/<m> completed` is a completion line.
  - The same patterns were counted over the whole stream with escapes removed, which catches a frame drawn between snapshots.
  - The final rendering, scrollback included, was searched for Mill's failed-test list (`tests failed`, `  gamma.G03 t2`), ScalaTest's summary (`Run completed in`, `Tests: succeeded`, …) and worker output (`SEQ-OUT`, `CHILD-ERR`, the JVM warning, `Running Test Class`, `- t1`, …).
  - Capture (R-042) and the comparison with Mill's own task (R-053) are `MTW`'s `analyse.py` checks, extended to each worker's `testargs` file. A worker's captured PID must also equal the PID of the `worker-info` file in the folder its index line names.
  - Output: `R/summary.txt`, `R/summary.tsv`, `R/tables.txt`, and one `R/timelines/<run>.timeline.tsv` per run.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`, Azul Zulu `21.0.10` fetched by Mill. Windows 11 (`10.0.22631`), MinTTY `3.8.1`, Python `3.14.6`, `pywinpty` `3.1.2`, `pyte` `0.8.2`. Ubuntu 26.04 under WSL 2 (kernel `6.18.54.1`), util-linux `script` `2.41.3`.

## 2. Where the runner's progress comes from

### 2.1 Source

Every piece of progress R-044 names is set through the `TaskCtx` under which `TestModuleUtil` runs:

| Progress | Set by | Through |
|---|---|---|
| Each worker's prompt line | `runTestFuture` calls `Task.fork.async(processFolder, label, "", …)` when `testParallelism` is on and more than one class runs (`TMU` lines 293–307) | `fork.async` wraps `ctx.log` in `PrefixLogger(ctx.log, Seq(key), …)` and runs the body in `logger.withPromptLine` (`EC` lines 109–116, 132). `withPromptLine` calls `prompt.setPromptLine(logKey, …)` (`LOG` lines 27–31). The key is the ctx log's key plus the label (`PFX` line 37) |
| Each worker's ticker, its current test class | `logger.ticker(...)` on the claimed class, then from `poll` every 10 ms (`TMU` lines 329, 341–361, 165–175) | `PrefixLogger.ticker` calls `prompt.setPromptDetail(logKey, s)` (`PFX` line 91); `prompt` is the ctx log's (`PFX` line 93) |
| `N/M completed` | `waitForFutures` every 10 ms (`TMU` lines 597–623, line 620) | `ctx.log.ticker(...)` |

- *Inference:* a ctx whose `log` drops its own `ticker`, and whose `prompt` drops `setPromptLine`, `removePromptLine` and `setPromptDetail` for keys below its own, removes all three while leaving the goal's line, which Mill sets with its own logger.
- Mill's failed-test list is printed for a named task's failure; `MTW` § 2.4 found none for an unnamed goal. ScalaTest's summary is worker stdout (`MPC` § 4).

### 2.2 Mill's own task under the harness

| | ConPTY | MinTTY | Linux |
|---|---|---|---|
| Runs with a worker prompt line, a worker ticker, a completion line | 6, 6, 6 of 6 | 6, 6, 6 of 6 | 6, 6, 6 of 6 |
| Snapshots with a worker line / completion line | 147 / 146 of 207 | 133 / 133 of 220 | 118 / 118 of 154 |
| Failed-test list in the final rendering | 2 of 2 failing runs | 2 of 2 | 2 of 2 |
| ScalaTest summary and worker output in the final rendering | 6 of 6 | 6 of 6 | 6 of 6 |

The prompt read, for example, `139] jvm.test.testForked 3s 6/10 completed, 1 failures.` over `139-0-0] 3s gamma.G05`, `139-1-0] 3s gamma.G08` and `139-1-1] gamma.G09`. A worker's line read `<key>] <n>s <class>` and later `<key>] <n>s <class> <m>s`. After the run: `139] [error] jvm.test.testForked 1 tests failed:` and `  gamma.G03 t2`.

## 3. Route B from outside the module (`queue`)

- **Prompt.** In 18 of 18 runs, the only task lines were the command's `1] ntp.run <n>s` and the goal's `1-139] Task.Anon@…(build_.package_#ntp.queueGoal) <n>s`, with no detail. There was no worker line, ticker or completion line in any of 592 snapshots, and no test class name or `completed` anywhere in the 18 streams.
  - The goal's key is `1-139`: the command's key `1` and the nested task count padded to three digits, as `NPC` § 6 describes.
  - *Inference:* Route B calls neither `fork.async` nor a ticker (`B` lines 265–284), so no line of § 2.1 is set.
- **R-040, R-044's other items.** No failed-test list, ScalaTest summary or worker output line in any final rendering. The failing runs ended `1] [error] ntp.run ntp.run: a module failed`, the prototype's own failure.
- **R-042.** In 18 of 18 runs every worker that started a JVM was captured, 68 in all, each stream's `SEQ` numbers ran 1 to N, and each PID matched its folder's `worker-info` file. Route B makes no capture key for a worker that claims nothing.
- **R-053, with globs.** Classes per group, `testargs`, JVM working directory, arguments and environment, and the set of JUnit XML test cases matched Mill's own task in 18 of 18 runs. That includes the 6 glob runs, where both sides ran `G01`–`G05` and `G06`–`G09` in two groups. The differences were the variation of § 6.
- **Discovery.** On `jvm.test` the reflected member returns `None` (`MTW` § 3.2), so `NTP_DISC=none` (`B` line 160) would pass the same value; it was not run.

## 4. The `command` route with Mill's own logger

- **Prompt.** In 18 of 18 runs, Mill's runner progress was drawn, nested under the goal:
  - the goal's line: `1-139] Task.Anon@…(build_.package_#ntp.graphGoal) 3s 6/10 completed, 1 failures.`;
  - one line per worker: `1-139-0-0] 4s gamma.G05 1s`, `1-139-1-1] gamma.G09`.
  - Snapshots with a worker line, a ticker and a completion line: 402 of 581, each. The first appeared 0.5–3.5 s after recording began; under Mill's own task, also 0.5–3.5 s.
- *Inference:* `fork.async` and both tickers run with the goal's own logger as `ctx.log` (§ 2.1). The `PluginCtx` changes only `dest`, `args` and `fork` (`MTW` § 1).
- **R-040, R-042, R-053, R-092** held as for `queue`: no failed-test list, summary or worker output on the console; 68 workers captured in order; R-053's comparison the same apart from § 6's variation, in all 18 runs, globs included. `fork.async` ran a body for each group's worker `2`, which spawned nothing; the route recorded 40 such labels without bytes, as `MPC` § 4 found.

## 5. A plugin-supplied logger

### 5.1 How it was compiled

- `QuietLogger` and `QuietPrompt` are in package `mill.ntp`, in `mill-build/src/mill/ntp/QuietLogger.scala` (`QL` lines 31–80).
  - `QuietLogger` implements `Logger`, including the `private[mill]` `prompt`, `logKey`, `keySuffix`, `message`, `unprefixedStreams` and `redirectOutToErr` (`LOG` lines 21, 48, 55, 83, 89, 96, 98).
  - `QuietPrompt` implements the `private[mill]` trait `Logger.Prompt` and its `private[mill]` members (`LOG` lines 180–211).
  - `ticker` drops the call (`QL` line 38). `setPromptLine`, `removePromptLine` and `setPromptDetail` drop any key longer than the logger's own that starts with it (`QL` lines 49–62). Everything else is delegated.
- The meta-build compiled it with `build.mill`: the first run printed `compiling 2 Scala sources to out/mill-build/compile.dest/classes ...` and no warning.
  - The root build module's `moduleDir` is `<project>/mill-build` and its `sources` add `super.sources()`, its `src` folder, to the script sources (`MBR` lines 28, 237–239). Mill's examples use `mill-build/src/` for build classes (`example/javalib/module/17-custom-trait/build.mill` line 2).
  - The same class written in `package build`, as `MPC` § 2.2's probe was, fails with "private[mill] method prompt can only be accessed from package mill".
  - *General:* Scala checks `private[mill]` at compile time for code whose package is `mill` or inside it; in bytecode the members are public.
- **Marking.** Nothing marks a meta-build source in a `mill` package: no annotation, flag or warning. `Logger.Prompt` and the members implemented are `private[mill]`, not public API. `core/api/daemon` is MiMa-checked (`MNK` § 5); whether MiMa reports changes to `private[mill]` members was not determined.

### 5.2 `command-quiet` and `member-quiet`

| | `command-quiet` | `member-quiet` |
|---|---|---|
| Runs; snapshots | 18; 584 | 18; 579 |
| Snapshots with a worker line, ticker or completion line | 0 | 0 |
| Test class names / `completed` in the stream | 0 / 0 in 18 runs | 0 / 0 in 18 runs |
| Failed-test list, ScalaTest summary, worker output in the final rendering | 0, 0, 0 | 0, 0, 0 |
| Prompt lines dropped per run | 6 in every run | 6 in every run |
| Completion tickers / worker tickers dropped per run | 449–565 / 1 132–1 428 | 447–587 / 1 082–1 401 |
| Workers captured in order, PID matched | 68 | 70 |
| R-053, globs included | same in 11 runs; § 6's variation in 7 | same in 12; variation in 6 |

- The task lines were the command's and the goal's, `1-139] Task.Anon@…(build_.package_#ntp.graphGoal) <n>s` or `…#ntp.utilGoal`, with no detail.
- The six prompt lines dropped per run are one per `fork.async` body: two groups of up to three workers. `logLines=0`: no line was logged through the worker loggers, since `CaptureFork` sends each worker's process streams to the plugin (`B` lines 93–106).
- The workers' `testargs` matched Mill's, `colored` included: `true` on Linux and, for `command-quiet`, in the Windows `--color true` run; `false` under ConPTY and MinTTY, as for Mill's own task. Mill colours its prompt when the client is interactive or `--color true` is given (`MAIN` lines 170–173).
- *Inference:* the logger relies on `fork.async` keying each worker's logger below `ctx.log`'s key (`EC` line 113, `PFX` line 37), and on both tickers going through `ctx.log` (§ 2.1). Neither is part of a signature. A later Mill that keys workers otherwise would show their lines again without failing to compile.

### 5.3 `Logger.DummyLogger` (`command-dummy`)

- `Logger.DummyLogger` is a public object with a `Prompt.NoOp`, which does nothing and is never coloured (`LOG` lines 106–126, 213–240). It needs no `mill` package.
- No progress, failed-test list, summary or worker output was shown in 12 of 12 runs (393 snapshots), and every worker was captured in order.
- **R-053 broke.** In 4 of 4 Linux runs each worker's `testargs` had `colored: false` where Mill's own task had `true`; with `--color true` on Windows, Mill's task and `command-quiet` passed `true` and `command-dummy` `false`. `callTestRunnerSubprocess` reads `colored` from `Task.log.prompt.colored` (`TMU` line 134). Under ConPTY and MinTTY both were `false`, so those 8 runs matched.

## 6. Against R-053

Each route run was compared with Mill's own task of the same terminal, mode, outcome and kind.

| Compared | Result |
|---|---|
| Exit status | the same in 84 of 84 route runs |
| Classes run per group, as a set | the same in 84 of 84, including 24 glob runs |
| JVM working directory, input arguments, environment, command; on Linux the full command line | the same in 84 of 84, once task-folder paths are replaced and `MILL_PARAMS` set aside (`MTW` § 4), except the folder of a worker present on one side |
| Worker `testargs`, paths replaced | the same except `command-dummy`'s `colored` (§ 5.3) |
| JUnit XML test cases, as a set | the same in 84 of 84; the count differed where a class ran twice |
| Working directory | `<module folder>/<group>/worker-<i>/sandbox`, as under `<Task.dest>` |

Runs in which a class ran on two workers of one group, and worker counts per group:

| Route | Windows: runs with a class run twice | Windows: workers per group | Linux: runs with a class run twice | Linux: workers per group |
|---|---|---|---|---|
| Mill's own task | 9 of 12 | (2, 2) in 8, (2, 1) in 4 | 0 of 6 | (2, 2) in 6 |
| `queue` | 12 of 12 | (2, 2) in 8, (2, 1) in 4 | 0 of 6 | (2, 2) in 6 |
| `command` | 8 of 12 | (2, 2) in 8, (2, 1) in 4 | 0 of 6 | (2, 2) in 6 |
| `command-quiet` | 10 of 12 | (2, 2) in 8, (2, 1) in 4 | 0 of 6 | (2, 2) in 6 |
| `member-quiet` | 7 of 12 | (2, 2) in 9, (2, 1) in 3 | 0 of 6 | (2, 2) in 5, (2, 3) in 1 |
| `command-dummy` | 5 of 8 | (2, 2) in 8 | 0 of 4 | (2, 2) in 4 |

- With one round per case, these counts do not show whether a route changes the rate.
- `G03`, the failing class, never ran twice. Each failing run's goal failure text was `1 tests failed:` and `  gamma.G03 t2`, written to `results.txt`, not the console.

## 7. API marking per route

All routes share the nested evaluation and the capture: `MNK` § 5's fail-fast-off copy (`EvaluatorImpl.execution` by reflection; `mill.exec.Execution` outside the policy), `Evaluator.execute`'s public `logger` parameter (`NPC` § 6), `TaskCtx` implemented in full, `BuildCtx.withFilesystemCheckerDisabled` and `SystemStreamsUtils.withStreams` (public, unmarked; `MTW` § 6). The goal has no `Task.dest`, so each route supplies its own folder (`MTW` § 2.4).

| Route | Mill API beyond the shared set | Marking |
|---|---|---|
| `queue` | `TestModuleUtil` constructor, `prepareTestClassesFolder`, `callTestRunnerSubprocess` | `@experimental` class (`TMU` line 30) |
| | `jvmWorker().internalWorker()`, `InternalJvmWorkerApi.apply` | `@internal` (`libs/javalib/src/mill/javalib/JvmWorkerModule.scala` line 156); `mill.javalib.api.internal` |
| | `ZincOp.GetTestTasks` | `mill.javalib.api.internal` (`libs/javalib/api/src/mill/javalib/api/internal/ZincOp.scala` lines 59–68) |
| | `TestRunnerUtils.globFilter` | `@internal` object (`libs/javalib/testrunner/src/mill/javalib/testrunner/TestRunnerUtils.scala` lines 20, 427) |
| | `TestResult` | `@internal` case class (`libs/javalib/testrunner/src/mill/javalib/testrunner/Model.scala` line 34) |
| | `TestModule.handleResults` | public, unmarked (`TM` lines 749–756) |
| | `aheadOfTimeDiscoveredTestClassesIfNeeded` | `protected def` (`TM` line 138); reflection |
| | `TaskCtx.Fork.Api` in `PluginCtx`'s type | `@experimental` (`TCX` lines 193–196) |
| | relies on | the queue protocol and Mill's scheduling, copied (`MPC` § 6) |
| `command` | as `MTW` § 6 `command`: `testForked`, `testOnly`, `Task.inputs`, `Task.evaluate`, `Task.Named`, `Task.sequence`; `CaptureFork` implementing `TaskCtx.Fork.Api` | public, unmarked; `Fork` `@experimental` |
| `command-quiet` | as `command`, plus `Logger` and `Logger.Prompt` implemented with their `private[mill]` members | `private[mill]` (`LOG` lines 21–98, 180–211); compiled in package `mill.ntp`, which nothing marks |
| | relies on | worker keys below `ctx.log`'s (`EC` line 113, `PFX` line 37), in `mill.exec` and `mill.internal`, outside the policy |
| `member-quiet` | `MPC` § 6 Route A's API from outside the module (`TestModuleUtil` `@experimental`, `internalWorker` and `TestResult` `@internal`), the reflected discovery member, and `command-quiet`'s logger | as listed above |
| `command-dummy` | as `command`, plus `Logger.DummyLogger` | public object, unmarked (`LOG` line 106); breaks R-053 (§ 5.3) |

## 8. Against the earlier notes

- `.planning/event-stream-reporting/decisions/capture-and-console.md`, entry "R-040, R-042 and R-044 hold on every runtime…", finds no route that leaves the module unchanged meeting R-044 on the JVM. Here `queue`, `command-quiet` and `member-quiet` met it under ConPTY, MinTTY and Linux `script`.
- `MPC` § 2.2 found a plugin `log` cannot be built from build code. It can from a meta-build source in a `mill` package (§ 5.1). `.planning/event-stream-reporting/research/mill-command-lifetime.md` § 7's inference that a plugin outside package `mill` cannot implement `Logger` held: the class had to be inside it.
- `MPC` § 6 ran Route B only from a trait mixed into the module. From outside it ran with the reflected discovery member and with class globs (§ 3).
- `MTW` § 8 left a pseudo-terminal unrun. Under one, the `command` route showed the runner's progress (§ 4), as the decision entry above records.

## 9. What was not run or not determined

- macOS; Scala.js and Native; terminal emulators' own drawing, beyond `pyte`'s rendering.
- Terminal heights other than 40, `--ticker false`, and a test module with one class or `testParallelism` off, where `runTests()` runs the worker without `fork.async` (`TMU` line 307).
- Several modules in one command, as the test command runs them; other goals on the same command line.
- A worker that dies or is killed.
- `QuietLogger` from a published jar rather than a meta-build source; whether MiMa covers `private[mill]` members.
- `queue` with `NTP_DISC=none`, with `discoverTestsWithZinc` on, or with framework arguments after `--`.
- Whether `queue`'s pool of `jobs` threads schedules as Mill's shared pool does when other tasks compete.
- More than one round per case, so § 6's duplicate and worker-count rates are not compared.
- Mill versions other than `1.1.10`.
