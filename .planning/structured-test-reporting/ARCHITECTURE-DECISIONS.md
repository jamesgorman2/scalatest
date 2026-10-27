# Architecture decisions

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on.

**Alternatives**

- A new shared artifact that scalatest-core depends on adds a transitive dependency for every ScalaTest user, needs its own 2.11, Scala.js, Native and Scala 3 projects, and makes its types a published contract, against `SPECIFICATION-DECISIONS.md` § The test structure gets no public API, not even an additive one.
- Shared sources compiled into both core and a core-independent consolidator fit no build pattern, since every Scala.js and Native copy passes through the source generators' line filter, and would ship the same classes in two artifacts.

**Reasoning** The human renderer runs wherever a run runs, so its code is on every run's classpath whatever the choice; internal to core is the only option that adds no runtime dependency and no public type. The consolidator's binding contract stays the documented JSON (`SPECIFICATION.md` R-037), so its dependency on core is a convenience, not a coupling third parties inherit.

**Costs** Internal core code becomes a contract between two artifacts, so the consolidator is released in lockstep with core. The consolidator carries core's dependencies: scalactic, scala-xml, and scala-reflect on Scala 2.

**Settled by** [Which module owns the shared tree model and renderers?](https://github.com/jamesgorman2/scalatest/issues/3).

---

## Each JSON event points at its parent node only; consumers assemble the tree.

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
