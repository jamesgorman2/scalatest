# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces and what its JSON output holds, newest first.

## The consolidation produces no JUnit XML.

**Alternatives**

- Consolidated JUnit XML gives CI tools that read only JUnit XML one report for the whole test command, but loses failures outside tests.
- CTRF (Common Test Report Format) is a more plausible standard for CI readers, but loses the same failures.

**Reasoning** Neither format can hold a failure outside a test, so either would report a run as cleaner than the human-readable and JSON output show it (`../SPECIFICATION.md` R-021).

**Costs** CI tools that read only JUnit XML get nothing from the consolidation.

**Settled by** [Is consolidated JUnit XML in scope?](https://github.com/jamesgorman2/scalatest/issues/34).

---

## Each JSON node holds its final state, with nothing per event.

**Alternatives** Writing each node's events with every field, as ScalaTest 3.2.19's JSON lines do, repeats each event's suite name, suite ID and suite class name, and carries ordinals, formatters and rerunners, which only restate the order the tree already gives or have no meaning outside the run that wrote them.

**Reasoning** Every reader of the JSON output needs a node's state and its place, and the tree gives the place. One record per node, with one key per value, is what the format's documentation can describe and keep stable under semantic versioning (`../SPECIFICATION.md` R-108, R-084).

**Costs** The JSON output does not hold every field of every event, and a tool that reads only the JSON cannot recover the order of events across nodes.

---
