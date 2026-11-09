# A file per run as a Node or Native run's channel to the Mill plugin

What was observed when a stub emitter in each Scala.js run on the `NodeJs` JS environment and each Scala Native run wrote its run's events to a file of its own, in a folder the plugin named for the module, and plugin code in Mill's process read every file live, under Mill on Windows and on Linux. Answers [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51), including the check its comment adds. Observations only; no design is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/jvm-file-channel.md`, cited as `JFC`: the JVM spike whose method, fixtures, kill approach and plugin this one reuses.
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: how Mill launches a Node VM and a native binary, their working directory (§ 3.1), and the source reading this note tests (§ 8).
- `.planning/event-stream-reporting/research/native-stdio-wrapper.md`, cited as `NSW`: the Native `TestAdapter` built by plugin code, which `multiRun` copies.
- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`, and `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`.

A statement marked *Inference:* follows from the runs or the cited source and was not observed directly. Sources are pinned at the versions the runs used:

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939aab0be02540dbaf29d8bc01e509fd86aa3`) |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.5.0` (`1b560e68ce3065e0832ee0e6db638c111f3b1984`) |
| `[sn]` | <https://github.com/scala-native/scala-native> | tag `v0.5.12` (`65dd513df188300d0180514ad828308facc5ca61`) |
| `[jdk]` | <https://github.com/openjdk/jdk21u> | tag `jdk-21.0.5-ga` (`dfcd8d2eecfe62e91070244d8eb10906dc3c812d`), a reference version; the runs used Zulu `21.0.10` |
| `[this]` | this repository | branch `epic/event-stream-reporting`; the runs used the published ScalaTest `3.2.19` artifacts |

Abbreviations, as `JNC` § Sources defines them: `SJM`, `SJWI`, `SNM`, `SNWI` and `TRU` in `[mill]`; `TA/` in `[sjs]`; `NE/` in `[envs]`; `SNTR/` and `SNTI/` in `[sn]`. Also `TB/` is `[sjs]` `test-bridge/src/main/scala/org/scalajs/testing/bridge/`. In the prototype, `B` is `build.mill`, `NF` `emitter/shared/src/spike/NotingFramework.scala`, `FE` `emitter/shared/src/spike/FileEmitter.scala`, `PJ` and `PN` the Node and Native `emitter/<js|native>/src/spike/Platform.scala`, and `R/` its `runs/`.

## Short answer

| Point | Windows 11 | Ubuntu under WSL 2 |
|---|---|---|
| **Live** (§ 2) | The plugin opened and read every file while its process appended, from Node (`fs.appendFileSync` per line, or `fs.writeSync` on one descriptor) and from Native (`FileOutputStream`), with either JDK reader. No sharing error, no sequence gap, no partial line. Largest delay per file, median 17 ms (Node) and 28 ms (Native), largest 100 ms and 94 ms, at a 10 ms poll | The same. Median 13 ms (Node) and 15 ms (Native), largest 105 ms and 82 ms |
| **Death** (§ 3) | Every file of a process that killed itself or was killed from outside, the reader paused or not, held all 20 000 burst lines the process had logged as written, and ended with a whole line: 24 Node files, 18 Native | The same: 24 Node files, 18 Native |
| **Normal exit** (§ 4) | Every Node and Native file of a run that finished ended `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE`, `EXIT-HOOK`; the 20 000 lines written in the last test all arrived. Node's exit hook ran 104–112 ms after `done()` returned | The same. Node's exit hook ran 102–140 ms after `done()` returned |
| **End** (§ 5) | Under Mill's test task every Node VM and native binary served exactly one run, and its exit, watched with `ProcessHandle` after a start-time check, ended every stream: 6–14 ms (Node) and 21–63 ms (Native) after the exit hook on a normal exit. Native wrote a matching start time at no cost; Node needed PowerShell, 178–756 ms per run. The process outlived its run's `done()` by 111–379 ms. A Node VM or native binary given two runs by a plugin-built `TestAdapter` outlived the first run by 2.8–3.1 s; only the run's own `done()` line ended that run there | The same, with start times read from `/proc` (Node: `CLK_TCK` taken as 100): (a) 5–241 ms (Node), 2–226 ms (Native) after the exit hook. One dying PID had no start time. Two runs in one process: the first run's process outlived it by 2.9 s |
| **No task** (§ 6) | A run with no test class wrote header, `RUNNER-RETURNED`, `TASKS 0`, `done` lines and exit hook. A process that killed itself as its runner was constructed, or was killed on its header, left at least the header | The same |
| **Locating the file** (§ 7) | Every process's working directory was Mill's: `out/mill-daemon/sandbox`, or `out/mill-no-daemon/<sig>/sandbox`. The folder reached every run unchanged as a framework argument, on both runtimes; a module-wide folder also reached it through `jsEnvConfig`'s `env` (Node) and `forkEnv` (Native) | The same |

