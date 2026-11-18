# Consolidated human-readable output for one JVM module: the nodes shown

This part contains the acceptance criteria for which suites, scopes, tests and failures the human-readable output shows, and in what order. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## Order

```gherkin
Feature: The order of suites, scopes and tests
  A suite, scope or test that its parent's structure declares has a declared position among that parent's children.
  A top-level suite, a suite that a test ran, and a suite that its parent suite ran without declaring it have none.
  Ascending alphanumeric order compares two names as Java's `String.compareTo` compares them.

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: A run's top-level suites appear in ascending alphanumeric order by name
    Scenario: AC-065 Suites whose names sort apart from their classes and the order they ran in
      Given an event log holding:
        """
        run started
        structure
          suite "ZetaSuite", class "a.ZetaSuite"
            test "zeta", label "- zeta"
        suite "ZetaSuite": SuiteStarting
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        suite "ZetaSuite": SuiteCompleted
        structure
          suite "MidSuite", class "m.MidSuite"
            test "mid", label "- mid"
        suite "MidSuite": SuiteStarting
        test "mid": TestStarting
        test "mid": TestSucceeded
        suite "MidSuite": SuiteCompleted
        structure
          suite "AlphaSuite", class "z.AlphaSuite"
            test "alpha", label "- alpha"
        suite "AlphaSuite": SuiteStarting
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        suite "AlphaSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          AlphaSuite:
            - alpha
          MidSuite:
            - mid
          ZetaSuite:
            - zeta
        """

  Rule: The children that a suite's structure declares for a suite or scope appear in declared order
    Scenario: AC-066 Scopes and tests declared out of name order and run out of declared order
      Given an event log holding:
        """
        run started
        structure
          suite "OrderFunSpec", class "samples.OrderFunSpec"
            scope "B", label "B"
              test "z", label "- z"
              test "a", label "- a"
            scope "A", label "A"
              test "m", label "- m"
        suite "OrderFunSpec": SuiteStarting
        scope "B": ScopeOpened
        test "a": TestStarting
        test "a": TestSucceeded
        test "z": TestStarting
        test "z": TestSucceeded
        scope "B": ScopeClosed
        scope "A": ScopeOpened
        test "m": TestStarting
        test "m": TestSucceeded
        scope "A": ScopeClosed
        suite "OrderFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OrderFunSpec` are:
        """
        OrderFunSpec:
          B
            - z
            - a
          A
            - m
        """

  Rule: The suites that one execution of a test ran appear in ascending alphanumeric order by name
    Scenario: AC-198 A test ran two suites out of name order
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "uses helpers", label "- uses helpers"
        suite "OuterSuite": SuiteStarting
        test "uses helpers": TestStarting
        structure under test "uses helpers"
          suite "ZetaHelper", class "a.ZetaHelper"
            test "zeta", label "- zeta"
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        structure under test "uses helpers"
          suite "AlphaHelper", class "z.AlphaHelper"
            test "alpha", label "- alpha"
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        test "uses helpers": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuite` are:
        """
        OuterSuite:
          - uses helpers
            AlphaHelper:
              - alpha
            ZetaHelper:
              - zeta
        """

  Rule: The suites that a suite ran without declaring them, and that share a suite ID with no suite the parent declares, appear after the parent's declared children, in ascending alphanumeric order by name
    Scenario: AC-199 A suite ran two suites it does not declare, out of name order
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "DeclaredSuite", class "samples.DeclaredSuite"
              test "declared", label "- declared"
            test "own", label "- own"
        suite "OuterSuites": SuiteStarting
        structure under suite "OuterSuites"
          suite "ZetaSuite", class "a.ZetaSuite"
            test "zeta", label "- zeta"
        test "zeta": TestStarting
        test "zeta": TestSucceeded
        structure under suite "OuterSuites"
          suite "AlphaSuite", class "z.AlphaSuite"
            test "alpha", label "- alpha"
        test "alpha": TestStarting
        test "alpha": TestSucceeded
        test "declared": TestStarting
        test "declared": TestSucceeded
        test "own": TestStarting
        test "own": TestSucceeded
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuites` are:
        """
        OuterSuites:
          DeclaredSuite:
            - declared
          - own
          AlphaSuite:
            - alpha
          ZetaSuite:
            - zeta
        """
