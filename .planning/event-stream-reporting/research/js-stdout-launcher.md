# A launcher that sends a Scala.js VM's stdout and stderr to files

What was observed when the test command ran Scala.js modules' tests, in the `JsDom` and `NodeJs` JS environments, through a launcher that stands in for the Node executable and starts the real Node VM with its stdout and stderr redirected to files, under Mill on Windows and on Linux. The plugin read the files live and ended each VM's stream by the VM's PID. Answers option B of [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52), raised by the bytes `jsdom-channel.md` § 4 saw lost on Linux. Observations only; no design is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/jsdom-channel.md`, cited as `JDC`: the console channel, the fixtures, the witness and the plugin structure this spike reuses, and the Linux loss it answers (§ 4).
- `.planning/event-stream-reporting/research/native-stdio-wrapper.md`, cited as `NSW`: the Native launcher passed as `binaryFile`.
- `.planning/event-stream-reporting/research/node-native-file-channel.md`, cited as `NNFC`: reading files live, and ending a stream by `ProcessHandle`.
- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`: the `command` route, and replacing a named task's value in the plugin's own evaluation (§ 2.3, § 3.3).
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`, and `.planning/event-stream-reporting/research/js-environment-channels.md`, cited as `JEC`.

A statement marked *Inference:* follows from the runs or the cited source and was not observed directly. A statement marked *General:* is operating-system behaviour that no source here was opened for. Sources are pinned at the versions the runs used:

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939aab0be02540dbaf29d8bc01e509fd86aa3`) |
| `[sjs1]` | <https://github.com/scala-js/scala-js> | tag `v1.0.0` (`e6a24304c00cf66cb79627c23a53456d3ab8b7be`), the `scalajs-env-nodejs` the jsdom env loads (`JEC` § Sources) |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.4.0` (`ef4576137227545ce4f534c935b7de1690f33db5`); `v1.5.0`, which the `NodeJs` env loads, differs in its main sources only by two `final` modifiers (`JNC` § Sources) |
| `[jsdomenv]` | <https://github.com/scala-js/scala-js-env-jsdom-nodejs> | tag `v1.1.1` (`1bef3aeba3c9d2811604c808d6d1a1077ba54433`) |
| `[node]` | <https://github.com/nodejs/node> | tag `v22.21.1`: `doc/api/process.md` |
| `[jdk]` | <https://github.com/openjdk/jdk21u> | tag `jdk-21.0.5-ga` (`dfcd8d2eecfe62e91070244d8eb10906dc3c812d`), a reference version; the runs used Zulu `21.0.10` |
| `[this]` | this repository | branch `epic/event-stream-reporting`; the runs used the published ScalaTest `3.2.19` artifacts |

Abbreviations: `SJM` in `[mill]` is `libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala`, `JEV` is `libs/scalajslib/src/mill/scalajslib/api/JsEnvConfig.scala`, and `JV/` is `libs/scalajslib/worker/1/src/mill/scalajslib/worker/jsenv/`. `JE/` is `[envs]` `js-envs/src/main/scala/org/scalajs/jsenv/`; `NE1/` is `[sjs1]` `nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/`; `JD` is `[jsdomenv]` `jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala`. In the prototype, `B` is `build.mill`, `LW` `launcher/win-launcher.c`, `W` `witness.js`, and `R/` its `runs/`.

## Short answer

"Handed" is what the VM's witness (§ 1) last saw handed to the operating system. Both JS environments behaved alike unless a cell says otherwise.

