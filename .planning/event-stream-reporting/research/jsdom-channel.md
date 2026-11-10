# Console lines and jsdom's WebSocket as a JsDom run's channel to the Mill plugin

What was observed when a stub emitter in each Scala.js run under Mill's `JsDom` JS environment sent its run's items either as framed lines through the window's `console.log`, which the plugin read out of the VM's stdout, or as messages over jsdom's `WebSocket` to a server in plugin code, under Mill on Windows and on Linux. Answers [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52) for those two candidates, including the check its comment adds. Observations only; no channel is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/node-native-file-channel.md`, cited as `NNFC`: the `NodeJs` and Native spike whose shim framework, fixtures, kill method and plugin structure this one reuses.
- `.planning/event-stream-reporting/research/jvm-file-channel.md`, cited as `JFC`.
- `.planning/event-stream-reporting/research/js-environment-channels.md`, cited as `JEC`: the `JsDom` env, its console forwarding and its `jsdomError` handler (§ 2).
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: how a VM's stdout reaches Mill (§ 5.1) and the com channel's exit (§ 2.1).
- `.planning/event-stream-reporting/research/mill-test-without-module-trait.md`, cited as `MTW`: plugin code evaluating a module's own test task inside `withStreams`.

A statement marked *Inference:* follows from the runs or the cited source and was not observed directly. Sources are pinned at the versions the runs used:

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `[sjs]` | <https://github.com/scala-js/scala-js> | tag `v1.20.2` (`6af939aab0be02540dbaf29d8bc01e509fd86aa3`) |
| `[sjs1]` | <https://github.com/scala-js/scala-js> | tag `v1.0.0` (`e6a24304c00cf66cb79627c23a53456d3ab8b7be`), the `scalajs-env-nodejs` version `JEC` § Sources infers the jsdom env loads; resolution was not run |
| `[envs]` | <https://github.com/scala-js/scala-js-js-envs> | tag `v1.4.0` (`ef4576137227545ce4f534c935b7de1690f33db5`), the `scalajs-js-envs` version `JEC` § Sources infers for non-Node envs |
| `[jsdomenv]` | <https://github.com/scala-js/scala-js-env-jsdom-nodejs> | tag `v1.1.1` (`1bef3aeba3c9d2811604c808d6d1a1077ba54433`) |
| `[jsdom]` | <https://github.com/jsdom/jsdom> | `27.4.0` (`098d16d6b86c5f215d48658c3005cb54b1325603`), installed with npm in the prototype folder; it brought `ws` `8.22.2` |
| `[jdk]` | <https://github.com/openjdk/jdk21u> | tag `jdk-21.0.5-ga` (`dfcd8d2eecfe62e91070244d8eb10906dc3c812d`), a reference version; the runs used Zulu `21.0.10` |
| `[this]` | this repository | branch `epic/event-stream-reporting`; the runs used the published ScalaTest `3.2.19` artifacts |

Abbreviations: `SJM` and `SSU` in `[mill]` as `JNC` § Sources defines them; `TA/` in `[sjs]`; `NE1/` is `[sjs1]` `nodejs-env/src/main/scala/org/scalajs/jsenv/nodejs/`; `JE/` is `[envs]` `js-envs/src/main/scala/org/scalajs/jsenv/`; `JD` is `[jsdomenv]` `jsdom-nodejs-env/src/main/scala/org/scalajs/jsenv/jsdomnodejs/JSDOMNodeJSEnv.scala`; `WSI` is `[jsdom]` `lib/jsdom/living/websockets/WebSocket-impl.js`. In the prototype, `B` is `build.mill`, `EM` `emitter/src/spike/Emitter.scala`, `NF` `emitter/src/spike/NotingFramework.scala`, `PL` `emitter/src/spike/Platform.scala`, `W` `witness.js`, and `R/` its `runs/`.

## Short answer

Per channel and platform. "Handed" is what the VM's witness (§ 1) last saw handed to the operating system; bytes still inside Node are excused by `SPECIFICATION.md` R-073.

