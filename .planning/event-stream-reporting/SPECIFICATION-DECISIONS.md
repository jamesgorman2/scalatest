# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, newest first.

## The consolidation produces no JUnit XML.

**Alternatives**

- Consolidated JUnit XML, as `../structured-test-reporting/SPECIFICATION.md` § Multi-run consolidation plugin writes it, keeps the two epics' outputs the same, but loses failures outside tests.
- CTRF (Common Test Report Format) is a more plausible standard for CI readers, but loses the same failures.

**Reasoning** No output drops what a run sent (`SPECIFICATION.md` R-021), and neither format can hold a failure outside a test.

**Costs** CI tools that read only JUnit XML get nothing from the consolidation. The two epics differ by one output when they are compared, an exception to reuse by default that consolidating many runs does not force.

**Settled by** [Is consolidated JUnit XML in scope?](https://github.com/jamesgorman2/scalatest/issues/34).

---

## This epic's test structure and outputs behave as structured test reporting's, departing only where consolidating many runs forces it.

**Alternatives** Designing the test structure and the JSON, human-readable and progress output afresh frees this epic from choices shaped by reporting inside one run, but the two epics' outcomes would then differ in ways their architectures do not force.

**Reasoning** The two epics are to be compared for ease of implementation, maintenance and appropriateness of outcome. Holding the reported behaviour common leaves one difference to compare: reporting inside each run, or in a plugin reading every run's channel. Of what is reused, only the JSON layout of `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object is designed in depth (`SPECIFICATION.md` R-028).

**Costs** A flaw found in the reused documents, which this plan may not edit, is carried here as a stated difference. Because each JSON node holds its final state, a tool that reads only the JSON cannot recover a payload or the order of events across nodes.

**Settled by** [What does this epic reuse from structured test reporting?](https://github.com/jamesgorman2/scalatest/issues/25).

---
