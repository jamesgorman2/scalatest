# Architecture decisions: event-stream test reporting for Mill

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## Every Native test binary starts through a launcher compiled with the module's toolchain, which writes the binary's stdout and stderr to files of its own.

**Alternatives**

- Leaving the binary on the stdio it inherits from Mill passes none of its output through Mill's JVM, so the output reaches the console or the Mill daemon's files, never the plugin.
- A script launcher fails under `qemu-user`, which cannot load a script, wherever `CROSSCOMPILING_EMULATOR` names it.

**Reasoning** With a launcher in the binary's place, nothing the binary wrote reached the console, on Windows and Linux, whether it was written from Scala, through C `stdio`, by a child process sharing the binary's stdio, or before `abort()`, and the plugin read it from the files, each stream in order. Mill's exit status, test results and adapter warnings matched Mill's own Native test task. A launcher compiled with the module's `nativeClang`, which starts `<emulator words> <binary> <port>` itself, did the same under `qemu-user`.

**Costs** The test command compiles a launcher for every Native module it runs. The compiled launcher has run only on Linux, under `--no-daemon` and for the host's architecture, never on Windows or macOS.

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

**Reasoning** Both are named tasks Mill evaluates for the plugin below the tasks the plugin evaluates itself, so the plugin can put its own value in their place while the module's definition stays unchanged (R-092). Mill's own test body then runs, with its results and JUnit XML: tests ran under the launcher in 112 of 112 Scala.js module runs, and on Native the exit status and JUnit XML test cases matched Mill's own task.

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

- Reading the VM's stdout and stderr through Mill's pipes lost 64 417 handed bytes on Linux when the adapter's `destroyForcibly()` closed the pipe, and would have meant narrowing `SPECIFICATION.md` R-073.
- A Node `--require` preload that sends `process.stdout` and `process.stderr` to files misses Node's fatal-error report, which bypasses `process.stderr`.
- A `.cmd` or PowerShell launcher on Windows cannot hold the VM in a job object without compiled code, so a kill of it would orphan the VM, as a kill of the C launcher without one did.

**Reasoning** Node writes to a file synchronously, so it queued nothing at any exit, no byte reached a launcher's file after the VM's exit, and every byte the VM had handed to the operating system was read, under `NodeJs` and `JsDom`, on Windows and Linux, whether the VM exited, died or was killed, and whether the reader kept up or paused. The launcher's header gives every VM's PID, which `JsDom` window code cannot learn, and on Windows and Linux its start time, which matched `ProcessHandle`'s for 112 of 112 VMs.

**Costs** The plugin ships a Windows launcher binary, which Windows on ARM64 runs under emulation, and Windows start-up rises about 12 ms per VM. The launcher has not run on macOS.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/js-stdout-launcher.md` § 2.3, § 3, § 5, § 7.
- `research/jsdom-channel.md` § 4.

---

## Under `JsDom`, a run's channel is framed lines the run writes with the window's `console.log`, read from the launcher's stdout file.

**Alternatives**

- jsdom's `WebSocket` lost item 1 whenever the VM died before `open`, 11–59 ms after the runner's construction (`SPECIFICATION.md` R-071), sent items only when window code yielded, up to 850 ms late, and on Windows lost unread bytes when the connection reset.
- A synchronous `XMLHttpRequest` costs a `spawnSync` child Node process per request, plus a CORS preflight.
- The test-interface `Logger` is owned by Mill's test task and usable only during `execute`.

**Reasoning** jsdom forwards the window's `console.log` synchronously to Node's stdout, which the launcher sends to a file, so each item leaves the VM as it is written. Item 1 arrived for every run that died at its runner's construction, and no item was lost, corrupted or found among the other lines, including lines forged with the prefix and a wrong nonce. Items reached the plugin with a median delay of 5–6 ms.

**Costs** The channel shares the VM's stdout with test output, so it holds only while every item is one whole framed line.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/jsdom-channel.md` § 2, § 3, § 4, § 7.
- `research/js-stdout-launcher.md` § 5, § 6, § 8, § 9.

---

## On the JVM, on Scala.js under `NodeJs` and on Native, a run's channel is one file per run.

**Alternatives**

- A socket lost every byte not yet read on Windows when the JVM worker died (`research/emitter-plugin-spike.md` § 4), against `SPECIFICATION.md` R-073.
- A JVM worker's stdout shares one pipe with test output, Mill's own runner lines and the `done()` string, so a write to file descriptor 1 from native code or a child process can land inside one of the channel's items (`research/mill-worker-channels.md` § 3.3).
- Under `NodeJs`, framed lines in the launcher's stdout file, as under `JsDom`, share the stream with test output and hold only while every item is one whole framed line.

**Reasoning** Every byte a JVM worker, Node VM or native binary had written to its file before it halted, killed itself or was force-killed reached the plugin, on Windows and on Linux, whether or not the reader had fallen behind; Node also lost nothing when it exited because the test adapter closed its connection to the VM. Wherever a process outlived the plugin's first read, Mill's process read its file while it appended, with no sharing error on Windows and a median per-file maximum delay of 10–28 ms at a 10 ms poll; files of processes that ended sooner were read whole afterwards. A run given no task, or whose process died as its runner was constructed, left at least its first item. Naming the folder through the runner argument puts every run of a module where the plugin looks, even though every Scala.js and Native run shares Mill's working directory.

**Costs** A file's end is not the end of the run's stream, so the plugin needs a separate end signal, and it sees new bytes only by reading each file again.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `research/jvm-file-channel.md` § 2, § 3, § 5.
- `research/node-native-file-channel.md` § 2, § 3, § 4, § 6, § 7.

---

## The plugin learns that a run's stream has ended when the run's process exits.

**Alternatives**

- A lock the run holds beyond end of file, which the operating system releases on death, worked on the JVM, but needs the emitter to hold an exclusive lock for its process's life and the plugin to try it on every file at every poll.
- The return of `callTestRunnerSubprocess`, the `@experimental` Mill call by which the plugin starts each JVM worker, ties the end signal to Mill API where `ProcessHandle` is plain JDK, and still needs each file matched to its worker.
- The end of a Scala.js or Native module's Mill test task comes before its process has exited, while lines are still to come, and marks a module, not a run.
- The exit of a Scala.js VM's launcher on Windows was signalled up to 16 ms before the VM's.
- The run's own last line ends only a run that finishes.

**Reasoning** `ProcessHandle.onExit` signalled the end of every JVM worker, Node VM and native binary on Windows and on Linux, under `NodeJs` and `JsDom`, after a normal exit, a halt or self-kill, and a forced kill alike. It needs only the process's PID and start time, not how the run ended; on Scala.js they come from the launcher's header, written before the VM runs. The reader was still behind at the exit in 24 Scala.js runs, though no byte reached a file after it, so the plugin then reads to the end of the file. On Scala.js and Native, Mill's test task gives each process one run (`ARCHITECTURE.md` § External systems), so its exit is that run's end.

**Costs** It relies on every run's process running on the same machine as Mill's process, and on the start time to tell the run's process from a later one that reuses its PID. The Scala.js and Native adapters can host several runs in one process; no path in Mill was found that does so, but if one did, a run's stream would end only when that process exits, after every run it hosts.

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

**Costs** Two builds are kept in step, and a release must publish scalatest-core and the consolidation library before it builds the plugin. The plugin's build also compiles the Windows Scala.js launcher, with a C toolchain that targets Windows, to ship in the plugin jar.

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
- An environment variable set by the plugin's task-context fork cannot be read by `JsDom` window code, which has no `process`.

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
