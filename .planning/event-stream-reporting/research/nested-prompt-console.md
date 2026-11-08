# Does `Task.log.ticker` keep the test command's summary on its prompt line through the nested evaluation, and do `Task.log` lines reach the console intact?

What was observed when an exclusive command set a summary through `Task.log.ticker` and wrote whole lines through `Task.log.streams.out` while one `execute` on a fail-fast-off copy of its `mill.exec.Execution` ran nine modules' tests, under a pseudo-terminal on Linux and Windows. Answers [Does Task.log.ticker keep the test command's summary on its prompt line through the nested evaluation?](https://github.com/jamesgorman2/scalatest/issues/47). Observations only; no design is chosen.

Three notes hold facts this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/mill-command-lifetime.md`, cited as `MCL`: the exclusive command, its prompt line and `Task.log.ticker`, read from source.
- `.planning/event-stream-reporting/research/mill-nested-keep-going.md`, cited as `MNK`: the fail-fast-off copy of the `Execution`.
- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`: the earlier spike, its pseudo-terminal harness and its lost `Task.log` lines.

## Short answer

- **At 40 rows the summary held for the nested evaluation, on every terminal** (§ 3.1). In 22 runs of the settled route under Linux `script`, ConPTY and MinTTY, daemon and `--no-daemon`, the command's prompt line showed the latest summary in 96–99% of 250 ms snapshots. Every snapshot without it fell in the first 0.25 s or at the moment `execute` returned, or was a single snapshot of a MinTTY recording whose rows `pyte` drew garbled. No snapshot showed an older summary.
- **Threat 1, a nested key equal to `1`: observed only when the nested evaluation has fewer than ten tasks** (§ 2.1, § 3.2). Nested keys count from 1 but are zero-padded to the width of the nested task count: the test runs had 1240 tasks and keys `0001` to `1240`, none equal to `1`. A nested evaluation of one task got key `1`. From 0.5 s the command's prompt line showed that task's name with the command's summary, and its own ticker detail was overwritten.
- **A further limit: Mill draws at most rows/3 − 1 task lines, sorted by key as strings** (§ 2.2, § 3.3). `1` sorts after every key beginning `0`. At 24 rows, seven nested inputs keyed `0107` to `0521` hid the summary behind `... and N more threads` from 0.5 s to 4.0 s, in one run on each terminal.
- **Threat 2: the route calls `execute` once; when it returns the summary is gone** (§ 4). Every snapshot from `execute`'s return to the command's end showed no prompt at all. The copy drew a final header `1240/1240+, 2 FAILED]` before the command ended in 11 of 11 `--no-daemon` runs and 3 of 26 daemon runs. Each time it stayed under 100 ms before the pause erased it.
- **Threat 3: no `Task.log` line was lost** (§ 5). 11 358 lines in 46 runs were all shown intact, 8 085 of them in 34 Windows runs; no stale prompt row was left in any scrollback. The loss `EPS` § 8.2 and § 8.4 saw did not recur. Its cause was not determined.
- **Alternative routes that held at 24 rows and with one nested task** (§ 6):
  - **`execute(tasks, logger = Task.log)`.** Nested keys became `1-<n>`, which sort after the command's `1`. The command's line was then always the prompt's first task line, and no nested key could equal `1`.
  - **`Evaluator.withBaseLogger(Task.log)` on the copy** did the same.
  - **Compatibility.** Both calls are public `mill.api` members with no annotation. The effect rests on `mill.internal` prompt behaviour outside Mill's compatibility policy, and on the copy route's own reflection (`MNK` § 5).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`) |
| `MCL` | `.planning/event-stream-reporting/research/mill-command-lifetime.md` | as committed |
| `MNK` | `.planning/event-stream-reporting/research/mill-nested-keep-going.md` | as committed |
| `EPS` | `.planning/event-stream-reporting/research/emitter-plugin-spike.md` | as committed |

Permalink base: `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/`. Paths are relative to the `[mill]` root. Abbreviations:

- `EX` is `core/exec/src/mill/exec/Execution.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `EV` is `core/api/src/mill/api/Evaluator.scala`.
- `EI` is `core/eval/src/mill/eval/EvaluatorImpl.scala`.
- `PL` is `core/internal/src/mill/internal/PromptLogger.scala`.
- `PLU` is `core/internal/src/mill/internal/PromptLoggerUtil.scala`.
- `PFX` is `core/internal/src/mill/internal/PrefixLogger.scala`.
- `ML` is `core/internal/src/mill/internal/MultiLogger.scala`.
- `LOG` is `core/api/daemon/src/mill/api/daemon/Logger.scala`.
- `BOOT` is `runner/daemon/src/mill/daemon/MillBuildBootstrap.scala`.

Statements marked *inferred* follow from the cited source or recordings and were not observed directly. § 8 lists what was not determined.

## 1. What was run

The prototype is on branch `prototype/nested-ticker`, in `.planning/event-stream-reporting/prototype-nested-ticker/`. It reuses `EPS`'s emitter, launchers and harness.

- **The command,** `nt.test`, is an exclusive command in `build.mill` taking the `Evaluator` and module selectors.
  - It builds `<module>.testForked("-C", "spike.SocketEmitter", …)` for each selector, as `EPS` § 1 did.
  - It makes one `execute` call, on an evaluator built over a reflective copy of its `Execution` with `failFast = false`, by the code of `MNK` § 3.1 (`nt.keepGoingEvaluator`).
  - `SocketEmitter` sends each test's events to the command. On every test end, suite end and connection end, the command calls `Task.log.ticker` with a summary. The summary is a counter and counts per module, for example `NTS#0093 ok=86 fail=0 suites=3 workers=7 | a=36 b=18 …`. It also writes one line through `Task.log.streams.out` per test or suite end: `NTL <n> <module> <event> …`.
  - Marker lines go to the same stream before `execute`, after it returns, after a 3 s hold, and at the command's end.
  - Each ticker call, each line and each marker is also appended, with its epoch time, to `ticker.tsv`, `console.tsv` and `markers.tsv` in `Task.dest`. The nested results' `transitivePrefixes`, the key of every nested task, go to `keys.tsv`.
  - `NT_ROUTE` picks the route: `copy`, the settled route; `copy-logger` and `copy-base`, § 6.
- **`nt.tiny`** runs the same copy route over one task, `nt.sleeper`, a `Task.Input` that sets its own ticker and sleeps 4 s. A thread in the command calls `Task.log.ticker` every 500 ms meanwhile.
- **Fixture.** Modules `a`, `b`, `c`, `d`, `e`, `g` and `h` each run three suites of twelve 500 ms tests, with `testParallelism` on.
  - Their tests start 0, 4, 8, 12, 16, 2 and 10 s in, because each module's `forkArgs` depends on a `Task.Input` that sleeps that long.
  - `failing.test` holds one failing test among eight passing ones, and starts 6 s in. `broken.test` does not compile.
  - All nine modules are selected in every `nt.test` run. The nested evaluation lasted 23–24 s and held 1240 tasks. About 22 worker connections reached the command per run, and several test tasks with three workers each ran at once.
- **Recording.** Each case ran under a pseudo-terminal 160 columns wide; every chunk of the terminal's output was stamped with its arrival time:
  - **Linux:** `script -q -e -c "stty cols 160 rows <n>; ./mill …" /dev/null`, piped to `tsrec.py` (`run.sh`).
  - **ConPTY:** `pywinpty` (`conpty-run.py`, `run-conpty.ps1`). The recording carried Mill's own cursor-up, clear-line and clear-screen sequences, as `EPS` § 8.2 found.
  - **MinTTY:** Git Bash's MinTTY with its default MSYS settings, in a hidden window with `--log` (`mintty-run.py`, `run-mintty.ps1`). The harness polled the log's size every 20 ms to stamp each new run of bytes. A login `bash` started by MinTTY exited before running its command in this environment, so a non-login `bash -c` ran `./mill` with `MILL_VERSION=1.1.10-jvm`. The log held the pseudo-console's re-rendering, with absolute cursor positions (`ESC[<row>;<col>H`), not Mill's own sequences.
- **Measures** (`analyse.py`). Each recording was replayed through `pyte` at the terminal's size, and the screen was rendered every 250 ms, from 1 s before `execute` started to 1.5 s after the command ended.
  - A snapshot taken mid-burst also took in chunks arriving less than 10 ms apart, so that it did not cut a redraw in half.
  - Each snapshot was classed by the command's prompt row (`1] nt.test <seconds> <detail>`):
    - **current:** it showed a summary at least as new as the latest one set 200 ms or more before the snapshot. Mill redraws every 100 ms (`MCL` § 4.2).
    - **stale:** it showed an older summary.
    - **hidden:** the row was absent and the prompt ended `... and N more threads`.
    - **other-text:** a key-`1` row showed another task's name.
    - **absent:** the row was absent but a prompt header was shown.
    - **noprompt:** no prompt was drawn at all.
  - Lines shown intact and stale prompt rows were counted on the final rendering, scrollback included, as in `EPS` § 8.2.
- **Matrix.** At 160 by 40, the settled route ran 4 times each under the daemon and `--no-daemon` on ConPTY and MinTTY, and 3 times each on Linux. Under the daemon, once on each terminal:
  - the settled route at 160 by 24;
  - `nt.tiny`;
  - `copy-logger` and `copy-base` at 160 by 40, at 160 by 24, and with `nt.tiny`.

  That is 46 runs. `runs/summary.txt` is copied, with each run's timeline, to `results/`.

Versions: Mill `1.1.10`, Scala `3.3.6`, ScalaTest `3.2.19`, Azul Zulu `21.0.10` fetched by Mill. Windows 11 (`10.0.22631`) with Git for Windows' MinTTY `3.8.1`, Python `3.14.6`, `pywinpty` `3.1.2` and `pyte` `0.8.2`. Ubuntu under WSL 2 (kernel `6.18.54.1`) with util-linux `script` `2.41.3`.

## 2. How nested keys are allocated and drawn

### 2.1 Keys

- The command's prompt line has key `1`: the outer evaluation's only task (`EX` lines 258–273). The line is set by the `withPromptLine` that wraps the command (`LOG` lines 27–31), then the prompt is paused for the body (`GE` lines 888–889).
- Each `execute0` counts its tasks from 1 (`EX` line 160). Each task's key is that count left-padded with `0` to the number of digits in the nested evaluation's task count (`EX` lines 258–262). Keys therefore equal `1` only in a nested evaluation of fewer than ten tasks. That is the first task, as `nt.tiny`'s `keys.tsv` showed: `1 nt.sleeper`.
- A task's key is its `PrefixLogger`'s `logKey`, the logger passed to `executeTasks` followed by the count (`EX` lines 264–270; `PFX` line 37). That logger defaults to the `Execution`'s `baseLogger` (`EX` line 135). The copy shares the command's `baseLogger` (`MNK` § 3.3), whose key is empty. Nested keys were bare counts: `0245 d.delay`, `1219 a.test.testForked`.
- The planning step's key is `planning` under the `baseLogger` (`EX` lines 163–167).
- `setPromptLine` on an existing key replaces that line's text, and `removePromptLine` sets it to none (`PL` lines 149–153, 276–286, 576–600). `setPromptDetail` on a line set to none does nothing (`PL` lines 572–574). *Inferred:* a nested task numbered `1` takes over the command's line while it runs, and leaves no line for the command's later `ticker` calls.

### 2.2 Drawing

- Prompt lines are kept in a map sorted by key, element by element as strings (`PL` lines 524–525; `PLU` lines 198–209). `1` sorts after every key beginning `0`, before every other nested key, and before every key that begins with `1` and is longer, such as `1-0245`.
- The prompt shows at most `rows / 3 − 1` task lines; beyond that it shows the first `rows / 3 − 2` and `... and N more threads` (`PLU` lines 96–98, 137–144). That is 12 lines at 40 rows and 7 at 24.
- A line set less than 250 ms ago is drawn with its previous text, which is none for a new line (`PLU` lines 34, 117–119). The command's line is set just before its body starts, and the body reached `execute` 15–20 ms later. *Inferred:* this is why the first frame after `execute` started showed a header and no task line (§ 3.1). The Linux recording of `linux-daemon-copy-1` held such a header-only frame at +188 ms, and the command's line at +295 ms.
- The prompt's bytes are recomputed by the updater thread every 100 ms (`PL` lines 75–103). The stream pumper redraws them after each burst of lines (`PL` lines 448–475).

## 3. The summary during the nested evaluation

### 3.1 The settled route, 160 by 40

| Terminal | Mode | Runs | Snapshots current | Snapshots not current |
|---|---|---|---|---|
| Linux `script` | daemon | 3 | 271 of 277 (98%) | 3 `noprompt` at 0 s; 3 `absent` at 0.25 s |
| Linux `script` | `--no-daemon` | 3 | 276 of 279 (99%) | 3 `noprompt` at 0 s |
| ConPTY | daemon | 4 | 367 of 374 (98–99%) | 4 `noprompt` at 0 s; 3 `absent` at 0.25 s |
| ConPTY | `--no-daemon` | 4 | 372 of 377 (98–99%) | 4 `noprompt` at 0 s; 1 `noprompt` at 23.5 s, the snapshot `execute` returned in |
| MinTTY | daemon | 4 | 365 of 374 (97–98%) | 4 `noprompt` at 0 s; 3 `absent` at 0.25 s; 2 single snapshots mid-run |
| MinTTY | `--no-daemon` | 4 | 370 of 380 (96–99%) | 4 `noprompt` at 0 s; 6 single snapshots mid-run |

- **At 0 s** the prompt was still paused from the command's body. **At 0.25 s** the command's line was within Mill's 250 ms delay for a new line (§ 2.2).
- **No snapshot was `stale` or `other-text`.** The summary's counter always reached the latest value set 200 ms earlier.
- **The command's line was as low as the eighth task line** while the nested inputs keyed `0107`–`0521` ran (`prompt-position.py` over `linux-daemon-copy-1` and `conpty-nodaemon-copy-1`). After those inputs ended it was the first or second, since every test task's key began `1` and was four digits long.
- **The mid-run MinTTY snapshots** (`hidden` or `absent`) never lasted two snapshots. In those examined, such as `mintty-nodaemon-copy-1` at 12.75 s, rows were shifted or overwritten with each other's text, for example `0315] e.delay 12sForked 11s 3/3 completed.`, and the command's row was missing.
  - *Inference:* these are `pyte`'s renderings of the pseudo-console repainting rows by absolute position. Neither the Linux nor the ConPTY recordings, which carried Mill's own sequences, had any such snapshot.
  - What MinTTY itself drew was not observed.

### 3.2 A nested evaluation of one task

`nt.tiny`, once per terminal under the daemon, with the same result on all three:

- 0.25 s: header only.
- From 0.5 s to 4.0 s, the end of the nested evaluation: the key-`1` row read `1] nt.sleeper SLEEPER-DETAIL`, then `1] nt.sleeper 1s NTS#0003 tiny` and so on. The nested task's name replaced the command's, and the command's next `ticker` call replaced the nested task's detail. No row read `1] nt.tiny` with a summary: 0 of 17 snapshots current.

### 3.3 A short terminal

At 160 by 24 (seven task lines), under the daemon, the settled route's summary was current in 75 of 92 snapshots on Linux, 76 of 93 on ConPTY and 74 of 93 on MinTTY. Each run was `hidden` from 0.5 s to 4.0 s. During that time, the prompt's first six task lines were nested inputs keyed `0107` to `0521`, which sort before `1`, followed by `... and N more threads`. The command's line returned when `b.delay` ended at 4 s.

## 4. When `execute` returns

- **One call.** `nt.test` calls `execute` once, on the copy. Each run's header counted up once, to `1240/1240+`. The `+` is Mill's marker for an evaluation that is not at the final depth (`EX` line 195). *Inferred:* it shows because the command's `Evaluator`, and so the copy, has `isFinalDepth` false. Only the outer evaluation is given `withIsFinalDepth(true)` (`BOOT` lines 406, 503, 509; `GE` line 767).
- **The summary vanished at once.** In every run, every snapshot from `execute`'s return to the command's end, 3.0–3.3 s later, was `noprompt`: no header and no task line.
  - `execute0` clears every prompt line (`EX` line 377), and leaving `withPromptUnpaused` pauses the prompt again (`EX` line 137). Pausing clears the drawn prompt (`PL` lines 364–372, 417–426).
  - The command's two later `ticker` calls drew nothing. The lines it wrote meanwhile (`NTL … after-execute`, the per-module results, `command-end`) were all shown.
- **The copy's final header.** The copy's `executionNestingDepth` starts at 0 (`MNK` § 3.3), so it set the final header for an outermost evaluation with failures (`EX` lines 372–375).
  - The header `1240/1240+, 2 FAILED]` reached the terminal, with no task line, in every `--no-daemon` run: 4 of 4 on ConPTY, 4 of 4 on MinTTY, 3 of 3 on Linux. Under the daemon it reached the terminal in 3 of the 26 `nt.test` runs of every route, all on ConPTY.
  - Each time it was replaced 21–96 ms later, when the pause cleared the prompt. On Linux this was a cursor-up and `ESC[0J` before the `after-execute` line.
  - *Inferred:* it is drawn only when the updater thread redraws between `setPromptHeaderPrefix` and the pause.
  - In the `nt.tiny` runs, whose nested task succeeded, the copy drew no final header (`EX` line 374).
- **Mill's own final header**, `1/1, SUCCESS]`, followed 0–0.11 s after the command's last line in every run.

## 5. `Task.log` lines

| Terminal | Runs | Lines written | Shown intact | Stale prompt rows |
|---|---|---|---|---|
| Linux `script` | 12 | 3 273 | 3 273 | 0 |
| ConPTY | 17 | 4 010 | 4 010 | 0 |
| MinTTY | 17 | 4 075 | 4 075 | 0 |

- Every line was prefixed `1] `. That includes lines written while the prompt was paused, before `execute` and after it returned. The `EPS` losses were lines of that kind: `SPIKE body-start` and `SPIKE nested execute values` (`EPS` § 8.2, § 8.4).
- The settled route's runs at 40 rows wrote 297, 323 or 336 lines each. Runs on Windows sometimes ran a class twice, adding events, as in `EPS` § 2.
- The loss seen in `EPS` did not recur in 34 Windows runs with 8 085 lines; its cause was not determined.
  - These runs differ from `EPS` in the fixture, in writing on every event, in `pywinpty` (`EPS` does not state its version), and in running MinTTY's `bash` without `-l`.
  - *Inferred*, as a candidate only:
    - The stream pumper moves the cursor up over the prompt under the prompt's lock, then writes the line outside it (`PL` lines 477–492).
    - The updater thread can draw the prompt in between (`PL` lines 92–93, 434–437).
    - The next move up would then start below the line and land inside the prompt. The line would be cleared, and the prompt's first row left behind.
    - No recording here showed a lost line or a stale prompt row.

## 6. Alternative routes

Both alternatives keep the settled route's single `execute` on the fail-fast-off copy; they change only the logger the nested tasks' keys hang from.

- **`copy-logger`:** `ev2.execute(tasks, logger = Task.log)`. `Task.log` is a `MultiLogger` whose key is the command's `1` (`ML` line 136). Nested keys became `1-<n>` (`1-1219 a.test.testForked`; `nt.tiny`: `1-1`), and the planning line stayed `planning`.
- **`copy-base`:** `keepGoingEvaluator(ev).withBaseLogger(Task.log)`, which copies the copy's `Execution` again with `failFast` unchanged (`EI` lines 57–62; `EX` lines 112–122). Nested keys also became `1-<n>`. *Inferred:* the planning key becomes `1-planning` (`EX` lines 163–167).

| Case, under the daemon | Settled route | `copy-logger` | `copy-base` |
|---|---|---|---|
| 160 by 40, current | 97–99% (§ 3.1) | Linux 90 of 92, ConPTY 91 of 93, MinTTY 91 of 93 | Linux 90 of 92, ConPTY 92 of 94, MinTTY 91 of 93 |
| 160 by 24, current | 80–82%; hidden 0.5–4.0 s | Linux 90 of 92, ConPTY 91 of 93, MinTTY 90 of 93 | Linux 90 of 92, ConPTY 91 of 93, MinTTY 89 of 93 |
| `nt.tiny`, current | 0 of 17; another task's name | 15 of 17 on each | 15 of 17 on each |
| Lines shown intact | all | all | all |

- **Not current** under either alternative: only the snapshots at 0 s and 0.25 s, plus three single MinTTY snapshots of the kind in § 3.1.
- **The command's line came first.** It was the first task line in every snapshot that showed it, in the five runs `prompt-position.py` was run over. *Inferred:* `1` sorts before every `1-<n>` key (§ 2.2), so the command's line is always drawn while it exists, at any terminal height, and no nested key can equal it.
- **What they change on the console.** Nested tasks' lines are prefixed `1-<n>]` instead of `<n>]`, for example `1-1162] broken.test.compile compiling …`.
- **What they do not change.** `execute` still clears every prompt line when it ends (§ 4). The copy's `FAILED` header still appeared, in the ConPTY `copy-logger` run at 24 rows.
- **Not tried.** A route that held was found, so these were not run: a long-lived ticker task inside the nested `execute`; re-asserting the command's prompt line as nested tasks start; `Logger.prompt.setPromptLine` by reflection. Showing the summary after `execute` returns was not attempted. It would need the prompt unpaused and the line set again, through `Logger.prompt`'s `private[mill]` `withPromptUnpaused` and `setPromptLine` (`LOG` lines 89, 180–193).

| Route | Held | API it adds to the settled route | Marker in `[mill]` |
|---|---|---|---|
| Settled route | At 40 rows; not at 24 rows; not with fewer than ten nested tasks | — | As `MNK` § 5: `EvaluatorImpl.execution` `private val` by reflection; `mill.exec.Execution` outside the policy |
| `copy-logger` | In every case run | `Evaluator.execute`'s `logger` parameter | Public, `mill.api`, no annotation, MiMa-checked (`EV` lines 98–105) |
| `copy-base` | In every case run | `Evaluator.withBaseLogger` | Public, `mill.api`, no annotation (`EV` line 43; `MCL` § 7) |
| Both alternatives | — | Behaviour relied on: keys extend the logger's `logKey` (`PFX` line 37), sorting and the line cap (`PL`, `PLU`) | `Logger.logKey` and `Logger.prompt` are `private[mill]` (`LOG` lines 83, 89); `PrefixLogger`, `PromptLogger` and `PromptLoggerUtil` are in `mill.internal`, outside the policy (`MCL` § 7) |

- *Inferred:* the alternatives fail silently, not at compile time, if a later Mill stops extending the passed logger's key or changes how prompt lines are sorted or capped. The summary would then behave as under the settled route.

## 7. Against the earlier notes

- `MCL` § 4.2's last bullet held only for nested evaluations of fewer than ten tasks (§ 3.2). For the test runs it did not, because keys are zero-padded (§ 2.1).
- `MCL` § 4.2, on `execute` clearing every prompt status, held: the summary disappeared when the nested `execute` returned (§ 4).
- `MNK` § 3.3's inference that the copy sets a final `SUCCESS` or `N FAILED` header held for `N FAILED`. The header was drawn in 14 of 37 `nt.test` runs and then erased within 100 ms. `SUCCESS` did not appear, since the copy is not at the final depth (§ 4).
- `EPS` § 8.2 and § 8.4's lost `Task.log` line did not recur (§ 5).

## 8. What was not run or not determined

- macOS; Scala.js and Native; terminal emulators' own drawing. Recordings were rendered by `pyte`, and what MinTTY and Windows Terminal draw was not observed.
- MinTTY with `MSYS=disable_pcon`, where `EPS` § 8.4 found no prompt is drawn.
- Why `EPS` lost a `Task.log` line under ConPTY and MinTTY, and whether the race inferred in § 5 can occur.
- Why the copy's `FAILED` header reached the terminal in every `--no-daemon` run but in only 3 of 26 daemon runs.
- A nested evaluation with no failures, other than `nt.tiny`; *inferred* from `EX` lines 372–375, the copy then draws no final header.
- Terminal heights other than 40 and 24, and builds where many long-running nested tasks have keys beginning `0` at 40 rows.
- `--ticker false`, a terminal of unknown width, `--watch`, BSP, and a command line holding other goals.
- The alternative routes under `--no-daemon`, and more than one run of each.
- The routes not tried in § 6.
- Mill versions other than `1.1.10`.
