# A file per run as a JVM worker's channel to the Mill plugin

What was observed when a stub emitter in each JVM test worker wrote its run's events to a file of its own, in a folder the plugin named for the module, and plugin code in Mill's process read every file live, under Mill on Windows and on Linux. Answers [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50). Observations only; no design is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`: the socket spike whose fixtures and kill method this one reuses.
- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`, and `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: the source reading this note tests (§ 7).
- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`, and `.planning/event-stream-reporting/research/mill-runner-progress-without-trait.md`, cited as `MRP`: the two routes by which the plugin ran the tests.
- `.planning/event-stream-reporting/research/mill-test-worker-args.md`, cited as `MILL`: where Mill's discovery runner runs.

A statement marked *Inference:* follows from the runs or the cited source and was not observed directly. `TMU` is `[mill]` `libs/javalib/src/mill/javalib/TestModuleUtil.scala` at tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`). `B` is the prototype's `build.mill`, `FE` its `emitter/src/spike/FileEmitter.scala`, `NF` its `emitter/src/spike/NotingFramework.scala`, and `R/` its `runs/`.

## Short answer

| Point | Windows 11 | Ubuntu under WSL 2 |
|---|---|---|
| **Live** (§ 2) | The plugin opened and read every file while its worker appended, with either writer and either reader. No sharing or locking error on any worker's file. Event delay per file: median of maxima 12 ms, largest 238 ms, at a 10 ms poll. No sequence gap | The same, without error or gap. Median of maxima 10 ms, largest 20 ms |
| **Death** (§ 3) | Every case, the paused reader included, received all 20 000 lines the worker had logged as written before `Runtime.halt` or `destroyForcibly()`, with no partial line | The same |
| **End** (§ 4) | (a) `ProcessHandle.onExit` signalled every worker's end, median 19–75 ms after the reference. (b) the lock beyond end of file did too, median 11–62 ms, without blocking reads. (c) `fork.async`'s body end did on every module with several classes, median 10–27 ms, and not at all on single-class modules. Each signal sometimes fired before the reader had read every byte; reading on to end of file then gave every byte | (a) median 9–42 ms, (b) 10–26 ms, (c) 17–28 ms, with the same gaps in coverage |
| **No task** (§ 5) | A module with no test class made Mill spawn one worker, whose file held header, `RunStarting`, `done` and `RunCompleted`, and no `tasks` call. Every worker that died before its first task left a file holding at least the header, from an external kill on the header and from a test hook | The same |
| **Discovery** (§ 6) | Mill's discovery runner created one file per module per command, in Mill's own process, or, with `javaHome` set, in a JVM-worker subprocess. Its PID, its working directory, a `tasks` call with no `done`, and the end signals set it apart. Under the daemon its `FileOutputStream` file stayed open after the command and could not be deleted. A plugin that ran discovery itself without the runner arguments got no discovery file | The same, except that every such file could be deleted |

Not run: macOS, Scala.js, Native, a sidecar lock file, other poll intervals (§ 8).

## 1. What was run

The prototype is on branch `prototype/jvm-file-channel`, in `.planning/event-stream-reporting/prototype-jvm-file-channel/`, commit `de937039d`.

- **The emitter,** `spike.FileEmitter`, a ScalaTest `Reporter` on each test module's class path through a module dependency, turned on with `-C spike.FileEmitter`.
  - On `RunStarting` it creates `<dir>/<UUID>.events`, `<dir>` being the framework argument `-Dspike.dir=<absolute folder>`, which ScalaTest puts in `RunStarting`'s config map (`FE` lines 66–97). A `-C` reporter receives no arguments in its constructor, so the file is made on the runner's first event, not in the constructor.
  - It writes a `HEADER` line: run identifier, PID, the process start time from `ProcessHandle.current().info().startInstant()`, working directory, module, writer. Then one tab-separated line per event, with a sequence number and its write time, each written straight to the stream with no buffer in between.
  - `-Dspike.writer=fos` writes through `new FileOutputStream(file, true)`; `nio` through `Files.newOutputStream(file, CREATE_NEW, WRITE, APPEND)` (`FE` lines 73–74).
  - It takes an exclusive lock on one byte at offset 2^62, far beyond end of file, and holds it for the life of the process: on the writer's own channel for `fos`, on a second write channel for `nio` (`FE` lines 81–93). Every `LOCK` line, 427 of them, read `held at 4611686018427387904`; no lock attempt failed.
  - A shutdown hook writes `NOTE SHUTDOWN-HOOK`, the reference time for a normal exit.
  - `FileEmitter.burst(n, tag)` writes `n` lines the same way and logs to stderr every 500 lines how many it has written, as `EPS` § 1's did.
- **A shim `testFramework`,** `spike.NotingFramework`, on every fixture module, delegates to ScalaTest's `Framework` and writes a `NOTE` line when `runner` returns and on each `tasks` and `done` call, which a reporter cannot see (`NF`).
  - Test hook: `SPIKE_DIE_AFTER_RUNNER`, set only through `forkEnv`, halts the JVM right after `runner` returns. With `now` it halts at once, writing nothing; with `wait` it first waits until the emitter's file exists, then writes `HOOK-HALT` and halts (`NF` lines 16–29).
- **The plugin,** `spike.test [--route command|queue] <selector>...`, an exclusive command taking the `Evaluator` (`B` lines 507–609).
  - It names one folder per module, `out/spike-run/<stamp>/streams/<module>`, and passes it, with `-C spike.FileEmitter` and the module label, as the module's framework arguments.
  - **Tailer.** One thread lists every module folder and reads each file from its last offset every 10 ms (`B` lines 137–320). It keeps one channel per file open, from `FileChannel.open(path, READ)` (`SPIKE_READER=nio`, the default) or `new FileInputStream(file).getChannel()` (`fis`). It records each line's receipt time, sequence gaps, and the first open, read or lock error, quoted.
  - **End signals,** per file:
    - (a) on reading the header, `ProcessHandle.of(pid)`, a check that its start time equals the header's, then `onExit().thenRun(…)` (`B` lines 202–214);
    - (b) on each poll, `tryLock(2^62, 1, shared = true)` on the reader's channel, which succeeds once the writer's lock is gone (`B` lines 261–272);
    - (c) the end of the body Mill's `fork.async` runs for each worker, which contains the spawn and returns once the process and its pumper threads have ended (`TMU` lines 165–176), seen through a plugin-built `TaskCtx` whose `fork` wraps Mill's (`B` lines 325–336) and mapped to the file whose header names that worker folder's `sandbox` as its working directory;
    - for comparison, the end of the module's whole test task.
  - **Routes.** `command` evaluates the module's public `testForked` with the plugin's `TaskCtx`, as `MTW` § 3.4 does; Mill's own `runTests` then runs discovery with the same arguments (`B` lines 376–405). `queue` is `MRP` § 3's Route B: the plugin calls `ZincOp.GetTestTasks` itself and spawns each worker with `callTestRunnerSubprocess`. With `SPIKE_DISC=withheld` the discovery call gets the module's `testArgsDefault` only, without the spike's arguments; the workers get them all (`B` lines 408–504, line 440).
  - At the start it deletes every earlier `out/spike-run/<stamp>` file by file and prints each refusal, quoted (`B` lines 518–533).
  - When `execute` returns it waits at most 5 s for (a) on every file from another process, then reads every file to its end and writes `summary.tsv` and each file without its burst lines.
- **Fixtures,** each a `ScalaTests` module with `TestModule.ScalaTest`, `testParallelism` at Mill's default, on:

  | Module | What it does |
  |---|---|
  | `alpha.test` | four suites of four 400 ms tests, in two fork groups of two: four workers at once |
  | `beta.test` | a passing suite and a suite with one failing test |
  | `crash.test` | `EPS` § 1's `HaltSuite`, `HaltLaggingSuite`, `KillSuite` and `CrashOk`, each its own fork group. The halting suites write `NOTE HALTING` just before `Runtime.halt`. The plugin pauses reading `HaltLaggingSuite`'s file for 3 s at its first burst line; at `KillSuite`'s first burst line it pauses that file for 2 s and calls `destroyForcibly()` on the worker after 1.5 s, as `EPS` § 1 did |
  | `empty.test` | no test class |
  | `diehook.test` | one suite; `SPIKE_DIE_AFTER_RUNNER=now` |
  | `diewait.test` | the same suite; `SPIKE_DIE_AFTER_RUNNER=wait` |
  | `killstart.test` | one suite; the plugin calls `destroyForcibly()` on the worker as soon as it reads the file's header |
  | `subdisc.test` | two suites; `javaHome` set to Mill's own `java.home`, so that Mill runs discovery in a JVM-worker subprocess (`MILL` § 1.4) |

- **Matrix.** `run.ps1` (Windows) and `run.sh` under `run-wsl.ps1` (Linux, in a copy of the prototype in the WSL file system), each case twice, output redirected to files:
  - `spike.test` over all eight modules under the daemon with `fos` and `nio` writers and with the `fis` reader, and under `--no-daemon` with `fos`;
  - `spike.test --route queue alpha.test beta.test empty.test` under the daemon, with discovery arguments full and withheld.

  On Linux the second `fos`/`fis` run lost every module to `subdisc.test`'s failure (§ 8), so a third, `linux-daemon-fos-fis-r3`, ran on a fresh daemon. A first Windows matrix, whose final read stopped early on a paused file, was discarded and rerun after the fix.
- **Outputs.** Each run's stdout, stderr, `summary.tsv` and compact files are in `R/<platform>-<case>-r<n>/`; `analyse.py` summarises them in `R/analysis.txt`.
- **Delay** is a line's receipt time less the time written in the line. **End latency** is the time a signal fired less a reference: the `SHUTDOWN-HOOK` line for a normal exit, the `HALTING` or `HOOK-HALT` line for a halt, and the plugin's `destroyForcibly()` call for a kill.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`; Mill and the workers ran on Azul Zulu `21.0.10`, fetched by Mill, on both platforms.

