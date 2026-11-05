# What a lossless emitter and Mill-side consolidation have to work with

What ScalaTest's events carry and lose leaving a process, what already ships events out of process, where a new emitter can attach, what capturing test output needs, and what is known about a channel from a Mill test-worker JVM to a Mill plugin ([Event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/20)). Facts only; no design is chosen.

Two earlier notes hold facts this one relies on and does not repeat:

- `.planning/structured-test-reporting/research/mill-test-worker-args.md`, cited as `MILL`: how Mill launches test-worker JVMs.
- `.planning/structured-test-reporting/research/js-native-structure-and-capture.md`, cited as `JSN`: runs, channels and capture on Scala.js and Scala Native.

## Short answer

- **Events are Java-serialisable but have no lossless language-neutral form.** `Event.toJson` drops the ordinal's stamps, the payload and the throwable's cause chain, and does not escape control characters (§ 1).
- **No existing transport is both lossless and cross-platform.** `-K` is the nearest and is JVM-only Java serialisation (§ 2).
- **`-C` is JVM-only and attaches late.** It works under `Runner` and the JVM `Framework`, takes no arguments, sits after sorting and filtering, and has no equivalent on Scala.js or Native (§ 3).
- **ScalaTest captures no test output today**, and a reporter cannot attribute output to a test (§ 4).
- **Events alone do not rebuild the tree or identify the run** (§ 5).
- **How a Mill test-worker's stdout and stderr are wired is not established**, so stdout as the channel is neither confirmed nor ruled out (§ 6, § 7).

## Sources

| Tag | Source | Version |
|---|---|---|
| `[this]` | this repo | `f3a9c910a` |
| `MILL` | `.planning/structured-test-reporting/research/mill-test-worker-args.md` | read from Mill tag `1.1.10` (`92d51b7`) |
| `JSN` | `.planning/structured-test-reporting/research/js-native-structure-and-capture.md` | read from the sources its § Sources lists |

`[this]` paths are relative to the repo root. Abbreviations:

- `EV/` is `jvm/core/src/main/scala/org/scalatest/events/`.
- `TOOLS/` is `jvm/core/src/main/scala/org/scalatest/tools/`.
- `JS/` is `js/core/src/main/scala/org/scalatest/tools/`.

No experiment was run for this note. §§ 1–5 are read from `[this]` source. Every `[this]` citation was opened and confirmed unless marked *unverified*, which means a research agent reported it and it was not re-opened. No Mill source was read: § 6 states only what follows from `MILL` and `JSN` for a channel, and § 7 lists what neither establishes.

## 1. The event model

- `Event` is `sealed abstract class Event extends Ordered[Event] with Product with Serializable` (`EV/Event.scala` line 34). Every event carries `ordinal`, `formatter`, `location`, `payload: Option[Any]`, `threadName` and `timeStamp` (lines 34–70).
- `Formatter`, `Location` and `Ordinal` are `Serializable` (`EV/Formatter.scala`, `EV/Location.scala`, `EV/Ordinal.scala`; *unverified*).

### 1.1 What `Event.toJson` loses

Every event has `private[scalatest] def toJson` (`EV/Event.scala` line 82), built from `EventJsonHelper` (lines 84–164).

- **Ordinal.** Written as `ordinal.runStamp` only, in every event type (for example line 297). The stamps that order events within a run are not written.
- **Payload.** Not written by any event type.
- **Throwable.** Written as class name, message, stack depth and stack frames (lines 137–143). The cause and suppressed chains are not written.
- **String escaping.** `string()` escapes only `\` and `"` (lines 93–94). A message holding a newline or another control character yields invalid JSON.
- **Config map.** Values are written with `toString` (line 1034).
- **Location.** An unmatched `Location` subtype yields an empty string, not a JSON value (lines 127–128).

### 1.2 What Java serialisation loses

- A payload that does not survive a serialisation round trip is dropped (`EV/Event.scala` lines 187–194).
- A throwable that does not is replaced by `NotSerializableWrapperException`, keeping message, class name and stack trace (lines 196–205).

## 2. Prior art for shipping events out of process

