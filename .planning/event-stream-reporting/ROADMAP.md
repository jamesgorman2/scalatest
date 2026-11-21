# Roadmap - Event-stream test reporting for Mill

Features of the epic [Event-stream test reporting for Mill](SPECIFICATION.md), in intended implementation order: a walking skeleton on one JVM module, then JSON output, then formatting, filtering and human-readable output to a file (feature 16), then filtering characters on JSON output (feature 18), then every worker, argument and module Mill runs, then supported Mill releases, then `--halt-on-failure`, then a module that presets a test command's arguments (feature 17), then unfinished runs, progress output and capture against nodes on the JVM, then Scala.js and Scala Native. Spec document paths are relative to `.planning/event-stream-reporting/`. Requirements are cited by ID; architecture by section; decisions by title.

No feature is released before the epic is complete. Behaviour a feature leaves to a later one is undefined until that later feature, and a feature specifies it only where its own acceptance criteria need it.

## 4. Class globs, framework arguments and JUnit XML

The test command takes class globs and framework arguments as Mill's `testOnly` takes them, applied alike to every selected module, and runs as `testOnly` runs (or as `testForked` without globs). It writes each module's JUnit XML report as that task writes it, moved with every other `Task.dest` path to a folder the test command owns for the module, and sets launcher environment variables such as `MILL_PARAMS` from the `mill` command line that started it. Builds on feature 3.

Enables:

- `SPECIFICATION.md` R-055 (class globs, framework arguments), R-053a and R-053b (`testOnly` or `testForked`; class globs and framework arguments), R-110, R-047
- `ARCHITECTURE.md` § APIs § The emitter and the Mill plugin (the runner argument among the arguments the test command adds to each run's), § Data flow (the ScalaTest arguments corresponding to the test command's class globs and framework arguments)

Settles:

- Where the folder the test command owns for each module sits (R-110 does not place it).

Risks:

