# Architecture decisions

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## On a soft death, a shutdown hook replaces a run's marker with the tree of every node it knows, with results where they exist.

**Alternatives** The marker alone loses every result the run reached whenever a test calls `System.exit` or the process receives SIGTERM under Mill or `Runner`.

**Reasoning** The hook recovers every result reached before a soft death, at no cost to a run that ends normally, because it is deregistered at the end.

**Costs** A hook runs inside a dying process, so it is best effort. On Scala.js and Native it exists only where the runtime offers an exit handler, and on Node it never fires on a signal (`ARCHITECTURE.md` § Modules, JSON writer).

**Settled by** [What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16).

---

## Every run with a `-fJ` reporter that can write its file leaves that file once it starts executing: a marker recording a failed process, written at its first execution and replaced at the end.

**Alternatives**

- Writing only at the end leaves no file when a Mill or Scala.js worker dies, when a main process hangs or is killed, or when a test calls `System.exit` under Mill or `Runner`, and an absent file cannot be told from a run that never started.
- Rewriting the file with results as the run goes costs quadratic I/O, and on Scala.js synchronous `fs` blocks the event loop.

**Reasoning** Only a file written before anything can die records that the run started. The JSON alone then shows a dead process, without the build's task result, and an absent file in a cleared location can only mean a run that never started. Because the marker carries structure only, it is rewritten only when a new top-level suite becomes known.

**Costs** A hard death (SIGKILL, OOM kill, crash) loses every result the run reached: its file shows only the top-level suites known.

**Settled by** [What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16).

**See** `research/mill-test-worker-args.md` § 1.2.

---

## A `RunAborted` may carry an explicit reason, *process failed* or *not run*, beside the `Event`.

**Alternatives** Inferring the cause from a 3.2.19 `RunAborted`'s fields ties the consolidator and every JSON reader to free-text messages written for people, and leaves a run that never started with no event to infer from.

**Reasoning** The reason tells a dead process apart from an abort with any other cause in the JSON, and lets the consolidator report an absent file in the same shape as a run's closing event. Held beside the `Event`, as the structure is, it leaves reporters built against the 3.2.x API receiving the plain `RunAborted` (`ARCHITECTURE.md` § The compatibility boundary).

**Costs** Every path that brings a `RunAborted` to the main process must carry or assign its reason, and reporters built against the 3.2.x API cannot see it.

**Settled by** [What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16).

---

## The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file: it passes a relative `-fJ` path, which resolves inside each worker's own sandbox, and fails the build when the sandbox is off.

**Alternatives**

- A shim framework: the plugin sets `testFramework` to its own class, which inside each worker rewrites `-fJ` to a name unique to that JVM and hands off to ScalaTest's `org.scalatest.tools.Framework`. It holds whatever the working directory, but it must be published as an artifact on every test module's classpath, and Metals and Bloop, which read `testFramework` through BSP, run through it too (`research/mill-test-worker-args.md` § 3, I-3).
- Collecting or renaming files after the run gathers files that may already have overwritten one another, so it prevents no collision. A `testTask` override that collects after `super.testTask` never reaches its collection when tests fail (`research/mill-test-worker-args.md` § 3, I-4).
- Giving each worker its own path through `testArgsDefault`, `forkArgs` or `forkEnv` cannot work: every worker of a command gets identical values, and Mill has no per-worker hook (`research/mill-test-worker-args.md` § 2.1, § 2.2).

**Reasoning** While `testSandboxWorkingDir` is on, Mill's default, each worker JVM of a command runs in its own `Task.dest[/<group>][/worker-<i>]/sandbox`, and Mill fails rather than start a second JVM in a folder already used in that command (`research/mill-test-worker-args.md` § 2.3, I-1). A relative path therefore gives one file per worker JVM, each a run. A user can still turn the sandbox off, through an override the plugin cannot defeat, so the plugin reads the setting and fails the build. A collision is never silent (`research/mill-test-worker-args.md` I-2).

**Costs** A Mill user who turns off `testSandboxWorkingDir` cannot use the plugin's command.

**Settled by** the user, without a sub-issue, in commit `df10bf6a4`.

**See** `research/mill-test-worker-args.md` § Short answer, § 1.4, § 2.1 to § 2.3, § 3, § 4 and § 5 (I-1 to I-5).

