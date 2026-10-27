# Architecture decisions

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## On Scala.js and Native, the controller writes all of a run's console output, only through its tasks' loggers or the `done()` string; workers only forward envelopes.

**Alternatives**

- Each worker writing its own progress output and `-oJ` through its task's loggers keeps sbt's log level on every line, but spreads one run's console output across processes, unlike the JVM's, and leaves worker `-oJ` without the run ID: the adapter gives every process identical arguments, and a JS worker cannot block on the asynchronous reply to a `notifyServer` message (`research/js-native-structure-and-capture.md` § 2.2).
- Writing to the controller's stdout whenever it has no task's loggers loses output: on Native that stdout is the build tool's inherited file descriptor, which does not reach the console under the Mill daemon and probably not under sbt server mode, and on Scala.js the adapter never waits for its pipe threads, so output near `done()` can be dropped.
- Holding that output and writing what is left to stdout at `done()` is late and still lossy in the same ways.

**Reasoning** Every envelope of the run already reaches the controller (`ARCHITECTURE.md` § Modules, envelope forwarding), so writing the console output there adds no traffic, makes Scala.js and Native output match the JVM's as far as the platform allows, and puts the controller's run ID on every `-oJ` object (`SPECIFICATION.md` R-033). Its tasks' loggers and the `done()` string are the controller's only channels to the console that do not drop output, so holding output for them keeps the run-level events (`RunStarting`, `RunCompleted`, …) without which `-oJ` cannot rebuild the run (`SPECIFICATION.md` R-032).

**Costs** Under sbt, where workers run most tasks, worker progress output may appear only at the controller's next task or at `done()` (`SPECIFICATION.md` R-020, R-022). Output still held when the controller dies before `done()` is lost; `-fJ` is unaffected.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

**See** `research/js-native-structure-and-capture.md` § 2.1 and § 2.2.

---

## Each consolidating caller gives every module a fresh JSON directory each time its command runs; neither ScalaTest nor the consolidator tells one use of the command from another.

**Alternatives**

- An ID for each use of the command, passed into every run, for example through `-D`, for the consolidator to filter on, puts a build-supplied identity back into the JSON, and every caller must mint and pass it.
- Filtering by run start time depends on clocks, which differ across machines and CI caches.

**Reasoning** Mill empties a task's `Task.dest` before each run of the task, so the Mill plugin gets a fresh directory per module for nothing, and the sbt recipe needs one delete per module before `test`. Each location then holds exactly the runs its caller just started, and one holding none is a module with no run (`SPECIFICATION.md` R-051, R-053, R-060).

**Costs** A hand-written sbt build that drops the delete consolidates stale runs silently.

**Settled by** [Who guarantees node-ID uniqueness and run identity?](https://github.com/jamesgorman2/scalatest/issues/2).

---

## A run's JSON file is written by the process that runs its `done()`, not by each process of the run.

**Alternatives**

- Each process writing its own file yields 1 + N files per run, N unknown until `done()`, all tied together by a run ID that a Scala.js worker has no way to learn from the controller (`research/js-native-structure-and-capture.md` § 2.2).
- Every process appending to one shared file has the same run ID problem, and needs single-write appends of whole lines from concurrent processes.

**Reasoning** The run's results output is rendered in the process that runs its `done()` (`SPECIFICATION-DECISIONS.md` § Under the `Framework`, a run's results output is one block per run, printed wherever the build tool prints the test framework's summary), so every envelope of the run already reaches that process; writing the JSON there adds no traffic, and that process mints the run ID every object of the run carries (`SPECIFICATION.md` R-033). Under sbt's forked mode that process is sbt's JVM, whose runner already receives the fork's events over its socket and already owns every file reporter.

**Costs** Complete JSON (`SPECIFICATION.md` R-035) depends on the adapter delivering each worker's messages before the controller's `done`, which the adapters' source implies but does not document (`research/js-native-structure-and-capture.md` § 2.2). Each worker envelope makes two hops with its payload wrapped twice.

**Settled by** [Where are a Framework run's JSON and results output produced?](https://github.com/jamesgorman2/scalatest/issues/6).

**See** `research/js-native-structure-and-capture.md` § 2.2, § 3.1 and § 5 item 2.

---

## Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on.

**Alternatives**

- A new shared artifact that scalatest-core depends on adds a transitive dependency for every ScalaTest user, needs its own 2.11, Scala.js, Native and Scala 3 projects, and makes its types a published contract, against `SPECIFICATION-DECISIONS.md` § The test structure gets no public API, not even an additive one.
- Shared sources compiled into both core and a core-independent consolidator fit no build pattern, since every Scala.js and Native copy passes through the source generators' line filter, and would ship the same classes in two artifacts.

**Reasoning** The human renderer runs wherever a run runs, so its code is on every run's classpath whatever the choice; internal to core is the only option that adds no runtime dependency and no public type. The consolidator's binding contract stays the documented JSON (`SPECIFICATION.md` R-037), so its dependency on core is a convenience, not a coupling third parties inherit.

**Costs** Internal core code becomes a contract between two artifacts, so the consolidator is released in lockstep with core. The consolidator carries core's dependencies: scalactic, scala-xml, and scala-reflect on Scala 2.

**Settled by** [Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3).

---

## Each JSON event names its node and that node's parent, not its full ancestry; consumers assemble the tree.

**Alternatives** Full ancestry on every event grows with depth and repeats every ancestor on every line.

**Reasoning** One parent link per event is enough for a reader of the whole run to rebuild the tree, and keeps each event the same size whatever the depth.

**Costs** A single event read alone does not say where it sits; every consumer holds the run's node-to-parent links.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## Structure is attached where each event is emitted, not reconstructed after the merge.

**Alternatives**

- A tree of the whole execution built before the run cannot be built under sbt, which gives no run-wide view: one task per top-level class, with `RunStarting` before any task is known.
- Inferring parents from `Ordinal` prefixes, as IntelliJ's reporter does, fails under sbt, where every task shares one linear ordinal tracker.

**Reasoning** Events from one emitter arrive in a reliable order even under `-P` and `ParallelTestExecution`; structure is lost only where emitters' events merge. Tagging each event before the merge keeps the structure without changing any event.

**Costs** Every path that builds `Args` for a suite or test must install a wrapper.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---