| Platform | |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | `mill.bat` through `cmd /c` |
| Ubuntu 26.04 under WSL 2 (kernel `6.18.54.1`), x86-64 | `./mill` |

## 2. Live

| | Windows | Linux |
|---|---|---|
| Runs; worker files | 12; 150 | 13; 156 |
| Open, read or lock errors on a worker's file | none | none |
| Worker files read while the worker was alive | 131 | 147 |
| Sequence gaps; partial line at the end | 0; 0 | 0; 0 |
| Event delay, maximum per file (files not paused) | median 12 ms, largest 238 ms | median 10 ms, largest 20 ms |
| Event delay, mean per file | median 6 ms, largest 36 ms | median 5 ms, largest 14 ms |
| `HaltSuite`'s 20 000-line burst, largest delay | 13–18 ms | 10–11 ms |

- **Sharing on Windows.** With the `FileOutputStream` writer and the `Files.newOutputStream` writer, read through `FileChannel.open(READ)` and through `FileInputStream`, the reader opened every file at its first attempt and read it while the worker appended. A shared `tryLock` beyond end of file on the reader's channel did not disturb the writer or the reads.
- The worker files not read while alive are those whose process ended before the plugin's first read, such as `killstart.test` and `diehook.test` (§ 5).
- The 238 ms maximum was in `windows-nodaemon-fos-r2`; the next largest Windows maximum was 83 ms.
- Under `alpha.test`, a class ran on two workers in 7 of 12 Windows runs and in no Linux run, as `EPS` § 2 records. Each worker's file held that class's events.

