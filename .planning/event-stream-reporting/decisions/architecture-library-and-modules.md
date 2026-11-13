# Architecture decisions: the consolidation library and the modules

Decisions behind `../ARCHITECTURE.md` on what the consolidation library takes, builds on and decides, and how the fork's artifacts are built and published, newest first.

## The fork's sbt build compiles with Scala 3.3.8 on every runtime.

**Alternatives**

- Scala 3.9.0, the version the fork's consumer builds with, has an unknown cost in the macros of Scalactic and ScalaTest, and raises the JDK the artifacts need.
- The compilers upstream 3.2.20 builds with, Scala 3.1.3 on the JVM and Scala.js and 3.3.3 on Native, serve only modules on Scala 3.1 and 3.2, which `../SPECIFICATION.md` § Scope does not cover. They also put two compiler versions over one set of sources, holding the emitter and the codec to the older language level.

**Reasoning** Upstream's 3.3 line, whose sources are near-identical to these, builds with Scala 3.3.8, so these sources are known to compile on it. A module on a later Scala 3 version, such as 3.9.0, uses a library built with 3.3.8 unchanged.

**Costs** A module on Scala 3.1 or 3.2 (JVM or Scala.js), or on Scala.js before 1.20.2, cannot use the fork's artifacts, as it can upstream's. The emitter, the codec and the consolidation library can use no Scala syntax or library API later than 3.3. A problem MiMa reports against upstream's jars, built with older compilers, can come from the compiler and not from the fork's sources.

**Settled by** [What binary compatibility does the fork promise, given its base is upstream 3.3.0-alpha.2?](https://github.com/jamesgorman2/scalatest/issues/107).

---

## The library gives the consolidation state as a synchronous read of four states, available from **Start**.

**Alternatives**

- **End** returning the result alone leaves `--halt-on-failure` (`../SPECIFICATION.md` R-057) with no way to learn of a failure while runs are still going.
- A Boolean reads as passed before **End**, where false means only that nothing has failed yet.
- A value that resolves once, on the first failure or at **End**, has three states and cannot tell failed and still open from failed and ended; as a future, it also needs a dependency or callbacks threaded through the library, which has neither.

**Reasoning** Whether the consolidation has ended and whether anything has failed are independent facts, so four states carry both. One read then serves the test command's result after **End** and `--halt-on-failure`'s check while runs are still going. A plain read asks nothing of the library but the state it already keeps.

**Costs** A caller can only poll: `--halt-on-failure` learns of a failure when it next reads the state, before it starts a module's tests, not when the failure happens.

**Settled by** [How does the test command learn whether a run reported a failure, and whether its stream ended without a runEnded event?](https://github.com/jamesgorman2/scalatest/issues/103).

---

## The consolidation library, not the Mill plugin, decides whether the test command has failed.

**Alternatives** Having the **Decode** handle expose each event's facts, so that the plugin derives the result, repeats in the plugin the rules by which the library decides how each run ended (`../SPECIFICATION.md` R-082), and widens what the plugin sees of core's events.

**Reasoning** For its output the library already decides what `../SPECIFICATION.md` R-046 turns on: which events report a failure, how each run ended and which did not finish. It is also told of each process in which no run existed and each module whose tests did not start. Deciding R-046 there keeps one reading of those rules, so the test command's result cannot disagree with the output, and the plugin derives no result from any event.

**Costs** The library does more than write output, and the test command's result rests on library state the plugin cannot inspect.

**Settled by** [How does the test command learn whether a run reported a failure, and whether its stream ended without a runEnded event?](https://github.com/jamesgorman2/scalatest/issues/103).

---

## Every artifact of this fork is published under Maven group `com.edc`, not upstream's `org.scalatest`.

**Alternatives**

- Keeping `org.scalatest` gives a fork release the same coordinates as upstream's release of that version, `org.scalatest:scalatest` 3.2.20, so a build cannot tell which it resolved. Against any other upstream version, conflict resolution silently keeps one and evicts the other.
- Renaming the artifacts under `org.scalatest` also separates the coordinates, but publishes under a group this fork does not own, which Maven Central refuses.

**Reasoning** A test module must test with this fork's release for the test command to run it (`../SPECIFICATION.md` R-059). Under a group of its own, the fork's artifacts stay on a module's resolved classpath whatever else the module resolves, and their coordinates alone tell them from upstream's.

**Costs** A build that also resolves upstream ScalaTest gets both jars on one classpath, and each class of their shared `org.scalatest` packages loads from whichever jar comes first.

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

---

## The Mill plugin is built by its own Mill build in this repo.

**Alternatives** An sbt subproject of the existing build keeps one build, but no known Mill 1.x plugin is built with sbt, and Mill's testkit, its `Task` and `Discover` macros and its plugin artifact naming are untried under sbt.

**Reasoning** A Mill build meets the Scala version and testkit needs in `../ARCHITECTURE.md` § Modules § Mill plugin the way every known Mill 1.x plugin does.

**Costs** Two builds are kept in step, and a release must publish scalatest-core and the consolidation library before it builds the plugin. The plugin's build also compiles the Windows Scala.js launcher, with a C toolchain that targets Windows, to ship in the plugin jar.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library builds on scalatest-core and shares its formatting.

**Alternatives** A standalone library with its own event-stream model and renderer needs the plugin to translate every decoded event stream into that model, and its human-readable output can diverge from the ScalaTest 3.2.20 format `../SPECIFICATION.md` R-106 requires.

**Reasoning** A test event log is read only by the release that wrote it (`event-log-contract.md` "A test event log is read only by the release of this fork that wrote it."), so one codec in core serves both the emitter and the plugin's event log reading with no version skew, and the event stream it decodes is already in core's form. Sharing core's formatting keeps the human-readable output in the format R-106 requires.

**Costs** Core's internals carry the library's needs, and the plugin loads scalatest-core's JVM build into Mill's JVM.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library takes each run's event stream, not its test event log.

**Alternatives** Reading test event logs, live or as recorded files, ties the library to the transport, and recording an event log outside Mill needs a supported way to turn the emitter on there: new public surface on `Runner` and the `Framework`, bound by `../SPECIFICATION.md` R-001 and R-004.

**Reasoning** Separating decoding from consolidation lets any front end in this fork, live under Mill or reading files, feed the same library, so the library never knows whether an event log was live or recorded.

**Costs** Every front end must turn event logs into event streams. This epic gives no supported way outside Mill to produce an event log.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---
