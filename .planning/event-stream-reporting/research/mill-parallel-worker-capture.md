# Taking each JVM test worker's stdout and stderr when Mill runs tests in parallel

What was observed when plugin code took each test-worker JVM's stdout and stderr while Mill ran a module's tests in several fork groups with `testParallelism` on. Two routes were run: a `TaskCtx` the plugin supplies to `TestModuleUtil.runTests()`, and plugin code driving `callTestRunnerSubprocess` and the test queue itself. Answers [Can the plugin take a JVM worker's stdout and stderr when Mill runs a module's tests in parallel workers?](https://github.com/jamesgorman2/scalatest/issues/42). Observations only; no design is chosen.

Two notes hold what this one builds on and does not repeat: `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`, and `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`.

## Short answer

- **Route A, a `TaskCtx` the plugin supplies,** can be built. A `TaskCtx` that delegates every member to the task's own except `fork`, whose `async` calls Mill's own `fork.async` and sets `os.Inherit.out` and `os.Inherit.err` inside the body, gave these results on Windows and Linux, under the daemon and `--no-daemon`:
  1. No worker output, including Mill's runner lines, a child process's writes and a JVM warning, reached the console in any of its 24 runs (§ 3).
  2. The plugin received every worker's stdout and stderr, each in order, tied to its worker label and folder (§ 4).
  3. Per fork group, these matched Mill's own `testForked`: the classes run; each worker's JVM arguments, environment and working directory; the exit status and failure message; and, as sets, the task's results and JUnit XML report. The worker count matched except in one Linux run. On Windows, a class sometimes ran on two workers under both, at different rates (§ 5).
- **Route A with a plugin-supplied `log` instead** cannot be built from build code: the `Logger` it would need must define `prompt`, which is `private[mill]` (§ 2.2).
- **Route B, the plugin driving the scheduler,** gave the same results, except that its worker count differed in one Windows run, and under the daemon on Windows a class ran on two workers in 5 of 6 runs against 2 of 6 for `testForked` (§ 3–5). Most of its roughly 100 lines mirror Mill's scheduling and reporting code, and it calls Mill's discovery through an internal API (§ 6).
- **Not run:** a pseudo-terminal, the sequential path, class globs, several modules in one exclusive command, a killed worker, macOS (§ 8).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[spike]` | this repo, branch `prototype/emitter-plugin-spike`, `.planning/event-stream-reporting/prototype-emitter-plugin-spike/` | `639342e85` |

A `[mill]` path `P` is at `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/P`. Abbreviations; `TCX` and `LOG` are new, the rest are as in `MWC`:

- `TM` is `libs/javalib/src/mill/javalib/TestModule.scala`.
- `TMU` is `libs/javalib/src/mill/javalib/TestModuleUtil.scala`.
- `TR/` is `libs/javalib/testrunner/src/mill/javalib/testrunner/`.
- `EC` is `core/exec/src/mill/exec/ExecutionContexts.scala`.
- `TCX` is `core/api/java11/src/mill/api/TaskCtx.scala`.
- `LOG` is `core/api/daemon/src/mill/api/daemon/Logger.scala`.

A statement marked *inference* follows from the code read or the runs and was not observed directly. A statement marked *general* is JVM or operating-system behaviour no source here was opened for.

## 1. What was run

- **The fixture,** `gamma.test`: ten ScalaTest suites, `gamma.G01`–`G10`, of three tests each, in two fork groups of five (`testForkGrouping`), `testParallelism` on, run with `-j 3`. Mill then starts up to three workers per group, two at a time, and the queue shares each group's five classes among two or three workers. `forkArgs` adds `-Xverify:none`, so HotSpot writes `OpenJDK 64-Bit Server VM warning: Options -Xverify:none and -noverify were deprecated …` to descriptor 2 at start-up.
  - Each test writes ten lines to `System.out` and ten to `System.err`, each carrying the JVM's PID and a sequence number per stream (`SEQ-OUT pid=… n=…`, `SEQ-ERR …`).
  - Each suite's first test starts a child process with `ProcessBuilder.inheritIO()` (`cmd.exe /c echo …` on Windows, `/bin/sh -c echo …` on Linux) that writes one line to each inherited descriptor (`CHILD-OUT`, `CHILD-ERR`).
  - Each JVM writes `worker-info-<pid>.txt` in its working directory: `ProcessHandle.current().info()` (command, arguments, command line), `RuntimeMXBean.getInputArguments`, `System.getenv` and `user.dir`.
  - With `SPIKE_FAIL` in the environment, `gamma.G03` `t2` fails.
- **Mill's own run,** `gamma.test.testForked`.
- **Route A,** `gamma.test.routeCtx`, builds the `TestModuleUtil` exactly as `TestModule.testTask` does for `testForked` (TM lines 312–341), but passes a `CaptureCtx` as its `using` argument. It then calls `runTests()` and returns its result.
  - `CaptureCtx` delegates every `TaskCtx` member to the task's own `Task.ctx()` except `fork`, which is a `CaptureFork`.
  - `CaptureFork.async(dest, key, message, priority)(t)` calls the real `fork.async` with the same arguments and the real ctx. The body it passes runs `t(logger)` inside `os.Inherit.out.withValue` and `os.Inherit.err.withValue`, each set to an `os.ProcessOutput.ReadBytes` that fills a buffer for worker `key`. `blocking`, `await` and `awaitAll` delegate.
- **Route B,** `gamma.test.routeQueue`:
  1. gets the classes as `runTests()` does, by sending `ZincOp.GetTestTasks` to `jvmWorker().internalWorker()` (TMU lines 71–95);
  2. lays out the group and worker folders and prepares each queue as `prepareTestGroups`, `jobsProcessLength` and `runTestFuture` do (TMU lines 193–196, 241–288);
  3. runs the workers on a fixed pool of `jobs` threads, submitted in Mill's priority order (TMU lines 299–303); each worker claims its first class as `runTestFuture` does (TMU lines 320–328) and calls `callTestRunnerSubprocess` with `Right((startingClass, queueFolder, claimFolder))` inside its own `os.Inherit.out`/`err.withValue`;
  4. merges results as `TestModuleUtil.processTestResults` does (TMU lines 553–587) and calls `TestModule.handleResults(doneMsg, results, ctx, testReportXml())` (TM lines 749–755).
- **Neither route prints worker output.** Each saves every worker's raw stdout and stderr bytes to `Task.dest/captured/<label>.out` and `.err`, with an index of label, worker folder and thread, and prints one line of its own, `SPIKE-ROUTE …`, with byte counts.
- **The matrix.** `run-capture.ps1` (Windows) and `run-capture.sh` (Linux) run the three commands in turn, passing and with `SPIKE_FAIL`, under the daemon and `--no-daemon`, three rounds each: 36 runs per platform, 12 per command. Each `mill` command's stdout and stderr went to files. After each run the script kept the command's `Task.dest`, its `<command>.json` and its exit status. `analyse-capture.py` makes the checks of §§ 3–5 and writes `summary.txt`. On Linux the matrix ran in a copy of the prototype in the WSL file system, as the `EPS` runs did.
- **A compile probe,** `log-probe/build.mill`: a build-code `Logger` for the `log` variant of Route A (§ 2.2).
- **A trial round** of the Windows matrix ran before the three rounds; its files were overwritten.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`. Every worker JVM was Azul Zulu `21.0.10`, by the command path each recorded.

| Platform | |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | `mill.bat` through `cmd /c` |
| Ubuntu under WSL 2 (kernel `6.18.54.1-microsoft-standard-WSL2`), x86-64 | `./mill` |

## 2. What a plugin-supplied `TaskCtx` meets in the source

### 2.1 What the parallel path takes from the ctx

`TestModuleUtil` takes one `TaskCtx` in its constructor (TMU line 54). `runTestFuture` has no `using` clause of its own, so its `Task.fork.async` and its `callTestRunnerSubprocess` call take that ctx (TMU lines 269–278, 295, 337). On a run like this one, the class's ctx supplies:

| Member | Where |
|---|---|
| `dest` | group and queue folders (TMU lines 243–264); the JUnit XML path (TMU lines 105, 431); the discovery call's context (TMU lines 82–91; `JvmWorkerApi.Ctx` is `TaskCtx.Dest & TaskCtx.Log & TaskCtx.Env`, `libs/javalib/api/src/mill/javalib/api/JvmWorkerApi.scala` line 10) |
| `jobs` | workers per group (TMU line 194) |
| `fork` | `Task.fork.async` per worker (TMU line 295) and `Task.fork.blocking` around the wait (TMU line 231); `Task.fork` is `ctx.fork` (`core/api/src/mill/api/Task.scala` line 131) |
| `log` | `Task.log.prompt.colored` (TMU line 134); the completion ticker (TMU line 620); the sequential path (TMU line 307) |
| `env` | the worker's environment when `propagateEnv` (TMU line 152); the `CI` check in `handleResults` (TMU line 407) |

`fork.async` builds the worker's logger from the ctx it is given (`ctx.log`) and calls `withStreams(logger.streams)` around the body (EC lines 109–140). A `withValue` inside the body therefore wins over it for a spawn made in the body.

### 2.2 Can build code construct or wrap one?

- **`TaskCtx`** is a public, unsealed trait with no Mill annotation; its abstract members are those of § 2.1 plus `args`, `arg`, `workspace`, `offline`, `fail`, `reporter` and `testReporter` (TCX lines 15–35, 81–191). Mill's own implementation, `TaskCtx.Impl`, is `private[mill]` (TCX line 42). `CaptureCtx` implements every member by delegation and compiled without warnings.
- **`TaskCtx.Fork.Api`** is a public trait inside `object Fork`, which is `@experimental` (TCX lines 193–236). `CaptureFork` implements it by delegation and compiled.
- **A plugin-supplied `log`** would not reach the worker's lines: `fork.async` wraps `ctx.log` in a `PrefixLogger` (EC line 113), which sends each complete line to `prompt.logPrefixedLine` (`core/internal/src/mill/internal/PrefixLogger.scala` lines 47–56). The `Logger` would have to supply that `prompt`, which `Logger.Upstream` declares `private[mill]`, of the `private[mill]` type `Logger.Prompt` (LOG lines 89, 180). The probe's `Logger` failed to compile in build code:
  - delegating `def prompt = real.prompt`: "private[mill] method prompt can only be accessed from package mill."
  - without it: "class PluginLogger needs to be abstract, since private[mill] def prompt: mill.api.daemon.Logger.Prompt in trait Upstream in object Logger is not defined".

  *General:* Scala lets code compiled in a package inside `mill` access `private[mill]` members; that was not tried.

## 3. Check 1: nothing reaches the console

| Command | Windows runs with a worker line on the console | Linux |
|---|---|---|
| `testForked` | 12 of 12 | 12 of 12 |
| `routeCtx` | 0 of 12 | 0 of 12 |
| `routeQueue` | 0 of 12 | 0 of 12 |

- A worker line is a console line, ANSI codes and Mill's prefix removed, holding any of `SEQ-OUT`, `SEQ-ERR`, `CHILD-OUT`, `CHILD-ERR`, the JVM warning, `Running Test Class`, ScalaTest's suite, test, failure or `Run completed` lines, or equal to any line a route captured.
- Under `testForked` all of them reached the console with Mill's per-worker prefix, for example `191-0-1] CHILD-OUT G01 pid=34128`.
- Under either route the console held only Mill's own lines and the route's: the task's header line, `SPIKE-ROUTE …`, the launcher line and Mill's final status line. When `G03` failed it also held Mill's `[error] gamma.test.<command> 1 tests failed:` followed by `  gamma.G03 t2`, as under `testForked`.
- **The per-worker log file.** Under Route A, Mill still wrote `worker-<i>.log` beside each worker folder; it held only Mill's ticker lines, the class name repeated (`gamma.G01`), and no worker output. `MultiLogger.ticker` passes the ticker to the `FileLogger`, which prints it (`core/internal/src/mill/internal/MultiLogger.scala` lines 37–39; `core/internal/src/mill/internal/FileLogger.scala` line 49). Under `testForked` the same file held the ticker lines and the worker's output. Route B does not call `fork.async`, and no `worker-<i>.log` was written.
- All output was redirected to files; what Mill's live prompt shows under a terminal was not looked at (§ 8).

## 4. Check 2: the plugin receives each worker's streams, in order, tied to its run

In every route run on both platforms, for every worker that started a JVM (95 on Windows, 97 on Linux):

- **Order.** On stdout the `SEQ-OUT` numbers ran 1 to N with no gap or reordering, and on stderr the `SEQ-ERR` numbers likewise, each stream carrying one PID.
- **What the streams held.** Stdout: ScalaTest's test-interface `Logger` lines (`G01:`, `- t1`, and `*** FAILED ***` where `G03` failed), one `CHILD-OUT` line per class the worker ran, and ScalaTest's end-of-run summary once, which is the `done()` string (`MWC` § 1.6): the task's result message is the same text. Stderr: the JVM warning once, then for each class `Running Test Class <name>`, its `CHILD-ERR` line and its `SEQ-ERR` lines.
- **Tied to its run.** Each captured stream pair is keyed by the worker's label, `<group>-<worker>` as Mill builds it (TMU lines 289–291), and recorded with the worker folder, from `fork.async`'s `dest` under Route A and from the route's own layout under Route B. In every case the PID on the stream was the PID of the `worker-info` file in that folder's sandbox, and the classes the stream named in `Running Test Class` were the classes in that folder's `out.json`.
- **Workers that started no JVM.** Under Route A, `fork.async` ran a body for worker `2` of each group, and Mill's own code then found the queue empty and spawned nothing (TMU lines 320–328): the route recorded the label with no bytes, 24 times on Windows and 23 on Linux.
- Bytes reached the route's `ReadBytes` callbacks on os-lib's reader threads while the workers ran; the spike kept them in memory and wrote them out after `runTests()` or the last worker returned. When each byte arrived was not measured.

## 5. Check 3: against Mill's own `testForked`

Each route run was compared with the `testForked` run of the same platform, mode, outcome and round. Per fork group:

| Compared | Result, both routes, both platforms |
|---|---|
| Classes run, as a set | the same in every run: all five per group |
| Workers that started a JVM | 2 per group in every run, except: Route A, Linux, daemon, passing, round 2, `group-1`: 3; Route B, Windows, daemon, passing, round 2, `group-1`: 1. In the trial round on Windows, `testForked` itself started 1 worker in `group-1` |
| JVM input arguments, command, command line, environment, working directory | the same in every run, once the task's `Task.dest`, each worker's folder and `run-<n>.jar` are replaced by placeholders, and except `MILL_PARAMS` on Windows |
| Exit status | the same in every run: 0 passing, 1 with `G03` failing |
| Failure message | the same in every failing run: `1 tests failed: gamma.G03 t2` |
| Task result (`<command>.json` value), passing runs | the same set of `(class, test, status)`; the list and the totals of tests and suites in the `done()` messages differed only by duplicate runs (below) |
| JUnit XML report (`test-report.xml` in `Task.dest`), ignoring `time`, `timestamp` and element order | the same in every run in which both sides ran each class the same number of times; otherwise the same set of test cases, with a duplicated class's test cases twice on the side that ran it twice |

- **Working directory.** In both routes, as in `testForked`, each worker ran in `<Task.dest>/<group>/worker-<i>/sandbox`.
- **`MILL_PARAMS`** in the Windows workers' environment held the `mill.bat` command line, `-j 3 gamma.test.testForked` against `-j 3 gamma.test.routeCtx`. The launcher sets it from its own arguments (`[spike] mill.bat` line 287, `set "MILL_PARAMS=%*%"`), and `propagateEnv` passes the client's environment to the worker (TMU line 152). The Linux workers had no `MILL_PARAMS`.
- **Arguments on Windows.** `ProcessHandle.info().arguments()` was empty and `commandLine()` unknown, so the Windows comparison covers the command and the JVM input arguments but not the main class or main arguments. On Linux the full command line was compared.
- **Duplicate runs.** No class ran on two workers in any Linux run. On Windows, runs in which a class ran on two workers of one group:

  | Command | Daemon | `--no-daemon` |
  |---|---|---|
  | `testForked` | 2 of 6 | 6 of 6 |
  | `routeCtx` | 0 of 6 | 6 of 6 |
  | `routeQueue` | 5 of 6 | 6 of 6 |

  - It happened only in `group-0`. In 19 of the 25 runs the class was `gamma.G01`, the group's first. In six runs later classes ran twice too: `G02` or `G04` beside `G01` (`testForked` under `--no-daemon`, twice; Route B under the daemon, once), `G04` and `G05` beside `G01` (Route B, twice), and in one run (`testForked`, daemon, passing, round 2) `G05` alone.
  - In all 32 cases of a class run twice, both workers' `claim.log` held a `CLAIM` line for it, as `EPS` § 2 describes for the first class.
  - `G03`, the failing class, never ran twice, so no failure message differed.
  - *Inference:* with three rounds a cell, the daemon counts do not show whether a route changes the rate.

Every difference between a route and `testForked` was one of these: the worker count or a duplicate run.

## 6. Cost of each route

| | Route A, `routeCtx` | Route B, `routeQueue` |
|---|---|---|
| Plugin code | about 30 lines: `CaptureCtx` (13 delegating members and `fork`) and `CaptureFork` (4) | about 100 lines in the command |
| Mill code copied | none; Mill's own `runTests()` runs | `prepareTestGroups`, `jobsProcessLength`, `runTestFuture`'s folder layout, labels, priorities and first claim, `TestRunnerUtils.claimFile` (TR/TestRunnerUtils.scala lines 357–365) and `processTestResults`: most of the command. `processTestResults`, `waitForFutures` and `TestModuleUtil.handleResults` cannot be called: `object TestModuleUtil` is `private[mill]` (TMU line 372) |
| Not reproduced | nothing | the `poll` callback's ticker and profile entries (TMU lines 341–361), the completion ticker (TMU lines 597–623), the selector error (TMU lines 65–68, 97, 104), Mill's shared task thread pool (approximated by `jobs` threads in Mill's priority order), the worker's prompt line and `worker-<i>.log` |
| `@experimental` | `TestModuleUtil` constructor and `runTests()` (TMU line 30); `TaskCtx.Fork.Api` (TCX line 193) | `TestModuleUtil` constructor, `prepareTestClassesFolder` and `callTestRunnerSubprocess` (TMU lines 30, 112, 183) |
| `@internal` or internal package | `jvmWorker().internalWorker()`, for the constructor as `testTask` passes it (`libs/javalib/src/mill/javalib/JvmWorkerModule.scala` line 156); `TestResult` in the result type (`TR/Model.scala` line 34) | the same two, plus `ZincOp.GetTestTasks` in `mill.javalib.api.internal` (`libs/javalib/api/src/mill/javalib/api/internal/ZincOp.scala` lines 59–68) for discovery |
| Unmarked | `TaskCtx`, implemented in full | `TestModule.handleResults` (TM lines 749–755) |
| Protected | `aheadOfTimeDiscoveredTestClassesIfNeeded` (TM line 138), passed as `testTask` passes it; the spike reaches it from a trait mixed into the test module | the same |
| Relies on | `runTests()` spawning each parallel worker inside the body of the ctx's `fork.async`, on the thread that runs that body (TMU lines 293–310, 337–362) | the queue protocol between Mill's process and `MillTestRunnerMain`: the `test-classes`, `claim` and `claim.log` files and `result.log` (TMU lines 183–191, 287–288, 314–333) |

## 7. Against the source reading

- `MWC` § 2.2 left open whether a plugin can pass a `TaskCtx` of its own, and § 6 lists it as not determined. It can: § 2 and Route A's runs.
- `MWC` § 2.2's statement that on the parallel path `fork.async` sets `os.Inherit` again and the caller's values are not seen held; Route A works because its `withValue` is inside the `fork.async` body.
- `MWC` § 2.2 says the per-worker `.log` file is written regardless of who called `runTests()`. Under Route A it was written but held only Mill's ticker lines; under Route B there was none (§ 3).
- `MWC` § 1.4 describes the per-worker log file as holding the worker's stdout and stderr together. Under `testForked` it also held Mill's ticker lines (§ 3).
- `MWC` § 2.3 held: `callTestRunnerSubprocess` with `Right(...)` from plugin threads honoured each thread's `os.Inherit.out` and `err`, and the caller owed discovery and the report.
- `EPS` § 2 describes the class run twice as the first class of a group. Here later classes also ran twice, once without the first (§ 5), and it happened under the daemon as well as `--no-daemon`.

## 8. What was not run or not determined

- a pseudo-terminal or Mill's live prompt: what the ticker and Route A's per-worker prompt lines show there; all output was redirected to files;
- the sequential path (`testParallelism` off, or one class in all), where `runTests()` spawns on the caller's thread (`MWC` § 2.2), under either route;
- class globs, as `testOnly` passes them (R-053);
- either route inside an exclusive command executing several modules' tasks, as `EPS` runs `testForked`;
- a worker that dies or is killed under either route;
- whether Route B's pool of `jobs` threads schedules as Mill's shared pool does when other tasks compete for it;
- `discoverTestsWithZinc` on;
- when bytes reach the plugin while the run is in progress (§ 4);
- why a class ran on two workers on Windows, and whether either route changes how often (§ 5);
- the main arguments of a Windows worker (§ 5);
- a plugin compiled in a package inside `mill` (§ 2.2);
- macOS; Scala.js and Native, which have no JVM worker (`MWC` § 4);
- versions other than § 1 lists.
