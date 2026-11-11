# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces, how its human-readable output is coloured, formatted and filtered, and what its JSON output holds, newest first.

## The human-readable output takes the formatting and filtering characters `d`, `s`, `f`, `w`, `v`, `c`, `x`, `e`, `o`, `m` and `r`, and no other of ScalaTest's.

**Alternatives**

- `h` and `p`, after ScalaTest's `H` and `P`, would drop suite and scope header lines, which hold the tree together.
- `n`, `l` and `q` would drop events that print nothing in formatted output.
- `u`, unformatted output, has no tree.
- `j` is covered by the `J` output letter.
- `i`, `t`, `g` and `k` add an end-of-run reminder, and the consolidated output has none.

**Reasoning** A character is kept only where its upper-case ScalaTest counterpart changes formatted output in a way that has meaning in output consolidating every module's tree (`../SPECIFICATION.md` R-127).

**Costs** Suite and scope header lines cannot be hidden, and no reminder of failures closes the human-readable output.

**Settled by** [Which ScalaTest 3.2.19 text configuration does the human-readable writer reproduce?](https://github.com/jamesgorman2/scalatest/issues/55).

---

## Human-readable output on stdout is coloured where Mill colours its own console output.

**Alternatives** Colouring always, as ScalaTest does under Mill's own test tasks (whose worker logger always reports ANSI codes as supported), ignores Mill's `--color` and `NO_COLOR`.

**Reasoning** Mill's own colour decision already weighs `--color`, an interactive console, `FORCE_COLOR`, `NO_COLOR` and BSP mode, so following it colours the test command's output exactly where Mill's output around it is coloured (`../SPECIFICATION.md` R-124).

**Costs** Mill gives a task its decision only as `Task.log.prompt.colored`, which is `private[mill]`, so the plugin reads Mill API with no compatibility promise. That value was `false` under ConPTY and MinTTY, so there the test command's output is uncoloured unless `--color true` is given. ScalaTest under Mill's own test tasks does colour its output there.

**Settled by** [Which ScalaTest 3.2.19 text configuration does the human-readable writer reproduce?](https://github.com/jamesgorman2/scalatest/issues/55).

**See** `../research/mill-runner-progress-without-trait.md` § 5.3.

---

## Short and full stack traces in the human-readable output leave out a `StackDepthException`'s stack trace, unlike ScalaTest's `S` and `F`.

**Alternatives** Following ScalaTest's `S` and `F` adds a noisy stack trace to every `StackDepthException`, such as a test assertion failure, and needs the test event log to carry frames `../JSON-LAYOUT.md` § Throwables leaves out.

**Reasoning** A `StackDepthException`'s message already carries the file and line it was raised at (`../SPECIFICATION.md` R-128).

**Costs** `s` and `f` do not mean exactly what ScalaTest's `S` and `F` mean, and no output shows the frames beneath a `StackDepthException`'s file and line.

**Settled by** [Which ScalaTest 3.2.19 text configuration does the human-readable writer reproduce?](https://github.com/jamesgorman2/scalatest/issues/55).

---

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
