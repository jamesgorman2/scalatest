# Decisions: compatibility with ScalaTest 3.2.x

Decisions behind `../SPECIFICATION.md` on what a reporter must receive to count as receiving what 3.2.19 sends, newest first.

## A throwable's ScalaTest-internal stack frames are exempt from the comparison with 3.2.19 whole, not only their line numbers.

**Alternatives** Exempting only the line numbers pins the class and method of every internal frame, so any refactor of `Engine` or `Suite`, or a wrapper the emitter puts round a suite or test body, breaks compatibility.

**Reasoning** No reporter or IDE depends on ScalaTest's internal call chain, which changes between upstream patch releases. What a reader takes from a stack trace is the user's frames and the failure's position, and both are still compared.

**Costs** A failure that reaches user code by an unintended internal path, such as a body run twice, passes the comparison when its other frames are unchanged.

**Settled by** [Does "receives what 3.2.19 sends" cover the stack frames of ScalaTest's own classes?](https://github.com/jamesgorman2/scalatest/issues/105).

---