| Mechanism | What it does | Lossless | Runtimes |
|---|---|---|---|
| `-K`, `SocketReporter` | Writes each event to an `ObjectOutputStream` over TCP (`TOOLS/SocketReporter.scala` lines 22–40). | Yes, apart from § 1.2. The format is Java serialisation. | JVM |
| `-k`, `XmlSocketReporter` | One XML document per event (`TOOLS/XmlSocketReporter.scala`; *unverified*). | No. The ordinal is `runStamp` only (`EV/EventXmlHelper.scala` line 134 and each later event). No cause chain (*unverified*). | JVM |
| `-oJ` | Prints `toJson` per event through the sbt `Logger` (`JSN` § 2.1; the JVM `Framework` *unverified*). | No (§ 1.1). | JVM, JS, Native |
| `-M`, `MemoryReporter` | Writes mementos of failed and canceled tests for rerun (`TOOLS/MemoryReporter.scala`; *unverified*). | Not a transport. | JVM |
| sbt fork `remoteArgs` | The main JVM opens a server socket; the fork is given `-K host port` and its events are replayed into the main JVM's `dispatchReporter` (`TOOLS/Framework.scala` lines 820–925, 1072–1075). The fork's `RunStarting` and `RunCompleted` are dropped (lines 921–922). | As `-K`. | JVM |
| JS and Native `notifyServer` | Worker-to-controller messages (`JSN` § 2.2). ScalaTest sends only the event's class name (`JS/TaskRunner.scala` line 228). | No. | JS, Native |

- `SocketReporter.scala`, `XmlSocketReporter.scala`, `ReporterFactory.scala` and `DispatchReporter.scala` are not compiled for JS or Native. Each is in the `genScalaPackages` skip list that `copyDir` filters out of the generated sources (`project/GenScalaTestJS.scala`, `project/GenScalaTestNative.scala`).

## 3. Where a reporter attaches

### 3.1 The contract

- A reporter implements `Reporter.apply(event)`; `ResourcefulReporter` adds `dispose()` (`jvm/core/src/main/scala/org/scalatest/Reporter.scala`, `ResourcefulReporter.scala`; *unverified*).

### 3.2 `-C` on the JVM

- The class is loaded by name and built with `newInstance`, so it receives no arguments: no path, port or label (`TOOLS/ReporterFactory.scala` lines 27–30). Configuration can reach it only through system properties, the environment or the working directory.
- Given configuration characters, it is wrapped in a `FilterReporter` (lines 213–217), which drops whole events before the reporter sees them.
- It sits behind `DispatchReporter`, which has one queue and does not reorder; it tracks the highest ordinal seen (`jvm/core/src/main/scala/org/scalatest/DispatchReporter.scala` lines 62–65, 168–170).
- Ordering is applied upstream by `SuiteSortingReporter` and `TestSortingReporter` (`TOOLS/SuiteSortingReporter.scala`, `TOOLS/TestSortingReporter.scala`; *unverified*). A `-C` reporter therefore receives events after sorting, which can hold them until the sorting timeout.
- Under the JVM `Framework` with empty `remoteArgs`, every non-stdout, non-stderr reporter argument is built into the runner's reporters (`TOOLS/Framework.scala` lines 1060–1071). Mill passes empty `remoteArgs`, so each Mill test-worker JVM builds its own `-C` instance (`MILL` § 1.4).
- In an sbt fork, the forked JVM builds only `-K` and a stdout reporter (lines 1072–1075); a `-C` reporter runs in the main JVM.

### 3.3 Scala.js and Native

- `MasterRunner` reads its reporter configuration from a single `-o` argument. One other reporter argument is ignored, and more than one throws `IllegalArgumentException("Only one -o can be passed in as test argument.")` (`JS/MasterRunner.scala` lines 67–89). How `-C <class>` is split into `reporterArgs` before this check is *unverified*.
- `TaskRunner` builds its reporter chain directly: `SbtReporter` over `SbtLogInfoReporter` (`JS/TaskRunner.scala` lines 80–93, 141). There is no point at which an argument adds a reporter.
- The Native `MasterRunner` and `TaskRunner` were reported to match (*unverified*).

