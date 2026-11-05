# Technical design decisions

Decisions behind [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md), newest first.

## A suite's file comes from the `Position`s of its own registrations.

**Alternatives** An implicit `Position` on each style class's constructor breaks binary compatibility with suites compiled against 3.2.19 (`../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x), makes overloads ambiguous, and gives the constructorless `…Like` traits nothing.

**Reasoning** Every registration in an `Engine`-built suite already captures a scalactic `Position`, so the suite's file is known wherever its registrations share one, at no cost to any compiled suite.

**Costs** A suite with no registrations, with registrations across several files, or not built on `Engine` has no file. No registration marks where the suite itself is declared, so no suite has a line and none can be opened at its declaration from the JSON.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---