## 3. Death

| Case | Windows: all 20 000 lines received | Linux: all 20 000 lines received |
|---|---|---|
| `HaltSuite`, reader keeping up | 8 of 8 files | 9 of 9 |
| `HaltLaggingSuite`, reader paused when the JVM halted | 8 of 8 | 9 of 9 |
| `KillSuite`, reader paused, `destroyForcibly()` | 8 of 8 | 9 of 9 |

- **Written before the death.** In every case the worker's stderr held `MARK-WROTE <tag> 20000 of 20000` before the halt or kill, and the file ended with a whole line.
- **Before the burst.** Every file held the events up to the `TestStarting` of the test that halted or was killed, and no `RunCompleted`. In 15 of 16 Windows halt files and all 18 Linux ones, that `TestStarting` came before `HALTING`. In `windows-nodaemon-fos-r2`'s `HaltLaggingSuite` it came after it, written 1 ms later.
  - The emitter receives ScalaTest's events on the `ScalaTest-dispatcher` thread: every `RunStarting` line, 422 of them, names it. `DispatchReporter` queues each event and a thread of that name delivers it (`[this]` `jvm/core/src/main/scala/org/scalatest/DispatchReporter.scala` lines 278, 299–300). The burst and `HALTING` lines are written on the test's own thread.
  - *Inference:* an event still in that queue when the JVM dies is lost, as bytes buffered in the process are (`.planning/event-stream-reporting/SPECIFICATION.md` R-073).
