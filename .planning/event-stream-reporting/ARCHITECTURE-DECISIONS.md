# Architecture decisions: event-stream test reporting for Mill

Decisions behind [ARCHITECTURE.md](ARCHITECTURE.md), newest first.

## The Mill plugin is built by its own Mill build in this repo.

**Alternatives** An sbt subproject of the existing build keeps one build, but no known Mill 1.x plugin is built with sbt, and Mill's testkit, its `Task` and `Discover` macros and its plugin artifact naming are untried under sbt.

**Reasoning** A Mill build meets the Scala version and testkit needs in `ARCHITECTURE.md` § Modules § Mill plugin the way every known Mill 1.x plugin does.

**Costs** Two builds are kept in step, and a release must publish scalatest-core and the consolidation library before it builds the plugin.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library builds on scalatest-core, taking event streams in the form core's decoder yields them and sharing core's formatting.

**Alternatives** A standalone library with its own event-stream model and renderer needs the plugin to translate every decoded event stream into that model, and its human-readable output can diverge from what `SPECIFICATION.md` R-030 takes.

**Reasoning** A channel is read only by the release that wrote it (`decisions/channel-contract.md` "A channel is read only by the release of this fork that wrote it."), so one codec in core serves both the emitter and the plugin's channel reading with no version skew, and the event stream it decodes is already in core's form. Sharing core's formatting keeps the human-readable output what R-030 takes.

**Costs** Core's internals carry the library's needs, and the plugin loads scalatest-core for Scala 3 into Mill's JVM.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The Mill plugin turns the emitter on with a ScalaTest runner argument.

**Alternatives**

- A JVM system property works on the JVM only, and needs the module's `forkArgs`, which the plugin cannot change without a trait (`SPECIFICATION.md` R-092).
- An environment variable set by the plugin's task-context fork is unverified to reach the Node VM or native binary the test adapters launch.

**Reasoning** Framework arguments are the only route into `JsDom` window code (`research/js-environment-channels.md` § 2.2), and they reach the runner alike on every runtime.

**Costs** The argument is visible beside the user's framework arguments, and Mill's discovery runner receives it too.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The consolidation library, not the run, assembles each run's test structure, from each suite's filtered structure and events tagged by node.

**Alternatives** Each run assembling its own test structure, as structured test reporting does, still leaves the library assembling for a run whose process dies, and puts more code on Scala.js and Native.

**Reasoning** A run's process can die at any point, so the library must build the test structure from whatever arrived (`SPECIFICATION.md` R-064–R-066). Structure sent as each suite starts (`decisions/run-that-does-not-finish.md` "A run's expected tests come from the run, one suite at a time…"), after filtering, gives it every scope and test a constructed suite registered and none a filter excluded.

**Costs** The channel carries more, and this assembly does not reuse structured test reporting's in-run assembly.

**Settled by** [What are the epic's components, and how does data flow between them?](https://github.com/jamesgorman2/scalatest/issues/31).

---

## The test command runs every selected module's tests in one nested evaluation of its own with fail-fast off, rather than relying on Mill's `--keep-going`.

**Alternatives**

- Re-running, through public API only, the goals fail-fast skipped starts their tests late and drops the console summary between rounds.
- Falling back to such rounds when reflection fails adds a code path that runs only after a Mill change has likely broken other internals too.
- Requiring `--keep-going` on the command line adds a flag to every run, and without it scheduling decides which modules report (`SPECIFICATION.md` R-056).
- Setting `--keep-going` in the build's `mill-opts` turns fail-fast off for every Mill command in the project.

**Reasoning** The nested evaluation is one `execute` call on a copy of `mill.exec.Execution`, made by reflection with `failFast` off, which holds `SPECIFICATION.md` R-056 without `--keep-going`. R-044, R-089 and R-092 already rest on Mill internals that a Mill change can break at run time (`decisions/capture-and-console.md` "`../SPECIFICATION.md` R-040, R-042 and R-044 hold on every runtime…" and "On the console, progress output is a one-line summary…"; `decisions/test-command.md` "A test module needs no change to its definition…"), and the JVM routes that met R-044 were observed only on the copy. Holding R-056 through the copy adds one more such dependency, not a new kind of risk.

**Costs** The route reaches a `private val` of Mill's evaluator by reflection and copies `mill.exec.Execution`, both outside Mill's compatibility policy, so a Mill release that changes either breaks it at run time, not compile time. The copy may briefly draw its own failure count on Mill's prompt before the test command ends.

**Settled by** [Does the test command hold R-056 through Mill internals, or does R-056 accept a later start for modules fail-fast skipped?](https://github.com/jamesgorman2/scalatest/issues/44).

**See**

- `research/mill-nested-keep-going.md` § 3, § 4.1, § 4.4, § 5.
- `research/mill-runner-progress-without-trait.md` § 1, § 9.
- `research/nested-prompt-console.md` § 4.

---

## The consolidation library takes each run's event stream, not its channel.

**Alternatives** Reading channels, live or as recorded files, ties the library to the transport, and recording a channel outside Mill needs a supported way to turn the emitter on there: new public surface on `Runner` and the `Framework`, bound by `SPECIFICATION.md` R-001 and R-004.

**Reasoning** Separating decoding from consolidation lets any front end in this fork, live under Mill or reading files, feed the same library, so the library never knows whether a channel was live or recorded.

**Costs** Every front end must turn channels into event streams. This epic gives no way outside Mill to produce a channel, so `SPECIFICATION.md` R-023 is exercised only by feeding event streams directly.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---
