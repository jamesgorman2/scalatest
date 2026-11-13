# Architecture decisions: test structure

Decisions behind `../ARCHITECTURE.md` on how each event's place in the test structure reaches the emitter, and where the test structure is assembled, newest first.

## The emitter holds the events of a suite instance the run did not know of until the suite it runs inside is known, rather than writing them against a node placed later.

**Alternatives** Writing each event at once against a provisional node that a later `structure` event places needs an unplaced-node form in the format. It also leaves assembly to hold the node's events and to show a node never placed.

**Reasoning** Every reporter of the run lies beyond the structure wrapper that shows where the instance runs, so a held event has reached none of them. The event log is therefore never behind the run's reporters. A process that dies loses only what `../SPECIFICATION.md` R-073 already excludes as buffered inside the process. Usually the event reaches that wrapper in the call that sent it, and is held only for that call.

**Costs** Where a `TestSortingReporter` of the instance sits before that wrapper, as for a helper suite mixing in `RandomTestOrder`, the instance's events reach the event log, and so live output, only as the sorter releases them. The events of an instance given a reporter that leads to none of the run's are not in the event log.

**Settled by** [Does the emitter hold an unregistered suite instance's events until its parent node is known?](https://github.com/jamesgorman2/scalatest/issues/72).

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

## Structure is attached where each event is emitted, not reconstructed from the run's merged events.

**Alternatives**

- A tree of the whole run built before the run starts cannot be built under the test-interface, which gives a runner one task per top-level class and no run-wide view; a JVM worker under Mill claims its classes one at a time (`../research/mill-test-worker-args.md` § 1.2).
- Inferring each node's parent from `Ordinal` prefixes, as IntelliJ's reporter does (`../ARCHITECTURE.md` § External systems), fails under the `Framework`, where every task of a run shares one linear ordinal tracker.
- Handing each event to the emitter at its send site, with no wrapper, misses the events user code builds and sends itself, and cannot place a suite instance user code creates and runs, whose enclosing node only the reporters downstream of it know.

**Reasoning** Where core sends an event, it knows the suite instance and the scope or test the event is for, but not the node that instance runs under, which only the reporter chain the event travels gives. Both are lost where the events of different suites and tests merge. Core records the first beside the event as it is sent, and a structure wrapper on each suite, nested suite and test supplies the second and hands the event to the emitter before that merge. This keeps the structure without changing any event (R-002) and lets the consolidation library place each event by its node ID alone.

**Costs** Every path that builds `Args` for a suite or test must install a wrapper, and every place core sends an event must record what it is for. An event user code builds has no such record, and is placed by its own data.

**Settled by**

- [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).
- [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---