| Point | Windows 11 | Ubuntu under WSL 2 |
|---|---|---|
| **Route** (§ 2) | The plugin replaced the value of the module's public `jsEnvConfig` task, in its own evaluation of the module's `testForked`, with a copy whose `executable` is the launcher. Tests ran in 52 of 52 module runs under the launcher, with no change to any module. A plugin-built `TestAdapter` also worked, at a higher cost | The same, 52 of 52 |
| **Launcher** (§ 3) | A C program. Stdin, arguments and exit code passed through; the com channel and the test `Logger` worked. Killing the launcher killed the VM 8–18 ms later when the VM was in a job object; without one the VM outlived it, 30 s in a blocking sleep. Start-up: 44 ms against Node's 32 ms (medians) | A `/bin/sh` script that `exec`s Node, so the launcher is the VM: one PID, one kill. Start-up: 20 ms against 19 ms |
| **Locating the files** (§ 4) | One launcher and one folder per module per command; one `.out` and one `.err` per VM, named by the launcher's PID: 1 pair in each of the 60 module runs | The same, 52 module runs |
| **Death** (§ 5) | Every handed byte was in the files and read, reader keeping up or paused, in all 40 death runs. Node wrote stdout synchronously to the file: 0 B queued in Node at every exit | The same, 32 runs. `killlag` and `haltlag` lost nothing, against `JDC`'s 64 417 B |
| **Normal exit** (§ 6) | All 20 000 last-test items, `DONE-RETURNED` and `ASYNC-AFTER-DONE` arrived in every run that finished (20) | The same (20) |
| **End** (§ 7) | The launcher writes the VM's PID and start time first. The start time matched `ProcessHandle`'s for 60 of 60 VMs. The VM's exit (`onExit`) came 7–24 ms after a normal exit; no byte reached a file after it | The same, 52 of 52 VMs. `onExit` 3–6 ms after a normal exit |
| **Live** (§ 8) | Item delay median 6 ms, p95 13 ms; largest per run 6–31 ms. 20 000-item burst: largest 17–23 ms | Median 5 ms, p95 10 ms; largest per run 8–62 ms. Burst: largest 19–85 ms |
| **No task** (§ 9) | Empty run: item 1 to `ASYNC-AFTER-DONE`. Death at runner construction: item 1 and the stderr. Death before any run: its stdout and stderr lines | The same |

Not run: macOS; `--no-daemon`; a script launcher on Windows; a compiled launcher on Linux; `ExoegoJsDomNodeJs`; two runs in one VM; a plugin-built `TestAdapter` under `NodeJs` (§ 12).

## 1. What was run

The prototype is on branch `prototype/jsdom-channel`, in `.planning/event-stream-reporting/prototype-js-stdout-launcher/`, commit `3d0f38548`. It reuses `JDC`'s shim framework, fixtures, witness and plugin structure, changed as follows.

- **Two environments.** Each fixture exists as `js.<fixture>.test`, with `JsEnvConfig.JsDom`, and as `node.<fixture>.test`, with `JsEnvConfig.NodeJs`. Both configs carry `--require witness.js` and `--title=spike-<env>-<fixture>` in `args` and the witness's settings in `env`; `JsDom` adds `NODE_PATH` (`B` lines 73–90). Under `NodeJs`, the probe and the death hook take the Node branch: `Platform.dieUncaught` calls `process.exit(1)`, the call the `JsDom` env's `jsdomError` handler makes (`emitter/src/spike/Platform.scala`).
- **The channel** is `JDC`'s console channel: one framed `console.log` line per item. Every item and test line therefore lands in the VM's stdout file.
- **The launcher** (§ 3) writes `<folder>/<launcher PID>.out` and `.err`. The `.out` file's first line is the launcher's header, `@@LAUNCHER`, the VM's PID, the VM's start time in epoch ms, the launcher's PID, the launcher's set-up time, and its mode.
- **The plugin,** `spike.test <selector>...`, an exclusive command taking the `Evaluator` (`B` lines 465–596).
  - It evaluates each module's own `testForked` under a plugin-built `TaskCtx` inside `SystemStreamsUtils.withStreams`, as `JDC` § 1 did. It replaces the value of the module's `jsEnvConfig` (§ 2.1; `B` lines 410–462). `SPIKE_LAUNCHER=off` skips the replacement.
  - **Tailer** (`B` lines 129–158, 207–235): one thread polls every module's folder every 10 ms, reads every `.out` and `.err` file from its last offset, and splits lines. It parses the header, then treats a stdout line beginning with the frame as an item and keeps every other line.
  - **On the header** (`B` lines 237–254) it compares the header's start time with `ProcessHandle.of(<VM PID>).info().startInstant()`, then watches `onExit()` of the VM and, where different, of the launcher.
  - **End signals,** per run: (x) the VM's exit; (l) the launcher's exit; (e) the module's test task returning; (p) the end of Mill's `PipeOutputThread` on the launcher's own stdout pipe. At each it records the files' size and the bytes read. After `execute` returns it waits for every VM's exit and every pause to end, then reads every file to its end.
  - **Mill's streams.** The streams given to `withStreams` count what Mill's `PipeOutputThread`s copy from the launcher's own pipes, and what Mill's other threads write (`B` lines 322–341).
