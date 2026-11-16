# Consolidated human-readable output for one JVM module: the layout's lines

This part contains the acceptance criteria for the text and colour of each scope, test, label, abort, count and block line, for the text and colour of failure, cancel and abort messages, and for Mill's prefix. It is part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix. Each row describes the human-readable output that the library, used directly with colour on and test run label `core.test`, writes when it consolidates an event log in which the node's suite last registered the node with the label `<label>`, whether or not that label begins `- `, and in which an execution of the node has the row's outcome. Where a scope or test has several executions, each row describes each execution's line.

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
| AC-027 | scope, aborted because its suite or its run aborted while it was open                                         | `<label> *** ABORTED ***`  | label green, suffix red |

| Requirement                    | Criteria                                                                               |
|--------------------------------|----------------------------------------------------------------------------------------|
| R-098                          | AC-017, AC-018, AC-019, AC-020, AC-021, AC-024, AC-025                                 |
| R-099                          | AC-023, AC-027                                                                         |
| R-100                          | AC-022, AC-026                                                                         |
| R-106                          | AC-017, AC-018, AC-019, AC-020, AC-021, AC-022, AC-023, AC-024, AC-025, AC-026, AC-027 |
| `../../JSON-LAYOUT.md` § Nodes | AC-027                                                                                 |

## Label, abort, count and block lines

Each row describes the human-readable output that the library, used directly with colour on and test run label `core.test`, writes when it consolidates an event log containing the row's node or event, with the row's captured line, where the row has one, in the run's captured output. `<class name>` is the class name the run first registered for the suite, and `<module path>` is `core.test`, the test run label the library is given for the run.

| ID     | Line                                                                  | Text                                                                                                        | Sits under                                             | Colour                          |
|--------|-----------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|---------------------------------|
| AC-045 | a test run label                                                      | `[<module path>]`                                                                                           | nothing: it is not indented                            | cyan                            |
| AC-012 | a suite's abort line                                                  | `<class name> *** ABORTED ***`, or `<class name> *** NOT RUN ***` where its reason is *not run*             | the suite's name line                                  | red                             |
| AC-013 | an aborted suite's abort message lines and its throwable's first line | ScalaTest 3.2.20's                                                                                          | the suite's abort line                                 | red                             |
| AC-016 | a collapsed subtree's count line                                      | `(<counts> not run)`, where `<counts>` counts the suites, scopes and tests at any depth below the subtree's root, in that order. Each count reads `<n> suite`, `<n> scope` or `<n> test`, with `s` added where `<n>` is not 1. Counts are joined by `, `, and a kind with none is left out | its root's line, a suite's name line or a scope's line, after every other line under the root's line | red                             |
| AC-046 | a run's block heading                                                 | `Output outside any suite:`                                                                                 | its test run label                                     | cyan                            |
| AC-014 | a message attached to the run                                         | `[<kind>] <text>`, where `<kind>` is `info`, `note`, `alert` or `markup`                                    | its run's block heading                                | yellow for an alert, else green |
| AC-047 | a line captured from stdout                                           | `[stdout] <text>`                                                                                           | its run's block heading                                | uncoloured                      |
| AC-048 | a line captured from stderr                                           | `[stderr] <text>`                                                                                           | its run's block heading                                | yellow                          |
| AC-050 | an aborted run's end                                                  | `*** RUN ABORTED ***`                                                                                       | its run's block heading                                | red                             |
| AC-015 | a run abort's message lines and its throwable's first line            | ScalaTest 3.2.20's                                                                                          | `*** RUN ABORTED ***`                                  | red                             |
| AC-051 | a stopped run's end                                                   | `*** RUN STOPPED ***`                                                                                       | its run's block heading                                | red                             |

