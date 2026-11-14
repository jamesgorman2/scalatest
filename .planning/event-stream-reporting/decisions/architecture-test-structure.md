# Architecture decisions: test structure

Decisions behind `../ARCHITECTURE.md` on how each event's place in the test structure reaches the emitter, and where the test structure is assembled, newest first.

## A node's ID is its node path, which each suite instance builds from the invocation context it runs in and its own `suiteId`.

**Alternatives**

- An ID the emitter mints for each suite instance needs a process-wide map of instances, which every run in the JVM pays for, and holds the events of an instance placed late.
- An ID from the `suiteId` alone merges the helper suites of two tests into one node with two parents.

**Reasoning** The engine sets the invocation context, the node and execution the code on a thread runs in, around each top-level suite's run, a suite's run outside its tests, its test bodies, distributor tasks and tasks on its own async `ExecutionContext`, so an instance knows its parent as it starts. Its ID then follows from the parent's node path and its own place under it, and the `structure` holding a node is written before the node's first event.

**Costs** IDs grow with nesting, and a helper suite that two tests run is two nodes.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## An instance a suite runs outside its tests that has the suite's `suiteId` is that suite's node, not a child of it.

**Alternatives**

- A child node for each such instance leaves the tests a `@WrapWith` wrapper or a per-test trait's suite declares as nodes that never start.
- Naming the sites that run such instances misses a custom wrapper and any user code that does the same.

**Reasoning** Such an instance reports the suite's own name, suite ID and test names, so 3.2.20's reporters already see it as that suite. One rule at registration covers every site, with no class-specific code. Where the instance registers other tests than the suite declared, its further `structure` replaces them.

**Costs** A suite that runs another suite sharing its `suiteId` as a distinct suite outside its tests has that suite's runs merged into its own node.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## A suite instance run where no invocation context is set is not registered, and its events are assigned by their own data.

**Alternatives** Registering such an instance as its run starts needs a process-wide map of instances, which every run in the JVM consults, `../SPECIFICATION.md` R-001 runs included.

**Reasoning** No context is set only where user code runs an instance on a thread it started or on an `ExecutionContext` it supplied, which the engine cannot see into. Its events still reach the structure wrapper of the enclosing suite or test through `args.reporter`, which assigns them, as it assigns the events user code builds, at or below its own node.

**Costs** Such an instance's scopes are lost, so its tests sit directly under its suite. Two such instances with one `suiteId` under one node merge. An event of one that reaches no wrapper is lost.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## An event core sends for a registered instance is handed to the emitter where it is sent, not by the structure wrapper it reaches.

**Alternatives** Handing every event off at the innermost wrapper it reaches delays the event log while a sorter holds the event back, and keeps each event's origin until a wrapper hands it off.

**Reasoning** The send helper that records the event's origin already holds its node path and execution, so nothing the wrapper adds is needed. The `structure` holding the event's node is written before the event, so the event can be written at once.

**Costs** The event log may be ahead of the run's reporters: an event a user's reporter drops, or a sorter still holds when the process dies, is in the event log.

**Settled by** [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---

## Events sent outside every structure wrapper reach the emitter by an explicit hand-off at each send site.

**Alternatives**

- A tap in `DispatchReporter.apply`, assigning to the run each event no wrapper handed off, cannot exist on Scala.js and Native, which build no `DispatchReporter`; their send sites would need a hand-off anyway, giving the epic two routes to the emitter.
- A run-level root structure wrapper receives neither the slowpoke alert, which `DispatchReporter` raises behind every wrapper, nor the events the runner passes straight to `dispatch`, so those send sites need a hand-off anyway.

**Reasoning** A hand-off at each send site is one route to the emitter on every runtime, visible where each event is sent. The senders outside every wrapper in a run the test command starts are a known, short list in ScalaTest core, so the risk that a missed or future site loses its events is worth taking for that one route.

**Costs** A send site missed now, or added by an upstream merge, silently loses its events (`../SPECIFICATION.md` R-010, R-097), and no acceptance test finds an unknown site.

**Settled by** [How does the emitter receive and tag events sent outside every structure wrapper?](https://github.com/jamesgorman2/scalatest/issues/66).

---

## The consolidation library, not the run, assembles each run's test structure, from each suite's filtered structure and events assigned to nodes.

**Alternatives** Each run assembling its own test structure still leaves the library to assemble the test structure of a run whose process dies, and puts more code on Scala.js and Native.

**Reasoning** A run's process can die at any point, so the library must build the test structure from whatever arrived (`../SPECIFICATION.md` R-064, R-065). Structure sent as each suite starts (`run-that-does-not-finish.md` "A suite's scopes and tests come from the run, one suite at a time…"), after filtering, gives it every scope and test a constructed suite registered and none a filter excluded.

**Costs** The test event log carries more, and the library must keep the place of a node that a later structure drops.

**Settled by**

- [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## An event without an origin is assigned by the innermost structure wrapper it reaches, not reconstructed from the run's merged events.

**Alternatives**

- A tree of the whole run built before the run starts cannot be built under the test-interface, which gives a runner one task per top-level class and no run-wide view; a JVM worker under Mill claims its classes one at a time (`../research/mill-test-worker-args.md` § 1.2).
- Inferring each node's parent from `Ordinal` prefixes, as IntelliJ's reporter does (`../ARCHITECTURE.md` § External systems), fails under the `Framework`, where every task of a run shares one linear ordinal tracker.
- Handing only the events core sends to the emitter misses the events user code builds and sends itself, and those of an instance run where no invocation context is set.

**Reasoning** An event without an origin carries only the `suiteId` and test name it names, which are ambiguous across a run, and nothing names its node once the events of different suites and tests merge. A structure wrapper on each suite, nested suite and test receives it before that merge and hands it to the emitter with its own node, below which the event's data names its node. This keeps the structure without changing any event (R-002).

**Costs** Every path that builds `Args` for a suite or test must install a wrapper. An event without an origin is assigned by its own data.

**Settled by**

- [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).
- [How are a run's events written to the test event log apart from its test structure?](https://github.com/jamesgorman2/scalatest/issues/110).

---
