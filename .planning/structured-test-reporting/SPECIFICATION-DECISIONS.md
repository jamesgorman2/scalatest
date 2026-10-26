# Specification decisions

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The consolidator ships as an executable and a Mill plugin; sbt users get a recipe.

**Alternatives**

- An sbt plugin costs a second plugin to build and maintain, and the executable already covers CI.
- A Mill recipe, as for sbt, must hand-maintain the module set and each module's JSON location, which the plugin knows, and cannot consolidate after failed tests, because Mill skips every task downstream of a module whose tests fail; the plugin owns a command that can.

**Reasoning** The executable runs anywhere a JSON file exists, which covers CI. The Mill plugin derives the module set and each module's JSON location from the build. sbt users get the same result through the recipe (`SPECIFICATION.md` R-060), so an sbt plugin would add only its cost.

**Costs** sbt users wire the executable into their build themselves.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The consolidator writes a fixed set of formats, and the documented JSON is the only extension point.

**Alternatives** A renderer plug-in API would be a second public contract to keep stable beside the JSON format.

**Reasoning** Given `SPECIFICATION.md` R-037, a renderer API adds a contract the consolidator must keep and no capability a third party lacks.

**Costs** A format outside the set is built and maintained outside this fork.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## JSON output is opt-in.

**Alternatives** On by default, files would appear in every build, and something would have to own the output directory.

**Reasoning** The human-readable default already makes runs readable without it; the JSON serves tools and the consolidator, which are configured deliberately.

**Costs** Consolidation needs every run configured to write JSON first.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## Completion is the end of one run, not of the whole build.

**Alternatives** Waiting for every module would need a view of the build that ScalaTest does not have: it sees one `Runner` run, or one `Framework` runner up to `done()`.

**Reasoning** The consolidator already reads every run's JSON after the build, so a whole-build view belongs there.

**Costs** A multi-module build prints one results output per run; one view of the whole build needs the consolidator.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## Progress-then-results is the default for plain `-o`, `-e` and the `Framework`, not an opt-in.

**Alternatives** A new config character to opt in would bring readability only to those who ask, and the fork exists to make it normal.

**Reasoning** Under `SPECIFICATION.md` R-003, the tools that parse ScalaTest text, IntelliJ's sbt-shell mode among them, keep what they parse.

**Costs** Anyone parsing plain `-o` text sees a changed format.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The test structure gets no public API, not even an additive one.

**Alternatives**

- New `Event` subclasses are excluded by `SPECIFICATION.md` R-002, because precompiled matches on the sealed `Event` would break.
- A new reporter trait is rejected because it would be a second stable public contract beside the JSON format.

**Reasoning** The structure needs to reach only ScalaTest's own reporters and the JSON, and the documented JSON (`SPECIFICATION.md` R-037) gives third parties the structure without a public type the fork must keep stable.

**Costs** Third-party reporters cannot see the test structure in process; it reaches them only through the JSON.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---

## The fork stays binary compatible with ScalaTest 3.2.x.

**Alternatives**

- New fields on the public `Event` classes, even source compatible, break IntelliJ's forked-mode reporter and every other precompiled `-C` reporter.
- Diverging freely would make every upstream merge manual and close the path to upstreaming.

**Reasoning** Precompiled reporters link against the 3.2.x event classes, so any change to them fails at run time in tools this fork cannot rebuild.

**Costs** No existing public type can change shape, so the test structure cannot ride on `Event`.

**Settled by** [Plan: structured test reporting](https://github.com/jamesgorman2/scalatest/issues/1).

---