| Requirement | Criteria                                                       |
|-------------|----------------------------------------------------------------|
| R-012       | AC-047, AC-048                                                 |
| R-021       | AC-013, AC-015, AC-047, AC-048                                 |
| R-078       | AC-045                                                         |
| R-082       | AC-046, AC-014, AC-047, AC-048, AC-050, AC-015, AC-051         |
| R-097       | AC-014                                                         |
| R-098       | AC-050, AC-051                                                 |
| R-099       | AC-012                                                         |
| R-100       | AC-012                                                         |
| R-106       | AC-045, AC-012, AC-013, AC-016, AC-014, AC-047, AC-048, AC-015 |
| R-107       | AC-016                                                         |

## Failure, cancel and abort messages

```gherkin
Feature: Failure, cancel and abort messages

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

  Rule: A message whose throwable is a `StackDepth` ends with the failed code's file and line, and the throwable shows no line of its own
    Scenario: AC-123 A cancelled test's message
      Given an event log holding:
        """
        run started
        structure
          suite "StackFlatSpec", class "samples.StackFlatSpec"
            scope "A Stack", label "A Stack"
              test "have size 0", label "- must have size 0"
        suite "StackFlatSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": InfoProvided(message: "checked")
        test "have size 0": NoteProvided(message: "a note")
        test "have size 0": AlertProvided(message: "an alert")
        test "have size 0": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack" at StackFlatSpec.scala:7)
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
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
      Given an event log holding:
        """
        run started
        structure
          suite "SetupFailsSpec", class "samples.SetupFailsSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
              test "write", label "- should write"
        suite "SetupFailsSpec": SuiteStarting
        suite "SetupFailsSpec": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - database unreachable", throwable: org.scalatest.exceptions.TestFailedException "database unreachable" at SetupFailsSpec.scala:5)
        run ended completed
        """
      When the library consolidates it
      Then the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            Exception encountered when invoking run on a nested suite - database unreachable (SetupFailsSpec.scala:5)
          (1 scope, 2 tests not run)
        """

  Rule: A failure's analysis follows its message, after an `Analysis:` line
    Scenario: AC-124 A failure with analysis
      Given an event log holding:
        """
        run started
        structure
          suite "StackFlatSpec", class "samples.StackFlatSpec"
            scope "A Stack", label "A Stack"
              test "have size 0", label "- must have size 0"
        suite "StackFlatSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": InfoProvided(message: "checked")
        test "have size 0": NoteProvided(message: "a note")
        test "have size 0": AlertProvided(message: "an alert")
        test "have size 0": TestFailed(throwable: org.scalatest.exceptions.TestFailedException "Stack(1) did not equal Stack(0)" at StackFlatSpec.scala:9, analysis: ["Stack(size: 1 -> 0)"])
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
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

| Requirement | Criteria               |
|-------------|------------------------|
| R-106       | AC-123, AC-124, AC-125 |

## Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Background:
    Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
    And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
    And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test core.test`

  Rule: On stdout, each line of the human-readable output carries Mill's prefix
    Scenario: AC-044 Every line of the human-readable output is prefixed
      Given the module is at Mill's default `testLogLevel`
      And suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      And Mill colours its console output
      When the test command runs the module
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation

  Rule: Human-readable output on stdout is coloured where Mill colours its own console output and uncoloured otherwise, and colour changes no text
    Scenario: AC-053 The same text with colour on and off
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      When the test command runs the module once with Mill's `--color true` and once with Mill's `--color false`
      Then the first human-readable output, with Mill's prefix and every ANSI escape sequence removed from each line, is:
        """
        [core.test]
          CalculatorSuite:
            - adds two numbers
          Output outside any suite:
            [stdout] dividing now
        """
      And in the first human-readable output, `[core.test]` and `Output outside any suite:` are cyan, `- adds two numbers` is green and `[stdout] dividing now` is uncoloured
      And the second human-readable output holds no ANSI escape sequence
      And the second human-readable output equals the first with every ANSI escape sequence removed
```

| Requirement | Criteria |
|-------------|----------|
| R-106       | AC-044   |
| R-124       | AC-053   |