| Point | Console, Windows 11 | Console, Ubuntu under WSL 2 | WebSocket, Windows 11 | WebSocket, Ubuntu under WSL 2 |
|---|---|---|---|---|
| **Live** (§ 2) | Item delay median 0 ms; largest per run median 15.5 ms, largest 120 ms. 20 000-line burst: largest 2 ms | Median 0 ms; largest per run median 7 ms, largest 39 ms. Burst: largest 76 ms | Items leave only when window code yields: median per run 1–404 ms, largest 850 ms. Burst: largest 358 ms | Median per run 8–403 ms, largest 821 ms. Burst: largest 196 ms |
| **Interleaving** (§ 3) | Every item and every test line whole, in 2 of 2 `noisy` runs, with the plugin's sink assembling lines per writing thread. With one buffer for all writers, 2 items were corrupted in 1 of 24 Linux runs | as Windows | Test lines on stdout unaffected; items on their own socket | as Windows |
| **Death, reader keeping up** (§ 4) | Every handed byte arrived: uncaught exception (`jsdomError`, 2 runs), kill mid-burst (2), kill at item 1 (2) | The same | The same, though each close was a reset | The same |
| **Death, reader paused** (§ 4) | Node blocked in its own stdout write; every handed byte arrived (4 runs) | Kill: 64 417 handed bytes lost in 2 of 2 runs, when the adapter's close closed the pipe. Uncaught exception: none lost; 2.69 MB still inside Node | 2 288 686–2 288 752 handed bytes lost in 4 of 4 runs: the connection was reset | Every handed byte arrived (4 runs) |
| **Normal exit** (§ 5) | All 20 000 last-test lines, `DONE-RETURNED` and `ASYNC-AFTER-DONE` arrived; nothing queued in Node at `process.exit(0)` (10 runs) | The same | The same | The same |
| **End** (§ 6) | The module's test task returned 0–2 ms before a normal exit, with every byte in. No byte came after it in this matrix; in the first, 196 B and 30 B came after it in two runs whose VM died. `PipeOutputThread` ended 8–117 ms after a normal exit; no byte came after it | Task 1 ms before to 1 ms after a normal exit; no byte after it. `PipeOutputThread` ended 7–23 ms after the exit; no byte after it | Close (reset) 3–5 ms after a normal exit | Close (end of stream) 4–13 ms after a normal exit |
| **No task** (§ 7) | Empty run: item 1 to `ASYNC-AFTER-DONE`. Death at runner construction: item 1 arrived (2 of 2). Death before any run: its stdout and stderr captured | The same | Item 1 lost in 2 of 2 deaths at runner construction; the socket opened 11–33 ms after it | Lost in 2 of 2; open after 11–59 ms |
| **Finding the VM** (§ 8) | Window code has no `process`. `ProcessHandle` descendants held every running VM but no argument to tell them apart | as Windows | as Windows | as Windows |

Not run: macOS; `--no-daemon`; the `ExoegoJsDomNodeJs` environment; jsdom other than `27.4.0`; the synchronous `XMLHttpRequest` and `Logger` candidates of the issue; a plugin-built `TestAdapter` or `onOutputStream` wrapper; a run whose framework calls `slaveRunner`; two runs in one VM (§ 11).

## 1. What was run

The prototype is on branch `prototype/jsdom-channel`, in `.planning/event-stream-reporting/prototype-jsdom-channel/`, commit `b4edcb2a4`. It reuses `NNFC`'s shim framework, fixtures and plugin structure, changed as follows.

- **The environment.** Every fixture's `jsEnvConfig` is `JsEnvConfig.JsDom`, with `NODE_PATH` naming the prototype's `node_modules` in `env`, so that the env's program finds jsdom, and two Node arguments in `args`: `--require witness.js` and `--title=spike-<fixture>` (`B` lines 59–68).
- **The window.** Every run's first item records `typeof process`, `typeof require` and `typeof WebSocket` as window code sees them (`PL` lines 13–15). In all 44 first items received they were `undefined`, `undefined` and `function`, at `http://localhost/`. So window code learns nothing but its framework arguments: the channel, the framing prefix, a per-command nonce, the run ID, the WebSocket URL and the test hook (`B` lines 503–508).
- **The emitter,** `spike.Emitter` (`EM`), opened by the shim on the thread that constructs the runner (`NF` lines 25–33). Each item is one tab-separated record: type, sequence number, write time, kind, three fields. Item 1 is a `HEADER` record.
  - `console`: each record is one `console.log` call of `<prefix>\t<nonce>\t<runId>\t<record>` (`EM` lines 76–82). jsdom forwards it synchronously to Node's `console.log` (`JEC` § 2.3).
  - `ws`: the runner's construction calls `new WebSocket(<url>)`; records are queued until `open`, then each is one text message `<runId>\t<record>`. On `open` the emitter flushes the queue and adds a `WS-OPEN` item carrying the delay since construction (`EM` lines 58–84).
