# Architecture decisions: event-stream test reporting for Mill

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## The emitter holds the events of a suite instance the run did not know of until the suite it runs inside is known, rather than writing them against a node placed later.

**Alternatives** Writing each event at once against a provisional node that a later `structure` event places needs an unplaced-node form in the format. It also leaves assembly to hold the node's events and to show a node never placed.

**Reasoning** Every reporter of the run lies beyond the structure wrapper that shows where the instance runs, so a held event has reached none of them. The event log is therefore never behind the run's reporters. A process that dies loses only what `SPECIFICATION.md` R-073 already excludes as buffered inside the process. Usually the event reaches that wrapper in the call that sent it, and is held only for that call.

**Costs** Where a `TestSortingReporter` of the instance sits before that wrapper, as for a helper suite mixing in `RandomTestOrder`, the instance's events reach the event log, and so live output, only as the sorter releases them. The events of an instance given a reporter that leads to none of the run's are not in the event log.

**Settled by** [Does the emitter hold an unregistered suite instance's events until its parent node is known?](https://github.com/jamesgorman2/scalatest/issues/72).

---

## Each side of core's codec is tested on its own against the test event log's written format, not by round trip.

**Alternatives**

- Round-trip tests in one build pass even where the builds of core that write and read an event log disagree, and never feed the decoder a torn last line.
- End-to-end tests alone cannot produce a torn last line on demand, and a failure does not say which side drifted.

**Reasoning** The encoder runs in the run's process, on core's build for the module's runtime and Scala version, and the decoder in Mill's JVM on core's Scala 3 build (`ARCHITECTURE.md` § Modules § Mill plugin), so within one release they are separate components whose only interface is the format. Testing each side against expected text in that format covers every pairing of those builds, and lets the decoder's tests feed it what a dying run leaves.

**Costs** The format must be written down, and every change to it updates the expected text of both sides' tests.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## The run's own state travels in the test event log as run events among the ScalaTest events.

**Alternatives** Folding the run events into the `runStarted` and `runEnded` events needs a new kind of consolidator event for `RunStarting`, which, where a runner sends it, follows the `runStarted` event.

**Reasoning** The run events already reach the emitter by the hand-off for events sent outside every structure wrapper (entry "Events sent outside every structure wrapper reach the emitter…"), so the emitter writes them like any other ScalaTest event assigned to the run. The `runEnded` event needs no run event, so a run whose runner sends none still ends (`ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

**Costs** A run whose event stream holds its closing run event but no `runEnded` event does not finish, though that event says how it ended. This happens when the `runEnded` event is torn or the process dies between the two writes.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## Core's decoder yields each ScalaTest event as a member of a decoded event family, one class per `org.scalatest.events` class.

**Alternatives**

- A generic record of a kind and a field map makes every reader cast, and fails only at run time on a field the emitter stops sending.
- Real `org.scalatest.events` instances need default ordinals and formatters that read as real data, and a stand-in `Throwable`, which cannot pass `StringReporter`'s `StackDepth` and `PropertyCheckFailedException` tests.

**Reasoning** A class per event gives each reader the event log's fields by name, checked at compile time, so a field the emitter stops sending breaks the reader's build rather than a run.

**Costs** A second family of about 30 classes runs parallel to `org.scalatest.events`, and changes whenever the event log gains a field.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## Events sent outside every structure wrapper reach the emitter by an explicit hand-off at each send site.

**Alternatives**

- A tap in `DispatchReporter.apply`, assigning to the run each event no wrapper handed off, cannot exist on Scala.js and Native, which build no `DispatchReporter`; their send sites would need a hand-off anyway, giving the epic two routes to the emitter.
- A run-level root structure wrapper receives neither the slowpoke alert, which `DispatchReporter` raises behind every wrapper, nor the events the runner passes straight to `dispatch`, so those send sites need a hand-off anyway.

**Reasoning** A hand-off at each send site is one route to the emitter on every runtime, visible where each event is sent. The senders outside every wrapper in a run the test command starts are a known, short list in ScalaTest core, so the risk that a missed or future site loses its events is worth taking for that one route.

**Costs** A send site missed now, or added by an upstream merge, silently loses its events (`SPECIFICATION.md` R-010, R-097), and no acceptance test finds an unknown site.

**Settled by** [How does the emitter receive and tag events sent outside every structure wrapper?](https://github.com/jamesgorman2/scalatest/issues/66).

---

## Every artifact of this fork is published under Maven group `com.edc`, not upstream's `org.scalatest`.

**Alternatives**

- Keeping `org.scalatest` makes a fork release and upstream `org.scalatest:scalatest` 3.2.19 versions of one artifact, so in a build that also resolves upstream ScalaTest, conflict resolution silently keeps one and evicts the other.
- Renaming the artifacts under `org.scalatest` also separates the coordinates, but publishes under a group this fork does not own, which Maven Central refuses.

**Reasoning** A test module must test with this fork's release for the test command to run it (`SPECIFICATION.md` R-059). Under a group of its own, the fork's artifacts stay on a module's resolved classpath whatever else the module resolves, and their coordinates alone tell them from upstream's.

**Costs** A build that also resolves upstream ScalaTest gets both jars on one classpath, and each class of their shared `org.scalatest` packages loads from whichever jar comes first.

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

---

## The test manifest is compiled from Mill's test queue and claim files on the JVM, and no manifest file is written.

**Alternatives**

- Each run writing the classes it is given into its own test event log records nothing for a process that dies before its runner is constructed.
- A manifest file the test command writes before any test process starts, with an entry per fork group, repeats what the queue already holds and cannot say which worker of the group took a class.

**Reasoning** On the JVM the test command lays out each fork group's queue before it starts any worker, and each claim a worker makes is a file in the `processFolder` the test command started it in. Those files are on disk whatever the worker then does, so a class no run reported is tied to the worker that claimed it, and a class no worker claimed is still in its queue. On Scala.js and Native, Mill's test task gives a module one run in one process, so the test classes the test command holds are that process's.

**Costs** The manifest rests on Mill's `@internal` queue and claim layout; on Windows two workers' `claim.log` can record one class, so a class counts as reported if any run reported it. On Scala.js and Native the test command must learn the module's test classes itself, ahead of Mill's test task, which no prototype has tried.

**Settled by** The user, on 2026-11-11, under no sub-issue of the plan.

**See**

- `research/mill-test-worker-args.md` § 1.2, § 2.2, § 2.3.
- `research/mill-worker-channels.md` § 3.1, § 4.
- `research/mill-parallel-worker-capture.md` § 5, § 6.
- `research/emitter-plugin-spike.md` § 2.
- `research/node-native-file-channel.md` § 5.2.

---

## Every Native test binary starts through a launcher compiled with the module's toolchain, which writes the binary's stdout and stderr to files of its own.

**Alternatives**

- Leaving the binary on the stdio it inherits from Mill passes none of its output through Mill's JVM, so the output reaches the console or the Mill daemon's files, never the plugin.
- A script launcher fails wherever `CROSSCOMPILING_EMULATOR` names `qemu-user`, which cannot load a script.

**Reasoning** With a launcher in the binary's place, on Windows and Linux, nothing the binary wrote reached the console, whether written from Scala, through C `stdio`, by a child process sharing the binary's stdio, or before `abort()`, and the plugin read that output from the files, each stream in order. Mill's exit status, test results and adapter warnings matched Mill's own Native test task. A launcher compiled with the module's `nativeClang`, which starts `<emulator words> <binary> <port>` itself, did the same under `qemu-user`. Its header gives the PID and start time of a binary that dies before any run exists in it, which no test event log carries.

**Costs** The test command compiles a launcher for every Native module it runs. The compiled launcher has run only on Linux, under `--no-daemon` and for the host's architecture, never on Windows or macOS, and none has yet run with a header or with its paths compiled in.

**Settled by**

- [Does a wrapper executable deliver a Native run's stdout and stderr to the plugin and keep them off the console?](https://github.com/jamesgorman2/scalatest/issues/41).
- [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45).

**See**

- `research/native-stdio-wrapper.md` § 2, § 3, § 4, § 5.
- `research/js-native-channels.md` § 5.2.

---

## The plugin installs each launcher by replacing a task's value in its own evaluation of the module's test task: `jsEnvConfig`'s on Scala.js, `nativeLink`'s on Native.

**Alternatives**

- A plugin-built `TestAdapter` re-implements Mill's discovery and task loop, and puts adapter versions on the build's class path in place of the module's.
- Overriding `jsEnvConfig` or `testTask` in the module changes the module's definition, against `SPECIFICATION.md` R-092.

**Reasoning** Both are named tasks upstream of the tasks the plugin evaluates, so the plugin can put its own value in their place while the module's definition stays unchanged (R-092). Mill's own test body then runs, producing its results and JUnit XML. Tests ran under the launcher in 112 of 112 Scala.js module runs, and on Native the exit status and JUnit XML test cases matched Mill's own task.

**Costs** The swap relies on Mill's test task using each value only as `ARCHITECTURE.md` § External systems describes, so a Mill change there breaks it at run time, not compile time.

**Settled by**

- [Can the test command run a module's tests without the module mixing in a plugin trait?](https://github.com/jamesgorman2/scalatest/issues/45).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/js-stdout-launcher.md` § 2.
- `research/mill-test-without-module-trait.md` § 2.3, § 3.3, § 4, § 6.

---

## Every Scala.js VM starts through a launcher the plugin supplies, which writes the VM's stdout and stderr to files of its own.

**Alternatives**

- Reading the VM's stdout and stderr through Mill's pipes lost 64 417 bytes already handed to the operating system on Linux when the adapter's `destroyForcibly()` closed the pipe, and would have meant narrowing `SPECIFICATION.md` R-073.
- A Node `--require` preload that sends `process.stdout` and `process.stderr` to files misses Node's fatal-error report, which bypasses `process.stderr`.
- A `.cmd` or PowerShell launcher on Windows cannot hold the VM in a job object without compiled code, so killing it would orphan the VM, as killing the C launcher without a job object did.

**Reasoning** Node writes to a file synchronously, so under `NodeJs` and `JsDom`, on Windows and Linux, it queued nothing at any exit, no byte reached a launcher's file after the VM's exit, and every byte the VM had handed to the operating system was read. This held whether the VM exited, died or was killed, and whether the reader kept up or paused. The launcher's header gives every VM's PID, which `JsDom` window code cannot learn. On Windows and Linux it also gives the VM's start time, which matched `ProcessHandle`'s for 112 of 112 VMs.

**Costs** The plugin ships a Windows launcher binary, which Windows on ARM64 runs under emulation, and it adds about 12 ms to each VM's start-up on Windows. The launcher has not run on macOS.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/js-stdout-launcher.md` § 2.3, § 3, § 5, § 7.
- `research/jsdom-channel.md` § 4.

---

## Under `JsDom`, a run's test event log is framed lines the run writes with the window's `console.log`, read from the launcher's stdout file.

**Alternatives**

- jsdom's `WebSocket` lost the `runStarted` event whenever the VM died before the socket's `open`, which came 11–59 ms after the runner's construction (`SPECIFICATION.md` R-071), sent events only when window code yielded, up to 850 ms late, and on Windows lost unread bytes when the connection reset.
- A synchronous `XMLHttpRequest` costs a `spawnSync` child Node process per request, plus a CORS preflight.
- The test-interface `Logger` is owned by Mill's test task and usable only during `execute`.

**Reasoning** jsdom forwards the window's `console.log` synchronously to Node's stdout, which the launcher sends to a file, so each event leaves the VM as it is written. The `runStarted` event arrived for every run that died at its runner's construction, and no event was lost or corrupted. Lines forged with the prefix and a wrong nonce passed through as ordinary output, never taken as events, and no event appeared among the passed-through lines. Events reached the plugin with a median delay of 5–6 ms.

**Costs** The event log shares the VM's stdout with test output, so it holds only while every event is one whole framed line.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/jsdom-channel.md` § 2, § 3, § 4, § 7.
- `research/js-stdout-launcher.md` § 5, § 6, § 8, § 9.

---

## On the JVM, on Scala.js under `NodeJs` and on Native, a run's test event log is one file per run.

**Alternatives**

- A socket lost every byte not yet read on Windows when the JVM worker died (`research/emitter-plugin-spike.md` § 4), against `SPECIFICATION.md` R-073.
- A JVM worker's stdout shares one pipe with test output, Mill's own runner lines and the `done()` string, so a write to file descriptor 1 from native code or a child process can land inside one of the event log's events (`research/mill-worker-channels.md` § 3.3).
- Under `NodeJs`, framed lines in the launcher's stdout file, as under `JsDom`, share the stream with test output and hold only while every event is one whole framed line.
- On the JVM, a folder per module holding every run's file, each named by its run, leaves the test command unable to tie a file, or the lack of one, to the worker it started.

**Reasoning** Every byte a JVM worker, Node VM or native binary had written to its file before it halted, killed itself or was force-killed reached the plugin, on Windows and on Linux, whether or not the reader had fallen behind; Node also lost nothing when it exited because the test adapter closed its connection to the VM. Wherever a process outlived the plugin's first read, Mill's process read its file while it appended, with no sharing error on Windows and a median per-file maximum delay of 10–28 ms at a 10 ms poll; files of processes that ended sooner were read whole afterwards. A run given no task, or whose process died as its runner was constructed, left at least its first event. On the JVM the file has one fixed name in the worker's `processFolder`, which the test command chose and in which Mill starts at most one JVM, so the folder ties the file to its worker, and a folder without one is a worker in which no run existed.

**Costs** A file's end is not the end of the run's stream, so the plugin needs a separate end signal, and it sees new bytes only by reading each file again. On the JVM, live reading was tested only in a folder of the plugin's own, not in a `processFolder`, where Mill polls `claim.log` and `result.log` every 10 ms; each worker needs its own argument, so the arguments of a module's workers differ from each other by the event log path.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/jvm-file-channel.md` § 2, § 3, § 5.
- `research/node-native-file-channel.md` § 2, § 3, § 4, § 5.2, § 6, § 7.
- `research/mill-test-worker-args.md` § 2.3.
- `research/mill-worker-channels.md` § 3.1.

---

## The plugin learns that a run's stream has ended when the run's process exits.

**Alternatives**

- A lock the run holds beyond end of file, which the operating system releases on death, worked on the JVM, but needs the emitter to hold an exclusive lock for its process's life and the plugin to try it on every file at every poll.
- The return of `callTestRunnerSubprocess`, the `@experimental` Mill call by which the plugin starts each JVM worker, as the end of every JVM run ties the signal to Mill API where `ProcessHandle` is plain JDK, and leaves Scala.js and Native on `ProcessHandle` anyway.
- The end of a Scala.js or Native module's Mill test task comes before its process has exited, while lines are still to come, and marks a module, not a run.
- The exit of a Scala.js VM's launcher on Windows was signalled up to 16 ms before the VM's.
- The run's own last line ends only a run that finishes.

**Reasoning** `ProcessHandle.onExit` signalled the end of every JVM worker, Node VM and native binary on Windows and on Linux, under `NodeJs` and `JsDom`, after a normal exit, a halt or self-kill, and a forced kill alike. It needs only the process's PID and start time, not how the run ended; the launcher's header, written before the VM or binary runs, gives them on Scala.js and for a Native binary that dies before any run exists in it. In 24 Scala.js runs the reader was still behind at the exit, though no byte reached a file after it, so after the exit the plugin reads to the end of the file. On Scala.js and Native, Mill's test task gives each process one run (`ARCHITECTURE.md` § External systems), so its exit is that run's end.

**Costs** It relies on every run's process running on the same machine as Mill's process, and on the start time to tell the run's process from a later one that reuses its PID; a JVM worker that dies before the `runStarted` event gives no PID, so the plugin takes that worker's end from the return of `callTestRunnerSubprocess`, a second signal on the JVM. The Scala.js and Native adapters can host several runs in one process; no path in Mill was found that does so, but if one did, a run's stream would end only when that process exits, after every run it hosts.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/jvm-file-channel.md` § 4.
- `research/mill-runner-progress-without-trait.md` § 7.
- `research/node-native-file-channel.md` § 5.
- `research/js-stdout-launcher.md` § 7.

---

## The plugin runs a module's test discovery itself, without the runner argument, so Mill's discovery runner opens no test event log.

**Alternatives**

- Telling the discovery runner's file from a run's by comparing its PID with Mill's own fails with `javaHome` set, where discovery runs in a JVM-worker subprocess, not Mill's own process.
- Telling it by its working directory depends on `testSandboxWorkingDir`, which the module may turn off.
- Telling it by its missing `done` also matches a run whose process dies.
- Any such test still leaves the discovery runner's file, one per module per command; on Windows under the daemon, written through `FileOutputStream`, it stays open after the command and cannot be deleted.
- Mill's own JVM test task, with a logger compiled in package `mill`, keeps the runner's progress off the console, but passes discovery the runner argument.

**Reasoning** `SPECIFICATION.md` R-072 keeps the discovery runner out of the consolidated output. With the argument withheld from discovery, no discovery file appeared, while the workers received it and ran the same classes with the same results.

**Costs** On the JVM the plugin must drive Mill's test queue itself, since evaluating the module's own test task passes discovery the same arguments as the workers. That route needs reflection on Mill's protected discovery member, `@experimental` `TestModuleUtil`, `@internal` `internalWorker`, `TestResult` and `TestRunnerUtils.globFilter`, `mill.javalib.api.internal.ZincOp` and a copy of Mill's test-queue scheduling, any of which a Mill release can break at run time, not compile time.

**Settled by** [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).

**See**

- `research/jvm-file-channel.md` § 6.
- `research/mill-runner-progress-without-trait.md` § Short answer, § 3, § 5, § 7.
- `research/mill-test-worker-args.md` § 2.3.

---

## The Mill plugin is built by its own Mill build in this repo.

**Alternatives** An sbt subproject of the existing build keeps one build, but no known Mill 1.x plugin is built with sbt, and Mill's testkit, its `Task` and `Discover` macros and its plugin artifact naming are untried under sbt.

**Reasoning** A Mill build meets the Scala version and testkit needs in `ARCHITECTURE.md` § Modules § Mill plugin the way every known Mill 1.x plugin does.

**Costs** Two builds are kept in step, and a release must publish scalatest-core and the consolidation library before it builds the plugin. The plugin's build also compiles the Windows Scala.js launcher, with a C toolchain that targets Windows, to ship in the plugin jar.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library builds on scalatest-core and shares its formatting.

**Alternatives** A standalone library with its own event-stream model and renderer needs the plugin to translate every decoded event stream into that model, and its human-readable output can diverge from the ScalaTest 3.2.19 format `SPECIFICATION.md` R-106 requires.

**Reasoning** A test event log is read only by the release that wrote it (`decisions/event-log-contract.md` "A test event log is read only by the release of this fork that wrote it."), so one codec in core serves both the emitter and the plugin's event log reading with no version skew, and the event stream it decodes is already in core's form. Sharing core's formatting keeps the human-readable output in the format R-106 requires.

**Costs** Core's internals carry the library's needs, and the plugin loads scalatest-core for Scala 3 into Mill's JVM.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The Mill plugin turns the emitter on with a ScalaTest runner argument.

**Alternatives**

- A JVM system property works on the JVM only, and needs the module's `forkArgs`, which the plugin cannot change without a trait (`SPECIFICATION.md` R-092).
- An environment variable set by the plugin's task-context fork cannot be read by `JsDom` window code, which has no `process`.

**Reasoning** Framework arguments are the only route into `JsDom` window code (`research/js-environment-channels.md` § 2.2), and they reach the runner alike on every runtime.

**Costs** The argument is visible among the ScalaTest arguments each run receives, and Mill's own JVM test task passes it to Mill's discovery runner as well, so the plugin runs discovery itself (entry "The plugin runs a module's test discovery itself…").

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library, not the run, assembles each run's test structure, from each suite's filtered structure and events assigned to nodes.

**Alternatives** Each run assembling its own test structure still leaves the library to assemble the test structure of a run whose process dies, and puts more code on Scala.js and Native.

**Reasoning** A run's process can die at any point, so the library must build the test structure from whatever arrived (`SPECIFICATION.md` R-064, R-065). Structure sent as each suite starts (`decisions/run-that-does-not-finish.md` "A suite's scopes and tests come from the run, one suite at a time…"), after filtering, gives it every scope and test a constructed suite registered and none a filter excluded.

**Costs** The test event log carries more, and the library must keep the place of a node that a later structure drops.

**Settled by**

- [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## Structure is attached where each event is emitted, not reconstructed from the run's merged events.

**Alternatives**

- A tree of the whole run built before the run starts cannot be built under the test-interface, which gives a runner one task per top-level class and no run-wide view; a JVM worker under Mill claims its classes one at a time (`research/mill-test-worker-args.md` § 1.2).
- Inferring each node's parent from `Ordinal` prefixes, as IntelliJ's reporter does (`ARCHITECTURE.md` § External systems), fails under the `Framework`, where every task of a run shares one linear ordinal tracker.
- Handing each event to the emitter at its send site, with no wrapper, misses the events user code builds and sends itself, and cannot place a suite instance user code creates and runs, whose enclosing node only the reporters downstream of it know.

**Reasoning** Where core sends an event, it knows the suite instance and the scope or test the event is for, but not the node that instance runs under, which only the reporter chain the event travels gives. Both are lost where the events of different suites and tests merge. Core records the first beside the event as it is sent, and a structure wrapper on each suite, nested suite and test supplies the second and hands the event to the emitter before that merge. This keeps the structure without changing any event (R-002) and lets the consolidation library place each event by its node ID alone.

**Costs** Every path that builds `Args` for a suite or test must install a wrapper, and every place core sends an event must record what it is for. An event user code builds has no such record, and is placed by its own data.

**Settled by**

- [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## The test command runs every selected module's tests in one nested evaluation of its own with fail-fast off, rather than relying on Mill's `--keep-going`.

**Alternatives**

- Re-running the goals that fail-fast skipped, through public API only, starts their tests late and drops the console summary between rounds.
- Falling back to such rounds when reflection fails adds a code path that runs only after a Mill change has likely broken other internals too.
- Requiring `--keep-going` on the command line adds a flag to every run, and without it scheduling decides which modules report (`SPECIFICATION.md` R-056).
- Setting `--keep-going` in the build's `mill-opts` turns fail-fast off for every Mill command in the project.

**Reasoning** The nested evaluation is one `execute` call on a copy of `mill.exec.Execution`, made by reflection with `failFast` off, which holds `SPECIFICATION.md` R-056 without `--keep-going`. R-044, R-089 and R-092 already rest on Mill internals that a Mill change can break at run time (`decisions/capture-and-console.md` "`../SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime…" and "On the console, progress output is a one-line summary…"; `decisions/test-command.md` "A test module needs no change to its definition…"), and the JVM routes that met R-044 were observed only on the copy. Holding R-056 through the copy adds one more such dependency, not a new kind of risk.

**Costs** The route reaches a `private val` of Mill's evaluator by reflection and copies `mill.exec.Execution`, both outside Mill's compatibility policy, so a Mill release that changes either breaks it at run time, not compile time. The copy may briefly draw its own failure count on Mill's prompt before the test command ends.

**Settled by** [Does the test command hold R-056 through Mill internals, or does R-056 accept a later start for modules fail-fast skipped?](https://github.com/jamesgorman2/scalatest/issues/44).

**See**

- `research/mill-nested-keep-going.md` § 3, § 4.1, § 4.4, § 5.
- `research/mill-runner-progress-without-trait.md` § 1, § 9.
- `research/nested-prompt-console.md` § 4.

---

## The consolidation library takes each run's event stream, not its test event log.

**Alternatives** Reading test event logs, live or as recorded files, ties the library to the transport, and recording an event log outside Mill needs a supported way to turn the emitter on there: new public surface on `Runner` and the `Framework`, bound by `SPECIFICATION.md` R-001 and R-004.

**Reasoning** Separating decoding from consolidation lets any front end in this fork, live under Mill or reading files, feed the same library, so the library never knows whether an event log was live or recorded.

**Costs** Every front end must turn event logs into event streams. This epic gives no supported way outside Mill to produce an event log.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---
