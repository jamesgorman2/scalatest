# Decisions: compatibility with ScalaTest 3.2.x

Decisions behind `../SPECIFICATION.md` on what a reporter must receive to count as receiving what 3.2.19 sends, newest first.

## A throwable is compared with 3.2.19's without its stack frames.

**Alternatives**

- Exempting only the line numbers of ScalaTest's own frames freezes its internal call structure, so any refactor of `Engine` or `Suite`, or a wrapper the emitter puts round a suite or test body, breaks compatibility.
- Exempting ScalaTest's own frames whole, by package, still fails on forwarders the compiler adds to the user's class, library frames ScalaTest's code calls, traces a reporter cuts to a fixed number of frames, and the failed code's stack depth.

**Reasoning** The JVM fills a stack trace when the throwable is constructed, so the only frames the fork changes are those of its own call chain. A throwable replaced or wrapped on its way to a reporter still shows in its class, message or cause, and the failed code's file and line pin the position users and IDEs navigate to.

**Costs** A fork that called `setStackTrace` on a user's throwable, or computed a wrong stack depth that resolves to the same file and line, passes the comparison.

**Settled by** [Does "receives what 3.2.19 sends" cover the stack frames of ScalaTest's own classes?](https://github.com/jamesgorman2/scalatest/issues/105).

---