- **Dying from inside.** Window code cannot kill its process. `Platform.dieUncaught` throws from a listener inside a dispatched event, so jsdom reports the exception at once (`[jsdom]` `lib/jsdom/living/events/EventTarget-impl.js` line 363; `lib/jsdom/living/helpers/runtime-script-errors.js` lines 42–70) and the env's `jsdomError` handler calls `process.exit(1)` (`JD` lines 89–111) before `dispatchEvent` returns (`PL` lines 33–38). In a trial before the matrix, a throw from `setTimeout(0)` under the WebSocket channel fired only after `done()`, behind jsdom's flush of 20 000 queued sends.
- **The witness,** `W`, is instrumentation, not part of either channel. Node preloads it in the env's Node process, outside the window. It writes `<pid>.txt` synchronously with the VM's PID and title and, after each stdout write and each `ws` send in the death fixtures (every 200 elsewhere), every 20 ms, and at `process.on('exit')`, the bytes handed to the operating system: `bytesWritten` less `writableLength`, for stdout and for the WebSocket's socket.
  - *Inference:* on Windows, where pipe writes are synchronous, the figure is exact. On Linux a write libuv has completed only in part still counts in `writableLength` whole, so the figure is a lower bound.
- **The plugin,** `spike.test <selector>...`, an exclusive command taking the `Evaluator` (`B` lines 456–581). `SPIKE_CHANNEL` picks the channel.
  - It evaluates each module's own `testForked` under a plugin-built `TaskCtx`, as `NNFC` § 1 did. The evaluation runs inside `SystemStreamsUtils.withStreams` over two sinks per module, so it evaluates `testFrameworkInstance`, which starts the VM, inside them (`B` lines 420–452; `MTW` § 3.3).
  - **Console sink** (`B` lines 233–286). It splits what it receives into lines, one partial-line buffer per writing thread; `SPIKE_SINK=merged` keeps one buffer for all writers. A stdout line that starts with `<prefix>\t<nonce>\t` is an item; every other line passes through and is kept. It records which threads write to it, and takes the one whose class is `org.scalajs.testing.adapter.PipeOutputThread` as the VM's stdout copier. To pause, it blocks inside `write`, so `PipeOutputThread` stops draining the pipe.
  - **WebSocket server** (`B` lines 291–379): RFC 6455, text frames only, a thread per connection, on `127.0.0.1`. The request path names the module. To pause, it stops reading the socket.
  - **Finding the VM** (`B` lines 173–201). When item 1 arrives it lists `ProcessHandle.current().descendants()` whose executable is `node`, and looks for `spike-<fixture>` in each one's `commandLine()` and `arguments()`. Where no single VM matches, it takes the PID from the witness file of that title and records whether that PID is among the descendants. It kills with `destroyForcibly()` and watches `onExit()` on that PID.
  - **End signals,** per run: (e) the module's test task returning; (p) the end of the stdout `PipeOutputThread`, polled every 10 ms; (c) the WebSocket's close, with how it closed; (x) `onExit` of the VM.
- **Fixtures,** one module each. The death fixtures put the burst and the death in separate suites, so the event loop turns between them:

  | Fixture | What it does |
  |---|---|
  | `alpha`, `beta`, `empty`, `tail`, `killstart` | as `NNFC` § 1; `killstart`'s VM is killed when item 1 arrives |
  | `noisy` | 2 000 items, each after a `println`; every 100th item also inside an open `print`, with a stderr line, a line forged with the prefix and a wrong nonce, a line of the prefix alone, and a 20 000-character line |
  | `halt` | two tests pass, a 20 000-item burst, `HALTING`; then the next suite dies by an uncaught exception |
  | `haltlag` | as `halt`, but the plugin stops reading at burst item 1 until 1 s after the VM exits, at most 8 s; the death comes 500 ms into the next suite |
  | `kill` | one test passes, a 20 000-item burst, `SLEEPING`; the next suite sleeps 30 s. The plugin kills the VM when it reads burst item 5 000 (fallback: 4 s after item 1) |
  | `killlag` | as `kill`, but the plugin stops reading at burst item 1 (fallback: 2.5 s after item 1), kills 1.5 s later, and reads again 2 s after the kill |
  | `diehook` | the framework argument `-Dspike.dieAfterRunner=1`: an uncaught exception as soon as ScalaTest's runner exists, before construction returns |
  | `dieload` | the script throws while it loads, after printing a line to stdout and one to stderr, before any runner exists |

