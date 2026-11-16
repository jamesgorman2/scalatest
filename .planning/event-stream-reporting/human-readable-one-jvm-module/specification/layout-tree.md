# Consolidated human-readable output for one JVM module: the layout's tree

This part contains the acceptance criteria for the human-readable output's tree, for where each line sits, and for collapsed subtrees. It is part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## The tree

```gherkin
Feature: The tree of the test command's human-readable output

  Background:
    Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
    And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
    And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test core.test`
    And Mill colours its console output
    And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes

  Rule: The human-readable output is its test run labels, with every other line under them
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

  Rule: The scopes and tests of each `Any` style, `AsyncFeatureSpec` and `RefSpec` show the labels ScalaTest 3.2.20 prints for them
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

```gherkin
Feature: The tree of the consolidation library's human-readable output

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

  Rule: Every suite shows its name line
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

  Rule: A scope's or test's line shows the label that the node's suite last registered for the node, never the node's name
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
```

| Requirement | Criteria               |
|-------------|------------------------|
| R-025       | AC-002                 |
| R-078       | AC-002                 |
| R-096       | AC-003                 |
| R-106       | AC-003, AC-004, AC-119 |
| R-129       | AC-002                 |

## Where a node's lines sit

Each row describes the human-readable output that the library, used directly with colour on and test run label `core.test`, writes when it consolidates an event log in which the run registers the line's node and reports the event the line comes from.

| ID     | Line                                                                                                      | Sits under                                                         |
|--------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| AC-007 | a message attached to a suite                                                                             | the suite's name line                                              |
| AC-009 | a test execution's failure or cancel message lines and analysis lines, and its throwable's first line     | the test execution's line                                          |
| AC-010 | each frame line of a throwable or cause, `...` included                                                   | the throwable's first line, or the cause's `Cause:` line           |
| AC-011 | a cause's `Cause:` line                                                                                   | the line that the first line of the throwable it causes sits under |
| AC-158 | the name line of a suite a test ran                                                                       | the test's line                                                    |

| Requirement | Criteria                               |
|-------------|----------------------------------------|
| R-096       | AC-158                                 |
| R-097       | AC-007                                 |
| R-106       | AC-007, AC-009, AC-010, AC-011, AC-158 |

## Collapsed subtrees

Each row describes the human-readable output that the library, used directly with colour on and test run label `core.test`, writes when it consolidates an event log in which a suite aborts and the row's nodes, which the suite registered below it, never start.

| ID     | Below the aborted suite     | Count line                             |
|--------|-----------------------------|----------------------------------------|
| AC-042 | 2 suites, 1 scope, 3 tests  | `(2 suites, 1 scope, 3 tests not run)` |
| AC-043 | nothing                     | none                                   |

```gherkin
Feature: Collapsed subtrees

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

  Rule: A suite whose tests never started collapses into its abort
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

| Requirement | Criteria               |
|-------------|------------------------|
| R-021       | AC-039, AC-042         |
| R-100       | AC-039, AC-042         |
| R-107       | AC-039, AC-042, AC-043 |
| R-131       | AC-039, AC-042         |
