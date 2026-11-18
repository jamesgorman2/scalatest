# Decisions: JSON output layout

Decisions behind [JSON-LAYOUT.md](JSON-LAYOUT.md), newest first.

## Modules and top-level suites are ordered by name, compared as `String.compareTo` compares them, and those sharing a name by start order (`SPECIFICATION.md` R-105).

**Alternatives**

- Ordering top-level suites as their run started or discovered them changes between runs of the same build, since discovery order and parallel scheduling are not declared anywhere.
- Ordering modules as the module selectors gave them makes one build's output depend on how the test command was invoked, and leaves no order for a module two selectors share.
- Ordering by a locale's collation gives a different order on machines with different locales.

**Reasoning** R-105 orders the tree by declared order where it is known, otherwise by label, then by execution order where siblings share a label. Nothing declares the order of a build's modules or of a run's top-level suites, and neither has a label, so each sorts by its name, which stands where a label would: a module's Mill module path, a suite's suite name. `String.compareTo` gives the same order on every machine and in every reader. Top-level suites that share a name fall back to the order they started, as R-105 gives for siblings with identical labels, with those that never started last.

**Costs** The order differs from the order the human reading the build's source would list the modules or suites in, and from the order the suites ran.

**Settled by** [Consolidated JSON output - Suites, scopes and tests in the JSON output](https://github.com/jamesgorman2/scalatest/issues/143).

---

## The root's failures are a `failures` array of `{ "message" }` objects, beside `outcome`.

**Alternatives**

- One `message` key, as a run node has, holds one failure, but the root can be given several.
- An array of plain strings cannot gain a key, such as a throwable, without changing each item's type.
- Putting failures in `output` mixes a failure of the test command with what its runners wrote, and a reader must search `output` to learn why the root aborted.

**Reasoning** A failure attached to the root is why the root aborted, as a run node's `message` is why the run aborted, so it sits beside `outcome`. An object per failure keeps the order they were attached in and lets an item gain keys later.

**Costs** A reader that wants one reason for the root's abort must join the messages itself.

**Settled by** [Library JSON output: encoding, root, module and run node](https://github.com/jamesgorman2/scalatest/issues/142).

---

## A run node is identified by `number`, its 1-based position among its module's runs, unique only within its module.

**Alternatives**

- A run's process ID is not known for a run whose `runStarted` event never arrived, and is not unique where the operating system reuses it.
- An identifier unique across the whole JSON output, such as a module name joined to a number, repeats the module the tree already gives.

**Reasoning** The human-readable output already shows each run as `Run <n>:` under its module, from the order the consolidation library was given the module's runs. Writing that number gives a reader the same name for a run in both outputs, and the tree gives its module.

**Costs** A reader that collects runs across modules must pair each `number` with its module's `name`.

**Settled by** [Library JSON output: encoding, root, module and run node](https://github.com/jamesgorman2/scalatest/issues/142).

---

## The root names the release, build tool and module selectors `scalaTestVersion`, `buildTool` and `moduleSelectors`; a module is named by `name`.

**Alternatives**

- A flat `buildToolName` and `buildToolVersion` on the root splits one optional fact into two keys a reader must check together.
- Making each module selector a node, with its modules beneath it, nests a module under every selector that resolved to it, so a module two selectors share would appear twice.
- Writing modules as an object keyed by name loses the order § Tree gives and differs from the `contents` array every other node uses.

**Reasoning** Each key names the one fact it holds in ScalaTest's and Mill's own words: ScalaTest's version, the build tool, the module selector as given and the Mill module path. Grouping the build tool's name and version makes it absent or present as a whole, as the caller gives it. Keeping module selectors as data on the root, with the modules they resolved to by name, leaves `contents` as the tree alone.

**Costs** A reader that wants the selectors that chose a module must search `moduleSelectors` for its `name`.

**Settled by** [Library JSON output: encoding, root, module and run node](https://github.com/jamesgorman2/scalatest/issues/142).

---

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
