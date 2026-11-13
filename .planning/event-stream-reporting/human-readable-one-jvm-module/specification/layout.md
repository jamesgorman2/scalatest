# Consolidated human-readable output for one JVM module: layout

Acceptance criteria for how the human-readable output lays out its lines: its tree, where each line sits, each line's text and colour, and Mill's prefix. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

Each criterion below feeds the consolidation library an event log, except AC-002, AC-119, AC-044 and AC-053, which run the module with the test command.

## The tree

```gherkin
Feature: The tree of the human-readable output

  Rule: The output is its test run labels, with every other line under them (R-078, R-129)
    Scenario: AC-002 A module's label heads its suites
      Given the module is at Mill's default `testLogLevel`
      And suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SqrtPropSpec:
            - sqrt of a square is the root
        """

  Rule: Every suite shows its name line (R-106)
    Scenario: AC-003 Nested suites sit under the suite that nests them
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
            suite "Inner2Suite", class "samples.Inner2Suite"
              test "inner two", label "- inner two"
        suite "OuterSuites": SuiteStarting
        suite "Inner1Suite": SuiteStarting
        test "inner one": TestStarting
        test "inner one": TestSucceeded
        suite "Inner1Suite": SuiteCompleted
        suite "Inner2Suite": SuiteStarting
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "Inner2Suite": SuiteCompleted
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuites` are:
        """
        OuterSuites:
          Inner1Suite:
            - inner one
          Inner2Suite:
            - inner two
        """

  Rule: A scope's or test's line shows the label its suite's structure gives it, never its name
    Scenario: AC-004 Labels with and without a leading `- `
      Given an event log holding:
        """
        run started
        structure
          suite "SetWordSpec", class "samples.SetWordSpec"
            scope "A Set", label "A Set"
              scope "empty", label "when empty"
                test "have size 0", label "- should have size 0"
        suite "SetWordSpec": SuiteStarting
        scope "A Set": ScopeOpened
        scope "empty": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": TestSucceeded
        scope "empty": ScopeClosed
        scope "A Set": ScopeClosed
        suite "SetWordSpec": SuiteCompleted
        structure
          suite "ShopFeatureSpec", class "samples.ShopFeatureSpec"
            scope "Checkout", label "Feature: Checkout"
              test "paying by card", label "Scenario: paying by card"
        suite "ShopFeatureSpec": SuiteStarting
        scope "Checkout": ScopeOpened
        test "paying by card": TestStarting
        test "paying by card": TestSucceeded
        scope "Checkout": ScopeClosed
        suite "ShopFeatureSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `SetWordSpec` and `ShopFeatureSpec` are:
        """
        SetWordSpec:
          A Set
            when empty
              - should have size 0
        ShopFeatureSpec:
          Feature: Checkout
            Scenario: paying by card
        """

  Rule: The scopes and tests of each `Any` style, `AsyncFeatureSpec` and `RefSpec` show the labels ScalaTest 3.2.19 prints for them (R-106)
    Scenario Outline: AC-119 <style>
      Given suite `<suite>`, class `samples.<suite>`, of style `<style>`, declaring <declaration>, whose one test succeeds
      When the test command runs the module
      Then the lines for `<suite>` are `<suite>:` and, under it, <lines>

      Examples:
        | style            | suite                | declaration                                             | lines                                                                    |
        | AnyFunSuite      | CalcFunSuite         | `test("adds")`                                          | `- adds`                                                                 |
        | AnyPropSpec      | CalcPropSpec         | `property("adds")`                                      | `- adds`                                                                 |
        | AnyFunSpec       | CalcFunSpec          | `describe("A calculator") { it("adds") }`               | `A calculator`, with `- adds` under it                                   |
        | AnyFreeSpec      | CalcFreeSpec         | `"A calculator" - { "adds" in }`                        | `A calculator`, with `- adds` under it                                   |
        | AnyWordSpec      | CalcWordSpec         | `"A calculator" when { "empty" should { "add" in } }`   | `A calculator`, with `when empty` under it and `- should add` under that |
        | AnyFlatSpec      | CalcFlatSpec         | `"A calculator" should "add" in`                        | `A calculator`, with `- should add` under it                             |
        | AnyFeatureSpec   | CalcFeatureSpec      | `Feature("Addition") { Scenario("adds") }`              | `Feature: Addition`, with `Scenario: adds` under it                      |
        | AsyncFeatureSpec | CalcAsyncFeatureSpec | `Feature("Addition") { Scenario("adds") }`              | `Feature: Addition`, with `- Scenario: adds` under it                    |
        | RefSpec          | CalcRefSpec          | ``object `A calculator` { def `adds two`: Unit = () }`` | `A calculator`, with `- adds two` under it                               |
```

## Where a node's lines sit

Every line sits under the line it belongs to (R-106). Where that leaves a line's place open, it sits as follows when the library consolidates an event log holding the line's node in a `structure` and the event the line comes from.

| ID     | Line                                                                                                      | Sits under                                                         |
|--------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| AC-007 | a suite's message that no scope holds                                                                     | the suite's name line                                              |
| AC-009 | a test's failure or cancel message lines and analysis lines, its throwable's first line, and its messages | the test's line                                                    |
| AC-010 | each frame line of a throwable or cause, `...` included                                                   | the throwable's first line, or the cause's `Cause:` line           |
| AC-011 | a cause's `Cause:` line                                                                                   | the line that the first line of the throwable it causes sits under |

## Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix. Each row holds when the library consolidates an event log whose last `structure` for the node's suite gives the node the label `<label>`, whether or not that label begins `- `, and whose events give the node the row's outcome (R-098 to R-101). A node has an execution for each execution the events assigned to it carry, and has started once it has one. A scope or test with several executions has a line for each, which takes that execution's outcome, and every line this document puts under a scope's or test's line sits under the line of the execution its event carries (`runs-and-test-structure-executions.md` AC-141, AC-142).

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

## Lines that are not a node's

Each row holds when the library consolidates an event log holding the row's node or event, or, for AC-047 and AC-048, when the run's captured output holds the row's line. `<class name>` is the class the suite's `structure` gives it, and `<module path>` is `core.test`, the module path the run is fed with.

| ID     | Line                                                         | Text                                                                                                     | Sits under                                             | Colour             |
|--------|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------|--------------------|
| AC-045 | a test run label                                             | `[<module path>]`                                                                                        | nothing: it is not indented                            | cyan               |
| AC-012 | a suite's abort line                                         | `<class name> *** ABORTED ***`, or `<class name> *** NOT RUN ***` where its reason is *not run*          | the suite's name line                                  | red                |
| AC-013 | an aborted suite's abort message lines and its throwable's first line | 3.2.19's                                                                                        | the suite's abort line                                 | red                |
| AC-016 | a collapsed subtree's count line                             | AC-055's                                                                                                 | its root's line: a suite's name line or a scope's line | red                |
| AC-046 | a run's block heading                                        | `Output outside any suite:`                                                                              | its test run label                                     | cyan               |
| AC-014 | a message attached to the run                                | AC-028 to AC-031's                                                                                       | the block's heading                                    | AC-028 to AC-031's |
| AC-047 | a line captured from stdout                                  | `[stdout] <text>`                                                                                        | the block's heading                                    | uncoloured         |
| AC-048 | a line captured from stderr                                  | `[stderr] <text>`                                                                                        | the block's heading                                    | yellow             |
| AC-050 | an aborted run's end                                         | `*** RUN ABORTED ***`                                                                                    | the block's heading                                    | red                |
| AC-015 | a run abort's message lines and its throwable's first line   | 3.2.19's                                                                                                 | `*** RUN ABORTED ***`                                  | red                |
| AC-051 | a stopped run's end                                          | `*** RUN STOPPED ***`                                                                                    | the block's heading                                    | red                |

## Messages

Each row holds when the library consolidates an event log holding the message's event, `InfoProvided`, `NoteProvided`, `AlertProvided` or `MarkupProvided`, with `<text>` its `message`, or for markup its `text`.

| ID     | Kind   | Line              | Colour |
|--------|--------|-------------------|--------|
| AC-028 | info   | `[info] <text>`   | green  |
| AC-029 | note   | `[note] <text>`   | green  |
| AC-030 | alert  | `[alert] <text>`  | yellow |
| AC-031 | markup | `[markup] <text>` | green  |

```gherkin
Feature: Where messages sit

  Rule: Messages sit under the node they attach to (R-097), after its test's failure or cancel lines, in the order the event log holds them
    Scenario: AC-032 A test's messages sit under it, notes and alerts included
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": InfoProvided(message: "checked size")
        test "is empty": NoteProvided(message: "a note")
        test "is empty": AlertProvided(message: "an alert")
        test "is empty": MarkupProvided(text: "*markup*")
        test "is empty": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for test `is empty` are:
        """
        - is empty
          [info] checked size
          [note] a note
          [alert] an alert
          [markup] *markup*
        """
      And no line before `- is empty` holds `a note` or `an alert`

    Scenario: AC-033 A failed test's messages follow its failure
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
        test "have size 0": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for test `have size 0` are:
        """
        - must have size 0 *** FAILED ***
          java.lang.IllegalStateException: not empty
          [info] checked
          [note] a note
          [alert] an alert
        """
      And no line before `- must have size 0 *** FAILED ***` holds `a note` or `an alert`

    Scenario: AC-034 A scope's message sits among its children
      Given an event log holding:
        """
        run started
        structure
          suite "ScopeInfoFunSpec", class "samples.ScopeInfoFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              scope "when full", label "when full"
                test "pops", label "- pops"
        suite "ScopeInfoFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "when full": ScopeClosed
        scope "A Stack": ScopeClosed
        suite "ScopeInfoFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `A Stack` are:
        """
        A Stack
          - is empty
          [info] an info in a scope
          when full
            - pops
        """

  Rule: A message's throwable sits under the message (R-106)
    Scenario: AC-122 An info with a throwable and its cause
      Given the event log of AC-032, with its `InfoProvided` replaced by:
        """
        test "is empty": InfoProvided(message: "checked size", throwable: java.lang.IllegalStateException "size unknown" caused by java.io.IOException "closed")
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for test `is empty` begin:
        """
        - is empty
          [info] checked size
            java.lang.IllegalStateException: size unknown
            Cause: java.io.IOException: closed
          [note] a note
        """
```

## Collapsed subtrees

A collapsed subtree (R-107) shows its root's lines, then its count line (AC-016).

- AC-055 A count line reads `(<counts> not run)`. `<counts>` gives how many suites, scopes and tests, in that order, sit at any depth below the root. Each count reads `<n> suite`, `<n> scope` or `<n> test`, with `s` added where `<n>` is not 1. Counts are joined by `, `, and a kind with none is left out.

Each row holds when the library consolidates an event log in which a suite is aborted and the row's nodes, below it in its `structure`, never start.

| ID     | Below the collapsed root    | Count line                             |
|--------|-----------------------------|----------------------------------------|
| AC-042 | 2 suites, 1 scope, 3 tests  | `(2 suites, 1 scope, 3 tests not run)` |
| AC-043 | nothing                     | none                                   |

```gherkin
Feature: Collapsed subtrees

  Rule: A suite whose tests never started collapses into its abort (R-100, R-107)
    Scenario: AC-039 A suite aborts before its first test
      Given an event log holding:
        """
        run started
        structure
          suite "SetupFailsSpec", class "samples.SetupFailsSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
              test "write", label "- should write"
        suite "SetupFailsSpec": SuiteStarting
        suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            java.lang.RuntimeException: database unreachable
          (1 scope, 2 tests not run)
        """
```

## Failure, cancel and abort messages

```gherkin
Feature: Failure, cancel and abort messages

  Rule: A message whose throwable is a `StackDepth` ends with the failed code's file and line, and the throwable shows no line of its own (R-106)
    Scenario: AC-123 A cancelled test's message
      Given the event log of AC-033, with its `TestFailed` replaced by:
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
      Given the event log of AC-039, with its `SuiteAborted` replaced by:
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
      Given the event log of AC-033, with its `TestFailed` replaced by:
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
      Given the module of AC-002
      When the test command runs it
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation

  Rule: Colour changes no text (R-124)
    Scenario: AC-053 The same text with colour on and off
      Given the module of `runs-and-test-structure-runs.md` AC-035
      When the test command runs it once with Mill's `--color true` and once with `--color false`
      Then the second human-readable output holds no ANSI escape sequence
      And it equals the first with every ANSI escape sequence removed
```