Not run: macOS; JS environments other than `NodeJs`; a run whose framework calls `slaveRunner`; poll intervals other than 10 ms (§ 9).

## 1. What was run

The prototype is on branch `prototype/node-native-file-channel`, in `.planning/event-stream-reporting/prototype-node-native-file-channel/`, commit `416d72700`. It reuses `JFC`'s plugin, tailer, fixtures and kill method, changed as follows.

- **No `-C` emitter.** ScalaTest's Scala.js and Native runners accept a single `-o` reporter argument and ignore any other (`[this]` `js/core/src/main/scala/org/scalatest/tools/MasterRunner.scala` lines 67–88; `native/core/src/main/scala/org/scalatest/tools/MasterRunner.scala` lines 69–90), and nothing in either `tools` folder builds a `RunStarting`. So `JFC`'s `-C spike.FileEmitter` reporter, opened on `RunStarting`, could not be reused; the stub emitter is opened by the shim framework.
- **The shim `testFramework`,** `spike.NotingFramework`, one source cross-built for Scala.js and Native, delegating to ScalaTest's `Framework` (`NF`). Its `runner` and `slaveRunner`:
  - take the `-Dspike.*` framework arguments off before ScalaTest sees them;
  - open the run's emitter on the thread that constructs the runner, before ScalaTest's runner is constructed, so the file and its header exist before construction returns (`NF` lines 23–32);
  - write `RUNNER-RETURNED` once ScalaTest's runner exists; `TASKS` on each `tasks` call; `TASK-START` and `TASK-END` around each task; an `EV sbt-<status>` line per sbt event; `DONE-CALLED` and `DONE-RETURNED` around `done`; and from `done` queue one `ASYNC-AFTER-DONE` line for later (`NF` lines 34–90).
  - Scala.js runs each task through the runner's `deserializeTask` and the three-argument `execute` (`TB/TestAdapterBridge.scala` lines 87–110; `TB/TaskInfoBuilder.scala` lines 29–34), so the shim wraps both.
  - Test hook: with `SPIKE_DIE_AFTER_RUNNER` in the process's environment, the process kills itself as soon as ScalaTest's runner is constructed, writing nothing more (`NF` line 28).