- **What Mill reported.** `crash.test` failed with `Test reporting Failed: …/crash.KillSuite/worker-0/out.json does not exist`, as in `EPS` § 4.

## 4. End

Latency in ms from the reference (§ 1), as minimum / median / maximum over the files. "Behind" counts the files in which the reader had not yet read every byte when the signal fired.

| Case | Signal | Windows | Behind | Linux | Behind |
|---|---|---|---|---|---|
| Normal exit (102 Windows files, 105 Linux) | (a) `onExit` | 15 / 22 / 188 | 0 | 3 / 13 / 81 | 27 |
| | (b) lock | 4 / 12 / 89 | 63 | 4 / 10 / 33 | 57 |
| | (c) `fork.async` | 4 / 13 / 261 | 12 | 5 / 19 / 85 | 6 |
| | whole task | 9 / 76 / 2 071 | 0 | 11 / 48 / 1 012 | 0 |
| `destroyForcibly()`, reader paused (8, 9) | (a) | 18 / 19 / 32 | 8 | 4 / 9 / 20 | 9 |
| | (b) | 3 / 11 / 14 | 8 | 7 / 12 / 15 | 9 |
| | (c) | 4 / 10 / 15 | 8 | 7 / 17 / 28 | 9 |
| `HaltSuite`, reader keeping up (8, 9) | (a) | 27 / 37 / 44 | 0 | 14 / 19 / 31 | 0 |
| | (b) | 18 / 26 / 31 | 0 | 16 / 20 / 26 | 0 |
| | (c) | 19 / 24 / 34 | 0 | 19 / 24 / 37 | 0 |
| `HaltLaggingSuite`, reader paused (8, 9) | (a) | 28 / 39 / 51 | 8 | 16 / 24 / 35 | 9 |
| | (b) | 18 / 27 / 33 | 8 | 17 / 23 / 29 | 9 |
| | (c) | 17 / 27 / 36 | 8 | 18 / 28 / 42 | 9 |
| `diewait.test`, hook halt (8, 8) | (a) | 4 / 75 / 126 | 1 | 10 / 41 / 76 | 0 |
| | (b) | 10 / 62 / 132 | 1 | 11 / 26 / 28 | 1 |
| | (c) | never | | never | |
| `killstart.test`, kill on header (8, 8) | (a) | 19 / 33 / 68 | 0 | 10 / 42 / 92 | 3 |
| | (b) | 14 / 38 / 71 | 0 | 13 / 25 / 40 | 0 |
| | (c) | never | | never | |

- **Every worker's end was signalled** by (a) and (b) on both platforms, normal exit, halt and kill alike, and by (c) wherever (c) existed. None needed the stream to say how the run ended.
- **Reading after the signal.** Every signal fired, in some files, before the reader had read the last bytes: always where the reader was paused, and for a normal exit in up to 63 of 102 files with (b). Reading each file to its end after the signal gave every byte (§ 3).
- **(a) `ProcessHandle`.**
  - The header's start time equalled `ProcessHandle.of(pid)`'s in 439 files. In 3 Windows files (`diehook.test` twice, `diewait.test` once) the PID was already gone when the plugin first read the header, so no start time could be compared; the plugin took that as the end.
  - For a file written by Mill's own process, `onExit()` threw `java.lang.IllegalStateException: onExit for current process not allowed` (§ 6).
  - Cost in the runs: one `onExit` future per file, registered when the header is read.
- **(b) The lock.**
  - Cost: the writer holds an exclusive lock for its lifetime; the plugin makes one `tryLock` call per file per poll, so its latency is bounded below by the poll interval.
  - From Mill's own process, on a file whose writer is also in Mill's process, `tryLock` threw `java.nio.channels.OverlappingFileLockException` on every poll (§ 6). *General:* a JVM tracks its own file locks and refuses an overlapping one.