- **Matrix.** `run.ps1` (Windows) and `run.sh` under `run-wsl.ps1` (Linux, in a copy of the prototype in the WSL file system, after `npm ci`), two rounds of: `spike.test` over the ten fixtures other than `haltlag` and `killlag`, then over `haltlag` alone and `killlag` alone, once with each channel; 12 commands per platform. The paused fixtures run alone because a paused console sink stalls others (§ 3). A first full matrix with the merged sink is kept in `R/first-matrix-merged-sink/`; § 3 cites it and the other figures come from the second.
- **Outputs.** Each command's stdout, stderr, `summary.tsv`, per module the items without burst lines, the passed-through stdout, the stderr and a trimmed witness, in `R/<platform>-<channel>-<case>-r<n>/`. `analyse.py` summarises them in `R/analysis.txt`.
- **Delay** is an item's receipt time less the write time in it.

Versions: Mill `1.1.10`, Scala `3.3.6`, Scala.js `1.20.2`, ScalaTest `3.2.19`, jsdom `27.4.0`, `ws` `8.22.2`; Mill on Azul Zulu `21.0.10`, fetched by Mill. Windows 11 (`10.0.22631`) with Node `24.13.1`; Ubuntu 26.04 under WSL 2 (kernel `6.18.54.1`) with Node `22.21.1`.

## 2. Live

| | Console, Windows | Console, Linux | WebSocket, Windows | WebSocket, Linux |
|---|---|---|---|---|
| Item delay, all items of `alpha`, `beta`, `noisy`, `empty`, `tail` | median 0, p95 1, largest 120 ms | median 0, p95 3, largest 39 ms | median 54, p95 101, largest 850 ms | median 76, p95 105, largest 821 ms |
| Median delay per run (10 runs) | 0–1 ms | 0–9.5 ms | 1–404 ms | 8–403 ms |
| Largest delay per run | median 15.5, largest 120 ms | median 7, largest 39 ms | median 424, largest 850 ms | median 311.5, largest 821 ms |
| Largest delay in a 20 000-item burst (`tail`) | 1, 2 ms | 1, 76 ms | 349, 358 ms | 195, 196 ms |
| Sequence gaps | 0 | 0 | 0 | 0 |

- **Why the WebSocket lags.** jsdom's `send` puts each message on a queue that `process.nextTick` hands to `ws` (`WSI` lines 285–322), so nothing a test sends leaves the process until window code yields. ScalaTest runs a suite's tests in one synchronous call, so a suite's items leave when the suite ends: `alpha`'s suites of four 200 ms tests gave a median of 402–404 ms and a largest of 813–850 ms.
- **Console delay** includes the stdout pipe and the 8 192-byte reads of `PipeOutputThread` (`TA/PipeOutputThread.scala` lines 28–44).

## 3. Interleaving

| | Console, Windows | Console, Linux | WebSocket, Windows | WebSocket, Linux |
|---|---|---|---|---|
| `noisy` items, gaps | 2 000 + 20 of 2 020, 0 gaps (2 runs) | the same | the same | the same |
| `println` lines whole in the passed-through stdout | 2 000 of 2 000 | the same | the same | the same |
| Lines opened by `print`, closed after an item | 20 of 20 whole | the same | the same | the same |
| Forged lines (wrong nonce), prefix-only lines, 20 000-character lines | 20, 20, 20 of 20, passed through | the same | the same | the same |
| Items found among the passed-through lines | 0 | 0 | 0 | 0 |
| stderr lines | 20 of 20 | the same | the same | the same |

