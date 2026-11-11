# Consolidated human-readable output for one JVM module: layout

Acceptance criteria for how the human-readable output lays out its lines: its tree, where each line sits, each line's text and colour, and Mill's prefix. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use.

## The tree

```gherkin
Feature: The tree of the human-readable output

  Rule: The output is its test run labels, with every other line under them (R-078, R-129)
    Scenario: AC-002 A module's label heads its suites
      Given the module is at Mill's default `testLogLevel`
      And suite `SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, succeeds
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
      Given suite `OuterSuites`, with no tests, nesting `Inner1Suite` and `Inner2Suite`, whose one test each, `inner one` and `inner two`, succeeds
      When the test command runs the module
      Then the lines for `OuterSuites` are:
        """
        OuterSuites:
          Inner1Suite:
            - inner one
          Inner2Suite:
            - inner two
        """

  Rule: A scope's or test's line shows its label, with its style's control words
    Scenario: AC-004 Labels of a word spec and a feature spec
      Given suite `SetWordSpec`, an `AnyWordSpec` that ScalaTest 3.2.19 prints with scope labels `A Set` and `when empty` and test label `- should have size 0`, each nested in the one before
      And suite `ShopFeatureSpec`, an `AnyFeatureSpec` that it prints with scope label `Feature: Checkout` and, nested in it, test label `Scenario: paying by card`
      And both tests succeed
      When the test command runs the module
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

## Where a node's lines sit

Every line sits under the line it belongs to (R-106), with *sits under* as defined above. Where that leaves a line's place open, it sits as follows.

| ID     | Line                                                                                                      | Sits under                                                         |
|--------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| AC-007 | a suite's message that no scope holds                                                                     | the suite's name line                                              |
| AC-009 | a test's failure or cancel message lines and analysis lines, its throwable's first line, and its messages | the test's line                                                    |
| AC-010 | each frame line of a throwable or cause, `...` included                                                   | the throwable's first line, or the cause's `Cause:` line           |
| AC-011 | a cause's `Cause:` line                                                                                   | the line that the first line of the throwable it causes sits under |

## Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix.

| ID     | Node and outcome                                                   | Line                       | Colour                  |
|--------|--------------------------------------------------------------------|----------------------------|-------------------------|
| AC-017 | test, succeeded                                                    | `<label>`                  | green                   |
| AC-018 | test, failed                                                       | `<label> *** FAILED ***`   | red                     |
| AC-019 | test, ignored                                                      | `<label> !!! IGNORED !!!`  | yellow                  |
| AC-020 | test, pending                                                      | `<label> (pending)`        | yellow                  |
| AC-021 | test, cancelled                                                    | `<label> !!! CANCELED !!!` | yellow                  |
| AC-022 | test, aborted with reason *not run*                                | `<label> *** NOT RUN ***`  | red                     |
| AC-023 | test, aborted after it started                                     | `<label> *** ABORTED ***`  | red                     |
| AC-024 | scope, no outcome                                                  | `<label>`                  | green                   |
| AC-025 | scope, pending                                                     | `<label> (pending)`        | yellow                  |
| AC-026 | scope, aborted with reason *not run*                               | `<label> *** NOT RUN ***`  | label green, suffix red |
| AC-027 | scope, aborted because its suite aborted while it was open (R-099) | `<label> *** ABORTED ***`  | label green, suffix red |

## Lines that are not a node's

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

| ID     | Kind   | Line              | Colour |
|--------|--------|-------------------|--------|
| AC-028 | info   | `[info] <text>`   | green  |
| AC-029 | note   | `[note] <text>`   | green  |
| AC-030 | alert  | `[alert] <text>`  | yellow |
| AC-031 | markup | `[markup] <text>` | green  |

```gherkin
Feature: Where messages sit

  Rule: A message sits under the node it attaches to (R-097), after its test's failure or cancel lines, in the order the run emitted it
    Scenario: AC-032 A test's messages sit under it, notes and alerts included
      Given in suite `StackFunSpec`, test `is empty` calls `info("checked size")`, `note("a note")`, `alert("an alert")` and `markup("*markup*")` in that order, then succeeds
      When the test command runs the module
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
      Given in suite `StackFlatSpec`, test `must have size 0` calls `info("checked")`, `note("a note")` and `alert("an alert")` in that order, then fails
      When the test command runs the module
      Then the last three of the lines for test `must have size 0` are:
        """
        [info] checked
        [note] a note
        [alert] an alert
        """
      And no line before `- must have size 0 *** FAILED ***` holds `a note` or `an alert`

    Scenario: AC-034 A scope's message sits among its children
      Given in suite `StackFunSpec`, scope `A Stack` emits `info("an info in a scope")` before its scope `when empty` starts
      When the test command runs the module
      Then the lines for `A Stack` begin:
        """
        A Stack
          [info] an info in a scope
          when empty
        """
```

## Collapsed subtrees

A collapsed subtree (R-107) shows its root's lines, then its count line (AC-016).

- AC-055 A count line reads `(<counts> not run)`. `<counts>` gives how many suites, then scopes, then tests sit at any depth below the root, each as `<n> suite`, `<n> scope` or `<n> test` with `s` added where `<n>` is not 1, joined by `, `, leaving out a kind with none.

| ID     | Below the collapsed root    | Count line                             |
|--------|-----------------------------|----------------------------------------|
| AC-042 | 2 suites, 1 scope, 3 tests  | `(2 suites, 1 scope, 3 tests not run)` |
| AC-043 | nothing                     | none                                   |

```gherkin
Feature: Collapsed subtrees

  Rule: A suite whose tests never started collapses into its abort (R-100, R-107)
    Scenario: AC-039 A suite's `beforeAll` throws
      Given suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, an `AnyWordSpec` whose scope `A repo` holds tests `should read` and `should write`
      And its `beforeAll` throws `RuntimeException("database unreachable")`
      When the test command runs the module
      Then, with every stack frame left out, the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            java.lang.RuntimeException: database unreachable
          (1 scope, 2 tests not run)
        """
```

## Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Rule: On stdout, each line carries Mill's prefix (R-106)
    Scenario: AC-044 Every line of the human-readable output is prefixed
      When the test command runs the module
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation

  Rule: Colour changes no text (R-124)
    Scenario: AC-053 The same text with colour on and off
      Given the module of `runs-and-test-structure.md` AC-035
      When the test command runs it once with Mill's `--color true` and once with `--color false`
      Then the second human-readable output holds no ANSI escape sequence
      And it equals the first with every ANSI escape sequence removed
```