- **Fixtures,** `JDC` § 1's twelve, each in both environments, plus one:

  | Fixture | What the plugin does |
  |---|---|
  | `kill` | kills the VM with `destroyForcibly()` when it reads burst item 5 000 |
  | `killlag` | stops reading at burst item 1, kills the VM 1.5 s later, reads again 2 s after the kill |
  | `haltlag` | stops reading at burst item 1 until 1 s after the VM's exit, at most 8 s |
  | `killstart` | kills the VM when item 1 arrives |
  | `killlauncher`, new | as `kill`'s fixture, but kills the launcher, not the VM, when it reads `SLEEPING`, the VM then starting a 30 s sleep; waits up to 40 s for the VM's exit |

- **The witness,** `W`, preloaded in the VM, writes `<pid>.txt` with its start line (stream types, `execArgv`, working directory) and, after each write in the death fixtures, the bytes handed to the OS for stdout and for stderr. For a file stream, which has no `bytesWritten`, it counts the bytes given to `write` less `writableLength` (`W` lines 19–40).
- **Matrix.** `run.ps1` (Windows) and `run.sh` under `run-wsl.ps1` (Linux, in a copy in the WSL file system), two rounds of:
  - `spike.test` over the 13 fixtures of each environment: Windows with the launcher in mode `job`, Linux with the script;
  - Windows only: `spike.test` over `kill` and `killlauncher` of each environment with the launcher in mode `nojob`;
  - then once: `spike.adapter --selector js.alpha.test` (§ 2.2).
  - That made 60 module runs on Windows and 52 on Linux.
- **Outside Mill,** `bench.ps1` and `bench.sh` checked stdin, arguments, exit code and a kill of the launcher, and timed 30 alternating starts of `node -` and the launcher, each fed `process.exit(0)` on stdin.
- **Outputs** are in `R/<platform>-<env>-<mode>-r<n>/`, the bench results in `R/<platform>-bench.txt`, the adapter route's output in `R/<platform>-adapter-stdout.txt`. `analyse.py` writes `R/analysis.txt`.
- **Delay** is an item's receipt time less the write time in it.

Versions: Mill `1.1.10`, Scala `3.3.6`, Scala.js `1.20.2`, ScalaTest `3.2.19`, jsdom `27.4.0`; Mill on Azul Zulu `21.0.10`, fetched by Mill.

| Platform | Toolchain |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | Node `24.13.1`; the launcher built with MSVC `14.44.35207` and Windows SDK `10.0.26100.0` |
| Ubuntu 26.04 under WSL 2 (kernel `6.18.54.1`), x86-64 | Node `22.21.1`; `/bin/sh` is `dash`; `CLK_TCK` 100 |

## 2. The route

### 2.1 Replacing `jsEnvConfig`'s value in the plugin's evaluation