- `decisions/test-command.md` "A test module's `testArgsDefault` arguments outside … are suppressed…" (whether the list should also govern the test command's own framework arguments)
- `decisions/capture-and-console.md` "Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output." (Costs: no report at Mill's usual path)
- `decisions/architecture-test-event-log.md` "The Mill plugin turns the emitter on with a ScalaTest runner argument." (Costs: visible among the ScalaTest arguments each run receives)
- `research/mill-test-without-module-trait.md` § 4 (R-053a and R-053b compared on the JVM only)

## 5. Many modules in one test command

The test command takes several module selectors, resolves each to its test modules (a module two module selectors name runs once), and runs every selected module's tests in one nested evaluation with fail-fast off: each starts on its own compile, whatever happens to the others. Builds on features 2 and 4.

- One module node per module, in the order R-105 gives; the JSON root records each module selector with its modules.
- A module whose tests cannot start, as when it fails to compile, has an outcome showing that error (R-098e), and fails the test command.
- A module that does not test with this release of the fork fails the test command before any test starts (R-059). Until features 11–13, so does every Scala.js and Native module.
- A failure under R-059 leaves no file at the `-fJ` path.

Enables:

- `SPECIFICATION.md` R-055 (several module selectors), R-056a, R-056b, R-061, R-062, R-059 (test framework and fork release), R-098e, R-046c and R-137 (a module with an error), R-096a and R-105 (several modules)
- `JSON-LAYOUT.md` § Root and modules (`moduleSelectors` with several module selectors; several modules)
- `ARCHITECTURE.md` § Modules § Mill plugin (Test command: resolves the module selectors), § APIs § The Mill plugin and the consolidation library (Start: module selectors; Add a module whose tests did not start), § Data flow (one nested evaluation with fail-fast off)

Settles:

- How the test command tells that a module tests with this release of the fork (R-059).
- What the test command does with a module selector that resolves to no test module.
- How the error that stopped a module is shown (R-098e gives the module an outcome, not its form).

Risks:

- `decisions/architecture-running-tests.md` "The test command runs every selected module's tests in one nested evaluation of its own…" (Costs: reflection on a `private val`; a copy of `mill.exec.Execution`; its failure count on the prompt)
- `research/mill-nested-keep-going.md` § 2 (concurrent `execute` calls break Mill's pool), § 5
- `research/mill-command-lifetime.md` § 2.2 (the body is skipped after an earlier failure without `--keep-going`), § 2.6 (one evaluation, not one Mill command)
- `decisions/test-command.md` "A selected test module that does not test with the release of this fork…" (Costs: mixed builds narrow their module selectors)

## 6. Supported Mill releases

Each release of the fork lists the Mill releases its integration tests passed on. On any other Mill release the test command warns on the console and in any file outputs before any test starts, naming the running Mill version (R-093), and any JSON root records that the release is unsupported (`JSON-LAYOUT.md` § Not yet fixed). Beyond that warning, its behaviour on such a release is undefined. Mill's test tasks and IDE launches behave as without the plugin on every Mill release it runs on. Builds on features 2 and 5. Every later feature delivers its behaviour on every supported release.

Enables:

- `SPECIFICATION.md` R-003 and R-060 (on unsupported Mill releases), R-093, § Scope (supported Mill releases)
- `JSON-LAYOUT.md` § Not yet fixed (the root: support status)
- `ARCHITECTURE.md` § Modules § Mill plugin (integration tests under Mill's testkit; API confined to classes the test command alone loads), § APIs § The Mill plugin and the consolidation library (Start: support status and warning)

Settles:

- Where a release records its supported Mill releases, and how CI runs the integration tests on each.

Risks:

- `decisions/test-command.md` "A release of the Mill plugin supports exactly the Mill releases its integration tests passed on, not a range." (Costs) and "On an unsupported Mill release, the test command warns on the console and in every file output…" (a linkage error below the built-against release)
- `decisions/event-log-contract.md` "Every release of the Mill plugin is a release of the fork." (Costs)
- `research/mill-command-lifetime.md` § 7

## 7. `--halt-on-failure`

Under `--halt-on-failure`, once any node has failed (R-046a, R-046b, R-046c), the test command starts no further selected module's tests. Each such module's tests are shown as not run. Builds on feature 5.

Enables:

- `SPECIFICATION.md` R-055 (`--halt-on-failure`), R-056a (its exception under `--halt-on-failure`)
- `ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library (Add a module whose tests did not start: *not run*; Read the consolidation state: *open, failed* before each module starts), § Data flow (`--halt-on-failure` withholds modules not yet started)

Settles:

- Whether a module whose tests `--halt-on-failure` withheld, or whose tests could not start, has an outcome reason such as *not run* or *could not start* (R-098e gives a module an outcome only where the module has an error).

Risks:

- `decisions/test-command.md` "`--halt-on-failure` opts into starting no further selected module's tests…" (Costs: timing decides which modules report) and "A selected test module whose tests cannot start counts as a failure…" (Costs)

## 8. JVM runs that do not finish

When a JVM worker dies, the consolidated output still contains everything the run handed to the operating system:

- the run, and each node it left open, aborted; every scope and test a constructed suite registered shown; the test that ended the process shown;
- the output captured from the streams of a worker that dies before its first task, or before any run exists in it, attached to the most specific node that can be identified (R-076);
- the test command compiles each module's test manifest from Mill's queue and claim files, writing no file of its own; with each run, and each process in which no run existed, it gives the library its worker's assigned test classes, tied to the worker by its `processFolder`: those the worker's `claim.log` records or, where the module sets `testBatchFrameworkTasks`, the class list the test command gave the worker;
- each test class assigned to a run that did not finish, or to a process in which no run existed, and reported as a suite by no run, shown once by class name;
- test classes left in a fork group's queue when its last worker ended shown as not run.

Builds on features 2 and 3.

Enables:

- `SPECIFICATION.md` R-095b, R-102, R-095a (suites, scopes and tests of a run that does not finish; test classes no run reported), R-130c and R-105 (test classes aborted with reason *not run*), R-136 (assigned test classes), R-106c (on a run that does not finish), R-073, R-076, R-144 (a run whose worker died)
- `ARCHITECTURE.md` § Modules § Consolidation library (Runs that do not finish), § Modules § Mill plugin (Test command: compiles each module's test manifest; Test manifest, on the JVM), § APIs § The emitter and the Mill plugin (a `processFolder` with no event log file is a worker in which no run existed), § APIs § The Mill plugin and the consolidation library (Add a run and Add a process that ended before any run existed in it: the test classes the process was assigned; Add a test class), § Data flow (the module's test classes, on the JVM; failure path)

Settles:

- Whether a suite that has no events, and that a run was to execute, is written inside that run's node (R-101, R-136; `JSON-LAYOUT.md` § Tree writes every such suite as a child of its module).
- The reason, if any, that an unfinished run's open suites, scopes and tests carry (R-095b aborts them and states no reason; R-102 gives the run *process failed*, and R-095a gives registered suites, scopes and tests that have no events *not run*).

Risks:

- `decisions/architecture-running-tests.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: Mill's `@internal` queue and claim layout; on Windows one class in two workers' `claim.log`, `research/emitter-plugin-spike.md` § 2)
- `decisions/run-that-does-not-finish.md` "A suite's scopes and tests come from the run, one suite at a time…" (Costs) and "When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died." (Costs)
- `research/emitter-plugin-spike.md` § 4 (Mill reports `out.json does not exist` for a dead worker)
- `research/jvm-file-channel.md` § 8 (a worker killed between tasks or with classes queued, not run)
- `research/mill-command-lifetime.md` § 5 (after Ctrl-C the command's body does not regain control)

## 9. Progress output on Mill's console

Progress output is written while the runs execute, as new data reaches it. It reports a summary of the number of suites and tests expected to run and their current completion state, success and failure included, always with the most recent values. Progress output is written:

- on stdout, with no `-o` or with `P` on `-o`: through the test command's Mill logging, redrawn in place where it can be;
- in the file `-fP <path>` names.

Builds on features 5, 8 and 16.

Enables:

- `SPECIFICATION.md` R-026, R-048 (`P`), R-049a (`-fP`), R-055 (progress output on stdout and in files), R-090a–R-090e, R-089, R-091, R-020 (progress with JSON; progress on stdout and in a file), R-077 (a progress file), R-050a (the nested tasks' prefix)
- `ARCHITECTURE.md` § Modules § Consolidation library (Writers: progress), § APIs § The Mill plugin and the consolidation library (Start: the summary's sink on stdout; a progress file's sink), § Data flow (progress output on stdout through the test command's Mill logging; each output a `-f` names: progress)

Settles:

- The form of the progress summary (R-090a fixes what it reports, not its form), and whether it is debounced (R-090d). This feature may redefine the format of the summary line that feature 1 prints, and that summary line then follows this feature's (R-141). Feature 1's summary line, the unindented last line of the human-readable output, is:
  - text: `Suites: <completed> of <suites expected> completed, <aborted> aborted. Tests: <run> of <tests expected> run: succeeded <succeeded>, failed <failed>, canceled <canceled>, ignored <ignored>, pending <pending>`;
  - counts: each suite and test of the node tree, once per execution and once where it has none, leaving out attributed nodes. A suite not run counts only in `<suites expected>`; an ignored test only in `<ignored>`; an aborted or not-run test only in `<tests expected>`;
  - colours: each part, a word and its count, has the colour of a line of its kind whatever the count, 0 included: `<aborted> aborted` and `failed <failed>` red, `succeeded <succeeded>` green, `canceled <canceled>`, `ignored <ignored>` and `pending <pending>` yellow. Every other character is cyan.
- How progress output counts before a run's event stream ends, where attribution and the suites its end aborts are not yet decided. One function produces the summary line's counts, text and colours by counting over the assembled tree, so it needs every run's event stream to have ended. So that its last output equals the summary line (R-141), progress output keeps the counts that arriving events give apart from the changes a run's end makes (attributed suites and tests leave the counts; a suite still open at the run's end is aborted), and shows that its counts equal that function's once every stream has ended.
- What a progress file contains, where nothing can be redrawn in place (R-089).
- Which formatting and filtering characters other than `w` apply to progress output (R-090e names `w`; R-106g, R-127a and R-127b name only the human-readable output).

Risks:

- `decisions/capture-and-console.md` "Progress output is a summary of the number of suites and tests expected to run…" (summary cut to width and gone when the evaluation returns; lost `Task.log` lines under Windows pseudo-consoles; `mill.internal` prompt ordering; `1-<n>]` prefix)
- `research/nested-prompt-console.md` § 2.2, § 3.3 (at most rows/3 − 1 task lines), § 4, § 6 (`execute(tasks, logger = Task.log)`)
- `research/mill-command-lifetime.md` § 4.2 (no prompt under `--ticker false`; plain lines where the width is unknown)

## 10. Test output captured against nodes on the JVM

Inside each JVM run, output that capture can tie to a suite, scope or test is captured against the narrowest such node, written to the test event log in its place among that node's events, and withheld from the process's stdout and stderr, so it appears once. Everything else still reaches the run through process capture. Builds on features 1 and 2.

Enables:

- `SPECIFICATION.md` R-097a and R-097b (output captured against a node), R-043, R-087, R-011 (captured output)
- `JSON-LAYOUT.md` § Captured output (each piece of output written at most once)
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on the JVM)

Settles:

- Which write routes and thread rules tie a JVM write to a node, and where output written during a suite's construction goes (`decisions/capture-and-console.md` "Test output and worker output are told apart by their source…").

Risks:

- `research/emitter-and-mill-channel.md` § 4 (ScalaTest captures nothing today; nothing records which node a thread is running)

## 11. Scala.js under `NodeJs`

The test command runs Scala.js modules whose JS environment is `NodeJs`, and fails before any test starts on any JS environment other than `NodeJs` and `JsDom` (`JsDom` until feature 12 too):

- core's structure wrapper, emitter and encoder on Scala.js; the `runStarted` event carries the VM's PID; the test event log is the module's one event log file, named by the runner argument and written with Node's synchronous `fs`;
- the plugin's Scala.js launcher (a C program in a job object on Windows, built by the plugin's Mill build and shipped in its jar; a `/bin/sh` script that `exec`s on Linux and macOS) swapped into `jsEnvConfig` in the plugin's own evaluation of the module's test task;
- the launcher's header gives each VM's PID and start time, ending the run's stream on the VM's exit and tying its stdout and stderr files to the run; a VM that dies before any run exists in it is shown as R-076 states;
- the module's test manifest: the test classes the test command learns before it evaluates the module's test task and keeps in memory, all assigned to the module's one process and shown as feature 8 shows a JVM worker's.

Builds on features 5 and 8.

Enables:

- `SPECIFICATION.md` R-059 (JS environment), § Scope (Scala 3 on Scala.js), and on Scala.js under `NodeJs`: R-001, R-002, R-051, R-063, R-011, R-074a, R-074b, R-073, R-095a, R-076, R-040, R-106f, R-125a, R-125b, R-097a, R-097b, R-087, R-043 (order in the launcher's files), R-044, R-053a, R-053b, R-110, R-047
- `ARCHITECTURE.md` § Modules § ScalaTest core (Structure wrapper, Emitter, Codec encoder, on Scala.js), § Modules § Mill plugin (Scala.js launcher; launcher written once per command and swapped in; Event log reading and Process capture from launchers' files; Test manifest, on Scala.js), § APIs § The emitter and the Mill plugin (the runner argument and the `runStarted` event under `NodeJs`; the module's one event log file under `NodeJs`; launcher folder created afresh; end of the event stream from the launcher's header; each pair tied to its run by PID), § APIs § The Mill plugin and the consolidation library (the test classes the process was assigned, on Scala.js), § Data flow (Scala.js and Native bullet: `jsEnvConfig`; the module's test classes, on Scala.js), § External systems (Scala.js test adapter; `destroyForcibly()`)

Settles:

- How a module records the state of a run that has no node, and tells that run's outcome from the module's own (R-135, R-098d, R-098e).
- How the test command meets R-044 under Mill's own test task run with the plugin's `TaskCtx`, on Scala.js and, in feature 13, Native (`ARCHITECTURE.md` is silent; `research/native-stdio-wrapper.md` § 4 records Mill's own results line).
- The C toolchain that cross-compiles the Windows launcher (`decisions/architecture-library-and-modules.md` "The Mill plugin is built by its own Mill build in this repo." Costs).
- The names of each launcher's file pair in the launcher folder.
- How the test command learns a Scala.js module's test classes before it evaluates Mill's test task, so that they are the classes that task runs (`ARCHITECTURE.md` § Data flow: "from test classes it learns itself"). Unproven: if no way is found, R-095a cannot be met on Scala.js, and `SPECIFICATION.md` § Scope needs that exception.

Risks:

- `decisions/architecture-running-tests.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: no prototype has learned a module's test classes ahead of Mill's test task)
- `decisions/architecture-running-tests.md` "Every Scala.js VM starts through a launcher the plugin supplies…" (Costs: shipped Windows binary; ARM64 emulation; start-up cost; not run on macOS)
- `decisions/architecture-running-tests.md` "The plugin installs each launcher by replacing a task's value…" (Costs: breaks at run time)
- `decisions/architecture-test-event-log.md` "The plugin learns that a run's stream has ended when the run's process exits." (Costs: one process hosting several runs)
- `decisions/architecture-test-event-log.md` "The Mill plugin turns the emitter on with a ScalaTest runner argument." (Alternatives: Scala.js runners ignore `-C` and build no `RunStarting`)
- `research/emission-gap-analysis.md` § Short answer (run-level events and a throwing constructor need new code on Scala.js and Native)
- `research/js-stdout-launcher.md` § 3.3 (without a job object the VM outlives a killed launcher), § 12 (not run: macOS, `--no-daemon`, a compiled launcher on Linux)
- Mill 1.3.1, which the plugin is built against, corrects a loss of data on stream close in Scala.js and Native test reporting. The launcher research ran on Mill 1.1.10 (`research/jsdom-channel.md` § 4: output Mill had not read is discarded); what the launcher is still needed for on 1.3.1 is unchecked.
- `jvm/core/src/main/scala/org/scalatest/tools/ArgsParser.scala`, `ArgsParser.parseArgs` (its `SKIP-SCALATESTJS,NATIVE` markers): on Scala.js and Native it appears to reject `-D`, `-P`, `-F`, `-T` and `-W`, which R-125a passes. Not checked against those builds.

## 12. Scala.js under `JsDom`

The test command also runs Scala.js modules whose JS environment is `JsDom`. Each run writes its test event log's events as framed lines with the window's `console.log`, each carrying the framing prefix, the test command's nonce from the runner argument and the run's own ID from the `runStarted` event; event log reading takes them from the launcher's stdout file, and every other line is the process's ordinary output, captured against the run whose events the file contains. Builds on feature 11.

Enables:

- `SPECIFICATION.md` R-059 (`JsDom` accepted), and under `JsDom`: R-001, R-002, R-051, R-063, R-011, R-074a, R-074b, R-073, R-076, R-040, R-106f, R-125a, R-125b, R-097a, R-097b, R-087, R-043 (order in the launcher's files), R-044, R-053a, R-053b, R-110, R-047
- `ARCHITECTURE.md` § Modules § Mill plugin (Event log reading of launchers' stdout files), § APIs § The emitter and the Mill plugin (runner argument's prefix and nonce; the `runStarted` event under `JsDom`; framed lines; a pair tied to its run by its events), § APIs § The Mill plugin and the consolidation library (Build the runner argument: prefix and nonce), § External systems (`JsDom` window code has no `require` or `process`)

Settles:

- The framing prefix, how the nonce is made, and the ID a `JsDom` run gives itself in the `runStarted` event.

Risks:

- `decisions/architecture-test-event-log.md` "Under `JsDom`, a run's test event log is framed lines…" (Costs: readable only while every event is one whole line)
- `research/jsdom-channel.md` § 3 (lines must be assembled per writing thread; one buffer corrupted events), § 11 (jsdom other than `27.4.0` not run)

## 13. Scala Native

The test command runs Native modules:

- core's structure wrapper, emitter and encoder on Native; the `runStarted` event carries the binary's PID and start time; the test event log is the module's one event log file, named by the runner argument and written through `FileOutputStream`;
- a Native launcher per module, compiled with the module's `nativeClang` with the launcher folder and real binary's path compiled in, swapped into `nativeLink`; it starts `<CROSSCOMPILING_EMULATOR words> <binary> <port>` and writes the binary's stdout and stderr to its own files, headed with the binary's PID and start time;
- the module's test manifest, as feature 11;
- a binary that crashes fails the test command, though Mill's own task passes it.

Builds on features 8 and 11.

Enables:

- `SPECIFICATION.md` § Scope (Scala 3 on Native), and on Native: R-001, R-002, R-051, R-063, R-011, R-074a, R-074b, R-073, R-095a, R-076, R-040, R-106f, R-125a, R-125b, R-097a, R-097b, R-087, R-043 (order in the launcher's files), R-044, R-046c, R-137, R-053a, R-053b, R-110, R-047
- `ARCHITECTURE.md` § Modules § ScalaTest core (Structure wrapper, Emitter, Codec encoder, on Native), § Modules § Mill plugin (Native launcher; launcher written once per command and swapped in; Test manifest, on Native), § APIs § The emitter and the Mill plugin (the runner argument and the `runStarted` event on Native; the module's one event log file on Native; header and the `runStarted` event carrying the same PID and start time), § APIs § The Mill plugin and the consolidation library (the test classes the process was assigned, on Native), § Data flow (Scala.js and Native bullet: `nativeLink`; the module's test classes, on Native), § External systems (Native test adapter; `inheritIO()`)

Settles:

- Whether a module's compiled launcher is reused across test commands (`decisions/architecture-running-tests.md` "Every Native test binary starts through a launcher…" Costs). Any reuse still writes the launcher into the module's launcher folder, which the test command creates afresh for each test command (`ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
- How the test command learns a Native module's test classes before it evaluates Mill's test task, so that they are the classes that task runs (`ARCHITECTURE.md` § Data flow: "from test classes it learns itself"). Unproven: if no way is found, R-095a cannot be met on Native, and `SPECIFICATION.md` § Scope needs that exception.

Risks:

- `decisions/architecture-running-tests.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: no prototype has learned a module's test classes ahead of Mill's test task)
- `decisions/architecture-running-tests.md` "Every Native test binary starts through a launcher compiled with the module's toolchain…" (Costs: compiled per module; run only on Linux, `--no-daemon`, host architecture)
- `decisions/test-command.md` "A run that did not complete fails the test command, even where Mill's test task passes it." (Costs)
- `research/native-stdio-wrapper.md` § 4 (Mill's results line; exit status after a crash), § 7 (another architecture under its emulator; Windows crash exit status)
- `research/mill-test-without-module-trait.md` § 8 (Native on Linux not run)
- Mill 1.3.1 corrects a loss of data on stream close in Scala.js and Native test reporting, as feature 11's risks note; the Native launcher research ran on Mill 1.1.10 and is unchecked on 1.3.1.
- `ArgsParser.parseArgs` on Native may reject `-D`, `-P`, `-F`, `-T` and `-W`, which R-125a passes, as feature 11's risks note.

## 14. Test output captured against nodes on Scala.js

As feature 10, on Scala.js under `NodeJs` and `JsDom`: writes through `console` and, on Node, `process.stdout` and `process.stderr` are captured against the narrowest node they can be tied to and withheld from the VM's streams. Under `JsDom`, the test event log's own framed lines are never captured. Builds on features 10 and 12.

Enables:

- `SPECIFICATION.md` R-011, R-097a, R-097b, R-043, R-087, and `JSON-LAYOUT.md` § Captured output, on Scala.js
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on Scala.js)

Risks:

- `decisions/capture-and-console.md` "Test output and worker output are told apart by their source…" (Costs: concurrent async tests land on a broader node)

## 15. Test output captured against nodes on Native

As feature 10, on Native: writes through `System.out` and `System.err` are captured against the narrowest node they can be tied to and withheld from the binary's streams; C writes reach the run through the launcher's files. Builds on features 10 and 13.

Enables:

- `SPECIFICATION.md` R-011, R-097a, R-097b, R-043, R-087, and `JSON-LAYOUT.md` § Captured output, on Native
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on Native)

Risks:

- `decisions/capture-and-console.md` "Test output and worker output are told apart by their source…" (Costs: a C `printf` lands on a broader node; `research/js-native-structure-and-capture.md` § 4.2)

## 16. Formatting, filtering and human-readable output to a file

The test command takes the formatting and filtering characters on `-o` and `-f` and applies them to human-readable output (R-138, R-139, R-106f, R-106g, R-127a, R-127b), and `-fH <path>` writes human-readable output to the file at that path. Feature 18 applies the filtering characters to JSON output. Builds on features 1 and 2.

Enables:

- `SPECIFICATION.md` R-138 and R-139 (parsing on every `-o` and `-f`; effect on human-readable output), R-127a, R-127b, R-106c (nodes a filtering character removes), R-106f (`w`), R-106g (`d`), R-049a (`-fH`), R-049b (more than one `-f`), R-055 (human-readable files; formatting and filtering), R-020 (human-readable on stdout and in a file), R-077 and R-059 (a human-readable file), § Scope (the test command's output arguments)
- `ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library (Start: a sink for each output in a file; each human-readable output's characters), § Data flow (each output a `-f` names: human-readable)

Settles:

- Whether one `-f` may name output letters in a combination other than those R-049a lists, such as `J` with `H`.
- What the test command does with a lowercase character outside the set R-106f, R-106g and R-127a name.
- What the test command does with an `-o` output letter other than `H` and `P`, such as `J` (R-048 names only those two).

Risks:

- `decisions/outputs.md` "Each `-o` and `-f` argument takes the formatting characters `w` and `d` for human-readable output, and the filtering characters…"

## 17. A module that presets a test command's arguments

A build defines a module that presets a test command's arguments, such as module selectors and tags, so that a `mill` command naming only that module, such as `mill performanceTests` or `mill integrationTests`, runs that test command (R-126). The build, not the user, then knows which modules and tags make up a kind of test run. Builds on features 4 and 5.

Enables:

- `SPECIFICATION.md` R-126, R-055 (from a module that presets them), R-092 (modules a preset module selects), § Scope (a module that presets a test command's arguments)

Settles:

- How a build defines such a module (R-126 gives its effect only).
- How arguments given on its `mill` command combine with its presets.

Risks:

- `decisions/test-command.md` "Only an explicit test command produces consolidated output, leaving Mill's test tasks unchanged." (Costs: built on evaluator commands that Mill documents as experimental), which a preset module's command must reach

## 18. Filtering characters on JSON output

The filtering characters on a `-fJ` argument omit nodes and messages from the JSON output that argument names. Builds on features 2 and 16.

- `c` omits succeeded tests, `x` ignored tests, `e` pending tests, `o` info, `m` markup and `r` pending scopes (R-127c).
- A scope whose children have all been omitted is omitted, recursively (R-127d).
- The characters apply only to the `-fJ` they are attached to: another `-f` or the `-o` of the same test command is unaffected, and a `-fJ` with no filtering character writes every node (R-139, R-028).

Enables:

- `SPECIFICATION.md` R-127c, R-127d, R-138 and R-139 (filtering characters on a `-fJ`), R-028 (the tree less what filtering characters omit), R-055 (filtering of JSON output)
- `JSON-LAYOUT.md` § Tree (every node except what the `-f` argument's filtering characters omit)

Settles:

- How the JSON writer is given its `-f` argument's filtering characters (`ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library, Start, gives characters for each human-readable output only).
- Whether the JSON output records which filtering characters were applied, or that a node's `contents` had nodes or messages omitted (`JSON-LAYOUT.md` § Nodes, § Messages and § Not yet fixed are silent).
- Where the output captured against an omitted test or scope goes (R-127c and R-127d are silent; `JSON-LAYOUT.md` § Captured output).
- Whether a scope is omitted when its tests and scopes are all omitted but a message remains in its `contents`, or output is captured against it (R-127d speaks of its children).
- Whether a suite, run node or module is omitted when everything beneath it is omitted (R-127d names scopes only).
- What a formatting character, `w` or `d`, does on a `-fJ` (R-138 allows formatting characters on each `-f`; R-106f and R-106g name the human-readable output only).

Risks:

- `JSON-LAYOUT.md` § Nodes ("No node's outcome is rolled up from its children; readers of the output roll up outcomes themselves"): a reader rolling up a filtered tree counts only the nodes left.

## Deferred

- Showing a test that ran more than once, such as one a retry wrapper reruns, as one line in the human-readable output. Each time it ran is shown as its own line.
- Distinguishing suites that share a suite name but have different IDs, and relating runs of one suite ID under different suite names, in both the human-readable and JSON outputs. Siblings are ordered by suite name (R-105), so the former interleave indistinguishably by start order and the latter are not recognisable as the same suite.