```

## Outcomes

```gherkin
Feature: Outcomes

  Rule: No line shows an outcome rolled up from the nodes below it
    Scenario: AC-067 A failed test leaves its scope, suite and module without a suffix
      Given the consolidation library is used directly, without Mill, with colour on
      And the library consolidates a run of the module `core.test`
      And an event log holding:
        """
        run started
        structure
          suite "FailingFunSpec", class "samples.FailingFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "pops", label "- pops"
        suite "FailingFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "FailingFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the module's line is `[core.test]`
      And the suite's name line is `FailingFunSpec:`
      And the scope's line is `A Stack`
      And the test's line is `- is empty *** FAILED ***`

  Rule: A suite whose constructor throws is aborted, and the module's other suites still run
    Scenario: AC-068 A suite's constructor throws
      Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
      And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
      And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
      And the test command is invoked as `mill scalatest/test core.test`
      And Mill colours its console output
      And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes
      And suite `BrokenCtorSpec`, class `samples.BrokenCtorSpec`, an `AnyFunSuite` whose constructor throws `IllegalStateException("no config")`
      And suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      When the test command runs the module
      Then the lines for `BrokenCtorSpec` begin:
        """
        BrokenCtorSpec:
          samples.BrokenCtorSpec *** ABORTED ***
        """
      And a line under `samples.BrokenCtorSpec *** ABORTED ***` is `java.lang.IllegalStateException: no config`
      And the lines for `SqrtPropSpec` are:
        """
        SqrtPropSpec:
          - sqrt of a square is the root
        """
```

## Nodes that share a name

```gherkin
Feature: Nodes that share a name

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: Nodes that share a name are never merged
    Scenario: AC-069 Two scopes share a name
      Given an event log holding:
        """
        run started
        structure
          suite "DupFunSpec", class "samples.DupFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "DupFunSpec": SuiteStarting
        scope "A Stack" #1: ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack" #1: ScopeClosed
        scope "A Stack" #2: ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack" #2: ScopeClosed
        suite "DupFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `DupFunSpec` are:
        """
        DupFunSpec:
          A Stack
            - is empty
          A Stack
            - pops
        """

  Rule: Suites that share a name and have no declared position are never merged, and appear in the order they started
    Scenario: AC-070 Two top-level suites share a name, and the suite registered second starts first
      Given an event log holding:
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          SameSuite:
            - in b
          SameSuite:
            - in a
        """

  Rule: Where a suite declares a nested suite, and one execution of the parent runs more nested suites sharing that suite ID than the parent declares, each extra suite sits directly after the last sibling sharing that suite ID, with the scopes and tests that the extra suite registers
    Scenario: AC-163 A nested suite that ran twice in one run of its parent
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
              test "inner more", label "- inner more"
            suite "Inner2Suite", class "samples.Inner2Suite"
              test "inner two", label "- inner two"
        suite "OuterSuites": SuiteStarting
        test "inner one" #1: TestStarting
        test "inner one" #1: TestSucceeded
        test "inner more": TestStarting
        test "inner more": TestSucceeded
        structure under suite "OuterSuites"
          suite "Inner1Suite" #2, class "samples.Inner1Suite"
            test "inner one", label "- inner one"
        test "inner one" #2: TestStarting
        test "inner one" #2: TestSucceeded
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "OuterSuites": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuites` are:
        """
        OuterSuites:
          Inner1Suite:
            - inner one
            - inner more
          Inner1Suite:
            - inner one
          Inner2Suite:
            - inner two
        """
```

## Filtered tests

```gherkin
Feature: Filtered tests

  Background:
    Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
    And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
    And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test core.test`
    And Mill colours its console output
    And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes

  Rule: A test that a filter excludes is shown only through the events ScalaTest 3.2.20 reports for that test
    Scenario: AC-071 A tag the module's own arguments exclude
      Given suite `SpeedSuite`, class `samples.SpeedSuite`, an `AnyFunSuite` with tests `fast` and `slow`, labelled `- fast` and `- slow`, each succeeding where it runs, of which `slow` is tagged `samples.Slow` and writes `slow ran` to stdout
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      And the module's own ScalaTest arguments are `-l samples.Slow`
      When the test command runs the module
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          SpeedSuite:
            - fast
        """