- **Source.** `testFrameworkInstance` passes `jsEnvConfig()` to `ScalaJSWorker.getFramework` (`SJM` lines 398–406). A test module's `jsEnvConfig` is a named task, defaulting to the outer module's (`SJM` lines 32, 274). Mill's worker builds the env from the config's `executable`, `args` and `env`: `JSDOMNodeJSEnv` (`JV/JsDom.scala` lines 5–12) or `NodeJSEnv` (`JV/NodeJs.scala` lines 15–21). Each env runs `executable :: args` with the program on stdin (`JD` lines 63–69; `JNC` § 1.1).
- **What the plugin did.** Under `MTW`'s `command` route, `jsEnvConfig` is one of the named tasks Mill evaluates for the plugin, below the anonymous tasks the plugin evaluates itself (`MTW` § 2.3). In 112 of 112 module runs it was found among them by identity. The plugin put `config.copy(executable = <launcher>)` in its place before evaluating `testFrameworkInstance`. It kept the config's case class, `args` and `env` (`B` lines 410–434), as `MTW` § 3.3 did with Native's `nativeLink`.
- **Observed.** In every run the VM's `execArgv` was the config's `args`, and its working directory Mill's `out/mill-daemon/sandbox`, as without the launcher (`JDC` § 1; `NNFC` § 7). Mill's results matched `JDC`'s fixture by fixture: success, `beta`'s `1 tests failed`, and `RPCCore$ClosedException: …JSEnvRPC$RunTerminatedException` for every death.
- **API.** Beyond what `MTW` § 6 lists for the `command` route:

  | Used | Where | Marking |
  |---|---|---|
  | `ScalaJSModule.jsEnvConfig: T[JsEnvConfig]` | `SJM` line 274 | public task |
  | `JsEnvConfig.JsDom`, `JsEnvConfig.NodeJs`, their `copy` | `JEV` lines 24–40 | public case classes, unannotated |
  | the replaced value | | relies on `testFrameworkInstance` using `jsEnvConfig` only to build the env (`SJM` lines 398–406) |

  - `jsEnvConfig` is also read by `scalaJSJsEnvMvnDeps`, which picks the env's artifact (`SJM` line 70). Mill evaluates that with the module's own value. *Inference:* since the replacement keeps the case class, the artifact is the same.

### 2.2 A plugin-built `TestAdapter`

`spike.adapter` (`B` lines 598–648) built `new TestAdapter(new JSDOMNodeJSEnv(Config().withExecutable(<launcher>).withArgs(…).withEnv(…)), Seq(Input.Script(<linked main.js>)), …)` from the module's `fastLinkJSTest` and `jsEnvConfig`. It loaded the framework, ran `alpha.A1` and `alpha.A2` through the runner, and closed the adapter.

- On both platforms the 8 tests passed, the 10 `Logger` lines arrived, and the VM's stdout was in the launcher's file, header first.
- **What it cost:**
  - `scalajs-sbt-test-adapter_2.13:1.20.2` and `scalajs-env-jsdom-nodejs_2.13:1.1.1` on the build's class path, at fixed versions, not the module's. Mill's worker loads them per module in its own class loader (`JNC` § 6.1).
  - The `Input` built by hand from `Report`; Mill's `jsEnvInputs` is `private[mill]` (`JNC` § 6.1).
  - Discovery and the task loop. The prototype named the suites itself; Mill's are in `@internal` `TestRunner` and `TestRunnerUtils` (`MTW` § 6).
  - No `TestModule.handleResults`, so no JUnit XML or Mill result.
  - One `JSEnv` class per environment: `NodeJSEnv` `1.5.0` and the jsdom env's `scalajs-env-nodejs` `1.0.0` share a package. Not run under `NodeJs`.

### 2.3 Other routes found, not run

- **A Node preload.** `jsEnvConfig`'s `args` could `--require` a script that sends `process.stdout` and `process.stderr` writes to files. It would miss writes that bypass those streams. Observed: under `NodeJs`, `dieload`'s fatal-error report, 1 016–1 651 B, reached the `.err` file but not the witness's count of `process.stderr.write` (§ 5).
- **Overriding `jsEnvConfig` in the module** breaks `SPECIFICATION.md` R-092.
- **A `node` earlier on the `PATH`.** *Inference:* it would apply only where `executable` is `node`, and it would act through Mill's own environment.

## 3. The launcher

### 3.1 What it is

