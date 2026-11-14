# Consolidated human-readable output for one JVM module: the layout's tree

Acceptance criteria for the human-readable output's tree, where each line sits, and collapsed subtrees. Part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

Each criterion below feeds the consolidation library an event log, except AC-002 and AC-119, which run the module with the test command.

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

  Rule: A scope's or test's line shows the label its suite's last structure to hold it gives it, never its name
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

  Rule: The scopes and tests of each `Any` style, `AsyncFeatureSpec` and `RefSpec` show the labels ScalaTest 3.2.20 prints for them (R-106)
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
| AC-007 | a message attached to a suite                                                                             | the suite's name line                                              |
| AC-009 | a test's failure or cancel message lines and analysis lines, and its throwable's first line               | the test's line                                                    |
| AC-010 | each frame line of a throwable or cause, `...` included                                                   | the throwable's first line, or the cause's `Cause:` line           |
| AC-011 | a cause's `Cause:` line                                                                                   | the line that the first line of the throwable it causes sits under |
| AC-158 | the name line of a suite a test ran                                                                       | the test's line                                                    |

## Collapsed subtrees

A collapsed subtree (R-107) shows its root's lines, then its count line (`layout-lines.md` AC-016).

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