- **The emitter,** `spike.FileEmitter` (`FE`), with a platform object per runtime (`PJ`, `PN`):
  - It creates `<dir>/<UUID>.events`, `<dir>` being the framework argument `-Dspike.dir=<absolute folder>`. The first write is one `HEADER` line: run identifier, PID, start time, how the start time was found and its cost in ms, Node's own estimate of its start, working directory, module, runtime, writer, the framework-argument folder, the environment variable `SPIKE_ENV_DIR`, OS, runner role, how many runs this process has opened, write time. Then one tab-separated line per event, with a sequence number and its write time (`FE` lines 35–69).
  - Writers. Node: `append` calls `fs.appendFileSync(path, line)` per line; `fd` opens the file once with `fs.openSync(path, 'a')` and calls `fs.writeSync` per line (`PJ` lines 56–62). Native: `new FileOutputStream(path, true).write` per line (`PN` lines 72–75).
  - PID: Node `process.pid`; Native `getpid()` or `GetProcessId(GetCurrentProcess())`. Scala Native's `ProcessHandle.current()` throws `UnsupportedOperationException` (`[sn]` `javalib/src/main/scala/java/lang/ProcessHandle.scala` lines 54–55), and its `UUID.randomUUID` needs `java.security.SecureRandom`, which did not link (`[sn]` `javalib/src/main/scala/java/util/UUID.scala` lines 135–137), so Native makes its UUID from `scala.util.Random`.
  - Start time, computed as the JDK computes `ProcessHandle.Info.startInstant()`. Linux: `btime` from `/proc/stat` times 1000, plus field 22 of `/proc/<pid>/stat` times 1000 over `sysconf(_SC_CLK_TCK)` (`[jdk]` `src/java.base/linux/native/libjava/ProcessHandleImpl_linux.c` lines 58–59, 121–129, 252–270). Windows: the creation time from `GetProcessTimes`, divided by 10 000 and rebased from 1601 to 1970 (`[jdk]` `src/java.base/windows/native/libjava/ProcessHandleImpl_win.c` lines 338–342).
    - Native reads `/proc` with `sysconf(_SC_CLK_TCK)`, or calls `GetProcessTimes` (`PN` lines 46–67).
    - Node exposes neither `CLK_TCK` nor its process's creation time. On Linux it reads `/proc` with `CLK_TCK` taken as 100. On Windows it runs `powershell.exe -Command "[Diagnostics.Process]::GetProcessById(<pid>).StartTime.ToFileTimeUtc()"` synchronously (`PJ` lines 30–50). Node's header also carries `Date.now()` less `process.uptime()` (`PJ` line 53).
  - An exit hook writes `EXIT-HOOK`: `process.on('exit')` on Node, `Runtime.addShutdownHook` on Native (`FE` line 60).
  - `burst(n, tag)` writes `n` lines the same way and logs to stderr every 500 lines how many it has written, synchronously: Node through `fs.writeSync(2, …)` (`PJ` line 65).
  - Killing itself: Node `process.kill(process.pid, 'SIGKILL')`; Native `kill(getpid(), SIGKILL)` or `TerminateProcess(GetCurrentProcess(), 3)` (`PJ` line 73; `PN` lines 81–88).
- **The plugin,** `spike.test <selector>...`, an exclusive command taking the `Evaluator` (`B` lines 474–523).
  - It names one folder per module, `out/spike-run/<stamp>/streams/<module>`, and passes it and the module label as framework arguments to the module's own `testForked`. It evaluates that command under a plugin-built `TaskCtx`, as `JFC`'s `command` route did, so that it learns when each module's test task returns (`B` lines 447–472).
  - **Tailer** as `JFC`'s: one thread reads every file from its last offset every 10 ms through one channel per file, from `FileChannel.open(READ)` or, with `SPIKE_READER=fis`, `new FileInputStream(file).getChannel()` (`B` lines 169–384).
  - **End signals,** per file: (a) on reading the header, `ProcessHandle.of(pid)`, a comparison of its start time with the header's, then `onExit()` (`B` lines 264–281); (d) the run's own `DONE-RETURNED` line; (e) the end of the module's whole test task.
  - When `execute` returns it waits at most 5 s for (a) on every file, reads every file to its end, and writes `summary.tsv` and each file without its burst lines.
- **`multiRun`,** a command on `js.alpha.test` and `native.alpha.test`. It builds the runtime's own public `TestAdapter`: Native's as `NSW` § 1 did, Scala.js's with `NodeJSEnv` and the module's linked `main.js`. On one thread, so in one Node VM or native binary, it runs two runs. The first, of `A1` and `A2`, runs to `done()`. 1.5 s later the second, given `A3` and `A4`, executes `A3`; 0.5 s later the plugin calls `destroyForcibly()` on the process, and 1 s after that closes the adapter (`B` lines 530–615).
- **Fixtures,** each module a `ScalaJSModule` or `ScalaNativeModule` with a `test` module using `TestModule.ScalaTest`, `testFramework` set to the shim, and `SPIKE_ENV_DIR=out/spike-env/<fixture>` in `jsEnvConfig`'s `env` (Node) or `forkEnv` (Native) (`B` lines 82–136). Each fixture exists once as `js.<fixture>.test` and once as `native.<fixture>.test`, from one source folder:

  | Fixture | What it does |
  |---|---|
  | `alpha` | four suites of four 200 ms tests, one after another in one process |
  | `beta` | a passing suite and a suite with one failing test |
  | `halt` | two tests pass, then a 20 000-line burst, `HALTING`, and the process kills itself; the reader keeps up |
  | `haltlag` | as `halt`, but the reader stops at the first burst line until 1 s after the process has exited |
  | `kill` | one test passes, then a burst, `SLEEPING` and a 30 s sleep. The reader stops at the first burst line; once the file has not grown for 1 s the plugin calls `destroyForcibly()` on the process, and reads again 2 s later |
  | `empty` | no test class |
  | `diehook` | one suite; `SPIKE_DIE_AFTER_RUNNER` set, so the process kills itself as its runner is constructed |
  | `killstart` | one suite; the plugin calls `destroyForcibly()` on the process as soon as it reads the header |
  | `tail` | one test passes, then the last test writes a 20 000-line burst and returns |