- **Windows** (`LW`): a C program, built once with `cl.exe` and copied into each module's folder with a `launcher.cfg` naming the real executable and the mode.
  - It creates `<own folder>\<own PID>.out` and `.err`, shareable for reading, writing and deleting.
  - It takes its own command line after its own path, verbatim, and starts `"<real executable>"<rest>` with `CreateProcess`, suspended (`LW` lines 59–63, 104). Only stdin and the two files are inheritable into the VM (`LW` lines 76–90).
  - Mode `job` puts the VM in a job object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` (`LW` lines 93–106).
  - It reads the VM's creation time with `GetProcessTimes`, rebased to epoch ms as the JDK computes `startInstant()` (`NNFC` § 1). It writes the header through the same file handle, resumes the VM, waits for it, writes `<own PID>.exit` with the exit code, and exits with that code (`LW` lines 108–135).
- **Linux:** a `/bin/sh` script generated per module with the folder and the real executable baked in (`B` lines 380–404).
  - It reads its own start ticks from `/proc/$$/stat` and `btime` from `/proc/stat`, and writes the header with `CLK_TCK` baked in.
  - It then runs `exec '<real>' "$@" >> "$D/$$.out" 2> "$D/$$.err"`, so the VM keeps the launcher's PID and start time.
- **A trial before the matrix:** the script, generated from a `build.mill` checked out with CRLF line ends, had `#!/bin/sh\r` and could not start. Every module failed with `RunTerminatedException`, and nothing reached Mill's streams or any file.

### 3.2 Transparency

| Check | Windows | Linux |
|---|---|---|
| Program fed on stdin, outside Mill | ran | ran |
| Arguments `- "a b" "c\"d"` | reached the VM as `-`, `a b`, `c"d` | the same |
| Exit code of `process.exit(7)` | launcher exited 7; `.exit` file `7` | exited 7 |
| Inside Mill: com channel and test `Logger` | every run that finished delivered `DONE-RETURNED`; the `Logger`'s lines reached Mill's stdout from the `ComRun receiver` thread, as in `JDC` § 3 | the same |
| Bytes Mill's `PipeOutputThread` copied from the launcher's own stdout and stderr | 0 and 0 in 60 of 60 runs | 0 and 0 in 52 of 52 |

- On Windows the launcher's `.exit` file held `0` after every one of the 20 normal exits and `1` after 2 of the 4 `NodeJs` `dieload` runs. It was absent after every other death.
  - *Inference:* the adapter's `ExternalJSRun.close()`, which calls `destroyForcibly()` (`JE/ExternalJSRun.scala` lines 177–180), ended the launcher before it wrote the file. `NSW` § 4 saw the same with a `.cmd` launcher.

### 3.3 A kill of the launcher

| | Windows, mode `job` | Windows, mode `nojob` | Linux |
|---|---|---|---|
| Outside Mill, VM idle, 300 ms after the launcher was killed | VM gone | VM alive; it was killed by hand | VM gone: it is the launcher (`exec`), and `/proc/<pid>/comm` read `node` |
| In Mill, `killlauncher`, launcher killed at `SLEEPING` | VM exit 8–18 ms after the kill (4 runs) | VM exit 30 026–30 030 ms after the kill in 3 of 4 runs; 11 ms in 1 | VM exit 3–5 ms after the kill (4 runs) |
| What the VM wrote after the kill | 0 B | 282–414 B; all in the files and read | 0 B |

- **Without a job object the VM outlived its launcher.** Each orphaned VM's stderr ended `Scala.js Com failed: Error: write ECONNABORTED` or `read ECONNRESET`, and its witness recorded exit code `-1`. That is the com client's `error` handler, `process.exit(-1)` (`NE1/ComSupport.scala` line 284).
  - *Inference:* the launcher's exit failed the run's future, so the com run force-closed the socket (`NE1/ComSupport.scala` lines 43–45). The VM saw the close only when its event loop turned. In 3 runs that was after the 30 s sleep; in the fourth the kill landed before the sleep began.
  - In a trial with the launcher killed mid-burst, the orphaned VM finished the burst, about 2.1–2.2 MB, into the files, and exited 163 ms and 587 ms after the kill.
- **On Linux,** a kill of the launcher is a kill of the VM, as `JDC`'s runs had without a launcher.

### 3.4 Start-up cost