## 4. Test output capture

- The `jvm`, `js` and `native` main sources hold no call to `System.setOut`, `System.setErr`, `Console.withOut` or `Console.withErr`. ScalaTest captures nothing today.
- A reporter cannot attribute output. It runs on the dispatch thread after sorting, not on the thread running the test when the write happens.
- An event's `threadName` is not a test identity when threads come from a pool.
- Nothing in ScalaTest records which node a thread is running at the moment of a write.
- Scala.js and Native capture: `JSN` § 4.

## 5. What events alone do not carry

- **No hierarchy.** No event names a suite's parent. Nested suites are related only by `suiteId` strings, and scopes only by the order of `ScopeOpened` and `ScopeClosed`, which parallel runs interleave (`.planning/structured-test-reporting/ARCHITECTURE-DECISIONS.md` § Structure is attached where each event is emitted, not reconstructed after the merge).
- **No run identity.** Every `Framework` runner creates `new Tracker` (`TOOLS/Framework.scala` line 672), whose default first ordinal is `new Ordinal(0)` (`jvm/core/src/main/scala/org/scalatest/Tracker.scala` line 48). Every Mill test-worker JVM and sbt fork therefore issues the same ordinals. JS and Native: `JSN` § 1.4.
- **Recorded events arrive late.** Information and markup recorded during a test travel in the test-completion event's `recordedEvents` (`EV/Event.scala` line 358), not as they happen.
- **Filtering precedes reporters.** A `FilterReporter` in front of a reporter removes whole events (§ 3.2). `.planning/structured-test-reporting/runner-json/prototype-json-layout/06-filtered-fCJ.notes.md` shows `-fCJ` losing a test's outcome and duration.
- **The summary is outside the reporter chain under `Framework`.** `done()` builds the `Summary` from `SummaryCounter` and returns the summary and reminder text as its result string (`TOOLS/Framework.scala` lines 790–812). `RunCompleted` carries the same `Summary` to reporters (line 794); the reminder text reaches no reporter.
- **A run that never ends.** An emitter attached to Mill's discovery runner sees `RunStarting` and nothing after it (`MILL` § 1.4, I-5).

## 6. Mill: what the earlier notes mean for a channel

- **Telling workers apart.** Only a worker's working directory differs from another's, and only while `testSandboxWorkingDir` is on (`MILL` § 2.1–2.3).
- **Plugin code inside the worker.** The shim `testFramework` is the one place plugin code runs in the worker JVM before ScalaTest (`MILL` § 3, I-3). `MILL` examines it only for rewriting `-fJ`, not for capture or for opening a channel.
- **Plugin code after failing tests.** It runs only through a direct call to the `@experimental` `TestModuleUtil.runTests` (`MILL` § 2.2, I-4).
- **What is known about printing.** `MILL` § 1.4 and `JSN` § 2.1 cover sbt test-interface `Logger` lines and the `done()` string. Neither covers raw writes to the worker's stdout or stderr.
- **End of the channel.** The worker exits straight after `done()` (`MILL` § 1.3).
- **No worker JVM.** `testLocal` runs in Mill's own process (`MILL` § 1.1), as do Scala.js and Native runs through the adapter (`JSN` § 1.3). Their channels out: `JSN` § 2.1, § 2.3.

## 7. What is not known

- How Mill wires a test-worker JVM's stdout and stderr: inherited, piped or redirected to a file. `MILL` § 1.3 lists the spawn's arguments, environment and working directory, and not its streams.
- Whether `callTestRunnerSubprocess` exposes the worker's streams to its caller.
- What Mill does with worker output before a plugin could see it: prefixing, line buffering, or merging of stdout and stderr.
- Whether a shim `testFramework` can install output capture, or open a channel of its own, inside the worker before any test runs.
- Whether a live channel from a worker JVM to Mill's own process (socket, pipe or tailed file) works. No note evaluates one, and no experiment has been run.
- How `testLocal` routes test output.
- Whether the facts in § 6 hold on Mill versions other than `1.1.10`.
- Every item marked *unverified* above.