- **Matrix.** `run.ps1` (Windows) and `run.sh` under `run-wsl.ps1` (Linux, in a copy of the prototype in the WSL file system), each case twice, output redirected to files, with `--keep-going` so that one failing module does not skip the others:
  - `spike.test` over all 18 modules under the daemon, with the default writers (`default`) and with the `fis` reader (`fis`), and under `--no-daemon` (`nodaemon`);
  - `spike.test` over the 9 Node modules under the daemon with the `fd` writer (`fd`);
  - `js.alpha.test.multiRun` and `native.alpha.test.multiRun` under the daemon.
- **Outputs.** Each run's stdout, stderr, the part of the daemon's `stderr` file the run wrote, `summary.tsv` and compact files are in `R/<platform>-<case>-r<n>/`. `analyse.py` summarises them in `R/analysis.txt`.
- **Delay** is a line's receipt time less the time written in the line. **End latency** is the time a signal fired less a reference: the `EXIT-HOOK` line for a normal exit, `HALTING` for a process that killed itself, and the plugin's `destroyForcibly()` call for a kill. **Witness**: a process's highest `MARK-WROTE` count on stderr before it died.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`, Scala.js `1.20.2`, Scala Native `0.5.12`; Mill on Azul Zulu `21.0.10`, fetched by Mill.

| Platform | Toolchain |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | `mill.bat` through `cmd /c`; Node `24.13.1`; LLVM `20.1.8` with the MSVC linker |
| Ubuntu 26.04 under WSL 2 (kernel `6.18.54.1`), x86-64 | `./mill`; Node `22.21.1`; clang `21.1.8`; `getconf CLK_TCK` 100 |

Per platform: 12 runs; 72 Node and 54 Native files from `spike.test`, 4 of each from `multiRun`.

## 2. Live

| | Windows Node | Windows Native | Linux Node | Linux Native |
|---|---|---|---|---|
| Open or read errors | none | none | none | none |
| Files read while their process was alive | 59 of 76 | 43 of 58 | 60 of 76 | 46 of 58 |
| Sequence gaps; partial line at the end | 0; 0 | 0; 0 | 0; 0 | 0; 0 |
| Largest delay per file, files not paused | median 17 ms, largest 100 ms | 28 ms, 94 ms | 13 ms, 105 ms | 15 ms, 82 ms |
| Mean event delay per file | median 7 ms, largest 19 ms | 8 ms, 19 ms | 5 ms, 12 ms | 6 ms, 30 ms |
| Largest delay in a 20 000-line burst (`halt`, `tail`) | `append` 43–100 ms; `fd` 12–14 ms | 21–94 ms | `append` 11–105 ms; `fd` 11 ms | 11–49 ms |

- **Sharing on Windows.** With Node's `fs.appendFileSync` and `fs.writeSync`, and with Scala Native's `FileOutputStream`, read through `FileChannel.open(READ)` and through `FileInputStream` (18 files per runtime and platform), the reader opened every file at its first attempt and read it while the process appended. `JNC` § 3.2's inference from share modes held.
- **Not read while alive:** the `diehook` and `killstart` files on both platforms, whose process was killed by the time of, or on, the plugin's first read of the header; and on Windows 1 Node and 3 Native `empty` files.
- `--no-daemon` delays were of the same order: largest per file 3–74 ms (Node) and 15–94 ms (Native) on Windows, 13–43 ms and 9–82 ms on Linux.

## 3. Death

| Case | Windows Node | Windows Native | Linux Node | Linux Native |
|---|---|---|---|---|
| `halt`, reader keeping up | 8 of 8 | 6 of 6 | 8 of 8 | 6 of 6 |
| `haltlag`, reader paused when the process killed itself | 8 of 8 | 6 of 6 | 8 of 8 | 6 of 6 |
| `kill`, reader paused, `destroyForcibly()` | 8 of 8 | 6 of 6 | 8 of 8 | 6 of 6 |