| | Windows | Linux |
|---|---|---|
| `node -`, 30 starts | min 31, median 32, max 39 ms | min 17, median 19, max 22 ms |
| launcher `-`, 30 starts | min 43, median 44, max 58 ms | min 19, median 20, max 22 ms |
| Launcher's own set-up, start to VM resumed, in Mill | 10.2–28.3 ms, median about 13 ms (52 VMs, mode `job`); 7.3–11.3 ms (8 VMs, `nojob`) | not measured |

## 4. Locating the files

- The module's config is unchanged; only the launcher knows the folder. On Windows the launcher writes into its own folder. On Linux the folder is in the script's text. The plugin writes one launcher per module per command into `out/spike-run/<stamp>/streams/<module>/` (`B` lines 380–404).
- Each VM's pair is named by the launcher's PID, so two VMs from one launcher get separate pairs. Every module run here had exactly one `.out` and one `.err` (112 of 112): one VM per run, as `NNFC` § 5.2 found.
- The header was always the `.out` file's first line, read 125–2 664 ms before the run's item 1 was written. Under `JsDom` that gap was 0.75–2.66 s, jsdom's load; under `NodeJs` it was 0.13–0.31 s.

## 5. Death

Each cell: runs in which every byte the witness counted as handed, on stdout and on stderr, was in the files and read by the plugin.

| Case | `JsDom`, Windows | `JsDom`, Linux | `NodeJs`, Windows | `NodeJs`, Linux |
|---|---|---|---|---|
| `halt`: `process.exit(1)` in the next suite, reader keeping up | 2 of 2 | 2 of 2 | 2 of 2 | 2 of 2 |
| `haltlag`: the same, reader paused | 2 of 2 | 2 of 2 | 2 of 2 | 2 of 2 |
| `kill`: VM killed at burst item 5 000, reader keeping up | 4 of 4 | 2 of 2 | 4 of 4 | 2 of 2 |
| `killlag`: VM killed, reader paused | 2 of 2 | 2 of 2 | 2 of 2 | 2 of 2 |
| `killstart`: VM killed at item 1 | 2 of 2 | 2 of 2 | 2 of 2 | 2 of 2 |
| `killlauncher`: launcher killed | 4 of 4 | 2 of 2 | 4 of 4 | 2 of 2 |
| `diehook`, `dieload` | 4 of 4 | 4 of 4 | 4 of 4 | 4 of 4 |

- **Node wrote stdout and stderr synchronously.** In every run the witness reported both streams as `SyncWriteStream`, with 0 B queued in Node at every sample, `process.exit` included.
  - `[node]` `doc/api/process.md` line 4189: writes to files are synchronous on Windows and POSIX. For pipes they are asynchronous on POSIX (line 4191).
  - So `haltlag` handed its whole burst, 2.76–2.80 MB, before `process.exit(1)`. Under `JDC` on Linux, 2.69 MB stayed queued in Node.
- **A paused reader did not hold the VM back.** In `haltlag` the VM finished the burst and died 0.61–0.77 s into the pause. In `killlag` it had written all 20 000 burst items before the kill. The reader then read 2.50–2.79 MB after the VM's exit. On Windows, under `JDC`, the VM had blocked in its own stdout write (`JDC` § 4).
- **The Linux loss of `JDC` § 4 did not occur.** No byte reached the files after the VM's exit (§ 7), so the adapter's `destroy` of the launcher, which closes only Mill's pipes on Linux (`JDC` § 4), had nothing to lose. Those pipes carried 0 B (§ 3.2).
- **Bytes beyond the witness's count.**
  - In 7 `kill` runs the stdout file held 130 or 132 B more than the witness: one burst item. *Inference:* the kill landed between the item's write and the witness's sample.
  - In the 4 `NodeJs` `dieload` runs the stderr file held 1 016 B (Linux) or 1 651 B (Windows) more. This is Node's report of the uncaught load error, which the witness's patched `process.stderr.write` never saw. The `.err` file had it.
- **How far the VM got before a kill:** `kill` files held 5 551–11 290 burst items; `killstart` files 1–2 items.