```

## Failures

```gherkin
Feature: Failures

  Rule: A failure message reaches the human-readable output unchanged
    Scenario: AC-072 A multi-line, non-ASCII failure message
      Given suite `UnicodeSuite`, class `samples.UnicodeSuite`, an `AnyFunSuite` whose test `reports`, labelled `- reports`, calls `fail("first line\nsecond line: ü ß 日本 ✓")` at line 7 of `UnicodeSuite.scala`
      And `UnicodeSuite` runs through ScalaTest's `Framework`, outside Mill, given the ScalaTest runner argument that writes the run's test event log to a file
      And the consolidation library is used directly, without Mill, with colour on
      When the library consolidates that test event log as a run of the module `core.test`
      Then the lines under `- reports *** FAILED ***` begin:
        """
        first line
        second line: ü ß 日本 ✓ (UnicodeSuite.scala:7)
        """

  Rule: Every suite, scope, test, failure and line of captured output of a run is shown, apart from every node below the root of a collapsed subtree, a subtree whose root is aborted and below whose root every node that is neither attributed nor an ignored test is aborted with the same reason, and apart from every test that has no execution where another test of the run with the same suite ID and the same test name has one, and every scope or suite that has not started, has a test below it, and has only such tests below it
    Scenario: AC-073 The compatibility fixture
      Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
      And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
      And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
      And the test command is invoked as `mill scalatest/test core.test`
      And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes
      And the module's suites are the compatibility fixture, which holds at least one suite of each of these styles:
        | `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureAsyncFunSuite`                    |
        | `AnyPropSpec`, `FixtureAnyPropSpec`                                                             |
        | `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`      |
        | `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec` |
        | `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`                    |
        | `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`                    |
        | `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec`        |
        | `RefSpec`                                                                                       |
      And the compatibility fixture's suites hold between them:
        | nested suites                                                                                                                          |
        | scopes, one of them pending                                                                                                            |
        | tests that succeed, fail, are ignored, are pending and are cancelled                                                                   |
        | `info`, `note`, `alert` and `markup` messages from suites, scopes and tests, and all four from a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test |
        | a suite whose `beforeAll` throws                                                                                                       |
        | a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, with a scope, that does not abort                                       |
        | a test that writes to stdout and to stderr                                                                                             |
      When the test command runs the module
      Then the human-readable output shows every suite, scope and test of the run, apart from every node below the root of a collapsed subtree, a subtree whose root is aborted and below whose root every node that is neither attributed nor an ignored test is aborted with the same reason, and apart from every test that has no execution where another test of the run with the same suite ID and the same test name has one, and every scope or suite that has not started, has a test below it, and has only such tests below it
      And the human-readable output shows every failure with its message, and every line the fixture writes to stdout or stderr
```

## Requirements covered

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-011       | AC-072                                         |
| R-098b      | AC-067                                         |
| R-098c      | AC-067, AC-068                                 |
| R-098e      | AC-067                                         |
| R-104       | AC-069, AC-070, AC-163                         |
| R-105       | AC-065, AC-066, AC-070, AC-163, AC-198, AC-199 |
| R-106c      | AC-073                                         |
| R-106e      | AC-073                                         |
| R-142       | AC-071                                         |
| R-130c      | AC-073                                         |
