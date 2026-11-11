# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces, how its human-readable output is coloured, formatted, filtered and collapsed, and what its JSON output holds, newest first.

## The human-readable output collapses a subtree only where one occurrence aborted every node in it, never where occurrences are merely equal.

**Alternatives**

- Equal class and message, with the cause chain, merges distinct failures that read alike, such as two `assert(x == 1)` failures reading "2 did not equal 1", and hides a real failure.
- Equal class, message and full stack trace almost never matches failures with one source, because their test-body frames differ, so it acts as identity under a costlier rule.
- Equal class, message and throw site (a `StackDepthException`'s failed code location, else the top frame) folds tests failing on one broken fixture, which collapsing was never meant to do, and is a heuristic to specify and test against each runtime's stack shapes.

**Reasoning** Collapsing is for where a module or suite failed, not for tests that failed for the same reason (`../SPECIFICATION.md` R-107). An abort is the one occurrence that ends many nodes at once, so identity collapses exactly what it took down and keeps every separately thrown failure on its own line.

**Costs** A suite whose 40 tests each fail on one broken fixture still prints 40 failures.

**Settled by** [What does "share one error" mean for a collapsed subtree?](https://github.com/jamesgorman2/scalatest/issues/56).

---

## A subtree that one occurrence aborted collapses even inside a suite that partly ran.

**Alternatives**

- Collapsing a subtree only when nothing in it ran lists every *not run* descendant, at every depth, of a suite that aborted partway.
- Hiding every *not run* descendant of an aborted suite loses which nodes did not run.

**Reasoning** One line for each subtree that did not run identifies what was not run without listing every node beneath it (`../SPECIFICATION.md` R-107).

**Costs** Siblings are never merged, so a flat suite that aborts early still prints one *not run* line for each remaining test.

**Settled by** [What does "share one error" mean for a collapsed subtree?](https://github.com/jamesgorman2/scalatest/issues/56).

---

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
