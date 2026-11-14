# Consolidated human-readable output for one JVM module: the layout's lines

Acceptance criteria for the text and colour of each scope, test, label, abort, count and block line, of failure, cancel and abort messages, and for Mill's prefix. Part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

Each criterion below feeds the consolidation library an event log, except AC-044 and AC-053, which run the module with the test command.

## Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix. Each row holds when the library consolidates an event log whose last `structure` to hold the node gives it the label `<label>`, whether or not that label begins `- `, and whose events give the node the row's outcome (R-098 to R-101). Where a scope or test has several executions, each row holds for each execution's line, and `layout-tree.md` AC-009 to AC-011 hold under that line (`runs-and-test-structure-executions.md` AC-141, AC-142).

| ID     | Node and outcome                                                                                              | Line                       | Colour                  |
|--------|---------------------------------------------------------------------------------------------------------------|----------------------------|-------------------------|
| AC-017 | test, succeeded                                                                                               | `<label>`                  | green                   |
| AC-018 | test, failed                                                                                                  | `<label> *** FAILED ***`   | red                     |
| AC-019 | test, ignored                                                                                                 | `<label> !!! IGNORED !!!`  | yellow                  |
| AC-020 | test, pending                                                                                                 | `<label> (pending)`        | yellow                  |
| AC-021 | test, cancelled                                                                                               | `<label> !!! CANCELED !!!` | yellow                  |
| AC-022 | test, aborted with reason *not run*                                                                           | `<label> *** NOT RUN ***`  | red                     |
| AC-023 | test, aborted after it started                                                                                | `<label> *** ABORTED ***`  | red                     |
| AC-024 | scope, no outcome                                                                                             | `<label>`                  | green                   |
| AC-025 | scope, pending                                                                                                | `<label> (pending)`        | yellow                  |
| AC-026 | scope, aborted with reason *not run*                                                                          | `<label> *** NOT RUN ***`  | label green, suffix red |
| AC-027 | scope, aborted because its suite or its run aborted while it was open (R-099; `../../JSON-LAYOUT.md` § Nodes) | `<label> *** ABORTED ***`  | label green, suffix red |

## Label, abort, count and block lines

Each row holds when the library consolidates an event log holding the row's node or event, or, for AC-047 and AC-048, when the run's captured output holds the row's line. `<class name>` is the class the suite's first `structure` gives it, and `<module path>` is `core.test`, the module path the run is fed with.

| ID     | Line                                                         | Text                                                                                                     | Sits under                                             | Colour             |
|--------|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------|--------------------|
| AC-045 | a test run label                                             | `[<module path>]`                                                                                        | nothing: it is not indented                            | cyan               |
| AC-012 | a suite's abort line                                         | `<class name> *** ABORTED ***`, or `<class name> *** NOT RUN ***` where its reason is *not run*          | the suite's name line                                  | red                |
| AC-013 | an aborted suite's abort message lines and its throwable's first line | 3.2.20's                                                                                        | the suite's abort line                                 | red                |
| AC-016 | a collapsed subtree's count line                             | `layout-tree.md` AC-055's                                                                                | its root's line: a suite's name line or a scope's line | red                |
| AC-046 | a run's block heading                                        | `Output outside any suite:`                                                                              | its test run label                                     | cyan               |
| AC-014 | a message attached to the run                                | `layout-messages.md` AC-028 to AC-031's                                                                  | the block's heading                                    | those rows'        |
| AC-047 | a line captured from stdout                                  | `[stdout] <text>`                                                                                        | the block's heading                                    | uncoloured         |
| AC-048 | a line captured from stderr                                  | `[stderr] <text>`                                                                                        | the block's heading                                    | yellow             |
| AC-050 | an aborted run's end                                         | `*** RUN ABORTED ***`                                                                                    | the block's heading                                    | red                |
| AC-015 | a run abort's message lines and its throwable's first line   | 3.2.20's                                                                                                 | `*** RUN ABORTED ***`                                  | red                |
| AC-051 | a stopped run's end                                          | `*** RUN STOPPED ***`                                                                                    | the block's heading                                    | red                |

## Failure, cancel and abort messages

```gherkin
Feature: Failure, cancel and abort messages

  Rule: A message whose throwable is a `StackDepth` ends with the failed code's file and line, and the throwable shows no line of its own (R-106)
    Scenario: AC-123 A cancelled test's message
      Given the event log of `layout-messages.md` AC-033, with its `TestFailed` replaced by:
        """
        test "have size 0": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack" at StackFlatSpec.scala:7)
        """
      When the library consolidates it
      Then the lines for test `have size 0` are:
        """
        - must have size 0 !!! CANCELED !!!
          no stack (StackFlatSpec.scala:7)
          [info] checked
          [note] a note
          [alert] an alert
        """

    Scenario: AC-125 A suite aborted with a `StackDepth` throwable
      Given the event log of `layout-tree.md` AC-039, with its `SuiteAborted` replaced by:
        """
        suite "SetupFailsSpec": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - database unreachable", throwable: org.scalatest.exceptions.TestFailedException "database unreachable" at SetupFailsSpec.scala:5)
        """
      When the library consolidates it
      Then the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            Exception encountered when invoking run on a nested suite - database unreachable (SetupFailsSpec.scala:5)
          (1 scope, 2 tests not run)
        """

  Rule: A failure's analysis follows its message under an `Analysis:` line (R-106)
    Scenario: AC-124 A failure with analysis
      Given the event log of `layout-messages.md` AC-033, with its `TestFailed` replaced by:
        """
        test "have size 0": TestFailed(throwable: org.scalatest.exceptions.TestFailedException "Stack(1) did not equal Stack(0)" at StackFlatSpec.scala:9, analysis: ["Stack(size: 1 -> 0)"])
        """
      When the library consolidates it
      Then the lines for test `have size 0` begin:
        """
        - must have size 0 *** FAILED ***
          Stack(1) did not equal Stack(0) (StackFlatSpec.scala:9)
          Analysis:
          Stack(size: 1 -> 0)
          [info] checked
        """
```

## Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Rule: On stdout, each line carries Mill's prefix (R-106)
    Scenario: AC-044 Every line of the human-readable output is prefixed
      Given the module of `layout-tree.md` AC-002
      When the test command runs it
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation

  Rule: Human-readable output on stdout is coloured where Mill colours its own console output, and colour changes no text (R-124)
    Scenario: AC-053 The same text with colour on and off
      Given the module of `runs-and-test-structure-runs.md` AC-035
      When the test command runs it once with Mill's `--color true` and once with `--color false`
      Then the first human-readable output is the one AC-035 gives, in colour
      And the second holds no ANSI escape sequence
      And it equals the first with every ANSI escape sequence removed
```