- **The VM's side.** Scala.js's `System.out` holds a partial line until its newline, and each item is one `console.log` call, so an item never landed inside a test's line, nor a test's line inside an item, in any run.
- **The plugin's side.** The stream handed to `withStreams` received more than the VM's stdout: the stdout `PipeOutputThread` wrote to it in every console run, and Mill's `ComRun receiver` thread, which prints the test `Logger`'s lines, in 33 of 48. A Mill thread wrote to stderr.
  - `PipeOutputThread` cuts the pipe into 8 192-byte reads regardless of lines.
  - With one partial-line buffer for all writers (`R/first-matrix-merged-sink/`), Mill's lines `Test result: 1 completed.` and `H2Die:` landed inside two items in `linux-console-haltlag-r2`. The run had 2 unparseable frames and a sequence gap at item 201. The other 23 Linux and 24 Windows console runs of that matrix had none.
  - With a buffer per writing thread, no run of the second matrix had a gap or an unparseable frame.
- **A paused sink stalls more than its pipe.** Mill's `System.out` is `ThreadLocalStreams.Out`, a `PrintStream` subclass (`SSU` lines 101–110). A subclass's `write` takes the stream's monitor (`[jdk]` `src/java.base/share/classes/java/io/PrintStream.java` lines 122–126, 618–632). So while the paused sink blocked inside `PipeOutputThread`'s write, every other writer to `System.out` in Mill's process waited.
  - On Linux the `haltlag` VM finished its burst into Node's memory, then wrote nothing for 8 s, the whole pause. Its next suite started only once the pause ended (§ 4).
  - *Inference:* Mill's `ComRun receiver`, which prints the `Logger`'s lines through `System.out`, was waiting on that monitor, so the run's com traffic stopped.
  - For this reason the paused fixtures ran alone.

## 4. Death

Received bytes against the witness's last "handed" figure, per run (2 per cell):

| Case | Console, Windows | Console, Linux | WebSocket, Windows | WebSocket, Linux |
|---|---|---|---|---|
| `halt`: uncaught exception, reader keeping up | all received; 2 639 624 B, 0 queued in Node | all received; 0 queued | all received; 2 299 606 B | all received |
| `kill`: killed at burst item 5 000, reader keeping up | all received; 5 023–5 034 burst items | all received; 5 030–5 271 items | all received; 5 009 items | all received; 5 029–5 034 items |
| `killstart`: killed when item 1 arrived | all received; 3 and 5 items | all received; 2 items | all received; 4 items | all received; 3 items |
| `diehook`: uncaught exception at runner construction | item 1 received | item 1 received | nothing sent | nothing sent |
| `haltlag`: uncaught exception, reader paused | all received; VM blocked in its stdout write | all received; 2.69 MB still queued in Node at `process.exit(1)` | **lost 2 288 752 B** of 2 290 116 handed | all received; 2 419 421 B |
| `killlag`: killed, reader paused | all received; 5 422 B handed | **lost 64 417 B** of 66 671 handed; 1.26–1.40 MB queued in Node | **lost 2 288 686 B** of 2 290 051 handed | all received; 2 399 424 B |

- **Console, Windows, paused.** Node's stdout writes to a pipe are synchronous on Windows (`JNC` § 5.1), so the VM stopped in its own write. In `haltlag` the burst's last items were written 8.15 s after the pause began, immediately after the 8 s cap ended it; in `killlag` the VM had handed 5 422 B when it was killed. Every handed byte arrived.
- **Console, Linux, `killlag`.** The pipe held about 64 KiB when the VM was killed. After the pause ended, `PipeOutputThread` died with `java.io.IOException: Stream closed`, and those bytes never arrived, in 2 of 2 runs. The same exception appeared on Linux in 2 `ws` `haltlag` runs, without a stdout witness to compare.
  - Source: the env's run is an `ExternalJSRun`, whose `close()` calls `destroyForcibly()` (`JE/ExternalJSRun.scala` lines 177–180). The com run calls it once the VM has ended, and on a failure (`NE1/ComSupport.scala` lines 88–95, 150–155).
  - On Linux, `destroy` closes the process's stdout and stderr after the kill, whether or not the process has exited (`[jdk]` `src/java.base/unix/classes/java/lang/ProcessImpl.java` lines 460–485). That close also discards what `processExited` had drained from the pipe at exit (lines 596–610). On Windows, `destroy` only terminates the process (`src/java.base/windows/classes/java/lang/ProcessImpl.java` lines 617–619).
  - *Inference:* on Linux, any stdout the plugin had not read through `PipeOutputThread` when the adapter closed the VM's run is lost, after a death or a normal exit alike. No normal exit here had any unread.
