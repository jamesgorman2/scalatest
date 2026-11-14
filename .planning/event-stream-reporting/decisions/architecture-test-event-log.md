# Architecture decisions: the test event log

Decisions behind `../ARCHITECTURE.md` on the test event log's format and codec, what carries it on each runtime, how it is turned on and how its end is known, newest first.

## Under the runner argument, `Framework.done` returns an empty string in place of ScalaTest's end-of-run summary.

**Alternatives**

- The plugin dropping the summary lines from a worker's captured stdout needs text matching, which the feature rules out.
- Leaving the summary in the captured stdout puts it in the run's block, against `../SPECIFICATION.md` R-045 and AC-088.

**Reasoning** Mill's worker prints what `done()` returns to its stdout, and the test command captures that stdout into the run's block. Not returning the summary is the only way to keep it out of the block without text matching. Mill 1.3.1 prints nothing for an empty result, so `""` adds no blank line. A run without the runner argument returns 3.2.20's summary unchanged, so the legacy outputs keep it. When Scala.js and Native land, their `MasterRunner.done` returns `""` under the argument too.

**Costs** Each runtime's runner must repeat the change to `done` in `jvm/core/src/main/scala/org/scalatest/tools/Framework.scala`.

**Settled by** [Consolidated human-readable output for one JVM module - Process capture and the console](https://github.com/jamesgorman2/scalatest/issues/94).

---

## Each side of core's codec is tested on its own against the test event log's written format, not by round trip.

**Alternatives**

- Round-trip tests in one build pass even where the builds of core that write and read an event log disagree, and never feed the decoder a torn last line.
- End-to-end tests alone cannot produce a torn last line on demand, and a failure does not say which side drifted.

**Reasoning** The encoder runs in the run's process, on core's build for the module's runtime, and the decoder in Mill's JVM on core's JVM build (`../ARCHITECTURE.md` § Modules § Mill plugin), so within one release they are separate components whose only interface is the format. Testing each side against expected text in that format covers every pairing of those builds, and lets the decoder's tests feed it what a dying run leaves.

**Costs** The format must be written down, and every change to it updates the expected text of both sides' tests.

**Settled by** [What is the test event log's format: its framing, its encoding, and how it carries the run's own state, throwables and the events of item 3?](https://github.com/jamesgorman2/scalatest/issues/67).

---

## The run's own state travels in the test event log as run events among the ScalaTest events.

**Alternatives** Folding the run events into the `runStarted` and `runEnded` events needs a new kind of consolidator event for `RunStarting`, which, where a runner sends it, follows the `runStarted` event.

**Reasoning** The run events already reach the emitter by the hand-off for events sent outside every structure wrapper (`architecture-test-structure.md` "Events sent outside every structure wrapper reach the emitter…"), so the emitter writes them like any other ScalaTest event assigned to the run. The `runEnded` event needs no run event, so a run whose runner sends none still ends (`../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

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

## Under `JsDom`, a run's test event log is framed lines the run writes with the window's `console.log`, read from the launcher's stdout file.

**Alternatives**

- jsdom's `WebSocket` lost the `runStarted` event whenever the VM died before the socket's `open`, which came 11–59 ms after the runner's construction (`../SPECIFICATION.md` R-071), sent events only when window code yielded, up to 850 ms late, and on Windows lost unread bytes when the connection reset.
- A synchronous `XMLHttpRequest` costs a `spawnSync` child Node process per request, plus a CORS preflight.
- The test-interface `Logger` is owned by Mill's test task and usable only during `execute`.

**Reasoning** jsdom forwards the window's `console.log` synchronously to Node's stdout, which the launcher sends to a file, so each event leaves the VM as it is written. The `runStarted` event arrived for every run that died at its runner's construction, and no event was lost or corrupted. Lines forged with the prefix and a wrong nonce passed through as ordinary output, never taken as events, and no event appeared among the passed-through lines. Events reached the plugin with a median delay of 5–6 ms.

**Costs** The event log shares the VM's stdout with test output, so it holds only while every event is one whole framed line.

**Settled by** [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `../research/jsdom-channel.md` § 2, § 3, § 4, § 7.
- `../research/js-stdout-launcher.md` § 5, § 6, § 8, § 9.

---

## On the JVM, on Scala.js under `NodeJs` and on Native, a run's test event log is one file per run.

**Alternatives**

- A socket lost every byte not yet read on Windows when the JVM worker died (`../research/emitter-plugin-spike.md` § 4), against `../SPECIFICATION.md` R-073.
- A JVM worker's stdout shares one pipe with test output, Mill's own runner lines and the `done()` string, so a write to file descriptor 1 from native code or a child process can land inside one of the event log's events (`../research/mill-worker-channels.md` § 3.3).
- Under `NodeJs`, framed lines in the launcher's stdout file, as under `JsDom`, share the stream with test output and hold only while every event is one whole framed line.
- On the JVM, a folder per module holding every run's file, each named by its run, leaves the test command unable to tie a file, or the lack of one, to the worker it started.

**Reasoning** Every byte a JVM worker, Node VM or native binary had written to its file before it halted, killed itself or was force-killed reached the plugin, on Windows and on Linux, whether or not the reader had fallen behind; Node also lost nothing when it exited because the test adapter closed its connection to the VM. Wherever a process outlived the plugin's first read, Mill's process read its file while it appended, with no sharing error on Windows and a median per-file maximum delay of 10–28 ms at a 10 ms poll; files of processes that ended sooner were read whole afterwards. A run given no task, or whose process died as its runner was constructed, left at least its first event. On the JVM the file has one fixed name in the worker's `processFolder`, which the test command chose and in which Mill starts at most one JVM, so the folder ties the file to its worker, and a folder without one is a worker in which no run existed.

**Costs** A file's end is not the end of the run's stream, so the plugin needs a separate end signal, and it sees new bytes only by reading each file again. On the JVM, live reading was tested only in a folder of the plugin's own, not in a `processFolder`, where Mill polls `claim.log` and `result.log` every 10 ms; each worker needs its own argument, so the arguments of a module's workers differ from each other by the event log path.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `../research/jvm-file-channel.md` § 2, § 3, § 5.
- `../research/node-native-file-channel.md` § 2, § 3, § 4, § 5.2, § 6, § 7.
- `../research/mill-test-worker-args.md` § 2.3.
- `../research/mill-worker-channels.md` § 3.1.

---

## The plugin learns that a run's stream has ended when the run's process exits.

**Alternatives**

- A lock the run holds beyond end of file, which the operating system releases on death, worked on the JVM, but needs the emitter to hold an exclusive lock for its process's life and the plugin to try it on every file at every poll.
- The return of `callTestRunnerSubprocess`, the `@experimental` Mill call by which the plugin starts each JVM worker, as the end of every JVM run ties the signal to Mill API where `ProcessHandle` is plain JDK, and leaves Scala.js and Native on `ProcessHandle` anyway.
- The end of a Scala.js or Native module's Mill test task comes before its process has exited, while lines are still to come, and marks a module, not a run.
- The exit of a Scala.js VM's launcher on Windows was signalled up to 16 ms before the VM's.
- The run's own last line ends only a run that finishes.

**Reasoning** `ProcessHandle.onExit` signalled the end of every JVM worker, Node VM and native binary on Windows and on Linux, under `NodeJs` and `JsDom`, after a normal exit, a halt or self-kill, and a forced kill alike. It needs only the process's PID and start time, not how the run ended; the launcher's header, written before the VM or binary runs, gives them on Scala.js and for a Native binary that dies before any run exists in it. In 24 Scala.js runs the reader was still behind at the exit, though no byte reached a file after it, so after the exit the plugin reads to the end of the file. On Scala.js and Native, Mill's test task gives each process one run (`../ARCHITECTURE.md` § External systems), so its exit is that run's end.

**Costs** It relies on every run's process running on the same machine as Mill's process, and on the start time to tell the run's process from a later one that reuses its PID; a JVM worker that dies before the `runStarted` event gives no PID, so the plugin takes that worker's end from the return of `callTestRunnerSubprocess`, a second signal on the JVM. The Scala.js and Native adapters can host several runs in one process; no path in Mill was found that does so, but if one did, a run's stream would end only when that process exits, after every run it hosts.

**Settled by**

- [Does a file per run carry a JVM worker's event stream to the Mill plugin live, and keep every byte written when the worker dies?](https://github.com/jamesgorman2/scalatest/issues/50).
- [Does a file per run carry a Node or Native run's event stream to the Mill plugin live, and keep every byte written when its process dies?](https://github.com/jamesgorman2/scalatest/issues/51).
- [Which channel carries a Scala.js run's event stream in the `JsDom` JS environment?](https://github.com/jamesgorman2/scalatest/issues/52).

**See**

- `../research/jvm-file-channel.md` § 4.
- `../research/mill-runner-progress-without-trait.md` § 7.
- `../research/node-native-file-channel.md` § 5.
- `../research/js-stdout-launcher.md` § 7.

---

## The Mill plugin turns the emitter on with a ScalaTest runner argument.

**Alternatives**

- A JVM system property works on the JVM only, and needs the module's `forkArgs`, which the plugin cannot change without a trait (`../SPECIFICATION.md` R-092).
- An environment variable set by the plugin's task-context fork cannot be read by `JsDom` window code, which has no `process`.

**Reasoning** Framework arguments are the only route into `JsDom` window code (`../research/js-environment-channels.md` § 2.2), and they reach the runner alike on every runtime.

**Costs** The argument is visible among the ScalaTest arguments each run receives, and Mill's own JVM test task passes it to Mill's discovery runner as well, so the plugin runs discovery itself (`architecture-running-tests.md` "The plugin runs a module's test discovery itself…").

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---