Each cell counts the files that held all 20 000 burst lines, as many as the witness logged, and ended with a whole line.

- **Paused at the death.** In every `haltlag` and `kill` file the process's exit fell inside the reader's pause; the reader read the remaining bytes after it.
- **Before the burst.** Each `halt` and `haltlag` file ended `HALTING`, each `kill` file `SLEEPING`, after every line written before them. None held `DONE-CALLED` or `EXIT-HOOK`: neither process ran its exit hook.
- **What Mill reported.** Node: `js.halt.test`, `js.haltlag.test` and `js.kill.test` failed with `org.scalajs.testing.common.RPCCore$ClosedException: org.scalajs.testing.adapter.JSEnvRPC$RunTerminatedException`. Native: `native.halt.test`, `native.haltlag.test` and `native.kill.test` succeeded, as `NSW` § 4 found for an aborted binary.
- **The witness.** Node's stderr reached the command's redirected stderr. So, under the daemon, did the `MARK-WROTE` lines of every Native process that wrote any, on both platforms, as well as the daemon's `stderr` file, which the binary inherits (`JNC` § 5.3).
- In a Windows trial before the matrix, with the kill 6 s after the first burst line, the Node process was killed mid-burst: the file held 8 619 burst lines, the witness 8 500, and the file ended with a whole line. That trial is not in `R/`.

## 4. Normal exit

- **How a run's process ends under Mill.** Mill calls `handleResults`, sleeps 100 ms and calls `close()` (`SJM` lines 424–430; `SNM` lines 513–518).
  - Node calls `process.exit(0)` when the com socket closes (`NE/ComSupport.scala` line 287).
  - A native binary's `NativeRPC.loop()` returns 0 at end of stream, and `TestMain` calls `sys.exit` with it (`SNTI/NativeRPC.scala` lines 29–48; `SNTI/TestMain.scala` lines 96–97). Only on a forced close does the adapter call `destroyForcibly()` (`SNTR/ComRunner.scala` lines 108–139; `SNTR/ProcessRunner.scala` lines 71–73).
- **Observed.** Every file of a run that finished (`alpha`, `beta`, `empty`, `tail`: 32 Node and 24 Native per platform) ended `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE`, `EXIT-HOOK`. Every `tail` file held all 20 000 lines written in its last test.

  | Time after `DONE-RETURNED` was written | Windows Node | Windows Native | Linux Node | Linux Native |
  |---|---|---|---|---|
  | `EXIT-HOOK` written | 104–112 ms | 104–347 ms | 102–140 ms | 145–330 ms |
  | (a) `onExit` fired | 111–124 ms | 130–379 ms | 110–354 ms | 149–379 ms |

- *Inference:* `fs.appendFileSync` and `fs.writeSync` return once the bytes are handed to the operating system, so `process.exit` abandons none of them; work merely queued, as `ASYNC-AFTER-DONE` was, survives only if it runs before the com socket closes. Here Mill's 100 ms sleep let it run every time.

## 5. End

### 5.1 Process exit

Latency in ms from the reference (§ 1), as minimum / median / maximum. "Behind" counts files in which the reader had not read every byte when (a) fired.

| Case | Windows Node | Behind | Windows Native | Behind | Linux Node | Behind | Linux Native | Behind |
|---|---|---|---|---|---|---|---|---|
| Normal exit (32, 24 files) | 6 / 9 / 14 | 10 | 21 / 30.5 / 63 | 0 | 5 / 56.5 / 241 | 2 | 2 / 35 / 226 | 5 |
| `halt` (8, 6) | 7 / 7.5 / 9 | 5 | 25 / 27.5 / 44 | 0 | 5 / 48.5 / 106 | 0 | 3 / 32 / 175 | 1 |
| `haltlag`, paused (8, 6) | 7 / 8 / 11 | 8 | 25 / 43 / 122 | 6 | 5 / 50 / 184 | 8 | 9 / 38.5 / 116 | 6 |
| `kill`, paused (8, 6) | 6 / 8.5 / 10 | 8 | 23 / 28 / 30 | 6 | 5 / 46.5 / 96 | 8 | 2 / 29.5 / 86 | 6 |
| `killstart` (8, 6) | 0 / 8 / 25 | 0 | 23 / 27 / 42 | 0 | 17 / 121.5 / 196 | 0 | 9 / 85 / 237 | 0 |