- **Console, Linux, `haltlag`.** Node itself queued the burst: 67 431–70 806 B handed and about 2.69 MB still inside Node when the VM ended by `process.exit(1)`. The plugin received 144 534–173 927 B, more than the witness's lower bound, and none of the run's later items.
- **WebSocket, Windows, paused.** Every connection to a VM that died or was killed, and every one at a normal exit, ended with `java.net.SocketException: Connection reset`. With the reader caught up nothing was lost. With it paused, the 2.29 MB that the witness saw handed to the socket did not arrive: the server read 1 364–1 365 B, then the reset. This matches `ARCHITECTURE-DECISIONS.md`'s socket finding for a JVM writer.
- **WebSocket, Linux, paused.** Every handed byte arrived after the death, and every connection ended with end of stream.
- **What Mill reported.** Every death failed its module's test task with `org.scalajs.testing.common.RPCCore$ClosedException: org.scalajs.testing.adapter.JSEnvRPC$RunTerminatedException`.

## 5. Normal exit

- **How the VM ends.** Mill calls `handleResults`, sleeps 100 ms and calls `close()` (`SJM` lines 424–429); the VM calls `process.exit(0)` when the com socket closes (`NE1/ComSupport.scala` line 287).
- **Observed.** In every run that finished (`alpha`, `beta`, `noisy`, `empty`, `tail`: 10 per channel and platform), `DONE-CALLED`, `DONE-RETURNED` and `ASYNC-AFTER-DONE` arrived, and nothing after them but, in some WebSocket runs, `WS-OPEN`. Every `tail` run held all 20 000 last-test items. The witness's `exit` sample showed 0 bytes queued in Node and as many handed as the plugin received, on stdout and on the socket alike. The VM exited 102–185 ms after `DONE-RETURNED` was written.
- **Under the WebSocket,** in 3 of 4 `empty` runs the socket opened after `DONE-RETURNED`: the whole run had been queued in the emitter, and was sent and received before the exit.
- *Inference:* the POSIX race of `JNC` § 5.1 did not lose any byte here because Node had handed everything to the kernel within Mill's 100 ms, both through the stdout pipe and through the socket. Work still queued in Node at `process.exit` is lost, as Linux `haltlag` showed (§ 4).

## 6. End

Times in ms from the VM's exit as the witness recorded it (normal exit, uncaught exception) or from the kill, as minimum / median / maximum.

### 6.1 Console

Death is the six death fixtures of § 4; (x) counts only runs whose VM the plugin found (§ 8).

| Signal | Windows, normal (10) | Windows, death (12) | Linux, normal (10) | Linux, death (12) |
|---|---|---|---|---|
| Last stdout byte | −173 / −99.5 / −87 | −517 / −2 / 2 004 | −140 / −103.5 / −100 | −510 / −8.5 / 2 003 |
| (e) the module's test task returned | −2 / −1 / 0 | 3 / 7.5 / 2 005 | −1 / −1 / 1 | 5 / 11.5 / 2 004 |
| (p) `PipeOutputThread` ended | 8 / 11.5 / 117 | 3 / 32.5 / 2 006 | 7 / 11 / 23 | 6 / 13 / 2 011 |
| (x) `onExit` of the found PID | 12 / 14 / 17 (8 runs) | 12 / 14 / 17 (10 runs) | 5 / 5.5 / 26 | 5 / 7 / 18 |
| Bytes after (e); after (p) | 0; 0 | 0; 0 | 0; 0 | 0; 0 |

- **(e)** marks the module, not the stream. On a normal exit it came within 2 ms of the VM's exit, about 100 ms after the last byte. After a death it came 3–62 ms after the exit or kill in the runs whose reader kept up. In the paused `killlag` runs it came 2 s after the kill, once the pause ended (§ 3). The first matrix had bytes after (e) in two Windows runs: `diehook`'s item 1, 196 B, and `dieload`'s load-time line, 30 B.
- **(p)** ended after the last byte in every run: 95–290 ms after it on Windows normal exits, 109–163 ms on Linux. It ends at end of stream, or, on Linux after a death, at `Stream closed` (§ 4).
  - Finding it needs a stdout write, from which the plugin learns the thread. Its class, `org.scalajs.testing.adapter.PipeOutputThread`, is private to the adapter, and Mill never waits for it (`TA/JSEnvRPC.scala` lines 26–47).
