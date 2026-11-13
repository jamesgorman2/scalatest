# Decisions: compatibility with ScalaTest 3.2.x

Decisions behind `../SPECIFICATION.md` on which upstream line and release the fork follows and is compared with, which Scala versions it covers, and what a reporter must receive to count as receiving what 3.2.20 sends, newest first.

## The fork follows upstream's 3.2 line, not its 3.3 line.

**Alternatives**

- A fork on upstream's 3.3 line, checked against 3.3.0-alpha.2, promises nothing to a build on 3.2.x.
- A fork on the 3.3 line, checked against 3.2.x, needs a MiMa filter for each of upstream's own breaks, 1682 problems in `scalatest-app` alone.

**Reasoning** The fork's users are on 3.2.x. Libraries compiled against 3.2.x link against API the 3.3 line removes, such as `styleName`, `TestRegistration` and the `Args` constructor, so a fork on the 3.3 line cannot replace upstream on their classpath.

**Costs** The fork takes nothing upstream adds only to its 3.3 line, and cannot serve a build that moves to that line.

**Settled by** [What binary compatibility does the fork promise, given its base is upstream 3.3.0-alpha.2?](https://github.com/jamesgorman2/scalatest/issues/107).

---

## The fork is based on upstream 3.2.20 and is compared with that same release.

**Alternatives**

- A base and comparator of 3.2.19 leaves the fork one release behind upstream.
- A base of 3.2.20 compared with 3.2.19 shows upstream's own 3.2.20 changes as differences.

**Reasoning** With the base equal to the comparator, the fork's sources differ from the comparator's only where the fork changed them. No comparison then shows a change upstream made between releases. This holds for MiMa's (`../SPECIFICATION.md` R-004) and for what reporters and the build tool receive (R-001, R-002, R-051, R-003, R-106). 3.2.20 is the tip of `3.2.x-new`, the branch upstream releases its 3.2 line from.

**Costs** Nothing in the fork checks it against an earlier 3.2.x release: compatibility with one rests on upstream's own between its releases.

**Settled by** [What binary compatibility does the fork promise, given its base is upstream 3.3.0-alpha.2?](https://github.com/jamesgorman2/scalatest/issues/107).

---

## The fork builds, tests and publishes no Scala 2 artifacts.

**Alternatives** Building the Scala 2.11, 2.12 and 2.13 artifacts upstream publishes serves no user of the fork. It also holds the emitter, the codec and their tests to what compiles and passes on each of those versions, on every runtime.

**Reasoning** The fork's consumer builds with Scala 3.9.0, so it would use no Scala 2 artifact. The consolidation library and the Mill plugin run on Scala 3 alone whatever the fork builds.

**Costs** A Scala 2 module cannot test with the fork. The build's Scala 2 projects, left as upstream wrote them, are not kept compiling.

**Settled by** [What binary compatibility does the fork promise, given its base is upstream 3.3.0-alpha.2?](https://github.com/jamesgorman2/scalatest/issues/107).

---

## A throwable is compared with 3.2.20's without its stack frames.

**Alternatives**

- Exempting only the line numbers of ScalaTest's own frames freezes its internal call structure, so any refactor of `Engine` or `Suite`, or a wrapper the emitter puts round a suite or test body, breaks compatibility.
- Exempting ScalaTest's own frames whole, by package, still fails on forwarders the compiler adds to the user's class, library frames ScalaTest's code calls, traces a reporter cuts to a fixed number of frames, and the failed code's stack depth.

**Reasoning** The JVM fills a stack trace when the throwable is constructed, so the only frames the fork changes are those of its own call chain. A throwable replaced or wrapped on its way to a reporter still shows in its class, message or cause, and the failed code's file and line pin the position users and IDEs navigate to.

**Costs** A fork that called `setStackTrace` on a user's throwable, or computed a wrong stack depth that resolves to the same file and line, passes the comparison.

**Settled by** [Does "receives what 3.2.19 sends" cover the stack frames of ScalaTest's own classes?](https://github.com/jamesgorman2/scalatest/issues/105).

---
