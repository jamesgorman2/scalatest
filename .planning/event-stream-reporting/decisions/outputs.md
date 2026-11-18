# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces, how its human-readable output is coloured, formatted, filtered and collapsed, how it shows a node that ran more than once, and what its JSON output holds, newest first.

## The JSON output is written for agents reading the test command that wrote it, with no format version and no compatibility promise (`../SPECIFICATION.md` R-084).

**Alternatives**

- Semantic versioning of the format, a patch release never changing it and a minor release only adding to it, binds every later release to additive change for third-party readers the goal does not name.
- A format version of its own beside the release version records nothing the release version does not, since only a release changes the format.

**Reasoning** The JSON output's readers are agents reading the run they just started, which read the output against the documentation of the release that wrote it. A change between releases therefore costs them nothing, and the root's release version tells them which release's layout applies (`../JSON-LAYOUT.md` § Compatibility).

**Costs** A tool that parses the JSON output across releases may break on any release.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## A node's messages and captured output are one ordered `output`, and its `contents` holds only its children.

**Alternatives**

- Placing each message among the node's children in `contents` needs a rule for where a message falls among children that ran concurrently or later, and splits the node's output between two keys.
- Keeping captured output as `{ "stdout", "stderr" }` loses the order between the two streams, and between captured output and messages, which `../SPECIFICATION.md` R-043 keeps.

**Reasoning** `../SPECIFICATION.md` R-097a makes messages output, and R-043 keeps all output of a node in order whatever its source. One array in the order the output occurred is that order, and leaves `contents` as the tree alone.

**Costs** A reader cannot tell from the JSON output which of a node's children had run when a message was emitted.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## A node's duration comes only from its events, and the test command times the root and each module with start and end events of its own (`../SPECIFICATION.md` R-103).

**Alternatives**

- Falling back to the sum of a node's children's durations overstates the time of a node whose children ran concurrently, such as a module with parallel runs, and gives the node a value no event carries.
- Leaving the root and modules untimed gives a reader no time for the test command or a module, though only the test command can observe them.

**Reasoning** A duration that no event recorded is a guess a reader cannot tell from a measurement. The test command alone sees when the consolidation and each module's tests start and end, so its own start and end events give the root and each module measured times. Those events are written to no test event log, so nothing a run records changes.

**Costs** A suite or test whose closing event carries no duration, such as an ignored test, has none.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## A suite, scope or test that was executed more than once appears as many times as it was executed, in the structure of its execution (`../SPECIFICATION.md` R-130e).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## The human-readable output shows each execution of a suite, scope or test with what that execution alone produced.

**Alternatives**

- Showing only the last execution hides a failure that a later running of the same test followed with a success (`../SPECIFICATION.md` R-130e).
- One line with a rolled-up outcome gives a node an outcome no event carries, which R-098a–R-098f leave to readers; it is deferred (`../ROADMAP.md` § Deferred).

**Reasoning** ScalaTest 3.2.20 prints every running of a test with its own outcome, failure and messages, so showing each execution keeps that output's content (R-130e).

**Costs** A test run many times prints as many lines.

**Settled by** [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## Where a subtree contains identical aborted messages, the human-readable output prints only the subtree's root (`../SPECIFICATION.md` R-106c).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Each `-o` and `-f` argument takes the formatting characters `w` and `d` for human-readable output, and the filtering characters `c`, `x`, `e`, `o`, `m` and `r` for human-readable and JSON output (`../SPECIFICATION.md` R-106f, R-106g, R-127a, R-127b, R-127c, R-127d, R-138, R-139).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## Human-readable output prints in colour unless the `w` formatting character is given (`../SPECIFICATION.md` R-106f).

**Alternatives**

**Reasoning**

**Costs**

**Settled by**

---

## The consolidation produces no JUnit XML.

**Alternatives**

- Consolidated JUnit XML gives CI tools that read only JUnit XML one report for the whole test command, but loses failures outside tests.
- CTRF (Common Test Report Format) is a more plausible standard for CI readers, but loses the same failures.

**Reasoning** Neither format can hold a failure outside a test, so either would report a run as cleaner than the human-readable and JSON output show it (`../SPECIFICATION.md` R-106d, R-028).

**Costs** CI tools that read only JUnit XML get nothing from the consolidation.

**Settled by** [Is consolidated JUnit XML in scope?](https://github.com/jamesgorman2/scalatest/issues/34).

---

## Each JSON node holds its final state, with nothing per event.

**Alternatives** Writing each node's events with every field, as ScalaTest 3.2.20's JSON lines do, repeats each event's suite name, suite ID and suite class name, and carries ordinals, formatters and rerunners, which only restate the order the tree already gives or have no meaning outside the run that wrote them.

**Reasoning** Every reader of the JSON output, an agent reading the test command that wrote it, needs a node's state and its place, and the tree gives the place. One record per node, with one key per value, gives that state without the reader replaying events, and keeps the output small enough for an agent to read whole.

**Costs** The JSON output does not hold every field of every event, and a tool that reads only the JSON cannot recover the order of events across nodes.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---