- **Several runs in one VM** were not run. *Inference:* (p) and (x) end the VM's output, not a run's, as `NNFC` § 5.2 found for process exit.

### 6.2 WebSocket

Death excludes `diehook`, whose VM never connected.

| | Windows, normal (10) | Windows, death (10) | Linux, normal (10) | Linux, death (10) |
|---|---|---|---|---|
| (c) close | 3 / 3 / 5 | 1 / 4.5 / 2 004 | 4 / 6 / 13 | 4 / 14 / 2 033 |
| How it closed | reset, every run | reset, every run | end of stream, every run | end of stream, every run |
| (x) `onExit` | 12 / 14.5 / 17 (8 runs) | 13 / 17.5 / 19 | 4 / 6 / 14 | 5 / 8 / 21 |
| Bytes after (e) | 0 | 0 | 0 | up to 2 398 059 B, in the paused runs |

- jsdom never closed a socket with a close frame: on a normal exit as after a death, the connection ended when the VM ended. Neither close kind says how the run ended; only a received `DONE-RETURNED` did.
- The largest close times, about 2 s, are the paused runs, whose reader saw the close only when it read again.

### 6.3 The issue's PID check

Window code has no `process` (§ 1), so a `JsDom` run cannot write its PID or start time. § 8 gives what the plugin could find.

## 7. No task

- **`empty`.** No test class; Mill called `runner`, `tasks` with none, and `done`. On the console every run delivered item 1, `RUNNER-RETURNED`, `TASKS 0`, `DONE-CALLED`, `DONE-RETURNED`, `ASYNC-AFTER-DONE`. On the WebSocket the same items arrived, with `WS-OPEN` among or after them (§ 5).
- **Death at runner construction (`diehook`).** Console: item 1 arrived in 4 of 4 runs, as the only item. WebSocket: nothing arrived in 4 of 4 runs; the VM died before its socket had opened.
  - Construction to `open` was 11–33 ms on Windows and 11–59 ms on Linux (20 runs each), with 2 or 6 items queued by then.
  - `killstart`, killed when item 1 arrived, delivered 3–4 items on the WebSocket, the first two queued until `open`.
- **Death before any run (`dieload`).** The load-time line on stdout reached the console sink's passed-through lines, and the stderr line its stderr, in 4 of 4 runs per platform, with no item, under either channel. Mill reported `RPCCore$ClosedException`.
- **Output appears once.** Across every run, no item appeared among the passed-through lines (§ 3). The test `Logger`'s lines reached the same sink from Mill's `ComRun receiver` thread, distinct from the VM's stdout only by the writing thread.

## 8. Finding the VM

| | Windows | Linux |
|---|---|---|
| `node` descendants of Mill's daemon when item 1 arrived | 1 with one module; 6–9 with ten modules in parallel | 1; 7–10 |
| Descendants whose `commandLine()` or `arguments()` held `spike-<fixture>` | 0 in every run | 0 in every run |
| What `ProcessHandle.Info` gave for a `node` descendant | no command line, no arguments | the executable's path; no arguments |
| VMs searched for (item 1 arrived) and found among the descendants, by the witness's PID | 36 of 42 | 42 of 42 |
| Cost of one search | 18–47 ms | 1–11 ms |

- **Every VM the plugin looked for while it was alive was a descendant of Mill's daemon.** Its `onExit` fired 4–26 ms after the witness recorded a normal exit or `process.exit(1)`.
- **No `ProcessHandle` field told the VMs apart** when several modules ran together, and the plugin depended on the witness to choose.
  - On Linux, Node's `--title` replaces the process's `/proc/<pid>/cmdline` with the title. Outside Mill, `node --title=spike-a` showed `spike-a`, while `node --disable-warning=spike-b` kept its whole command line.
  - *Inference:* on Linux a per-module Node argument other than `--title` would be visible to `ProcessHandle`; not run under Mill.