---

## On Scala.js and Native, the controller writes all of a run's console output; workers only forward envelopes.

**Alternatives** Each worker writing its own progress output through its task's loggers keeps sbt's log level on every line, but spreads one run's console output across processes, unlike the JVM's, and no worker holds the whole run for the progress summary.

**Reasoning** Every envelope of the run already reaches the controller (`ARCHITECTURE.md` § Modules, envelope forwarding), so only the controller holds the whole run that the human-readable block and progress output's summary of the whole invocation need (`SPECIFICATION.md` § Human-readable output, § Progress output). Writing the console output there adds no traffic and makes Scala.js and Native output match the JVM's as far as the platform allows.

**Costs** Under sbt, where workers run most tasks, worker progress output reaches the console only when the controller can write, at its next task or at `done()`, which `SPECIFICATION.md` § Progress output's best effort on JS/Native allows.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

**See** `research/js-native-structure-and-capture.md` § 2.2.

---

## On Scala.js and Native, the controller writes console output only through its tasks' loggers or the `done()` string, holding what arrives between tasks.

**Alternatives**

- Writing to the controller's stdout whenever it has no task's loggers loses output: on Native that stdout is the build tool's inherited file descriptor, which does not reach the console under the Mill daemon, and on Scala.js the adapter never waits for its pipe threads, so output near `done()` can be dropped.
- Holding that output and writing what is left to stdout at `done()` is late and still lossy in the same ways.

**Reasoning** Its tasks' loggers and the `done()` string are the controller's only channels to the console that do not drop output, so output held for them arrives late but is not lost.

**Costs** Output still held when the controller dies before `done()` is lost.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

**See** `research/js-native-structure-and-capture.md` § 2.1.

---

## Each consolidating caller gives every test run label fresh JSON files each time its command runs; neither ScalaTest nor the consolidator tells one use of the command from another.

**Alternatives**

- An ID for each use of the command, passed into every run, for example through `-D`, for the consolidator to filter on, puts a build-supplied identity back into the JSON, and every caller must mint and pass it.
- Filtering by run start time depends on clocks, which differ across machines and CI caches.

**Reasoning** Mill empties a task's `Task.dest`, and with it every worker sandbox inside it, before each run of the task, so the Mill plugin gets fresh files for each label, one per module, at no cost. The sbt recipe needs only one delete of each module's `-fJ` file before `test`. Each label's files then hold exactly the runs its caller just started, and a label with none appears with nothing under it (`ARCHITECTURE.md` § Multi-run consolidation executable).

**Costs** A hand-written sbt build that drops the delete consolidates stale runs silently.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on.

**Alternatives**

- A new shared artifact that scalatest-core depends on adds a transitive dependency for every ScalaTest user, needs its own 2.11, Scala.js, Native and Scala 3 projects, and makes its types a published contract, against `SPECIFICATION-DECISIONS.md` § The test structure gets no public API, not even an additive one.
- Shared sources compiled into both core and a core-independent consolidator fit no build pattern, since every Scala.js and Native copy passes through the source generators' line filter, and would ship the same classes in two artifacts.

**Reasoning** The human renderer runs wherever a run runs, so its code is on every run's classpath whatever the choice; internal to core is the only option that adds no runtime dependency and no public type. The consolidator's input is ScalaTest's JSON, so its dependency on core is a convenience, not a coupling third parties inherit.

**Costs** Internal core code becomes a contract between two artifacts, so the consolidator is released in lockstep with core. The consolidator carries core's dependencies: scalactic, scala-xml, and scala-reflect on Scala 2.

**Settled by** [Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3).

---

## Structure is attached where each event is emitted, not reconstructed after the merge.

**Alternatives**

- A tree of the whole execution built before the run cannot be built under sbt, which gives no run-wide view: one task per top-level class, with `RunStarting` before any task is known.
- Inferring parents from `Ordinal` prefixes, as IntelliJ's reporter does, fails under sbt, where every task shares one linear ordinal tracker.

**Reasoning** Events from one emitter arrive in a reliable order even under `-P` and `ParallelTestExecution`; structure is lost only where emitters' events merge. Tagging each event before the merge keeps the structure without changing any event.

**Costs** Every path that builds `Args` for a suite or test must install a wrapper.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---
