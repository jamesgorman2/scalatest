# Architecture decisions: event-stream test reporting for Mill

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## Under `NodeJs`, the plugin, not the run, finds the start time of the run's process, from `ProcessHandle` when it first reads the run's first item.

**Alternatives**

- The run writing its own start time, as on the JVM and Native, has no Node API for it: on Windows it must run PowerShell, 178–756 ms per run and up to 1.8 s with 18 starting together, and on Linux read `/proc` assuming `CLK_TCK` is 100.
- The run writing `Date.now()` less `process.uptime()` as its start time never matched `ProcessHandle`'s.

**Reasoning** The start time serves only to tell the run's process from a later one that reuses its PID, and when the plugin first reads the run's first item the PID still names the run's process unless that process has already died and its PID been reused. On the JVM and Native the run writes its own start time, at no measurable cost, so only `NodeJs` differs.

**Costs** A process that dies and whose PID is reused before the plugin first reads the run's first item is mistaken for the run's, so the run's stream does not end until that other process exits. The run's first item carries a start time on the JVM and Native but not under `NodeJs`.

**Settled by** [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).

**See** `research/node-native-file-channel.md` § 5.1.

---

## On the JVM, on Scala.js under `NodeJs` and on Native, a run's channel is one file per run.

**Alternatives**

- A socket lost every byte not yet read on Windows when the JVM worker died (`research/emitter-plugin-spike.md` § 4), against `SPECIFICATION.md` R-073.
- A JVM worker's stdout shares one pipe with test output, Mill's own runner lines and the `done()` string, so a write to file descriptor 1 from native code or a child process can land inside one of the channel's items (`research/mill-worker-channels.md` § 3.3).

**Reasoning** Every byte a JVM worker, Node VM or native binary had written to its file before it halted, killed itself or was force-killed reached the plugin, on Windows and on Linux, whether or not the reader had fallen behind, and Node lost nothing when it exited on the test adapter closing its connection to the VM. Wherever a process outlived the plugin's first read, Mill's process read its file while it appended, with no sharing error on Windows and a median per-file maximum delay of 10–28 ms at a 10 ms poll; files of processes that ended sooner were read whole afterwards. A run given no task, or whose process died as its runner was constructed, left at least its first item. Naming the folder through the runner argument puts every run of a module where the plugin looks, though every Scala.js and Native run's working directory is Mill's own.

**Costs** A file's end is not the end of the run's stream, so the plugin needs a separate end signal, and it sees new bytes only by reading each file again.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).

**See**

- `research/jvm-file-channel.md` § 2, § 3, § 5.
- `research/node-native-file-channel.md` § 2, § 3, § 4, § 6, § 7.

---

## On the JVM, on Scala.js under `NodeJs` and on Native, the plugin learns that a run's stream has ended when the run's process exits.

**Alternatives**

- A lock the run holds beyond end of file, which the operating system releases on death, worked on the JVM, but needs the emitter to hold an exclusive lock for its process's life and the plugin to try it on every file at every poll.
- The return of `callTestRunnerSubprocess`, the `@experimental` Mill call by which the plugin starts each JVM worker, ties the end signal to Mill API where `ProcessHandle` is plain JDK, and still needs each file matched to its worker.
- The end of a Scala.js or Native module's Mill test task comes before its process has exited, while lines are still to come, and marks a module, not a run.
- The run's own last line ends only a run that finishes.

**Reasoning** `ProcessHandle.onExit` signalled the end of every JVM worker, Node VM and native binary on Windows and on Linux, after a normal exit, a halt or self-kill, and a forced kill alike. It needs only the process's PID and start time, not how the run ended. Where that PID was already gone when the plugin first read the run's first item, the process had ended. On Scala.js and Native, Mill's test task gives each process one run (`ARCHITECTURE.md` § External systems), so its exit is that run's end.