- **The VMs not found.** On Windows the 4 `empty` VMs had exited, 102–230 ms after their item 1, before the plugin searched for them. The 2 console `diehook` VMs had exited 9–13 ms after theirs. *Inference:* the plugin searched for ten modules one after another at 18–47 ms each.
- **One run per VM.** Under Mill's test task each VM served one run, as `NNFC` § 5.2 found for `NodeJs`. The issue comment's caveat, a VM that outlives a run, was not exercised.

## 9. Against the requirements

What the runs show against each requirement the issue names, in `SPECIFICATION.md`:

| Requirement | Console, Windows | Console, Linux | WebSocket, Windows | WebSocket, Linux |
|---|---|---|---|---|
| R-011, nothing lost or altered | No gap or altered item in any run with a per-thread sink; test output never altered an item (§ 3) | The same. With one sink buffer for all writers, Mill's lines corrupted 2 items in 1 of 24 runs (§ 3) | No gap or altered item | No gap or altered item |
| R-071, a run record for a run with no task, and for a death before its first task | Item 1 arrived for every `empty`, `diehook` and `killstart` run (§ 7) | The same | Item 1 lost for every `diehook` run: the VM died before `open` (§ 7) | The same |
| R-073, everything handed to the OS arrives when the process dies | Every handed byte arrived, reader keeping up or paused (§ 4) | Paused reader and kill: 64 417 handed bytes lost in 2 of 2 runs (§ 4) | Paused reader: about 2.29 MB of handed bytes lost in 4 of 4 runs (§ 4) | Every handed byte arrived (§ 4) |
| R-074, the test event log carries how the run ended | `DONE-RETURNED` arrived for every run that finished, and for no run that died (§ 5) | The same | The same; the socket's close was a reset whether or not the run finished (§ 6.2) | The same; the close was end of stream whether or not the run finished |
| R-076, output of a process that ended before any run | `dieload`'s stdout and stderr lines captured, with no item (§ 7) | The same | The same, through the same capture | The same |
| R-088, each piece of output once | No item among the passed-through lines; each test line once (§ 3) | The same | Items never on stdout; each test line once | The same |

## 10. Against the source reading

- `JEC` § 2.2: `JsDom` window code has no `require`; observed, along with no `process` (§ 1).
- `JEC` § 2.3: window `console.log` reaches Node's stdout. Observed: every item and test line reached the plugin through the VM's stdout pipe.
- `JEC` § 2.3: a `jsdomError` makes the env call `process.exit(1)`. Observed, at once, for an exception thrown inside a dispatched event (§ 1); and the stdout still queued in Node on Linux was lost (§ 4).
- `JEC` § 2.2: jsdom's `WebSocket` sends origin `http://localhost`. All 40 connections sent it.
- `JEC` § 2.2's inference that a `ws` message still queued at `process.exit` is lost: no normal exit had any queued (§ 5). jsdom's own queue, before `ws`, held every item sent while window code ran (§ 2).
- `JNC` § 5.1's inference that, on POSIX, stdout still queued inside Node at `process.exit` is lost: observed for `process.exit(1)` after a paused reader (§ 4), not on a normal exit (§ 5).
- `JNC` § 5.1 and `MTW` § 3.3: plugin code that evaluates the module's test task inside `withStreams` receives the VM's stdout and stderr. Observed, together with the `Logger`'s lines from another thread (§ 3).

## 11. What was not run or not determined

- macOS; `--no-daemon`; the `ExoegoJsDomNodeJs` environment; jsdom other than `27.4.0` (the latest is `31.0.0`).
- The issue's synchronous `XMLHttpRequest` and `Logger` candidates.
- A plugin-built `TestAdapter`, or a `JSEnv` wrapping `onOutputStream`, which `JNC` § 5.1 says lets plugin code read the pipes without Mill's `System.out` proxy or `PipeOutputThread`.
- A console reader that buffers instead of blocking inside `write`; a reader that falls behind without stopping.
- A WebSocket emitter in a run whose test code yields to the event loop, or a fixture with tests that return promises.
- A run whose framework calls `slaveRunner`; two runs in one VM (§ 6.1).
- A per-module Node argument visible to `ProcessHandle` on Linux under Mill; any way to see a VM's arguments through `ProcessHandle` on Windows (§ 8).
- Whether stderr bytes were lost when `Stream closed` ended a stderr `PipeOutputThread` on Linux (§ 4): no stderr witness was kept.
- Versions other than § 1 lists.
