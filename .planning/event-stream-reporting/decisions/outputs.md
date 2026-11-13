# Decisions: outputs

Decisions behind `../SPECIFICATION.md` on which outputs the consolidation produces, how its human-readable output is coloured, formatted, filtered and collapsed, how it shows a node that ran more than once, and what its JSON output holds, newest first.

## A suite, scope or test that ran more than once is one node with many executions, and the human-readable output shows each.

**Alternatives**

- One node per running makes the test structure depend on how the run went, so a test's declared position, and whether it is *not run*, change with reruns.
- Showing only the last execution hides a failure that a later running of the same test followed with a success (`../SPECIFICATION.md` R-021).
- One line with a rolled-up outcome gives a node an outcome no event carries, which R-098 leaves to readers; it is deferred (`../ROADMAP.md` § Deferred).

**Reasoning** ScalaTest 3.2.19 prints every running of a test with its own outcome, failure and messages, so showing each execution keeps R-106 and R-021 while the test structure stays what the run registered (R-130).

**Costs** The event log, every writer and every reader must handle a node with several executions, and a test run many times prints as many lines. The JSON output's layout for such a node is not fixed (`../JSON-LAYOUT.md` § Not yet fixed).

**Settled by** [What does the human-readable output show for an event assigned to an ancestor of the node that emitted it?](https://github.com/jamesgorman2/scalatest/issues/71).

---

## In the human-readable output only nodes that never started collapse into an ancestor, never a node that started, however much its failure reads like another's.

**Alternatives**

- Equal class and message, with the cause chain, merges distinct failures that read alike, such as two `assert(x == 1)` failures reading "2 did not equal 1", and hides a real failure.
- Equal class, message and full stack trace rarely matches even failures from one source, because their test-body frames differ, and hides a real failure where it does.
- Equal class, message and throw site (a `StackDepthException`'s failed code location, else the top frame) folds tests failing on one broken fixture, which collapsing is not for.
- Collapsing every node an abort reached moves their messages and captured output onto the root, overriding `../SPECIFICATION.md` R-075, R-012 and R-097.

**Reasoning** Collapsing is for the nodes a failed module or suite, a stopped run or `--halt-on-failure` kept from starting, not for tests that failed for the same reason. A node that never started has no failure, message or captured output of its own, so collapsing it hides only its name, while a node that started keeps its failure, messages and output where it produced them.

**Costs** A suite whose 40 tests each fail on one broken fixture still prints 40 failures. A suite that aborts with a scope open before any test started shows two lines, the suite and the collapsed scope, and a process death mid-test shows the whole chain to that test.

**Settled by** [What does "share one error" mean for a collapsed subtree?](https://github.com/jamesgorman2/scalatest/issues/56).

---

## A subtree whose nodes below its aborted root never started collapses even inside a suite that partly ran.

**Alternatives**

- Collapsing only a whole suite, and only when nothing in it ran, lists every *not run* node, at every depth, below a suite that aborted partway.
- Hiding every *not run* descendant of an aborted suite loses which nodes did not run.

**Reasoning** One line for each such subtree shows what did not run without listing every node beneath it.

**Costs** Inside a suite that partly ran, the human-readable output names a collapsed scope but not the tests in it; only the JSON output lists them.

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
