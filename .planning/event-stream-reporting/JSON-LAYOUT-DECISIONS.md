# Decisions: JSON output layout

Decisions behind [JSON-LAYOUT.md](JSON-LAYOUT.md), newest first.

## A node's messages and captured output are one ordered `output`, and its `contents` holds only its children.

**Alternatives**

- Placing each message among the node's children in `contents` needs a rule for where a message falls among children that ran concurrently or later, and splits the node's output between two keys.
- Keeping captured output as `{ "stdout", "stderr" }` loses the order between the two streams, and between captured output and messages, which `SPECIFICATION.md` R-043 keeps.

**Reasoning** `SPECIFICATION.md` R-097a makes messages output, and R-043 keeps all output of a node in order whatever its source. One array in the order the output occurred keeps that order, and leaves `contents` as the tree alone.

**Costs** A reader cannot tell from the JSON output which of a node's children had run when a message was emitted.

**Settled by** [Plan: consolidated JSON output](https://github.com/jamesgorman2/scalatest/issues/140).

---

## Each JSON node holds its final state, with nothing per event.

**Alternatives** Writing each node's events with every field, as ScalaTest 3.2.20's JSON lines do, repeats each event's suite name, suite ID and suite class name, and carries ordinals, formatters and rerunners, which only restate the order the tree already gives or have no meaning outside the run that wrote them.

**Reasoning** Every reader of the JSON output is an agent that started the test command. Each such reader needs a node's state and its place, and the tree gives the place. One record per node, with one key per value, gives that state without the reader replaying events, and keeps the output small enough for an agent to read whole.

**Costs** The JSON output does not hold every field of every event, and a tool that reads only the JSON cannot recover the order of events across nodes.

**Settled by** [How is a run's test structure laid out as JSON?](https://github.com/jamesgorman2/scalatest/issues/19).

---
