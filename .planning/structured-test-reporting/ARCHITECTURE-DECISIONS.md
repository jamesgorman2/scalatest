# Architecture decisions

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## Structure-aware reporters receive envelopes before the sorting reporters.

**Alternatives** Behind `SuiteSortingReporter`, `TestSortingReporter` and `DispatchReporter`, as `J` is today, a crash under `-P` loses the events held for sorting (`SPECIFICATION.md` R-035).

**Reasoning** Each emitter's events arrive in order, and parent links plus declared sibling positions let consumers rebuild structure and order without sorting.

**Costs** JSON lines are in arrival order, not `-o`'s order.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

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