## 6. Normal exit

- In every run that finished (`alpha`, `beta`, `noisy`, `empty`, `tail`; 10 per environment and platform), the items ended `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE`, with no gap and no unparseable frame.
- Every `tail` run held all 20 000 items of its last test. The witness's exit sample showed 0 B queued and as many bytes handed as the files held, less the header.
- The last item was written 89–174 ms before the VM's exit hook ran, Mill's `handleResults` and 100 ms sleep (`SJM` lines 424–429).
- **Interleaving.** Every `noisy` run (8) had 2 031 items and no gap. Its stdout lines all passed through whole: 2 000 `println` lines, 20 lines left open by `print` across an item, and 20 lines of 20 000 characters. There were 40 forged or prefix-only lines and 20 stderr lines. Node is the only writer of the file, so no line came through Mill's writer threads (`JDC` § 3).

## 7. End

Times in ms, as minimum / median / maximum.

| Signal | `JsDom`, Windows | `NodeJs`, Windows | `JsDom`, Linux | `NodeJs`, Linux |
|---|---|---|---|---|
| (x) VM `onExit`, after its exit hook, normal exit (10 each) | 15 / 17 / 24 | 7 / 8 / 12 | 4 / 5 / 6 | 3 / 3.5 / 4 |
| (x) after the last item written, normal exit | 105 / 121.5 / 195 | 96 / 111.5 / 116 | 105 / 108 / 135 | 104 / 106 / 108 |
| (l) launcher `onExit`, after the exit hook | 17 / 18.5 / 26 | 8 / 10 / 14 | = (x) | = (x) |
| (e) task returned, after the exit hook | −1 / −0.5 / 0 | −1 / −1 / 0 | −1 / −0.5 / 0 | −1 / −0.5 / 0 |
| (x) after `destroyForcibly()` of the VM | 13 / 17 / 20 (8) | 7 / 8 / 9 (8) | 4 / 8 / 17 (6) | 2 / 6 / 11 (6) |
| (x) after a death by `process.exit(1)` or uncaught load error | 14–42 (8) | 7–14 (8) | 5–9 (8) | 3–8 (8) |
| (p) `PipeOutputThread` end | not seen: it wrote nothing, so the plugin never learned its thread | | | |

- **The PID-based end held for every VM.** The header's start time equalled `ProcessHandle`'s in 112 of 112 VMs, each checked 0–17 ms after the header was read, with every VM still alive. In every run the files' size at (x) equalled their final size: no byte reached a file after the VM's exit. At (x) the reader was behind in 24 runs, by up to 2.79 MB in the paused ones. Reading every file to its end after (x) gave every byte (§ 5).
  - This is the end `ARCHITECTURE.md` § APIs § The emitter and the Mill plugin gives the JVM, `NodeJs` and Native. The PID and start time come from the launcher's header, not from item 1, and the start time is the launcher's on both platforms.
- **(l) on Windows** fired from 16 ms before to 3 ms after (x). The launcher waits for the VM before it exits (`LW` lines 121–135). *Inference:* the order reflects the JDK's waiter threads, not the order of the exits. When the launcher was killed in mode `nojob`, (l) came up to 30 s before (x) (§ 3.3).
- **(e)** came within 1 ms before a normal exit, and 3–40 ms after the exit hook of a VM that ended by `process.exit(1)` or an uncaught load error. At (e) the reader was behind in 28 runs, so the task's return does not end the stream.

## 8. Live

| | `JsDom`, Windows | `NodeJs`, Windows | `JsDom`, Linux | `NodeJs`, Linux |
|---|---|---|---|---|
| Item delay, every non-burst item of `alpha`, `beta`, `noisy`, `empty`, `tail` (4 272 each) | median 6, p95 13, largest 29 ms | median 6, p95 13, largest 31 ms | median 5, p95 10, largest 28 ms | median 5, p95 10, largest 62 ms |
| Median delay per run (10) | 5–26 ms | 3–9 ms | 4–11 ms | 5–17 ms |
| Largest delay per run | median 16, largest 29 ms | median 13, largest 31 ms | median 14, largest 28 ms | median 11, largest 62 ms |
| Largest delay in the 20 000-item burst (`tail`) | 20, 23 ms | 17, 17 ms | 42, 19 ms | 68, 85 ms |