**Costs** It relies on every run's process running on the same machine as Mill's process, and on the start time to tell the run's process from a later one that reuses its PID. The Scala.js and Native adapters can host several runs in one process; no path in Mill does this, but if one did, a run's stream would end only when that process exits, after every run it hosts.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).

**See**

- `research/jvm-file-channel.md` § 4.
- `research/mill-runner-progress-without-trait.md` § 7.
- `research/node-native-file-channel.md` § 5.

---

## The plugin runs a module's test discovery itself, without the runner argument, so Mill's discovery runner opens no channel.

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
- `../structured-test-reporting/research/mill-test-worker-args.md` § 2.3.

---

## The Mill plugin is built by its own Mill build in this repo.

**Alternatives** An sbt subproject of the existing build keeps one build, but no known Mill 1.x plugin is built with sbt, and Mill's testkit, its `Task` and `Discover` macros and its plugin artifact naming are untried under sbt.

**Reasoning** A Mill build meets the Scala version and testkit needs in `ARCHITECTURE.md` § Modules § Mill plugin the way every known Mill 1.x plugin does.

**Costs** Two builds are kept in step, and a release must publish scalatest-core and the consolidation library before it builds the plugin.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library builds on scalatest-core, taking event streams in the form core's decoder yields them and sharing core's formatting.

**Alternatives** A standalone library with its own event-stream model and renderer needs the plugin to translate every decoded event stream into that model, and its human-readable output can diverge from what `SPECIFICATION.md` R-030 takes.

**Reasoning** A channel is read only by the release that wrote it (`decisions/channel-contract.md` "A channel is read only by the release of this fork that wrote it."), so one codec in core serves both the emitter and the plugin's channel reading with no version skew, and the event stream it decodes is already in core's form. Sharing core's formatting keeps the human-readable output what R-030 takes.

**Costs** Core's internals carry the library's needs, and the plugin loads scalatest-core for Scala 3 into Mill's JVM.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The Mill plugin turns the emitter on with a ScalaTest runner argument.

**Alternatives**

- A JVM system property works on the JVM only, and needs the module's `forkArgs`, which the plugin cannot change without a trait (`SPECIFICATION.md` R-092).
- An environment variable set by the plugin's task-context fork is unverified to reach the Node VM or native binary the test adapters launch.

**Reasoning** Framework arguments are the only route into `JsDom` window code (`research/js-environment-channels.md` § 2.2), and they reach the runner alike on every runtime.

**Costs** The argument is visible beside the user's framework arguments, and Mill's own JVM test task passes it to Mill's discovery runner as well, so the plugin runs discovery itself (entry "The plugin runs a module's test discovery itself…").

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library, not the run, assembles each run's test structure, from each suite's filtered structure and events tagged by node.

**Alternatives** Each run assembling its own test structure, as structured test reporting does, still leaves the library to assemble the test structure of a run whose process dies, and puts more code on Scala.js and Native.

**Reasoning** A run's process can die at any point, so the library must build the test structure from whatever arrived (`SPECIFICATION.md` R-064–R-066). Structure sent as each suite starts (`decisions/run-that-does-not-finish.md` "A run's expected tests come from the run, one suite at a time…"), after filtering, gives it every scope and test a constructed suite registered and none a filter excluded.

**Costs** The channel carries more, and this assembly does not reuse structured test reporting's in-run assembly.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

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

## The consolidation library takes each run's event stream, not its channel.

**Alternatives** Reading channels, live or as recorded files, ties the library to the transport, and recording a channel outside Mill needs a supported way to turn the emitter on there: new public surface on `Runner` and the `Framework`, bound by `SPECIFICATION.md` R-001 and R-004.

**Reasoning** Separating decoding from consolidation lets any front end in this fork, live under Mill or reading files, feed the same library, so the library never knows whether a channel was live or recorded.

**Costs** Every front end must turn channels into event streams. This epic gives no way outside Mill to produce a channel, so `SPECIFICATION.md` R-023 is exercised only by feeding event streams directly.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---
