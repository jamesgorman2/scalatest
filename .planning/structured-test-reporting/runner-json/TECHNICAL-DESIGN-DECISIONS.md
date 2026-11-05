# Technical design decisions

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## A suite's file comes from the `Position`s of its own registrations, and a suite has no line.

**Alternatives** An implicit `Position` on each style class's constructor is binary-incompatible with suites compiled against 3.2.19 (`../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x), makes constructor overloads ambiguous, and gives the `…Like` traits, which have no constructor, nothing.

**Reasoning** Every registration in an `Engine`-built suite already captures a scalactic `Position`, so the suite's file is known wherever its registrations share one, at no cost to any compiled suite. No registration marks where the suite itself is declared, so no line can be given honestly.

**Costs** A suite with no registrations, with registrations across several files, or not built on `Engine` has no file, and no suite can be opened at its declaration from the JSON.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## A node's `name` is its text as written in the source and its `label` the formatter's text; no joined test name is stored.

**Alternatives**

- Storing the event's text, as `ScopeOpened` and `testText` carry it, keeps the verb-prefixed, lossy naming the fork exists to replace (`../SPECIFICATION.md` § Summary).
- Storing ScalaTest's joined test name beside them repeats what a reader rebuilds from the names and labels of the test's scopes and the test.

**Reasoning** A tool needs the text the user wrote, to match a node to its source, and the text ScalaTest prints, to render a node as 3.2.19 would; together they reconstruct any joined name, so neither is derived from the other and nothing is stored twice.

**Costs** No 3.2.19 event carries the source text, so `Engine` must supply it through the envelope; a suite not built on `Engine` falls back to the event's text, so `name` means slightly different things by suite kind; and a reader wanting ScalaTest's test name must rebuild it.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## A `StackDepthException` is recorded with its failed-code location and no stack trace.

**Alternatives** Recording every frame of every throwable writes, for each failed or canceled test, ScalaTest's assertion machinery and the test body around the one line the exception's location already names.

**Reasoning** A `StackDepthException` exists to name the line of the failing code, which ScalaTest's own output reports in place of its trace; any other throwable has no such line, so its frames are the only record of where it came from, and its causes keep theirs. `../SPECIFICATION.md` § Constraints requires class, message, cause chain, suppressed exceptions and location to be preserved exactly, not frames.

**Costs** A reader cannot see how the test reached a failing assertion made inside a helper method, and the consolidator cannot print a `StackDepthException`'s trace.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---

## The object holds each node's final state, not the events attached to it.

**Alternatives** Writing each node's events with every field, as 3.2.19's JSON lines do, repeats each event's suite name, suite ID and suite class name, and carries ordinals, formatters and rerunners, which only restate the order the tree already gives or have no meaning outside the run that wrote them.

**Reasoning** Every reader of the format, the consolidator and third-party tools, needs a node's state and its place, and the tree gives the place; one record per node, with one key per value, is what the documentation of `SPECIFICATION.md` R-010 can describe and keep stable under semantic versioning.

**Costs** The object no longer holds every field of every event, and the consolidator can only approximate `Event`s from it, with no ordinals, formatters or rerunners (`../ROADMAP.md` § 9).

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---
