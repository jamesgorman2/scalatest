# What gives plugin code one lifetime spanning a whole Mill command?

This note covers:

- which Mill mechanisms let plugin code run once for a Mill command, before the first test task and after the last;
- how that code learns the modules and selectors, and that the command ended;
- what Mill does to its terminal output, and how the command's own output can share it with Mill's prompt;
- whether it can set the exit status;
- how stable the APIs are.

Question: [What gives plugin code one lifetime spanning a whole Mill command?](https://github.com/jamesgorman2/scalatest/issues/28). Facts only; no design is chosen.

Four other notes hold facts this one relies on and does not repeat:

- `.planning/structured-test-reporting/research/mill-test-worker-args.md`, cited as `MILL`: Mill's test tasks and test-worker JVMs.
- `.planning/event-stream-reporting/research/mill-worker-channels.md`, cited as `MWC`: how Mill wires a test-worker JVM's streams and what it does to a task's output.
- `.planning/event-stream-reporting/research/js-native-channels.md`, cited as `JNC`: the channels, stdout and stderr, and hooks of a Scala.js run on Node or a Native run under Mill.
- `.planning/event-stream-reporting/research/emitter-plugin-spike.md`, cited as `EPS`: what was observed when an exclusive command executed several modules' test tasks.

## Short answer

In this note and the notes citing it, *Mill command* and *selector* have Mill's senses: one run of `mill` from the command line, and a token on it naming tasks or modules. They are not the test command and module selector of `.docs/ubiquitous-language.md`.

- **Of the mechanisms examined, one spans the test tasks: an exclusive command that takes the `Evaluator` and executes the test tasks itself** (§ 2). Its body runs before the first test task, regains control after the last whether those tasks passed or failed, receives every task's result, and returns the command's success or failure. Without `--keep-going`, the body does not run at all when a task that ran before it in the same evaluation failed (§ 2.2). Mill documents evaluator commands as experimental.
- **It spans one evaluation, not one Mill command.** A command line holding `+` is several goals of one evaluation, and `--watch` repeats the evaluation inside one Mill command (§ 1, § 2.6).
- **The command learns the modules and selectors from its own parameters**, and resolves selector tokens through the `Evaluator`. Mill consumes a `+` before the command sees it (§ 2.3).
- **Each other mechanism examined lacks a start, an end, or the failure path, or does not run once per Mill command** (§ 3):
  - An aggregating command's body is skipped when a test task fails.
  - A sibling exclusive command is skipped after a failure unless `--keep-going` is set.
  - A `Task.Worker` outlives the command and has no end-of-command callback.
  - A JVM shutdown hook runs once per Mill command under `--no-daemon`, and under the daemon only when the daemon exits.
  - No evaluator or runner hook exists.
  - Mill has no cross-module test report.
- **Terminal output.** Tasks run by the nested `execute` write through Mill's prompt, prefixed per task. The command's own `System.out` is the raw streams, which bypass the prompt; its `Task.log.streams` go through the prompt. A raw write during a nested `execute` can interleave with Mill's prefixed output (§ 4; `EPS` § 8). `Task.log.ticker`, public with no annotation, sets a detail on the command's own prompt line, redrawn in place, drawn while the first nested `execute` runs and cleared when it ends; nothing is drawn under `--ticker false`, and where the console width is unknown the prompt is printed as plain lines every 60 s (§ 4.2).
- **Under the daemon, a user interrupt exits the JVM without interrupting the command's thread** (§ 5.1). Under the daemon and `--no-daemon`, the command body does not regain control (`EPS` § 8.3). *Inferred:* only a JVM shutdown hook observes the interrupt (§ 5.1, § 5.2).
- **Exit status is 0 or 1 from the command's result**; any other code needs `systemExitWithReason`, which stops the daemon (§ 6).
- **API stability.** `Task.Command(exclusive = true)`, `mill.api.Evaluator` and `Evaluator.Result` are public in `mill.api`, which Mill's binary-compatibility policy covers. `TestModuleUtil` and the API `Task.fork` returns are `@experimental`, `Logger.prompt` is `private[mill]`, and `mill.exec` and `mill.internal` are outside the policy (§ 7).
- **Not known** (§ 8): among others, how the launcher handles SIGINT.
- **Nothing here was run.** Every behaviour is read from source at one Mill version; what was later run against it is `EPS`.

## Sources

| Tag | Source | Version |
|---|---|---|
| `[mill]` | <https://github.com/com-lihaoyi/mill> | tag `1.1.10` (`92d51b79bc2243fd2e82a022c924b03b822b2d7b`), the version `MILL` pins |
| `MILL` | `.planning/structured-test-reporting/research/mill-test-worker-args.md` | as committed |
| `MWC` | `.planning/event-stream-reporting/research/mill-worker-channels.md` | as committed |
| `JNC` | `.planning/event-stream-reporting/research/js-native-channels.md` | as committed |
| `EPS` | `.planning/event-stream-reporting/research/emitter-plugin-spike.md` | as committed |

Permalink base: `https://github.com/com-lihaoyi/mill/blob/92d51b79bc2243fd2e82a022c924b03b822b2d7b/`. Paths are relative to the `[mill]` root. Abbreviations:

- `EV` is `core/api/src/mill/api/Evaluator.scala`.
- `EI` is `core/eval/src/mill/eval/EvaluatorImpl.scala`.
- `EX` is `core/exec/src/mill/exec/Execution.scala`.
- `GE` is `core/exec/src/mill/exec/GroupExecution.scala`.
- `TASK` is `core/api/src/mill/api/Task.scala`.
- `CTX` is `core/api/java11/src/mill/api/TaskCtx.scala`.
- `BOOT` is `runner/daemon/src/mill/daemon/MillBuildBootstrap.scala`.
- `MAIN` is `runner/daemon/src/mill/daemon/MillMain0.scala`.
- `DSRV` is `runner/server/src/mill/server/MillDaemonServer.scala`.
- `SRV` is `libs/daemon/server/src/mill/server/Server.scala`.
- `PL` is `core/internal/src/mill/internal/PromptLogger.scala`.
- `PLU` is `core/internal/src/mill/internal/PromptLoggerUtil.scala`.
- `TM` and `TMU` are as in `MILL`.

Statements marked *inferred* follow from the cited source and were not observed. § 8 lists what was not determined.

## 1. How a command line reaches tasks

- **One evaluation per command line.** The runner passes all of the command line's task arguments to one `evaluator.evaluate(tasksAndParams, SelectMode.Separated, …)` call (`BOOT` lines 399–413, 586–600). `evaluate` resolves them to tasks and makes one `execute` call (`EI` lines 405–426).
- **`+` separates goals.** `ParseArgs.separate` splits the arguments at each `+` token; `\+` passes a literal `+` (`core/api/src/mill/api/internal/ParseArgs.scala` lines 59–82). Each group is one selector followed by its arguments (lines 101–115). `mill foo.test + bar.test` is documented (`website/docs/modules/ROOT/pages/cli/flags.adoc` line 53).
- **`foo.test` is `foo.test.testForked`.** A selector that resolves to a `DefaultTaskModule` resolves to its default task (`core/resolve/src/mill/resolve/Resolve.scala` lines 147–153); `TestModule`'s is `testForked` (`TM` line 34).
- **`mill __.test`** therefore resolves to one `testForked` command per test module, all goals of the same `execute` call.
- **Scheduling.** Within one `execute`, all tasks that are not exclusive commands run first, in parallel on the `--jobs` pool. Exclusive commands then run one at a time on the calling thread (`EX` lines 216–223, 358–367).
- **`--watch`.** With `-w`, one Mill process repeats the whole evaluation on each change or Enter key, until interrupted (`runner/daemon/src/mill/daemon/Watching.scala` lines 70–92; `MAIN` lines 489–509).
- **BSP.** The BSP server calls `execute` directly with its own `testReporter` (`runner/bsp/worker/src/mill/bsp/worker/Endpoints.scala` lines 436–455). It does not go through a command line.

## 2. An exclusive command that takes the `Evaluator`

### 2.1 What it is

- A command declared `Task.Command(exclusive = true)` may take an `Evaluator` parameter, "providing those commands with the ability to inspect the build and dynamically decide what to evaluate" (`EV` lines 15–22). The parameter is filled from the current evaluator (`libs/util/src/mill/util/TokenReaders.scala` lines 6–10, 25–26).
- Only an exclusive command gets a working evaluator. Any other task gets a proxy that throws "No evaluator available here; Evaluator is only available in exclusive commands" (`GE` lines 868–874).
- "Exclusive commands run serially at the end of an evaluation, without any other tasks running parallel, and without the terminal logging prefixes that are applied to normal tasks" (`TASK` lines 259–265).
- Built-in precedents that resolve and execute tasks from inside the command: `show` (`libs/util/src/mill/util/MainModule.scala` lines 184–192, 483–543), `selective.run` (`libs/util/src/mill/util/SelectiveExecutionModule.scala` lines 100–113).
- Precedent for one command consolidating many modules: `ScoverageReport.htmlReportAll(evaluator, sources = "__.allSources", dataTasks = "__.scoverage.data")` resolves two selectors across the build and depends on the resolved tasks (`contrib/scoverage/src/mill/contrib/scoverage/ScoverageReport.scala` lines 52–110).
- A command can live in an `ExternalModule` object and be invoked as `mill <package>.<Object>/<command>`, as `mill.javalib.PublishModule/publishAll` is (`libs/javalib/src/mill/javalib/PublishModule.scala` lines 645, 743).

### 2.2 What runs once, before the first test task and after the last

- The command body is ordinary code. It can do work, call `evaluator.execute(tasks)` or `evaluator.evaluate(selectors)`, then do more work (`EV` lines 98–112).
- `execute` returns when every task it started has finished. Its nested call is anticipated: `Execution` counts nesting depth so that only the outermost call prints the final status (`EX` lines 46–47, 131–147, 369–375).
- The nested test tasks run in parallel on the same `--jobs` pool as any top-level goal (`EX` lines 220–223, 365).
- Other goals of the same command line are not spanned. Non-exclusive goals run before the exclusive command's body starts, and other exclusive goals run before or after it (§ 1 Scheduling).
- **The command is itself a goal of the top-level `execute`, and its body can be skipped.** One fail-fast flag serves both the non-exclusive and the exclusive tasks of an `execute` call (`EX` lines 159, 365–367). Without `--keep-going`, a task that does not succeed sets it, and a task that has not started when it is set is recorded as skipped without running its body (`EX` lines 276, 315–316, 401). The command's body therefore does not run when another goal of the command line, or a task the command depends on, failed before it. With `--keep-going` it is skipped only when a task it depends on failed (§ 2.4).

### 2.3 How it learns the modules and selectors

- The command's own parameters carry them: a `String*` parameter receives the tokens that follow the command name (`MainModule.scala` line 184).
- `evaluator.resolveTasks`, `resolveSegments` and `resolveModulesOrTasks` turn selector tokens into `Task.Named`, `Segments` or modules (`EV` lines 45–72). A `Task.Named` carries `ctx.segments` and `ctx.enclosingModule` (`EI` lines 131, 320).
- `evaluator.plan(tasks)` gives the transitive plan without running it (`EV` line 74).
- **The `+` separator is consumed before the command sees it.** `mill plug/cmd a.test + b.test` is two goals, `plug/cmd a.test` and `b.test` (§ 1). The command receives `+` only when the user writes `\+`.
- A parameter of type `mill.util.Tasks[T]` is resolved from selector tokens to `Task.Named[T]` values before the body runs (`libs/util/src/mill/util/Tasks.scala` lines 10–29).

### 2.4 How it learns the tasks ended, including when tests fail

- `execute` returns `Evaluator.Result(watchable, values, selectedTasks, executionResults)` (`EV` lines 160–172). It does not throw on task failure: `values` is a `Result.Failure` built from the failing tasks, and `executionResults.transitiveResults` maps every task to its `ExecResult`: success, failure, exception or skipped (`EI` lines 340, 383–398; `EX` lines 398–422).
- The command body continues after a failed nested `execute` and decides its own result. `selective.run` shows the pattern (`SelectiveExecutionModule.scala` lines 105–110).
- A failed test task's result holds Mill's list of failed tests (`MWC` § 5). It reaches the console only if something prints it; the nested `execute` does not.
- **Fail-fast applies inside the nested call.** Without `--keep-going`, the first task that does not succeed sets a flag, and every task that has not yet started is recorded as skipped (`EX` lines 159, 276, 315–316, 401; `BOOT` line 498; `MAIN` line 288). Tasks already running finish. The flag is local to each `execute` call (`EX` line 159).
- With `--keep-going`, only tasks downstream of a failed task are skipped (`GE` lines 509–514), and Mill logs `"<task> task failed"` for each failed task as it fails (`GE` lines 570–573).
- No public member of `Evaluator` changes fail-fast for one call. `failFast` is a constructor field of `mill.exec.Execution` (`EX` line 27).

### 2.5 Listeners the caller of `execute` can pass

- `execute` takes `testReporter: TestReporter` and `logger: Logger` (`EV` lines 98–105). The reporter reaches each task as `Task.testReporter` (`GE` lines 516–529; `TASK` line 119).
- `TestReporter` has `logStart(event)` and `logFinish(event)` over `sbt.testing.Event` (`core/api/daemon/src/mill/api/daemon/internal/TestReporter.scala` lines 11–18).
- Tasks that use it: `testLocal` (`TM` lines 347–358), the Scala.js test task (`libs/scalajslib/src/mill/scalajslib/ScalaJSModule.scala` line 420) and the Native test task (`libs/scalanativelib/src/mill/scalanativelib/ScalaNativeModule.scala` line 509).
- The forked JVM path does not (`MWC` § 1.6).
- `evaluator.withBaseLogger(logger)` returns an evaluator whose tasks log through `logger` (`EV` line 43). `show` uses it to send task stdout to stderr (`MainModule.scala` lines 491–502).
- `Logger` has abstract `private[mill]` members, `prompt` among them (`core/api/daemon/src/mill/api/daemon/Logger.scala` lines 83–96). *Inferred:* a plugin outside package `mill` cannot implement `Logger`.

### 2.6 Limits of the span

- **One evaluation, not one Mill command.** Under `--watch` the command body runs once per re-evaluation (§ 1).
- **Selective execution is off** for an `execute` whose goals include an exclusive command (`EI` line 300).
- **A non-command task cannot depend on an exclusive command**; Mill fails it (`EX` lines 235–239).

## 3. The other mechanisms examined

### 3.1 A command that depends on the test tasks

- A command can depend on other modules' commands or take `Tasks[T]`, as `publishAll` does (`PublishModule.scala` line 743).
- Its body runs only after every dependency succeeded. A task with any input that did not succeed is `Skipped` and its body does not run (`GE` lines 509–514).
- Its body cannot run before its dependencies. This gives no start and no failure path.

### 3.2 A sibling exclusive command on the same command line

- `mill __.test + plug/report` runs `plug/report` after every non-exclusive goal has finished (§ 1 Scheduling).
- Without `--keep-going`, a failed test task sets the fail-fast flag and the exclusive command is skipped (`EX` line 276). With `--keep-going` it runs, since it does not depend on the failed task.
- Nothing runs before the test tasks, and the command learns of them only by resolving selectors itself or reading `out/`.

### 3.3 `Task.Worker`

- A worker's value persists "as long as the Mill process is kept alive (e.g. via `--watch`, or via its default `MillDaemonMain` server process)" (`TASK` lines 297–311). The worker cache is carried from one command to the next in the daemon's `RunnerState` (`BOOT` lines 181–188, 416).
- A worker is created when a task that depends on it first runs, and one worker in an `ExternalModule` can be shared by tasks of many modules, as `ScoverageReportWorker` is (`ScoverageReport.scala` lines 42, 106–107).
- `close()` is called on an `AutoCloseable` worker when its inputs change (`GE` lines 744–773), when the build's classloader is replaced (`BOOT` lines 327–345), and on `clean` (`MainModule.scala` lines 251–256). The documentation states only the first: "Mill will call the `close()` method on it before any newer version of this worker is created" (`example/fundamentals/tasks/6-workers/build.mill` lines 189–190).
- No call closes workers at the end of a command or when the daemon exits: `runner/daemon/src/mill/daemon/RunnerState.scala`, `MillDaemonMain0.scala` and `MillNoDaemonMain0.scala` contain none.
- *Inferred:* a worker outlives a command under the daemon, lives as long as the process under `--no-daemon`, and receives no signal that a command started or ended.

### 3.4 Evaluator and runner hooks

- `core/api/src` and `core/api/daemon/src` declare no listener or hook for the start or end of an evaluation. The only listeners are `CompileProblemReporter` and `TestReporter`, both parameters of `execute` (§ 2.5).
- The runner builds the evaluator and calls `evaluate` with no extension point between (`BOOT` lines 399–413, 442–516).

### 3.5 Mill's own cross-module test report

- None exists. `testReportXml` writes one JUnit XML file into each test task's own `Task.dest` (`TMU` lines 422–436). `libs/javalib/src`, `libs/scalalib/src` and `libs/util/src` hold no task that aggregates test results across modules.

### 3.6 JVM shutdown hooks

- Under the daemon, the JVM outlives the command. A hook runs when the daemon exits: idle timeout, version or config change, or a client interrupt (§ 5).
- Under `--no-daemon`, the launcher starts `MillNoDaemonMain` as a subprocess for the one command (`runner/launcher/src/mill/launcher/MillLauncherMain.scala` lines 43, 65–67), and it ends with `System.exit` (`runner/daemon/src/mill/daemon/MillNoDaemonMain0.scala` line 72). A hook there runs once per Mill command.
- `[mill]` registers no shutdown hook of its own under `runner`, `core`, `libs/javalib/src` or `libs/util/src`.

## 4. Writing to the terminal while test tasks run

### 4.1 What Mill does to a task's output

- How the output of a non-exclusive task, and of a test-worker JVM it spawns, reaches the task's `PrefixLogger` and `.log` file: `MWC` § 1.1–1.4. The per-thread `System.out` proxy: `MWC` § 2.4.
- What the `PrefixLogger` does to each line with the ticker on: `MWC` § 1.5. With the ticker off, lines pass unchanged (`PL` line 273).
- All lines then pass through one pipe to a pumper thread, which clears and redraws the prompt around them on an interactive terminal (`PL` lines 391–412, 439–492).

### 4.2 What an exclusive command's own output gets

- An exclusive command's body runs with `exclusiveSystemStreams`: the command's raw streams, not the prompt logger's (`GE` lines 861–863; `BOOT` line 502; `MAIN` line 298). Its working directory is the workspace root (`GE` line 862).
- Mill prints the command's key once, then pauses the prompt for the body (`GE` lines 885–891). The pause first drains pending output, because the raw streams bypass the pipe (`PL` lines 288–294).
- **A nested `execute` unpauses the prompt** for its duration (`EX` line 137; `PL` lines 296–297). Nested tasks then write through the pipe with prefixes and prompt redraws, as in § 4.1.
- While a nested `execute` runs, a write from the command's own thread to `System.out` goes to the raw streams and is not coordinated with the prompt, so the two can interleave (`EPS` § 8.1, § 8.2).
- `Task.log` inside the command is a `MultiLogger` over the command's `PrefixLogger` and a `FileLogger` on its log file (`GE` lines 503, 516–519). Writes through `Task.log.streams` take the § 4.1 path: line-buffered, prefixed with the command's key, non-colour ANSI stripped, coordinated with the prompt.
- `SystemStreams.original` is public and documented as not to be used: its streams "do not get captured properly by Mill's stdout/err redirection, and thus only get picked up from the Mill server log files asynchronously", so logs "may appear out of order" (`core/api/daemon/src/mill/api/daemon/SystemStreams.scala` lines 20–28). How the daemon's `stdout` and `stderr` files reach the console: `JNC` § 5.3.
- `Logger.prompt`, with `withPromptPaused`, `setPromptLine` and `logPrefixedLine`, is `private[mill]` (`Logger.scala` lines 83–89; § 7).
- `--ticker false` turns the prompt and the prefixes off for the whole command; nothing of the prompt is drawn (`MAIN` lines 216–221; `PL` lines 73, 75, 273).
- **`Task.log.ticker`.** `ticker(s: String): Unit` is declared on `Logger.Actions` (`Logger.scala` lines 155–162); § 7 gives its marker.
- `MultiLogger.ticker` calls `ticker` on both its loggers (`core/internal/src/mill/internal/MultiLogger.scala` lines 37–40).
- `PrefixLogger.ticker` is `prompt.setPromptDetail(logKey, s)` (`core/internal/src/mill/internal/PrefixLogger.scala` line 91). It sets the detail of the prompt status for that key, and does nothing if the key has no status (`PL` lines 155–158, 572–574). The detail is drawn after the key's prompt line text and its seconds suffix, and is rewritten in place (`PLU` lines 120–126).
- The command's own prompt line is set when the command starts, by the `withPromptLine` that wraps it (`EX` lines 264–273). While the prompt is paused for the body, a `ticker` call only updates state; the detail is drawn once a nested `execute` unpauses the prompt (`EX` line 137; `PL` line 93).
- **Every `execute`, nested ones included, ends by clearing every prompt status** (`EX` line 377; `PL` lines 145–147, 569). The command's prompt line and its detail are therefore gone once its first nested `execute` returns, and later `ticker` calls draw nothing.
- The prompt is redrawn every 100 ms, and only when the rendered bytes change, so `ticker` calls between redraws coalesce (`PLU` line 16; `PL` lines 100–102, 562–566). Each line is cut to the console width less one; the detail takes what is left, shortened in the middle with `...` (`PLU` lines 96, 124, 173–186).
- The prompt is interactive only where the console width is known (`PL` line 47). Otherwise it is printed as plain lines every 60 s (`PL` lines 87–90), the interval Mill gives for "background job logs or piped to a log file" (`PLU` lines 18–26).
- `FileLogger.ticker` appends each call as a line to the command's log file (`core/internal/src/mill/internal/FileLogger.scala` line 49).
- *Inferred:* a nested `execute` numbers its task keys from a fresh counter (`EX` lines 160, 264–266) and by default logs through the base logger (`EX` line 135), so a nested task's key may equal the command's and overwrite or clear the command's status and detail.

### 4.3 What Mill itself prints for a test run

- A prompt header with the count of completed tasks and `N failing`, then `SUCCESS` or `N FAILED` (`EX` lines 197–204, 369–375, 438–450).
- One prompt line per running task (`EX` lines 264–273). As its `ticker` detail, each test worker's line shows the class it is running with its seconds, and the test task's line shows `N/M completed.`, counted by class, with `, K failures` before the full stop when K > 0 (`TMU` lines 329, 357–359, 619–620).
- On failure of the top-level evaluation, the formatted failure of every failing task, to stderr (`Watching.scala` lines 48–50, 66–68; `BOOT` lines 428–433).
- Under `--keep-going`, `"<task> task failed"` per failed task (`GE` lines 570–573).

## 5. User interrupt

### 5.1 Under the daemon

- The daemon ignores SIGINT: `Server.overrideSigIntHandling()` installs a no-op handler (`runner/daemon/src/mill/daemon/MillDaemonMain0.scala` line 55; `SRV` lines 372–381).
- The daemon polls the client with a heartbeat every millisecond while the command runs (`SRV` lines 226–243, 267; `DSRV` lines 43–52).
- When the client is gone and the command is not idle, the daemon does not interrupt the command's thread ("`interrupt` has lots of weird side effects"); it closes its server socket (`SRV` lines 267–278).
- Closing the socket ends the accept loop, `run()` returns, and `main` calls `System.exit` (`SRV` lines 142–169; `MillDaemonMain0.scala` lines 60–67).
- The command body's thread does not regain control (`EPS` § 8.3). *Inferred:* its `finally` blocks and any `close()` do not run; JVM shutdown hooks do.
- Subprocesses spawned through `Jvm.spawnProcess` default to `destroyOnExit = true` (`libs/util/java11/src/mill/util/Jvm.scala` lines 134–149).
- *Inferred:* the next command starts a new daemon.

### 5.2 Under `--no-daemon`

- `MillNoDaemonMain0` installs no SIGINT handler (`MillNoDaemonMain0.scala` lines 14–73).
- SIGINT ends the process without the command's body regaining control (`EPS` § 8.3). *Inferred:* shutdown hooks run, as is the JVM's default.

### 5.3 Under `--watch`

- The watch loop "Exits when the thread gets interrupted" and prints "(Enter to re-run, Ctrl-C to exit)" (`Watching.scala` lines 74, 86). Ctrl-C between evaluations ends the Mill command with no command body running.

## 6. Exit status

- A Mill command succeeds when the top-level evaluation has no error (`Watching.scala` line 68; `BOOT` lines 428–433).
- The daemon sends the client exit code 0 or 1 from that boolean (`DSRV` lines 158–160). `--no-daemon` exits with the same (`MillNoDaemonMain0.scala` line 72).
- A command fails by returning `Result.Failure`, calling `Task.fail`, or throwing (`GE` lines 547–560; `CTX` lines 183–191). The failure's message is printed to stderr (§ 4.3).
- A nested `execute` that fails does not fail the outer command; the outer command's own result does (§ 2.4).
- `Task.ctx().systemExitWithReason(reason, exitCode)` "Stops the Mill process" with any exit code (`CTX` lines 32–34). Under `--no-daemon` it calls `sys.exit` (`MillNoDaemonMain0.scala` line 63). Under the daemon it sends the code to the client and shuts the daemon down (`DSRV` lines 83–90, 168–174).
- Exit code 101 is reserved: the launcher treats it as "retry" (`libs/daemon/client/src/mill/client/ClientUtil.scala` line 10; `MillLauncherMain.scala` lines 105–120).

## 7. API stability

- **Policy.** "Mill maintains backward binary compatibility for each major version (`major.minor.point`), enforced with Mima", for `mill.api`, `mill.util`, `mill.eval`, `mill.resolve`, `mill.scalalib`, `mill.scalajslib` and `mill.scalanativelib`. "Other packages like `mill.runner`, `mill.bsp`, etc. are on the classpath but offer no compatibility guarantees", and `mill.contrib` has none (`developer.adoc` lines 310–328).
- **Annotations.** `@experimental` is "not guaranteed to stay" and is excluded from MiMa (`core/api/daemon/src/mill/api/daemon/experimental.scala`; `mill-build/src/millbuild/MillStableJavaModule.scala` line 100). `@internal` is "not guaranteed to stay" (`core/api/daemon/src/mill/api/daemon/internal/internal.scala`); it is not in the MiMa annotation exclusions.

| API | Marker in `[mill]` |
|---|---|
| `Task.Command(exclusive = true)` | Public, in `mill.api`, no annotation (`TASK` lines 259–279). |
| `mill.api.Evaluator`: `resolveTasks`, `resolveSegments`, `resolveModulesOrTasks`, `plan`, `execute`, `evaluate`, `withBaseLogger` | Public, in `mill.api`, no annotation (`EV` lines 23, 43–112). |
| Evaluator commands as a feature | "Evaluator Command are experimental and suspected to change. See … issue #502" (`example/fundamentals/libraries/4-mainargs/build.mill` lines 73–76). |
| `Evaluator.Result`, `ExecutionResults` | Public, in `mill.api` (`EV` lines 167–172). |
| `Evaluator.baseLogger`, `outPath`, `workerCache`, `rootModule`, `env` | `private[mill]` (`EV` lines 24–42). |
| `TestReporter` | Public trait in package `mill.api.daemon.internal`; no annotation (`TestReporter.scala` line 11). |
| `Logger.streams`, `info`, `error`, `ticker` | Public, no annotation (`Logger.scala` lines 19, 128–162). |
| `Logger.prompt`, `unprefixedStreams`, `withRedirectOutToErr` | `private[mill]` (`Logger.scala` lines 61, 83–96). |
| `mill.util.Tasks` | Public, in `mill.util` (`Tasks.scala` line 10). |
| `Task.Worker` | Public, in `mill.api` (`TASK` line 311). |
| `Task.ctx().systemExitWithReason` | Public (`CTX` line 33). |
| `Task.fork` | The accessor carries no annotation (`TASK` line 131). The `TaskCtx.Fork.Api` it returns is declared in `object Fork`, which is `@experimental` (`CTX` lines 193–196). |
| `mill.exec.Execution`, `GroupExecution` | Package `mill.exec`, not in the policy's list. |
| `mill.internal.PromptLogger`, `PrefixLogger`, `MultiLogger`, `FileLogger` | Package `mill.internal`, not in the policy's list. |
| `TestModuleUtil` | `@experimental` (`MILL` § 2.2). |

- `mill.api.daemon.internal` as a whole is not excluded from MiMa; only named members are (`MillStableJavaModule.scala` lines 24–27, 52–55, 88). Whether Mill treats `TestReporter` as covered by the policy is not stated.

## 8. What is not known

- Whether the behaviour of § 1 (`--watch`, BSP), § 2.5, § 2.6, § 3.1, § 3.3–3.6 and § 5.3 holds when run; `EPS` § 9 names the sections it ran against.
- Whether the prompt-line and `ticker` behaviour of § 4.2 and § 4.3 holds when run, including whether a nested task's key can equal the command's.
- How the launcher process itself handles SIGINT; `MillLauncherMain.scala` holds no signal handling, and the native launcher was not read.
- Whether the daemon's command thread is a daemon thread, and how long `System.exit` takes with test-worker JVMs alive.
- Whether a plugin compiled into a package under `mill` can implement `Logger` and pass it to `execute` (§ 2.5).
- Whether several `execute` calls can run concurrently from one exclusive command.
- Whether these facts hold on Mill versions other than `1.1.10`. The changelog records `Task.Command(exclusive = true)` as introduced in 0.12.0 (`changelog.adoc` lines 1678, 1798).
- What issue #502 proposes for evaluator commands; it was not read.