- **(c) `fork.async`.**
  - It needs a plugin-built `TaskCtx` whose `fork`, of `@experimental` type `TaskCtx.Fork.Api` (`MWC` § 5), wraps Mill's.
  - Mill calls `fork.async` only when `testParallelism` is on and the module's filtered class count is not 1 (`TMU` lines 293–307). On `diehook.test`, `diewait.test` and `killstart.test`, one class each, it never fired; only the whole task's end did.
  - On the `queue` route, the return of the plugin's own `callTestRunnerSubprocess` call is the same signal.
- **The whole task** ended only after every worker of the module had, so for a module with several workers it gives no per-worker end.

## 5. No task

- **A worker given no task, naturally.** `empty.test` has no test class. Mill still spawned one worker: `runTestFuture` runs process 0 of a group "even if there are no tests to run", with an empty queue when no class is discovered (`TMU` lines 219, 241–243, 326–328). In 12 of 12 Windows and 12 of 12 Linux runs its file held, in order: `HEADER`, `LOCK`, `RunStarting` with test count 0, `RUNNER-RETURNED`, `DONE-CALLED`, `RunCompleted`, `SHUTDOWN-HOOK`. There was no `tasks` call. Mill reported the task as successful.
- **A worker that found the queue empty** was not observed: every worker file of `alpha.test`, `beta.test`, `crash.test` and `subdisc.test`, on both platforms, held at least one `tasks` call. Process 0 of each group is the only one Mill spawns without first claiming a class (`TMU` lines 320–328).
- **Dying after the runner is constructed, before the first task:**

  | How | Windows files | Linux files | Every file held the header |
  |---|---|---|---|
  | `killstart.test`: the plugin kills the worker on reading the header; no hook in the worker | 8: header only 5, with `LOCK` 2, with `LOCK` and `RunStarting` 1 | 8: header only 6, with `LOCK` 1, with `LOCK`, `RunStarting` and `RUNNER-RETURNED` 1 | yes |
  | `diehook.test`, simulated: the shim halts as soon as `runner` returns, writing nothing | 8: with `LOCK` and `RunStarting` 6, header only 2 | 8: with `LOCK` and `RunStarting` 4, with `LOCK` 2, header only 2 | yes |
  | `diewait.test`, simulated: the shim halts once the file exists | 8: `LOCK`, `RunStarting`, `RUNNER-RETURNED`, `HOOK-HALT` | 8: the same | yes |

  No file of these held a `tasks` call. Mill reported each such module as `Test reporting Failed: …/out.json does not exist`.
- **When the file appears.** The emitter creates the file when it receives `RunStarting`, on the `ScalaTest-dispatcher` thread (§ 3). ScalaTest fires `RunStarting` through its dispatch reporter while it constructs the runner (`[this]` `jvm/core/src/main/scala/org/scalatest/tools/Framework.scala` lines 676, 687), so that thread may still be writing when `runner` returns: in 2 Windows and 4 Linux `diehook.test` files, halted as `runner` returned, the `RunStarting` line was missing. *Inference:* a process that dies straight after its runner is constructed can leave no file under this emitter, though in all 32 `killstart.test` and `diehook.test` runs it left one; an emitter that writes its header on the constructing thread would not have that window, which was not run.

## 6. The discovery runner

- **Under the `command` route,** Mill's own `runTests` ran discovery with the same arguments, and its runner created a file: one per module per command, in every run (67 files on Windows, 69 on Linux). The same held on the `queue` route with full arguments: 3 per run, one per module.
- **In Mill's own process** (every module but `subdisc.test`):
  - PID: Mill's own, the daemon's or the `--no-daemon` process's, with a matching start time.
  - Working directory: `out/mill-daemon/sandbox` under the daemon, `out/mill-no-daemon/<sig>/sandbox` under `--no-daemon`; no worker's `sandbox`.
  - Lines: `HEADER`, `LOCK`, `RunStarting`, `RUNNER-RETURNED`, then one `tasks` call holding every discovered class, for example `TASKS 4 alpha.A1,alpha.A2,alpha.A3,alpha.A4`, or `TASKS 0` on `empty.test`. Never `DONE-CALLED`, `RunCompleted` or a suite event.
  - (a) threw `java.lang.IllegalStateException: onExit for current process not allowed`; (b) threw `java.nio.channels.OverlappingFileLockException` on every poll; neither signalled.
