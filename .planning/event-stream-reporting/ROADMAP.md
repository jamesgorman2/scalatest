# Roadmap - Event-stream test reporting for Mill

Features of the epic [Event-stream test reporting for Mill](SPECIFICATION.md), in intended implementation order: a walking skeleton on one JVM module, then JSON output, then formatting, filtering and human-readable output to a file (feature 16), then every worker, argument and module Mill runs, then supported Mill releases, then `--halt-on-failure`, then a module that presets a test command's arguments (feature 17), then unfinished runs, progress output and capture against nodes on the JVM, then Scala.js and Scala Native. Spec document paths are relative to `.planning/event-stream-reporting/`. Requirements are cited by ID; architecture by section; decisions by title.

## 2. Consolidated JSON output

`-fJ <path>` writes JSON output to the file at that path once every event stream has ended. Builds on feature 1.

- The consolidation root records the ScalaTest version, the module selector with its module, and Mill's name and version; test run labels, run records and suites below it, each suite and node in the layout `JSON-LAYOUT.md` gives (R-028).
- The test command deletes or empties the file before any test starts.
- The format is documented for third-party readers.

Enables:

- `SPECIFICATION.md` R-028, R-121, R-122, R-096–R-105 and R-112 (JSON output), R-108, R-109, R-055 (a JSON file), R-080, R-081 (ScalaTest version; module selectors; Mill name and version), R-084, R-049 (`-fJ`), R-077, R-020, R-021, R-012 and R-086 (in JSON), R-113 (JSON)
- `ARCHITECTURE.md` § Modules § Consolidation library (Writers: JSON), § APIs § The Mill plugin and the consolidation library (Start: a JSON file's sink; build tool name and version), § Data flow (each output a `-f` names: JSON)

Settles:

- Key names above the suites: the root, test run labels, module selectors and build tool (R-028 fixes only the suites and below; R-080 fixes a run record's keys other than its identifier's).
- The form and key of a run's identifier, unique within its label (R-080).
- Where the JSON documentation lives (R-084).

Risks:

- `decisions/runs-labels-and-selectors.md` "In consolidated output a run is not a node…" (Costs: two layouts)
- `decisions/outputs.md` "Each JSON node holds its final state, with nothing per event." (Costs)
- `research/emission-gap-analysis.md` § 9 (run-level facts needing new code)

## 3. Every worker of a JVM module

The test command runs a JVM module in the processes Mill's fork grouping, test parallelism and test queue start, and consolidates every run under the module's label: one human-readable block per run that R-082 names, one run record per run, nothing of one run shown on another. A worker given no task is still a run. Builds on features 1 and 2.

Enables:

- `SPECIFICATION.md` R-053 (fork grouping, test parallelism, test queue), R-071 (a run given no task), R-022, R-043, R-080 and R-082 (several runs on a label)
- `ARCHITECTURE.md` § APIs § The emitter and the Mill plugin (the runner argument: each worker given the absolute path of the file in its own `processFolder`; the event log file read in every `processFolder`; a JVM worker's stdout and stderr tied to its run by its `processFolder`), § Data flow (JVM bullet: drives Mill's test queue), § External systems (Mill: fork grouping, test parallelism, test queue)

Settles:

- The order of a label's run blocks and run records (R-079 orders labels and suites only).

Risks:

- `research/mill-parallel-worker-capture.md` § 5 and `research/mill-runner-progress-without-trait.md` § 6 (on Windows a class sometimes runs on two workers; worker counts vary, under Mill's own task too)
- `research/emission-gap-analysis.md` § 11 (whether a JVM worker executes its tasks one at a time)
- `research/jvm-file-channel.md` § 5 (a module with no test class spawns one worker)
- `research/mill-test-worker-args.md` § 5 I-1 (with test parallelism off, a second JVM in a used `processFolder` makes Mill throw)
- `research/jvm-per-worker-event-log-path.md` § 6 (a per-worker argument with test sandboxing off not compared against Mill's own `testForked` and `testOnly`; no worker killed before or after item 1; paths with spaces untried)

## 4. Class globs, framework arguments and JUnit XML

The test command takes class globs and framework arguments as Mill's `testOnly` takes them, applied alike to every selected module, and runs as `testOnly` runs (or as `testForked` without globs). It writes each module's JUnit XML report as that task writes it, moved with every other `Task.dest` path to a folder the test command owns for the module, and sets launcher environment variables such as `MILL_PARAMS` from the `mill` command line that started it. Builds on feature 3.

Enables:

- `SPECIFICATION.md` R-055 (class globs, framework arguments), R-053 (`testOnly` or `testForked`; class globs and framework arguments), R-110, R-111, R-047
- `ARCHITECTURE.md` § APIs § The emitter and the Mill plugin (the runner argument among the arguments the test command adds to each run's), § Data flow (the ScalaTest arguments corresponding to the test command's class globs and framework arguments)

Settles:

- Where the folder the test command owns for each module sits (R-110 places it only outside the test command's task folder).
- Whether the R-125 allow-list also governs framework arguments given on the test command's own command line (R-125 governs only a module's own ScalaTest arguments).

Risks:

- `decisions/test-command.md` "A run receives only those of its module's own ScalaTest arguments on an allow-list…" (Costs: what the list withholds, should it also govern the test command's own framework arguments)
- `decisions/capture-and-console.md` "Under the test command, Mill's own console results for a module's tests are replaced by the consolidated output." (Costs: no report at Mill's usual path)
- `ARCHITECTURE-DECISIONS.md` "The Mill plugin turns the emitter on with a ScalaTest runner argument." (Costs: visible among the ScalaTest arguments each run receives)
- `research/mill-test-without-module-trait.md` § 4 (R-053 compared on the JVM only)

## 5. Many modules in one test command

The test command takes several module selectors, resolves each to its test modules (a module two module selectors name runs once), and runs every selected module's tests in one nested evaluation with fail-fast off: each starts on its own compile, whatever happens to the others. Builds on features 2 and 4.

- One test run label per module, in label order; the JSON root records each module selector with its modules.
- A module whose tests cannot start is aborted *could not start*, with its failure, and fails the test command.
- A module that does not test with this release of the fork fails the test command before any test starts. Until features 11–13, so does every Scala.js and Native module.

Enables:

- `SPECIFICATION.md` R-055 (several module selectors), R-056, R-061, R-062, R-059 (test framework and fork release), R-058, R-083, R-046 (third bullet), R-078 and R-079 (several labels), R-081 (each module selector with its modules), R-025
- `ARCHITECTURE.md` § Modules § Mill plugin (Test command: resolves the module selectors), § APIs § The Mill plugin and the consolidation library (Start: module selectors; Add a module whose tests did not start), § Data flow (one nested evaluation with fail-fast off)

Settles:

- How the test command tells that a module tests with this release of the fork (R-059).
- What the test command does with a module selector that resolves to no test module.
- How the failure that stopped a module is shown (R-058).

Risks:

- `ARCHITECTURE-DECISIONS.md` "The test command runs every selected module's tests in one nested evaluation of its own…" (Costs: reflection on a `private val`; a copy of `mill.exec.Execution`; its failure count on the prompt)
- `research/mill-nested-keep-going.md` § 2 (concurrent `execute` calls break Mill's pool), § 5
- `research/mill-command-lifetime.md` § 2.2 (the body is skipped after an earlier failure without `--keep-going`), § 2.6 (one evaluation, not one Mill command)
- `decisions/test-command.md` "A selected test module that does not test with the release of this fork…" (Costs: mixed builds narrow their module selectors)

## 6. Supported Mill releases

Each release of the fork lists the Mill releases its integration tests passed on. On any other Mill release the test command warns before any test starts, naming the running Mill version; any human-readable output repeats the warning and any JSON root records that the release is unsupported. Where a Mill internal it needs is missing, it may instead fail before any test starts, naming the Mill version. Mill's test tasks and IDE launches behave as without the plugin on every Mill release it runs on. Builds on features 2 and 5. Every later feature holds on every supported release.

Enables:

- `SPECIFICATION.md` R-093, R-118, R-119, R-120, R-081 (support status), § Scope (supported Mill releases)
- `ARCHITECTURE.md` § Modules § Mill plugin (integration tests under Mill's testkit; API confined to classes the test command alone loads), § APIs § The Mill plugin and the consolidation library (Start: support status and warning)

Settles:

- Where a release records its supported Mill releases, and how CI runs the integration tests on each.
- How the test command detects a missing Mill internal before any test starts (R-118).

Risks:

- `decisions/test-command.md` "A release of the Mill plugin supports exactly the Mill releases its integration tests passed on, not a range." (Costs) and "On an unsupported Mill release, the test command runs and warns." (Costs: a linkage error below the built-against release)
- `decisions/event-log-contract.md` "Every release of the Mill plugin is a release of the fork." (Costs)
- `research/mill-command-lifetime.md` § 7

## 7. `--halt-on-failure`

Under `--halt-on-failure`, once anything has failed the test command, it starts no further selected module's tests; each such module's label is aborted *not run*. Builds on feature 5.

Enables:

- `SPECIFICATION.md` R-055 (`--halt-on-failure`), R-057, R-114
- `ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library (Add a module whose tests did not start: *not run*), § Data flow (`--halt-on-failure` withholds modules not yet started)

Risks:

- `decisions/test-command.md` "`--halt-on-failure` opts into starting no further selected module's tests…" (Costs: timing decides which modules report) and "A selected test module whose tests cannot start counts as a failure…" (Costs)

## 8. JVM runs that do not finish

When a JVM worker dies, the consolidated output still holds everything the run handed to the operating system:

- the run, and each node it left open, aborted; every scope and test a constructed suite registered shown; the test that ended the process shown;
- a worker that dies before its first task, or before any run exists in it, shown on its label, with the output captured from its streams;
- the test command compiles each module's test manifest from Mill's queue and claim files, writing no file of its own; with each run, and each process in which no run existed, it gives the library the test classes its worker claimed, tied to the worker by its `processFolder`;
- each test class claimed by a run that did not finish, or by a process in which no run existed, and reported as a suite by no run, shown once by class name;
- test classes left in a fork group's queue when its last worker ended shown as not run;
- classes left in a queue do not fail the test command; an interrupted test command writes no human-readable or JSON output.

Builds on features 2 and 3.

Enables:

- `SPECIFICATION.md` R-064, R-065, R-066, R-094, R-095, R-123, R-075, R-107 (on a run that does not finish), R-073, R-076, R-068, R-071 (a process that dies before its first task), R-082 (third bullet: did not finish), R-115, R-069, R-116, R-117
- `ARCHITECTURE.md` § Modules § Consolidation library (Runs that do not finish), § Modules § Mill plugin (Test command: compiles each module's test manifest; Test manifest, on the JVM), § APIs § The emitter and the Mill plugin (a `processFolder` with no event log file is a worker in which no run existed), § APIs § The Mill plugin and the consolidation library (Add a run and Add a process that ended before any run existed in it: the test classes the process took; Add a test class), § Data flow (the module's test classes, on the JVM; failure path)

Settles:

- The reason, if any, that an unfinished run's open suites, scopes and tests carry (R-064 aborts them and states no reason; R-102 gives the run *process failed*, and R-100 gives registered scopes and tests that never started *not run*).

Risks:

- `ARCHITECTURE-DECISIONS.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: Mill's `@internal` queue and claim layout; on Windows one class in two workers' `claim.log`, `research/emitter-plugin-spike.md` § 2)
- `decisions/run-that-does-not-finish.md` "A suite's scopes and tests come from the run, one suite at a time…" (Costs) and "When a run's process dies, the consolidated output holds everything the run handed to the operating system before it died." (Costs)
- `research/emitter-plugin-spike.md` § 4 (Mill reports `out.json does not exist` for a dead worker)
- `research/jvm-file-channel.md` § 8 (a worker killed between tasks or with classes queued, not run)
- `research/mill-command-lifetime.md` § 5 (after Ctrl-C the command's body does not regain control)

## 9. Progress output on Mill's console

Progress output is written while the runs execute, showing every failure and every other cause of failure as it arrives (a run that aborts or does not finish as soon as its stream ends):

- on stdout, with no `-o` or with `P` on `-o`: whole lines through the test command's `Task.log` and, where Mill redraws its prompt, a one-line summary on the test command's prompt line through `Task.log.ticker`;
- in the file `-fP <path>` names.

Builds on features 5, 8 and 16.

Enables:

- `SPECIFICATION.md` R-026, R-048 (`P`), R-049 (`-fP`), R-055 (progress output on stdout and in files), R-127 (on progress output), R-090, R-089, R-091, R-067, R-020 (progress with JSON; progress on stdout and in a file), R-077 (a progress file), R-050 (the nested tasks' prefix)
- `ARCHITECTURE.md` § Modules § Consolidation library (Writers: progress), § APIs § The Mill plugin and the consolidation library (Start: line and summary sinks on stdout; a progress file's sink), § Data flow (progress output on stdout through the test command's Mill logging; each output a `-f` names: progress)

Settles:

- The content of progress lines and of the summary (R-090 and R-089 fix what they show, not their form).
- What a progress file holds (R-090 and R-089 cover stdout and Mill's prompt only).
- Which formatting and filtering characters apply to progress output (R-127 names only the human-readable output).

Risks:

- `decisions/capture-and-console.md` "On the console, progress output is a one-line summary rewritten in place plus whole lines…" (Costs: summary cut to width and gone when the evaluation returns; lost `Task.log` lines under Windows pseudo-consoles; `mill.internal` prompt ordering; `1-<n>]` prefix)
- `research/nested-prompt-console.md` § 2.2, § 3.3 (at most rows/3 − 1 task lines), § 4, § 6 (`execute(tasks, logger = Task.log)`)
- `research/mill-command-lifetime.md` § 4.2 (no prompt under `--ticker false`; plain lines where the width is unknown)

## 10. Test output captured against nodes on the JVM

Inside each JVM run, output that capture can tie to a suite, scope or test is captured against the narrowest such node, written to the test event log in its place among that node's events, and withheld from the process's stdout and stderr, so it appears once. Everything else still reaches the run through process capture. Builds on features 1 and 2.

Enables:

- `SPECIFICATION.md` R-010 and R-012 (output captured against a node), R-088, R-087, R-011 (captured output)
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on the JVM)

Settles:

- Which write routes and thread rules tie a JVM write to a node, and where output written during a suite's construction goes (`decisions/capture-and-console.md` "Test output and worker output are told apart by their source…").

Risks:

- `research/emitter-and-mill-channel.md` § 4 (ScalaTest captures nothing today; nothing records which node a thread is running)

## 11. Scala.js under `NodeJs`

The test command runs Scala.js modules whose JS environment is `NodeJs`, and fails before any test starts on any JS environment other than `NodeJs` and `JsDom` (`JsDom` until feature 12 too):

- core's structure wrapper, emitter and encoder on Scala.js; item 1 carries the VM's PID; the test event log is the module's one event log file, named by the runner argument and written with Node's synchronous `fs`;
- the plugin's Scala.js launcher (a C program in a job object on Windows, built by the plugin's Mill build and shipped in its jar; a `/bin/sh` script that `exec`s on Linux and macOS) swapped into `jsEnvConfig` in the plugin's own evaluation of the module's test task;
- the launcher's header gives each VM's PID and start time, ending the run's stream on the VM's exit and tying its stdout and stderr files to the run; a VM that dies before any run exists in it is shown as R-076 states;
- the module's test manifest: the test classes the test command learns before it evaluates the module's test task and holds in memory, all taken by the module's one process and shown as feature 8 shows a JVM worker's.

Builds on features 5 and 8.

Enables:

- `SPECIFICATION.md` R-059 (JS environment), § Scope (Scala.js Scala versions), and on Scala.js under `NodeJs`: R-001, R-002, R-051 and R-063 (with the emitter on), R-010, R-011, R-074, R-071, R-073, R-066, R-076, R-094, R-022, R-040, R-041, R-124, R-125, R-042, R-043 (order in the launcher's files), R-044, R-053, R-110, R-111, R-047
- `ARCHITECTURE.md` § Modules § ScalaTest core (Structure wrapper, Emitter, Codec encoder, on Scala.js), § Modules § Mill plugin (Scala.js launcher; launcher written once per command and swapped in; Event log reading and Process capture from launchers' files; Test manifest, on Scala.js), § APIs § The emitter and the Mill plugin (the runner argument and item 1 under `NodeJs`; the module's one event log file under `NodeJs`; launcher folder created afresh; end of the event stream from the launcher's header; each pair tied to its run by PID), § APIs § The Mill plugin and the consolidation library (the test classes the process took, on Scala.js), § Data flow (Scala.js and Native bullet: `jsEnvConfig`; the module's test classes, on Scala.js), § External systems (Scala.js test adapter; `destroyForcibly()`)

Settles:

- How R-044 holds under Mill's own test task run with the plugin's `TaskCtx`, on Scala.js and, in feature 13, Native (`ARCHITECTURE.md` is silent; `research/native-stdio-wrapper.md` § 4 records Mill's own results line).
- The C toolchain that cross-compiles the Windows launcher (`ARCHITECTURE-DECISIONS.md` "The Mill plugin is built by its own Mill build in this repo." Costs).
- The names of each launcher's file pair in the launcher folder.
- How the test command learns a Scala.js module's test classes before it evaluates Mill's test task, so that they are the classes that task runs (`ARCHITECTURE.md` § Data flow: "from test classes it learns itself"). Unproven: if no way is found, R-066 and R-094 cannot hold on Scala.js, and `SPECIFICATION.md` § Scope needs that exception.

Risks:

- `ARCHITECTURE-DECISIONS.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: no prototype has learned a module's test classes ahead of Mill's test task)
- `ARCHITECTURE-DECISIONS.md` "Every Scala.js VM starts through a launcher the plugin supplies…" (Costs: shipped Windows binary; ARM64 emulation; start-up cost; not run on macOS)
- `ARCHITECTURE-DECISIONS.md` "The plugin installs each launcher by replacing a task's value…" (Costs: breaks at run time)
- `ARCHITECTURE-DECISIONS.md` "The plugin learns that a run's stream has ended when the run's process exits." (Costs: one process hosting several runs)
- `ARCHITECTURE.md` § Modules § ScalaTest core, Emitter (Scala.js runners ignore `-C` and build no `RunStarting`; `research/node-native-file-channel.md` § 1)
- `research/emission-gap-analysis.md` § Short answer (run-level events and a throwing constructor need new code on Scala.js and Native)
- `research/js-stdout-launcher.md` § 3.3 (without a job object the VM outlives a killed launcher), § 12 (not run: macOS, `--no-daemon`, a compiled launcher on Linux)
- Mill 1.3.1, which the plugin is built against, corrects a loss of data on stream close in Scala.js and Native test reporting. The launcher research ran on Mill 1.1.10 (`research/jsdom-channel.md` § 4: output Mill had not read is discarded); what the launcher is still needed for on 1.3.1 is unchecked.
- `jvm/core/src/main/scala/org/scalatest/tools/ArgsParser.scala`, `ArgsParser.parseArgs` (its `SKIP-SCALATESTJS,NATIVE` markers): on Scala.js and Native it appears to reject `-D`, `-P`, `-F`, `-T` and `-W`, which R-125 passes. Not checked against those builds.

## 12. Scala.js under `JsDom`

The test command also runs Scala.js modules whose JS environment is `JsDom`. Each run writes its test event log's items as framed lines with the window's `console.log`, each carrying the framing prefix, the test command's nonce from the runner argument and the run's own ID from item 1; event log reading takes them from the launcher's stdout file, and every other line is the process's ordinary output, captured against the run whose items the file holds. Builds on feature 11.

Enables:

- `SPECIFICATION.md` R-059 (`JsDom` accepted), and under `JsDom`: R-001, R-002, R-051 and R-063 (with the emitter on), R-010, R-011, R-074, R-071, R-073, R-076, R-022, R-040, R-041, R-124, R-125, R-042, R-043 (order in the launcher's files), R-044, R-053, R-110, R-111, R-047
- `ARCHITECTURE.md` § Modules § Mill plugin (Event log reading of launchers' stdout files), § APIs § The emitter and the Mill plugin (runner argument's prefix and nonce; item 1 under `JsDom`; framed lines; a pair tied to its run by its items), § External systems (`JsDom` window code has no `require` or `process`)

Settles:

- The framing prefix, how the nonce is made, the ID a `JsDom` run gives itself in item 1, and how the codec keeps each item to one line.

Risks:

- `ARCHITECTURE-DECISIONS.md` "Under `JsDom`, a run's test event log is framed lines…" (Costs: holds only while every item is one whole line)
- `research/jsdom-channel.md` § 3 (lines must be assembled per writing thread; one buffer corrupted items), § 11 (jsdom other than `27.4.0` not run)

## 13. Scala Native

The test command runs Native modules:

- core's structure wrapper, emitter and encoder on Native; item 1 carries the binary's PID and start time; the test event log is the module's one event log file, named by the runner argument and written through `FileOutputStream`;
- a Native launcher per module, compiled with the module's `nativeClang` with the launcher folder and real binary's path compiled in, swapped into `nativeLink`; it starts `<CROSSCOMPILING_EMULATOR words> <binary> <port>` and writes the binary's stdout and stderr to its own files, headed with the binary's PID and start time;
- the module's test manifest, as feature 11;
- a binary that crashes fails the test command, though Mill's own task passes it.

Builds on features 8 and 11.

Enables:

- `SPECIFICATION.md` § Scope (Native Scala versions), and on Native: R-001, R-002, R-051 and R-063 (with the emitter on), R-010, R-011, R-074, R-071, R-073, R-066, R-076, R-094, R-022, R-040, R-041, R-124, R-125, R-042, R-043 (order in the launcher's files), R-044, R-046, R-053, R-110, R-111, R-047
- `ARCHITECTURE.md` § Modules § ScalaTest core (Structure wrapper, Emitter, Codec encoder, on Native), § Modules § Mill plugin (Native launcher; launcher written once per command and swapped in; Test manifest, on Native), § APIs § The emitter and the Mill plugin (the runner argument and item 1 on Native; the module's one event log file on Native; header and item 1 carrying the same PID and start time), § APIs § The Mill plugin and the consolidation library (the test classes the process took, on Native), § Data flow (Scala.js and Native bullet: `nativeLink`; the module's test classes, on Native), § External systems (Native test adapter; `inheritIO()`)

Settles:

- Whether a module's compiled launcher is reused across test commands (`ARCHITECTURE-DECISIONS.md` "Every Native test binary starts through a launcher…" Costs). Any reuse still writes the launcher into the module's launcher folder, which the test command creates afresh for each test command (`ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).
- How the test command learns a Native module's test classes before it evaluates Mill's test task, so that they are the classes that task runs (`ARCHITECTURE.md` § Data flow: "from test classes it learns itself"). Unproven: if no way is found, R-066 and R-094 cannot hold on Native, and `SPECIFICATION.md` § Scope needs that exception.

Risks:

- `ARCHITECTURE-DECISIONS.md` "The test manifest is compiled from Mill's test queue and claim files on the JVM…" (Costs: no prototype has learned a module's test classes ahead of Mill's test task)
- `ARCHITECTURE-DECISIONS.md` "Every Native test binary starts through a launcher compiled with the module's toolchain…" (Costs: compiled per module; run only on Linux, `--no-daemon`, host architecture)
- `decisions/test-command.md` "A run that did not complete fails the test command, even where Mill's test task passes it." (Costs)
- `research/native-stdio-wrapper.md` § 4 (Mill's results line; exit status after a crash), § 7 (another architecture under its emulator; Windows crash exit status)
- `research/mill-test-without-module-trait.md` § 8 (Native on Linux not run)
- Mill 1.3.1 corrects a loss of data on stream close in Scala.js and Native test reporting, as feature 11's risks note; the Native launcher research ran on Mill 1.1.10 and is unchecked on 1.3.1.
- `ArgsParser.parseArgs` on Native may reject `-D`, `-P`, `-F`, `-T` and `-W`, which R-125 passes, as feature 11's risks note.

## 14. Test output captured against nodes on Scala.js

As feature 10, on Scala.js under `NodeJs` and `JsDom`: writes through `console` and, on Node, `process.stdout` and `process.stderr` are captured against the narrowest node they can be tied to and withheld from the VM's streams. Under `JsDom`, the test event log's own framed lines are never captured. Builds on features 10 and 12.

Enables:

- `SPECIFICATION.md` R-010, R-012, R-088, R-087, on Scala.js
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on Scala.js)

Risks:

- `decisions/capture-and-console.md` "Test output and worker output are told apart by their source…" (Costs: concurrent async tests land on a broader node)

## 15. Test output captured against nodes on Native

As feature 10, on Native: writes through `System.out` and `System.err` are captured against the narrowest node they can be tied to and withheld from the binary's streams; C writes reach the run through the launcher's files. Builds on features 10 and 13.

Enables:

- `SPECIFICATION.md` R-010, R-012, R-088, R-087, on Native
- `ARCHITECTURE.md` § Modules § ScalaTest core (Test output capture, on Native)

Risks:

- `decisions/capture-and-console.md` "Test output and worker output are told apart by their source…" (Costs: a C `printf` lands on a broader node; `research/js-native-structure-and-capture.md` § 4.2)

## 16. Formatting, filtering and human-readable output to a file

The test command takes the formatting and filtering characters on `-o` and `-f` (R-127, R-128), and `-fH <path>` writes human-readable output to the file at that path. Builds on features 1 and 2.

Enables:

- `SPECIFICATION.md` R-127, R-128, R-049 (`-fH`; more than one `-f`), R-055 (human-readable files; formatting and filtering), R-106 (as its characters change it), R-124 (`w`; a file never coloured), R-020 (human-readable on stdout and in a file), R-077, R-059 and R-069 (a human-readable file), § Scope (the test command's output arguments)
- `ARCHITECTURE.md` § APIs § The Mill plugin and the consolidation library (Start: a sink for each output in a file; each human-readable output's characters), § Data flow (each output a `-f` names: human-readable)

Settles:

- Whether one `-f` may name more than one output letter (R-049 does not say).
- What the test command does with a lowercase character outside the set R-127 names.

Risks:

- `decisions/outputs.md` "Short and full stack traces in the human-readable output leave out a `StackDepthException`'s stack trace, unlike ScalaTest's `S` and `F`." (Costs) and "The human-readable output takes the formatting and filtering characters…" (Costs)

## 17. A module that presets a test command's arguments

A build defines a module that presets a test command's arguments, such as module selectors and tags, so that a `mill` command naming only that module, such as `mill tests/performance`, runs that test command (R-126). Builds on features 4 and 5.

Enables:

- `SPECIFICATION.md` R-126, R-055 (from a module that runs the test command over other modules), R-092 (modules a preset module selects), § Scope (a module that presets a test command's arguments)

Settles:

- How a build defines such a module (R-126 gives its effect only).
- How arguments given on its `mill` command combine with its presets.

Risks:

- `decisions/test-command.md` "Only an explicit test command produces consolidated output, leaving Mill's test tasks unchanged." (Costs: built on evaluator commands that Mill documents as experimental), which a preset module's command must reach
