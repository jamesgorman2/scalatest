# Decisions: event-stream test reporting for Mill

Decisions behind `SPECIFICATION.md`, newest first.

## The consolidated JSON holds each node's final state in the `runner-json` layout, not every event a run sent.

**Alternatives** Carrying every event in the JSON keeps payloads and the order of events across nodes for tools, but departs from the `runner-json` layout below the root, so the two epics' JSON would differ for a reason other than where reporting happens.

**Reasoning** The channel is lossless (`SPECIFICATION.md` R-011), so every formatter receives everything a run sent (R-021) and each decides what it shows. The JSON formatter shows what `../structured-test-reporting/runner-json/SPECIFICATION.md` § The JSON object already settled for one run.

**Costs** A tool that reads only the JSON cannot recover a payload or the order of events across nodes.

**Settled by** [What does this epic reuse from structured test reporting?](https://github.com/jamesgorman2/scalatest/issues/25).

---

## This epic takes the `structured-test-reporting` behaviour as written and states only its differences.

**Alternatives** Designing the test structure, the JSON and the human-readable and progress output afresh frees this epic from choices shaped by reporting inside one run, but the two epics' outcomes would then differ in ways their architectures do not force.

**Reasoning** The two epics are to be compared for ease of implementation, maintenance and appropriateness of outcome. Holding the reported behaviour common leaves one difference to compare: reporting inside each run, or in a plugin reading every run's channel.

**Costs** This specification depends on documents its plan may not edit, so a flaw found in them is carried here as a stated difference.

**Settled by** [What does this epic reuse from structured test reporting?](https://github.com/jamesgorman2/scalatest/issues/25).

---
