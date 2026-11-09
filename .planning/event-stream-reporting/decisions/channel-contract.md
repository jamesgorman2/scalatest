# Decisions: the channel contract

Decisions behind `../SPECIFICATION.md` on what a channel carries, who may read it, which release ships the Mill plugin, and the consolidation library's API, newest first.

## Every release of the Mill plugin is a release of the fork.

**Alternatives** A separate plugin release, reading the channels of the fork release it extends, adds a second version line and ships the reader apart from its emitter.

**Reasoning** The emitter always ships with its channels' reader, under one version, so a test module needs only the fork release that holds the plugin (`../SPECIFICATION.md` R-059).

**Costs** Supporting each new Mill release moves every test module's ScalaTest dependency to a new fork release.

**Settled by** [Which Mill releases does the Mill plugin support, and what does the test command do on any other?](https://github.com/jamesgorman2/scalatest/issues/49).

---

## The consolidation library's API carries no compatibility promise across releases and is not for callers outside this fork.

**Alternatives**

- A public API with a compatibility promise, checked by MiMa as `../SPECIFICATION.md` R-004 checks the fork, serves callers the goal does not name.
- A documented API marked experimental invites outside callers but gives them no more to rely on.

**Reasoning** sbt and command-line front ends are out of scope and this epic gives no supported way to produce a channel outside Mill, so the only caller is this fork's own Mill plugin, shipped in the same release. Any later front end in this fork ships likewise. The epic then adds no public programmatic API; tools read only the JSON output, as in structured test reporting (`../../structured-test-reporting/SPECIFICATION.md` § Scope § Out of scope).

**Costs** A third party that builds on the library, such as another build tool's plugin, may break on any release of the fork.

**Settled by** [Is the consolidation library's event-stream input a public API, and under what compatibility promise?](https://github.com/jamesgorman2/scalatest/issues/46).

---

## A channel carries what the consolidated output shows or uses, not the whole event.

**Alternatives**

- Carrying the payload as its `toString` sends unbounded text that no output reads.
- Carrying the whole event, with the payload kept where serialisable, needs a rule for each runtime because Scala.js and Native have no general serialisation, and nothing reads the payload.

**Reasoning** The emitter then makes no judgement at runtime about what can be sent (`../SPECIFICATION.md` R-011, R-063). Because the channel's format is private, a later epic that needs the payload can add it without breaking a reader.

**Costs** A later output that needs more of an event, such as its payload or a custom exception's fields, needs a change to the emitter in ScalaTest itself, not only to the consolidation library.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## A channel is read only by the release of this fork that wrote it.

**Alternatives** A private format versioned across releases lets one plugin read test modules on several releases of the fork, but each reader must keep decoding every older format, which the goal does not need.

**Reasoning** The emitter and its reader ship in the same release, so the format can change in any release without any reader decoding more than one.

**Costs** A test module on another release of the fork fails the test command (`../SPECIFICATION.md` R-059).

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---

## The channel's format is private to this fork.

**Alternatives** A documented format that third parties may read is a second public contract beside the JSON output (`../SPECIFICATION.md` R-028), needing its own specification and compatibility policy, and the goal names no reader for it.

**Reasoning** Tools already have a documented account of every run in the JSON output, so the channel need serve only this fork's own reader.

**Costs** No consumer other than this fork's own can read a channel.

**Settled by** [What is the contract between the emitter and the consolidation library?](https://github.com/jamesgorman2/scalatest/issues/24).

---