- **Every process's end was signalled** by (a), on both runtimes and platforms, whether the run finished, killed itself or was killed. None needed the stream to say how the run ended. Reading each file to its end after (a) gave every byte (§ 3, § 4).
- **Start time.** The header's start time equalled `ProcessHandle.of(pid).info().startInstant()`'s in every file whose process the plugin found alive at its header:

  | | Windows Node | Windows Native | Linux Node | Linux Native |
  |---|---|---|---|---|
  | Equal | 72 | 52 | 70 | 52 |
  | PID gone when the header was read | 4 | 6 | 5 | 6 |
  | PID present, no start time | 0 | 0 | 1 | 0 |
  | Method; cost per run | PowerShell, 178–756 ms | `GetProcessTimes`, 0–1 ms | `/proc`, `CLK_TCK` 100, 0–18 ms | `/proc`, `sysconf`, 0–2 ms |

  - Every PID that was gone or had no start time was a `diehook` process's; the plugin took each as exited, as `JFC` § 4 did.
  - Node's own estimate, `Date.now()` less `process.uptime()`, was 32–196 ms after `ProcessHandle`'s start time on Windows and 783–1 679 ms after it on Linux: it never matched. *Inference:* the Linux gap also contains whatever separates WSL 2's `btime` from its wall clock, which this spike did not measure.
  - In the Windows trial, with all 18 processes starting together, PowerShell took up to 1 815 ms and Node's estimate was up to 1 385 ms off.

### 5.2 One run per process under Mill; several under a plugin-built adapter

- **Source.** Each evaluation of a Scala.js or Native test task builds a new `TestAdapter` and closes it after the run (`SJM` lines 399–430; `SJWI` lines 202–227; `SNM` lines 492–520; `SNWI` lines 135–156). Mill calls `framework.runner` once per evaluation and runs every task on the calling thread (`TRU` lines 124–154, 156–206, 246–298). The adapter starts one process per JVM thread, creates each run's controller runner in the calling thread's process, and creates a worker runner only on another thread (`TA/TestAdapter.scala` lines 121–152; `TA/RunnerAdapter.scala` lines 68–107; `SNTR/adapter/TestAdapter.scala` lines 94–130; `SNTR/adapter/RunnerAdapter.scala` lines 63–106).
- **Observed under Mill.** Every one of the 252 `spike.test` files was the only file of its PID in its run, and every runner was a controller with ordinal 1: no process served two runs, and no `slaveRunner` was called. The process outlived its run's `done()` by Mill's `handleResults`, 100 ms sleep and close (§ 4).
- **Observed with two runs in one process** (`multiRun`, 2 per runtime and platform):

  | | Windows Node | Windows Native | Linux Node | Linux Native |
  |---|---|---|---|---|
  | Files with one PID; ordinals | 2; 1 and 2 | 2; 1 and 2 | 2; 1 and 2 | 2; 1 and 2 |
  | Process exit after run 1's `DONE-RETURNED` | 3 031, 3 056 ms | 2 839, 2 844 ms | 2 863, 2 881 ms | 2 940, 2 958 ms |
  | Run 2: last line; (a) after `destroyForcibly()` | `TASK-END`; 8 ms | `TASK-END`; 20 ms | `TASK-END`; 52, 68 ms | `TASK-END`; 50, 62 ms |

  - Run 1's stream was complete about 3 s before its process exited; its process's exit did not mark its end. Its own `DONE-RETURNED` line did.
  - Run 2 wrote no `done` line; only its process's exit ended its stream.
  - Native's adapter logged `TestAdapter.close() was called. Incomplete runs: Set(1)` on both platforms, and `Force close java.net.SocketException: Connection reset` on Windows. The Scala.js adapter had a `NullLogger`.

### 5.3 Other signals

- **(d) The run's `DONE-RETURNED` line** came 88–326 ms before the exit hook, in every run that finished and in no other. It is part of the stream, so it says only that the run finished.
- **(e) The module's test task** ended between 73 ms before and 1 ms after the exit hook of a normal exit. Mill's `close()` returns before the process has exited (§ 4), so in every such file the reader was behind at (e): the exit hook's line, at least, came later. After a death (e) fired 1–279 ms after the reference. It is per module; each module here had one process.