- **In a JVM-worker subprocess** (`subdisc.test`, `javaHome` set):
  - PID: neither Mill's nor a test worker's.
  - Working directory: `out/subdisc/test/compile.dest`, or `out/spike-run/<stamp>/tasks/subdisc.test`, the folder the plugin's `TaskCtx` gave as `dest` in the run that started the subprocess. In later runs that folder belonged to an earlier run. In neither case a `sandbox`.
  - The same lines as above. The subprocess outlived each run, so (a) and (b) never signalled.
- **The file stays open.** On Windows under the daemon, deleting a discovery file from an earlier command failed, for 36 files and at every attempt, with `java.nio.file.FileSystemException: <file>: The process cannot access the file because it is being used by another process`: 34 files from Mill's process and 2 from the subprocess, all written through `FileOutputStream`. Discovery files written through `Files.newOutputStream` were deleted, as were all those from `--no-daemon` runs, whose process had ended. On Linux every deletion succeeded. *Inference:* without `done()`, ScalaTest's dispatcher thread and its reporters stay reachable, and so does the open stream.
- **Facts that told a discovery file from a run's** in these runs: the PID was Mill's, or not that of a process Mill spawned for the module; the working directory was not a worker's `sandbox`; there was a `tasks` call and never a `done` or `RunCompleted`; (a) and (b) never signalled while the command ran.
- **Withholding the argument from discovery.** On the `queue` route with `SPIKE_DISC=withheld`, the discovery call received `testArgsDefault` only. There was no discovery file in 4 of 4 runs (2 per platform), while the 7 workers per run created theirs, ran the same classes as with full arguments, and had the same results. On the `command` route Mill's own `runTests` passes the same `args` to discovery and to the workers (`TMU` lines 82–89, 127–130), so the plugin cannot withhold it there.

## 7. Against the source reading

- `MWC` § 3.1 and § 6 left open the share mode of the worker's writer on Windows. Observed: Mill's process opened and read every file while the worker appended through `FileOutputStream` or `Files.newOutputStream`, by `FileChannel.open` and by `FileInputStream`, without a sharing error (§ 2). `JNC` § 3.2's inference from share modes, made there for Node and Scala Native writers, held for these JDK writers.
- `MWC` § 3.1's *general* statement that bytes handed to the operating system survive the worker's death held on both platforms, with the reader paused or not (§ 3). In the same cases `EPS` § 4's socket lost bytes on Windows.
- `MILL` § 1.4 held: the discovery runner received the same arguments, was constructed, received `tasks` and never `done`, and ran in Mill's own process or, with `javaHome` set, in a JVM-worker subprocess, neither in a test sandbox (§ 6). So did `MWC` § 3.2's "that runner never calls `done()`".
- `JNC` § 3.1's working directories of Mill's process, `<out>/mill-daemon/sandbox` and `<out>/mill-no-daemon/<sig>/sandbox`, were the discovery runner's working directory (§ 6), as `MWC` § 3.1's inference about relative paths assumes.
- `EPS` § 2's class run on two workers recurred on Windows only (§ 2).

## 8. What was not run or not determined

- macOS; Scala.js and Native.
- Poll intervals other than 10 ms; a file-system watch instead of polling; a reader that reopens the file each poll.
- A lock on a sidecar file; a lock region other than one byte at 2^62.
- An emitter that creates its file and writes its header on the thread that constructs the runner (§ 5).
- A worker killed between tasks, or with further classes queued; a kill while the reader was not paused.
- Whether the discovery runner's open stream is ever released in a long-lived daemon, and whether `Files.newOutputStream`'s file stays open too, short of deletion (§ 6).
- Why `subdisc.test` failed under the daemon in every second-round run on both platforms with `java.net.ConnectException: Connection refused: connect` (Windows) or `java.net.ConnectException: Connection refused` (Linux), discovery's JVM-worker subprocess having started in a folder of an earlier run that the plugin's cleanup had emptied. In `linux-daemon-fos-fis-r2` Mill then skipped every other module of the command.
- PID reuse: every PID checked had the header's start time (§ 4).
- Resource cost of (a) and (b) beyond what § 4 lists; files much larger or runs much longer than these.
- Versions other than § 1 lists.