- The tailer polls every 10 ms. *Inference:* the poll accounts for most of the median, against `JDC` § 2's 0 ms through the pipe.

## 9. No task

| Fixture | Every platform and environment, 2 runs each |
|---|---|
| `empty` | item 1, `RUNNER-RETURNED`, `TASKS 0`, `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE` |
| `diehook` | item 1 only. Its `.err` held the cause: jsdom's report of the uncaught error, 31 lines, under `JsDom`; `SPIKE-EXIT-1`, 1 line, under `NodeJs` |
| `dieload` | no item. Its `.out` held `DIELOAD-STDOUT before any run`; its `.err` held `DIELOAD-STDERR before any run` and the error, 34 lines under `JsDom`, 18 under `NodeJs` |

Every VM in these runs wrote its header and was found alive by its PID (§ 7).

## 10. Against the requirements

What the runs show against each requirement the issue names, in `SPECIFICATION.md`:

| Requirement | Windows | Linux |
|---|---|---|
| R-092, no change to the module | Held: only the plugin's evaluation saw the launcher (§ 2.1) | Held |
| R-011, nothing lost or altered | No gap or unparseable item in 60 runs (§ 6) | The same, 52 runs |
| R-071, a run with no task, and a death before its first task | Item 1 arrived for every `empty`, `diehook` and `killstart` run (§ 9) | The same |
| R-073 with R-042, everything handed to the OS arrives when the process dies | Every handed byte arrived, stdout and stderr, reader keeping up or paused (§ 5) | The same; `JDC`'s 64 417 B loss did not recur |
| R-074, how the run ended | `DONE-RETURNED` arrived for every run that finished and for none that died (§ 6) | The same |
| R-076, output of a process that ended before any run | `dieload`'s stdout and stderr lines captured, with no item (§ 9) | The same |
| R-088, each piece of output once | No item among the passed-through lines; Mill's pipes carried 0 B (§ 3.2) | The same |

## 11. Against the earlier notes

- `JDC` § 4 found 64 417 B lost on Linux when `destroy` closed Mill's stdout pipe. With the launcher that pipe carried nothing, and nothing was lost (§ 5).
- `JDC` § 3 found that a paused console sink stalled every writer to Mill's `System.out`. The file reader pauses without blocking any Mill thread or the VM (§ 5).
- `JDC` § 6.3 found that a `JsDom` run cannot write its PID or start time. The launcher writes both for it (§ 7).
- `JDC` § 8 found no `ProcessHandle` field that told one module's VM from another's. The header's PID names the VM, one per file pair (§ 4).
- `NNFC` § 5.1 needed PowerShell, 178–756 ms per run, for a Node VM's start time on Windows. The launcher read it with `GetProcessTimes`, inside a set-up of 7–28 ms (§ 3.4).
- `JNC` § 5.1's inference that stdout queued in Node at `process.exit` is lost on POSIX does not arise: with a file, Node queued nothing (§ 5).

## 12. What was not run or not determined

- macOS; `--no-daemon`; the `ExoegoJsDomNodeJs` environment; versions other than § 1 lists.
- A `.cmd` or PowerShell launcher on Windows; a compiled launcher on Linux.
- A launcher on Windows that keeps the VM's PID; *General:* Windows has no `exec`.
- Two runs in one VM, or a run whose framework calls `slaveRunner`.
- A full disk, a file the plugin cannot open, or a VM that writes its stdout through a child process.
- The plugin-built `TestAdapter` under `NodeJs`, with Mill's discovery, or with results reported to Mill (§ 2.2).
- What a launcher in mode `nojob` does when the VM ignores the com close: whether the orphan can outlive the test command.
- Whether `ProcessHandle.onExit` on Windows orders the launcher's and the VM's exits as they happened (§ 7).
- Poll intervals other than 10 ms.