## 6. No task

- **A run given no task.** `empty` has no test class. Mill still built the adapter and called `runner`, `tasks` with no task, and `done` (`TRU` lines 124–154). In every `empty` file, 8 Node and 6 Native per platform, the lines were `HEADER`, `RUNNER-RETURNED`, `TASKS 0`, `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE`, `EXIT-HOOK`. Mill reported the task as successful.
- **Dying while or just after the runner is constructed:**

  | How | Files per platform (Node, Native) | Lines | Mill reported |
  |---|---|---|---|
  | `diehook`: the process kills itself as soon as ScalaTest's runner exists | 8, 6 | header only | `RPCCore$ClosedException` |
  | `killstart`: the plugin kills the process on reading the header | 8, 6 | header and `RUNNER-RETURNED` | `RPCCore$ClosedException` |

  Every such file held the whole header: the emitter writes it on the constructing thread before ScalaTest's runner is built (§ 1), unlike `JFC` § 5's emitter, which waited for `RunStarting`.

## 7. Locating the file

- **Working directory.** Every process's working directory was Mill's, as `JNC` § 3.1 states: `out/mill-daemon/sandbox` under the daemon (58 Node and 40 Native files per platform), and `out/mill-no-daemon/<sig>/sandbox` under `--no-daemon`, one `<sig>` per command.
- **Framework arguments.** In all 252 `spike.test` files, `-Dspike.dir` named that module's folder for that command: Mill passed `testForked`'s arguments to the runner unchanged on both runtimes (`MWC` § 4). `multiRun` passed its own to `framework.runner` the same way.
- **Environment.** In every file `SPIKE_ENV_DIR` held the module's value: through `jsEnvConfig`'s `env` on Node, and `forkEnv` on Native, which read it with `System.getenv` (`JNC` § 2.1, § 2.2). Both are module tasks, set here to one value per module, not per command.

## 8. Against the source reading

- `JNC` § 3.2's inference that Mill's process can read a file Node or Scala Native holds open for writing held on Windows, with `FileChannel.open` and `FileInputStream` (§ 2).
- `JNC` § 7's open questions on whether a JS VM or native process that dies or is killed loses bytes written to a file: none were lost, on either platform (§ 3).
- `JNC` § 7 leaves open whether Node stdout or socket writes queued when the com socket closes are lost. For a file, a write merely queued in `done`, `ASYNC-AFTER-DONE`, arrived every time, with Mill's 100 ms sleep (§ 4); stdout and sockets were not tried.
- `NSW` § 2 found that, under the daemon with the command's output redirected, a native binary's inherited output did not reach that output. Here the Native processes' `MARK-WROTE` lines did reach the command's redirected stderr on both platforms (§ 3), as `JNC` § 5.3's reading of `TailManager` predicts. Why the two differ was not investigated.
- `JNC` § 2.2's inference that Scala Native's `System.getenv` finds upper-case names on Unix: `SPIKE_ENV_DIR` was found on Linux (§ 7).
- `JNC` § 1.1's inference that one VM serves a Scala.js run held, and held for Native (§ 5.2).

## 9. What was not run or not determined

- macOS; JS environments other than `NodeJs`, including `JsDom` ([issue 52](https://github.com/jamesgorman2/scalatest/issues/52)).
- A run whose tasks execute on more than one thread, so a framework's `slaveRunner` and worker processes (§ 5.2).
- A Mill path that serves several runs from one process: none was found (§ 5.2).
- Poll intervals other than 10 ms; a file-system watch; a reader that reopens the file each poll.
- A Node writer through `fs.createWriteStream` or another asynchronous API; a Native writer other than `FileOutputStream`.
- How the Node VM reads `CLK_TCK` on a Linux system where it is not 100; the start time on a PID reused between the header and the plugin's check; whether PowerShell's cost or availability varies across Windows installations (§ 5.1).
- Node's start-time estimate error under WSL 2: the gap between `btime` and the wall clock was not measured (§ 5.1).
- A process killed between tasks, or while the reader was not paused.
- Why, in a first Linux attempt whose script lost the paths to Node and clang, the Scala Native compiler plugin threw an unhandled exception in `scalanative-genNIR` on three fixtures; on a fresh copy every fixture compiled.
- Versions other than § 1 lists.
