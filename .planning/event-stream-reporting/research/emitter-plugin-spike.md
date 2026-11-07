# An emitter-to-plugin spike under parallel workers and several modules

What was observed when a stub socket emitter in each test-worker JVM sent its run's events to a minimal exclusive Mill command that executed several modules' `testForked` itself. Answers [Does an emitter-to-plugin spike work end to end under parallel workers and several modules?](https://github.com/jamesgorman2/scalatest/issues/30), with the checks its two comments add. Observations only; no design is chosen.

Two notes hold the source reading this note tests: `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`, and `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`.

## Short answer

- **Lifetime.** The command's body ran before any test task and regained control after the last, with a result for every task, with and without `--keep-going`. Without it, a module whose tests had not started when another task failed was skipped; tests already running finished. Exit status was 0 only when every module passed (§ 2).
- **Live events.** Every worker of every module connected and sent its events as the tests ran, each connection attributable to its module and worker. Mill's discovery runner never connected (§ 3).
- **Killed worker.** On Linux the reader saw end of stream and received every byte the worker had written. On Windows it saw `Connection reset`, and lost every byte it had not yet read when the worker died. Mill reported the task as failed with `out.json does not exist` for one of the dead workers (§ 4).
- **Selectors.** Separate arguments reached the command; Mill consumed `+` before it (§ 5).
- **Worker streams.** A direct `callTestRunnerSubprocess` call honoured the caller's `os.Inherit.out` and `os.Inherit.err`, and nothing reached the console. A shim `testFramework`'s `System.setOut` took effect before Mill's capture. `SystemStreamsUtils.withStreams` around `runTests()` captured nothing on the parallel path (§ 6).
- **`testLogLevel = Error`** silenced ScalaTest's per-test lines but not its end-of-run summary (§ 7).
- **Console.** Raw writes from the command broke Mill's prefixed lines even in redirected output under the daemon. In a pseudo-terminal, on Windows (ConPTY) and Linux, Mill's prompt was live and overwrote most raw lines, leaving stale copies of itself in the scrollback. `Task.log` writes were prefixed `1] ` and intact, apart from one line in two of the four Windows runs (§ 8).
- **Ctrl-C** ended the Mill command at once on both platforms, under the daemon and `--no-daemon`. The command body never regained control, and 20 s later no test-worker JVM or daemon was running (§ 8.3).
- **Not run:** macOS; Scala.js and Native (§ 10).

## 1. What was run

The prototype is on branch `prototype/emitter-plugin-spike`, in `.planning/event-stream-reporting/prototype-emitter-plugin-spike/`.

- **The emitter,** `spike.SocketEmitter`, a ScalaTest `Reporter` on each test module's class path through a module dependency, turned on with `-C spike.SocketEmitter`.
  - Its constructor opens nothing. It holds `RunStarting`, which ScalaTest fires when the runner is constructed, and connects on the first other event.
  - The address and a module label reach it as the framework arguments `-Dspike.addr=127.0.0.1:<port>` and `-Dspike.module=<module>`, which ScalaTest puts in `RunStarting`'s config map.
  - On connecting it sends one `HELLO` line: a run identifier it generates, its PID, the module label, its working directory and its JVM's start time. Each event follows as one tab-separated line carrying the run identifier, a sequence number and the send time, written straight to the socket's unbuffered stream. It closes the socket after `RunCompleted`.
  - `SocketEmitter.burst(n, tag)` writes `n` lines the same way, and logs to stderr every 500 lines how many it has written.
- **The plugin,** `spike.test`, an exclusive command in `build.mill` taking the `Evaluator` and module selectors as `String*` (`MCL` § 2).
  - It opens a loopback `ServerSocket`, resolves each selector with `resolveModulesOrTasks`, builds `<module>.testForked("-C", "spike.SocketEmitter", "-Dspike.addr=…", "-Dspike.module=…")` for each, and passes them all to one `evaluator.execute`.
  - A thread per connection reads lines, records each with its receive time, and prints a progress line with counts per module after each test or suite end.
  - After `execute` returns it waits at most 5 s for every connection to end, prints a summary per module, and fails with `Task.fail` if any task did not succeed, any test failed or any connection ended without `RunCompleted`.
  - `SPIKE_CONSOLE=raw` writes through `SystemStreamsUtils.current().out` taken on the command's thread, the command's raw stream; `SPIKE_CONSOLE=log` writes through `Task.log.streams.out`. `SPIKE_PROGRESS=cr` rewrites one progress line with `\r` instead of printing a line per event.
- **The fixture modules,** each a `ScalaTests` module with `TestModule.ScalaTest`:
  - `alpha.test`: four passing suites of four 400 ms tests, in two fork groups of two, with `testParallelism` on: four worker JVMs at once.
  - `beta.test`: a passing suite and a suite with one failing test.
  - `crash.test`: each suite its own fork group. `HaltSuite` passes two tests, bursts 20 000 lines and calls `Runtime.halt`. `HaltLaggingSuite` does the same while the plugin pauses reading its connection for 3 s. `KillSuite` passes a test, bursts 20 000 lines and sleeps; on the first burst line the plugin pauses reading for 1.5 s, calls `destroyForcibly()` on the worker's PID, and resumes reading 0.5 s later. `CrashOk` passes.
  - `broken.test`: does not compile.
  - `late.test`: passes, and cannot start until 6 s into each Mill command, because its `forkArgs` depends on a `Task.Input` that sleeps.
  - `loud.test` and `quiet.test` share two suites, one with a failing test; `quiet.test` sets `testLogLevel` to `Error`.
  - `shimmed.test`: `testFramework` is `spike.ShimFramework`, and `forkEnv` sets `SPIKE_SHIM_CAPTURE`.
  - `slow.test`: two suites of twelve 1 s tests, for the Ctrl-C cases.
- **The stream checks,** module commands defined beside the fixtures:
  - `captureDirect <globs>` builds a `TestModuleUtil` as `TestModule.testTask` does and calls `callTestRunnerSubprocess` with `Left(globs)` inside `os.Inherit.out.withValue` and `os.Inherit.err.withValue`, each set to an `os.ProcessOutput.Readlines` that collects lines. It then prints each collected line prefixed `CAPTURED|`.
  - `withStreamsRunTests` calls `runTests()` inside `SystemStreamsUtils.withStreams` over a buffer, then prints what the buffer holds.
  - `ShimFramework` delegates to `org.scalatest.tools.Framework`. When `SPIKE_SHIM_CAPTURE` is set, its `runner` first replaces `System.out` and `System.err` with files in the working directory.
- **The measure.** Each `mill` command ran with its stdout and stderr redirected to files. Every line the suites write begins `MARK-`. `run.ps1` (Windows) and `run.sh` (Linux) make the same runs:
  - `spike.test alpha.test beta.test crash.test broken.test late.test`, with and without `--keep-going`; `spike.test alpha.test`; `beta.test + spike.test alpha.test`, with and without `--keep-going`; `spike.test beta.test + alpha.test`; all under the daemon.
  - `spike.test alpha.test beta.test` with each `SPIKE_CONSOLE`, under the daemon and `--no-daemon`.
  - `beta.test.captureDirect beta.BetaOk` under the daemon and `--no-daemon`; `alpha.test.withStreamsRunTests`, `shimmed.test`, `loud.test` and `quiet.test` under the daemon.
- **Linux only,** `run.sh` also ran under a pseudo-terminal with `script`, 160 by 40:
  - `spike.test alpha.test beta.test` with each `SPIKE_CONSOLE` and each `SPIKE_PROGRESS`, under the daemon and `--no-daemon`. `render-pty.py` replays each recording through a minimal terminal that handles the only control sequences Mill emitted (carriage return, line feed, `ESC[nA`, `ESC[nD`, `ESC[nK`, `ESC[nJ`, `ESC[…m`) and compares what the terminal ends up showing, scrollback included, with what the command wrote.
  - `spike.test slow.test` with Ctrl-C typed into the terminal 8 s in, under the daemon and `--no-daemon` (`./run.sh ctrl-c`).
- **Windows only,** `run-conpty.ps1` ran the same pseudo-terminal cases under a Windows pseudo-console (ConPTY) through `pywinpty`, 160 by 40, via `conpty-run.py`:
  - Each `mill.bat` command ran as `cmd /c .\mill.bat …` in the pseudo-console. The script saved what the pseudo-console emitted, rendered it, scrollback included, with `pyte`, and compared the result with every line the command wrote, which the plugin also appends to `console.txt` in its `Task.dest`. A written line counts as shown when its characters, spaces aside, appear in order in the rendering, so that a line the terminal wrapped still counts.
  - For Ctrl-C it wrote `\x03` to the pseudo-console 8 s in. When `cmd.exe` then asked "Terminate batch job (Y/N)?", it answered `N`. It listed `java.exe` processes whose command line names `MillTestRunnerMain` or `MillDaemonMain` once the client had exited, and again 20 s later.

The matrix ran twice on each platform; the counts below are from the second round, whose outputs were kept. The first round showed the same behaviour, with different counts.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`.

| Platform | JDK |
|---|---|
| Windows 11 (`10.0.22631`), x86-64 | Microsoft OpenJDK `21.0.12.1` |
| Ubuntu 26.04 under WSL 2, x86-64 | Azul Zulu `21.0.10`, fetched by Mill |

## 2. The command's lifetime, results and exit status

- **Before and after.** In every run the body's first line preceded every compile and test line Mill printed, and every worker JVM started after it: the earliest worker start was 0.24 s after the body began on Windows and 0.07 s on Linux. `execute` returned after every connection had ended, and the body then printed its summary.
- **Results.** `executionResults.transitiveResults` held a result for each `testForked` task the command passed:

  | Module | Without `--keep-going` | With `--keep-going` |
  |---|---|---|
  | `alpha.test` | `Success` | `Success` |
  | `beta.test` | `Failure`: "1 tests failed: beta.BetaFail fails" | the same |
  | `crash.test` | `Failure`: "Test reporting Failed: …/crash.KillSuite/worker-0/out.json does not exist" | the same |
  | `broken.test` | `Skipped` | `Skipped` |
  | `late.test` | `Skipped` | `Success` |

- **The module that cannot start.** Its `testForked` was `Skipped` with no message. The compile error was in `transitiveFailing` as `broken.test.compile`: "Compilation failed". Mill printed the compiler's diagnostics to the console as the compile ran.
- **Fail-fast.** Without `--keep-going`, `late.test.forkArgs` and `late.test.testForked` were skipped and `alpha`, `beta` and `crash`, already running when `broken.test.compile` failed, ran to the end. With `--keep-going`, `late.test` ran and passed.
- **What Mill printed for the nested tasks.** Without `--keep-going`, nothing: no list of failed tests and no task failure. With it, `"<task> task failed"` for `broken.test.compile`, `beta.test.testForked` and `crash.test.testForked`, and `"spike.test task failed"`. In both, the command's own failure message followed Mill's final status line.
- **Exit status.** 0 for `spike.test alpha.test`; 1 for every run with a failing module, with and without `--keep-going`.
- **A failing sibling goal.** `beta.test + spike.test alpha.test` without `--keep-going`: the command's body did not run, and the exit status was 1. With `--keep-going` the body ran, `alpha.test` passed, and the exit status was 1 from `beta.test`.
- **A class run twice.** On Windows, in 3 of the 13 runs in which a module ran several workers (`daemon-all`, `nodaemon-console-raw`, `nodaemon-console-log`), two workers of one fork group each ran the same first class: Mill logged `Running Test Class alpha.A1`, `alpha.A3` and `beta.BetaFail` twice each, the plugin received their events twice, and `beta.test`'s failure read "2 tests failed: beta.BetaFail fails / beta.BetaFail fails". It also happened in a trial run before the matrix. It did not happen on Linux.

## 3. Events from every worker, live

- **Every worker connected.** Each worker JVM that ran a class opened one connection. No connection came from Mill's own PID in any run, so the discovery runner (`MWC` § 3.2) never connected.
- **Attribution.** The `HELLO` line's module label matched the selector the plugin had built the task for in every connection. Its working directory named the worker's sandbox, for example `out/alpha/test/testForked.dest/group-0-alpha.A1/worker-1/sandbox`; its PID and run identifier were distinct per connection.
- **Live.** Events arrived while the tests ran: progress lines stepped up at the tests' 400 ms interval, interleaved with the workers' own `MARK-` lines, and in the Windows run of all five modules each connection of a worker that completed ended 0.3 s to 2.5 s before `execute` returned. The largest gap between an event's send time and its receipt, where the reader was not deliberately paused, was 64 ms on Windows and 43 ms on Linux.
- **Order.** Sequence numbers had no gap on any connection.

## 4. A killed worker

| Case | Reader saw, Windows | Burst lines received, Windows | Reader saw, Linux | Burst lines received, Linux |
|---|---|---|---|---|
| `HaltSuite`, reader keeping up | `Connection reset` | 20 000 of 20 000 | end of stream | 20 000 of 20 000 |
| `HaltLaggingSuite`, reader paused when the JVM halted | `Connection reset` | 1 of 20 000 | end of stream | 20 000 of 20 000 |
| `KillSuite`, reader paused, `destroyForcibly()` | `Connection reset` | 1 of 20 000 (without `--keep-going`), 52 of 20 000 (with) | end of stream | 20 000 of 20 000 |

- **What arrived before the death.** Every event up to the `TestStarting` of the test that halted or was killed, then the burst lines received. No `RunCompleted` arrived.
- **Written before the death.** In every case the worker's stderr recorded `MARK-WROTE <tag> 20000 of 20000` before the halt or kill: all 20 000 lines had been handed to the socket.
- **Lost.** On Windows, the lines written but not yet read when the worker died never arrived; the read that would have returned them threw `Connection reset`. On Linux none were lost.
- **What Mill reported.** `crash.test.testForked` failed with "Test reporting Failed: …/crash.KillSuite/worker-0/out.json does not exist", naming one of the three dead workers. The `Failure` in the nested `execute`'s `values` chained one such message for each of `HaltLaggingSuite`, `HaltSuite` and `KillSuite`. `CrashOk`, in the same module, completed normally. Mill printed nothing about the dead workers to the console without `--keep-going`.
- **The halted worker's JVM** was not alive when the reader resumed (`ProcessHandle.isAlive`), on both platforms.

## 5. Selectors

- `spike.test alpha.test beta.test crash.test broken.test late.test`: the command received the five selectors as five `String*` elements, and resolved each to its test module.
- `spike.test beta.test + alpha.test`: the command received `[beta.test]` only. `alpha.test` ran as a separate goal, all of its output preceding the command's first line.

## 6. Worker streams

- **A direct `callTestRunnerSubprocess` call** with the caller's `os.Inherit.out` and `os.Inherit.err`: under the daemon and `--no-daemon` on both platforms, the readers collected all 15 lines the worker wrote, stdout and stderr, and no worker line reached the console except as the command later printed it.
- **`SystemStreamsUtils.withStreams` around `runTests()`**, `alpha.test`, `testParallelism` on, four workers: the buffer held nothing, and every worker line reached the console with Mill's per-worker prefix, on both platforms.
- **A shim `testFramework` that calls `System.setOut` and `System.setErr` in `runner`:** on both platforms the console held only Mill's own failure lines. The shim's stdout file held the suite's `System.out` and `Console.out` lines, ScalaTest's per-test lines and its end-of-run summary; its stderr file held the suite's `System.err` line and Mill's `Running Test Class shimmed.ShimOk`. *Inference:* Mill's test-interface `Logger` lines and the `done()` `println` followed the replaced streams, so the replacement preceded Mill's capture of `System.out` and `scala.Console` had not cached the original (`MWC` § 2.4).

## 7. `testLogLevel = Error`

- `loud.test` (default `Debug`) printed ScalaTest's per-suite and per-test lines, the failure with its location, Mill's `Running Test Class` lines, and ScalaTest's end-of-run summary.
- `quiet.test` (`Error`) printed only the suite's own `System.out` line and ScalaTest's end-of-run summary for each worker, on both platforms.
- With `-C spike.SocketEmitter` on the default level, no ScalaTest per-test line was printed in any `spike.test` run; the end-of-run summary and Mill's `Running Test Class` lines were.

## 8. The console

### 8.1 Redirected to files

- **`SPIKE_CONSOLE=log`.** Every line the command wrote reached stdout prefixed `1] `, whole, under the daemon and `--no-daemon`, on both platforms.
- **`SPIKE_CONSOLE=raw`.** The command's lines reached stdout unprefixed. Under the daemon, a line of the command sometimes landed inside one of Mill's prefixed lines: Mill's prefix, then the command's whole line, then the rest of Mill's line on the next line, for example `319-1] PROGRESS +2.354s …` followed by `MARK-OUT BetaOk ok3`. This happened in 3 of the 6 daemon runs in which the command wrote raw while tests ran on Windows (1 or 2 lines each), and in 1 of 6 on Linux. It did not happen in either `--no-daemon` raw run on either platform.
- **Under the daemon,** the command's raw lines reached the client's redirected stdout; `out/mill-daemon/stdout` held none of them.

### 8.2 Under a pseudo-terminal

Mill treated both pseudo-terminals as interactive: its prompt, a header and one line per running task, was live in every run, redrawn by moving the cursor up over the prompt and clearing to the end of the screen. Under ConPTY, Mill's cursor-up, clear-line and clear-screen sequences appeared in the pseudo-console's output, after a few of ConPTY's own at the start.

| Case | Linux, daemon | Linux, `--no-daemon` | Windows, daemon | Windows, `--no-daemon` |
|---|---|---|---|---|
| raw, a line per event | 28 of 77 intact; 35 stale | 25 of 77; 28 | 34 of 91; 48 | 37 of 90; 39 |
| log, a line per event | 78 of 78; 0 | 77 of 77; 0 | 77 of 78; 0 | 92 of 92; 0 |
| raw, `\r` rewrite | 22 of 78; 19 | 21 of 77; 20 | 24 of 78; 16 | 32 of 92; 20 |
| log, `\r` rewrite | 42 of 78; 0 | 43 of 78; 0 | 41 of 77; 0 | 41 of 92; 0 |

Each cell gives the command lines shown intact out of those written, then the stale prompt lines left in the scrollback. Mill's own key line, `1] spike.test`, printed once in every case, is not counted as stale.

- **Raw.** The prompt's next redraw moved the cursor up by the prompt's height from below the command's lines, so it overwrote them and left the upper part of the previous prompt in the scrollback. Lines written after `execute` returned were all intact.
- **Log.** Lines were prefixed `1] `, and the prompt was cleared and redrawn around them as it was around the workers' lines. On Windows the ConPTY cases ran twice. In two of the four runs with a line per event, one line was erased: the pseudo-console's output held the line followed by a carriage return, `ESC[0K` and another carriage return. The line was `SPIKE body-start` in one run and `SPIKE nested execute values` in the other. It did not happen on Linux.
- **Log with `\r`.** Each update reached the terminal as a prefixed segment ending in a carriage return, so the next line written, the workers' included, overwrote it. *Inference:* the counts under `\r` rewrite include updates that the design itself overwrites.
- **Mill's own lines.** In every Linux recording, worker lines were occasionally merged by Mill itself: two prefixes on one line, followed by the stdout line and then the stderr line, 2 to 13 times per recording. *Inference:* this is stdout and stderr lines of one worker interleaving on the terminal, independent of the command's writes.

### 8.3 Ctrl-C

- **Linux, under the daemon.** The terminal showed nothing more after Ctrl-C: no `execute-returned`, summary or `body-end` line. The client exited with status 130.
- **Linux, under `--no-daemon`.** The readers printed end of stream for both workers, with `TestStarting` as each connection's last event, then nothing more; status 130.
- **Windows, both modes.** `cmd.exe` asked "Terminate batch job (Y/N)?", and the client had exited 0.1 s after Ctrl-C, with status `0xC000013A`. Both readers recorded `Connection reset` with `TestStarting` as the last event. That was the last line the command wrote; it never wrote `execute-returned` or `body-end`.
- **Afterwards.** On both platforms, in both modes, no test-worker JVM and no `mill.daemon.MillDaemonMain` process was running 20 s after Ctrl-C. On Windows none was running right after the client exited either.

## 9. Against the source reading

- `MCL` § 2.2, § 2.4, § 2.3, § 4.2 and § 6, and `MWC` § 2.2, § 2.3 and § 2.4, held as observed in §§ 2–8. `MCL` § 4.2's *inferred* interleaving occurred, and also with output redirected to files under the daemon (§ 8.1).
- `MCL` § 8 asks whether the `[N]` prefix is printed on an exclusive command's `Task.log.streams` lines: it was, as `1] ` (§ 8.1).
- `MWC` § 3.2 leaves open whether unread socket bytes survive a killed worker: on Windows they did not, on Linux they did (§ 4).
- `MCL` § 8 leaves Ctrl-C on Windows open: under ConPTY it ended the Mill command at once in both modes, and no test-worker JVM or daemon survived. `MCL` § 5.1's *inferred* outcome, that the command body does not unwind, matched: after Ctrl-C it wrote only its readers' connection-end lines, never `execute-returned` or `body-end` (§ 8.3).
- `.planning/structured-test-reporting/research/mill-test-worker-args.md` § 1.2 describes a class as claimed by one worker through an atomic move. On Windows two workers ran the same class (§ 2).

## 10. What was not run or not determined

- anything on macOS, or on Scala.js or Native;
- a terminal emulator's own rendering: the pseudo-terminal output was rendered by `pyte` on Windows and by `render-pty.py` on Linux, not by Windows Terminal, conhost or a Linux terminal emulator;
- Ctrl-C under `--watch`, and Ctrl-C answered `Y` at `cmd.exe`'s prompt;
- why a `Task.log` line was erased under ConPTY (§ 8.2);
- a killed worker that had further classes queued, and a reader that was not paused when an external kill came;
- why a class ran on two workers on Windows (§ 2), and how often;
- whether the Windows reset depends on the JDK's socket implementation or on the reader pausing for the lengths used here;
- a command line holding the plugin together with other non-test goals, and `--watch`;
- R-040 for the whole of a module's run under the plugin: the spike ran `testForked` unchanged, so worker output reached the console in every `spike.test` run;
- versions other than § 1 lists.
