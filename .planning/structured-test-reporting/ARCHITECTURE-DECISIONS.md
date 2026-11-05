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

## An aborted node may carry an explicit reason, *process failed* or *not run*, held in the structure, never in an `Event`.

**Alternatives** Inferring the cause from a 3.2.19 `RunAborted`'s fields ties the consolidator and every JSON reader to free-text messages written for people, and leaves a node that never started, or a run that never started, with no event to infer from.

**Reasoning** The reason tells a dead process apart from an abort with any other cause in the JSON, tells a node that never ran apart from one that started and failed, and lets the consolidator report an absent file in the same shape as a run's closing event. Held in the structure, it leaves reporters built against the 3.2.x API receiving the plain `RunAborted` or `SuiteAborted` (`ARCHITECTURE.md` § The compatibility boundary).

**Costs** Every path that brings a `RunAborted` to the main process must carry or assign its reason, and reporters built against the 3.2.x API cannot see it.

**Settled by**

- [What does a run leave on disk when it never reaches its end, and how does the consolidator classify it?](https://github.com/jamesgorman2/scalatest/issues/16).
- the user, in grilling on [Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18).

---

## The Mill plugin fails the build for a selected module whose `testSandboxWorkingDir` is off.

**Alternatives**

- Forcing the setting on cannot work: a user's override wins.
- Running with the sandbox off lets the module's worker JVMs overwrite one file silently.

**Reasoning** Without the sandbox, the plugin's relative `-fJ` path no longer gives each worker JVM its own file (`ARCHITECTURE.md` § External systems, Mill), and failing the build is the only way the plugin keeps a collision from being silent.

**Costs** A Mill user who turns off `testSandboxWorkingDir` cannot use the plugin's command.

**Settled by** the user, without a sub-issue, in commit `df10bf6a4`.

**See** `research/mill-test-worker-args.md` I-2.

---

## The Mill plugin, not ScalaTest, gives each test-worker JVM its own JSON file, by passing a relative `-fJ` path that resolves inside each worker's own sandbox.

**Alternatives**

- A shim `testFramework` that gives each worker a unique `-fJ` must ship on every test module's classpath, and Metals and Bloop run through it too.
- Collecting or renaming files after the run cannot undo files that have already overwritten one another.
- Per-worker values through `testArgsDefault`, `forkArgs` or `forkEnv` cannot exist: every worker gets identical values.

**Reasoning** While `testSandboxWorkingDir` is on, each worker JVM of a command runs in its own sandbox, and Mill refuses to start a second JVM in one already used (`ARCHITECTURE.md` § External systems, Mill), so a relative path gives one file per worker JVM, each a run.

**Costs** The plugin depends on Mill's sandbox layout under `Task.dest` to find each worker's file.

**Settled by** the user, without a sub-issue, in commit `df10bf6a4`.

**See** `research/mill-test-worker-args.md` § 2.1 to § 2.3, § 3, and § 5 I-1, I-3 and I-4.

---

## On Scala.js and Native, the controller writes all of a run's console output; workers only forward envelopes.

**Alternatives** Each worker writing its own progress output through its task's loggers keeps sbt's log level on every line, but spreads one run's console output across processes, unlike the JVM's, and no worker holds the whole run for the progress summary.

**Reasoning** Every envelope of the run already reaches the controller (`ARCHITECTURE.md` § Modules, envelope forwarding), so only the controller holds the whole run that the human-readable block and progress output's summary of the whole run need (`SPECIFICATION.md` § Human-readable output, § Progress output). Writing the console output there adds no traffic and makes Scala.js and Native output match the JVM's as far as the platform allows.

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

- An ID for each use of the command, passed into every run for the consolidator to filter on, puts a build-supplied identity back into the JSON.
- Filtering by run start time depends on clocks, which differ across machines and CI caches.

**Reasoning** Mill empties a task's `Task.dest`, and with it every worker sandbox inside it, before each run of the task, so the Mill plugin gets fresh files for each label, one per module, at no cost. The sbt recipe needs only one delete of each module's `-fJ` file before `test`. Each label's files then hold exactly the runs its caller just started, and a label with none appears with nothing under it (`ARCHITECTURE.md` § Multi-run consolidation executable).

**Costs** A hand-written sbt build that drops the delete consolidates stale runs silently.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on.

**Alternatives**

- A new shared artifact under scalatest-core adds a dependency for every ScalaTest user and makes its types a published contract.
- Shared sources compiled into both core and a core-independent consolidator fit no build pattern and ship the same classes in two artifacts.

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
