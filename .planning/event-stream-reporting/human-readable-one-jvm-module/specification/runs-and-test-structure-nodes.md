# Consolidated human-readable output for one JVM module: the nodes shown

Acceptance criteria for which suites, scopes, tests and failures the human-readable output shows, and in what order. Part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them; [layout.md](layout.md) gives where each line sits and its text and colour.

## Order

```gherkin
Feature: The order of suites, scopes and tests

  Rule: A label's top-level suites appear in ascending order by name (R-079)
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
      Then the human-readable output is:
        """
        [core.test]
          AlphaSuite:
            - alpha
          MidSuite:
            - mid
          ZetaSuite:
            - zeta
        """

  Rule: Below a top-level suite, a node's children appear in declared order (R-105)
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
```

## Outcomes

```gherkin
Feature: Outcomes

  Rule: No line shows an outcome rolled up from the nodes below it (R-098)
    Scenario: AC-067 A failed test leaves its scope, suite and label without a suffix
      Given an event log holding:
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
      Then the test run label's line is `[core.test]`, the suite's name line `FailingFunSpec:` and the scope's line `A Stack`
      And the test's line is `- is empty *** FAILED ***`

  Rule: A suite whose constructor throws is aborted, and the module's other suites still run (R-099)
    Scenario: AC-068 A suite's constructor throws
      Given suite class `samples.BrokenCtorSpec`, an `AnyFunSuite` whose constructor throws `IllegalStateException("no config")`
      And suite `SqrtPropSpec` of `layout.md` AC-002
      When the test command runs the module
      Then the lines for the suite named `BrokenCtorSpec` begin:
        """
        BrokenCtorSpec:
          samples.BrokenCtorSpec *** ABORTED ***
        """
      And a line under `samples.BrokenCtorSpec *** ABORTED ***` is `java.lang.IllegalStateException: no config`
      And the lines for `SqrtPropSpec` are those `layout.md` AC-002 gives
```

## Nodes that share a name

```gherkin
Feature: Nodes that share a name

  Rule: Nodes that share a name are never merged (R-104)
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

    Scenario: AC-070 Two suites share a name
      Given an event log holding:
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the human-readable output holds two `SameSuite:` lines
      And the lines for one are `SameSuite:` and `- in a`, and for the other `SameSuite:` and `- in b`
```

## Filtered tests

```gherkin
Feature: Filtered tests

  Rule: A test a filter excludes is shown only as ScalaTest 3.2.19 reports it (R-112)
    Scenario: AC-071 A tag the module's own arguments exclude
      Given suite `SpeedSuite`, class `samples.SpeedSuite`, an `AnyFunSuite` with tests `fast` and `slow`, labelled `- fast` and `- slow`, each succeeding where it runs, of which `slow` is tagged `samples.Slow` and writes `slow ran` to stdout
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      And the module's own ScalaTest arguments are `-l samples.Slow`
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SpeedSuite:
            - fast
        """
```

## Failures

```gherkin
Feature: Failures

  Rule: A failure message reaches the human-readable output unchanged (R-010, R-011)
    Scenario: AC-072 A multi-line, non-ASCII failure message
      Given suite `UnicodeSuite`, class `samples.UnicodeSuite`, an `AnyFunSuite` whose test `reports`, labelled `- reports`, calls `fail("first line\nsecond line: ü ß 日本 ✓")` at line 7 of `UnicodeSuite.scala`
      And a run of `UnicodeSuite` through `Framework` with the runner argument, whose test event log, decoded with core's codec, is fed to the consolidation library directly
      When the library consolidates it
      Then the lines under `- reports *** FAILED ***` begin:
        """
        first line
        second line: ü ß 日本 ✓ (UnicodeSuite.scala:7)
        """

  Rule: Every suite, scope, test and failure ScalaTest 3.2.19 prints is shown (R-021)
    Scenario: AC-073 The compatibility fixture
      Given the module's suites are the compatibility fixture, in which the suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` does not abort
      And the file ScalaTest 3.2.19's `-f` reporter writes for the same suites when Mill's `testForked` runs them through `Framework`
      When the test command runs the module with Mill's `--color false`
      Then each line of that file from its first suite's name line and before the first line that begins `Run completed in`, with leading spaces removed, is a line of the human-readable output with leading spaces removed, unless it then begins `+ ` (a message) or is a stack-frame line (`test-event-log-events.md` AC-114)
      And each line the fixture writes to stdout or stderr is a `[stdout]` or `[stderr]` line of the human-readable output
```
