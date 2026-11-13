# Consolidated human-readable output for one JVM module: suites run by other code, and executions, in the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM where a suite, scope or test is run by code other than ScalaTest's own, or more than once: which node each event is assigned to, when a suite's structure is sent again, and the execution each event carries. Part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events, executions and further `structure` events among them.

Each run below is launched outside Mill. In each criterion, no event is assigned to an ancestor of the suite its `suiteId` or `nameInfo` names, or of the test its `testName` or `nameInfo` names.

## A suite that runs another instance

```gherkin
Feature: A suite that runs another instance of itself

  Rule: An instance a suite runs that shares the suite's ID is that suite: what it registers is the suite's further structure, and its events are assigned to the suite's own nodes (R-096, R-130)
    Scenario: AC-135 A custom `@WrapWith` wrapper runs the class it wraps
      Given suite `StackSpec`, class `samples.StackSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[samples.DelegatingSuite])`, declaring scope `A Stack`, labelled `A Stack`, holding test `pops`, labelled `- pops`, which succeeds
      And `samples.DelegatingSuite`, a `Suite` whose constructor takes the annotated class and builds an instance of it, which gives that instance's `suiteName`, `suiteId` and `testNames` as its own, and overrides `run` to call that instance's `run` with the arguments it was given
      When `Framework` runs `StackSpec` with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
            test "A Stack pops", label "- A Stack pops"
        suite "StackSpec": SuiteStarting
        structure
          suite "StackSpec", class "samples.DelegatingSuite", id "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "StackSpec": SuiteCompleted
        """
      And `pops` has the node ID the first `structure` gives `A Stack pops`
      And no `structure` holds a suite other than `StackSpec`
```

## A suite run from a test

`HelperSuite` below is class `samples.HelperSuite`, an `AnyFunSpec` declaring scope `A helper`, labelled `A helper`, holding test `helps`, labelled `- helps`, which succeeds. A suite whose test *runs a helper* overrides `runTest` to keep the `args` it is given, and the test calls `new HelperSuite().run(None, args)` with them.

```gherkin
Feature: A suite run from a test

  Rule: A suite a test runs whose suite ID differs from the innermost enclosing suite's is one nested suite under that suite, never under the test, and has no `SuiteStarting` (R-096, R-130)
    Scenario: AC-136 A test runs a helper
      Given suite `OuterSuite`, class `samples.OuterSuite`, an `AnyFunSuite` whose one test, `uses a helper`, labelled `- uses a helper`, runs a helper, then succeeds
      When `Framework` runs `OuterSuite` with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "uses a helper", label "- uses a helper"
        suite "OuterSuite": SuiteStarting
        test "uses a helper": TestStarting
        structure under suite "OuterSuite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "uses a helper": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        """
      And no `SuiteStarting` or `SuiteCompleted` is assigned to `HelperSuite`

    Scenario: AC-155 Tests of two nested suites each run a helper
      Given suite `UserSuites`, class `samples.UserSuites`, a `Suites` with no tests, nesting `User1Suite`, class `samples.User1Suite`, and `User2Suite`, class `samples.User2Suite`, each an `AnyFunSuite` whose one test, `uses one` and `uses two`, labelled `- uses one` and `- uses two`, runs a helper, then succeeds
      When `Framework` runs `UserSuites` with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "UserSuites", class "samples.UserSuites"
            suite "User1Suite", class "samples.User1Suite"
              test "uses one", label "- uses one"
            suite "User2Suite", class "samples.User2Suite"
              test "uses two", label "- uses two"
        test "uses one": TestStarting
        structure under suite "User1Suite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        test "helps" #1: TestStarting
        test "uses one": TestSucceeded
        test "uses two": TestStarting
        structure under suite "User2Suite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        test "helps" #2: TestStarting
        test "uses two": TestSucceeded
        """
      And it holds no other `structure` holding `HelperSuite`

  Rule: Such a suite is one node however many times an instance with its suite ID is run under the innermost enclosing suite, each run a further execution of its scopes and tests, whose events carry the execution of their parent that they ran in (R-130, R-134)
    Scenario: AC-137 Two tests each run a helper
      Given suite `OuterSuite` of AC-136, with its one test replaced by tests `first` and `second`, labelled `- first` and `- second`, each of which runs a helper, then succeeds
      When `Framework` runs `OuterSuite` with the runner argument
      Then the event log holds, in this order:
        """
        test "first": TestStarting
        structure under suite "OuterSuite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps", parent execution 1: TestStarting
        test "helps", parent execution 1: TestSucceeded
        scope "A helper": ScopeClosed
        test "first": TestSucceeded
        test "second": TestStarting
        scope "A helper" execution 2: ScopeOpened
        test "helps" execution 2, parent execution 2: TestStarting
        test "helps" execution 2, parent execution 2: TestSucceeded
        scope "A helper" execution 2: ScopeClosed
        test "second": TestSucceeded
        """
      And it holds no other `structure` holding `HelperSuite`
```

## Nested suites

```gherkin
Feature: Nested suites run in other ways

  Rule: The events of a nested suite that its parent's own `runNestedSuites` runs are assigned to the nested suite's nodes (R-096)
    Scenario: AC-138 A `runNestedSuites` override sends no `SuiteStarting`
      Given suite `OuterSuites` of `test-event-log-structure.md` AC-098, which overrides `runNestedSuites` to call `run(None, args)` on each of its nested suites in turn, sending no event itself
      When `Framework` runs `OuterSuites` with the runner argument
      Then the event log holds the `structure` AC-098 gives, and then, in this order:
        """
        suite "OuterSuites": SuiteStarting
        test "inner one": TestStarting
        test "inner one": TestSucceeded
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "OuterSuites": SuiteCompleted
        """
      And no `SuiteStarting` or `SuiteCompleted` is assigned to `Inner1Suite` or `Inner2Suite`

  Rule: One instance nested twice is two nodes, each holding the events of one of its runs, in declared order (R-104)
    Scenario: AC-139 A `Suites` nests one instance twice
      Given suite `TwiceSuites`, class `samples.TwiceSuites`, a `Suites` with no tests whose two nested suites are one instance of `Inner1Suite` of `test-event-log-structure.md` AC-098
      When `Framework` runs `TwiceSuites` with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "TwiceSuites", class "samples.TwiceSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
        suite "TwiceSuites": SuiteStarting
        suite "Inner1Suite" #1: SuiteStarting
        test "inner one" #1: TestStarting
        test "inner one" #1: TestSucceeded
        suite "Inner1Suite" #1: SuiteCompleted
        suite "Inner1Suite" #2: SuiteStarting
        test "inner one" #2: TestStarting
        test "inner one" #2: TestSucceeded
        suite "Inner1Suite" #2: SuiteCompleted
        suite "TwiceSuites": SuiteCompleted
        """
      And it holds no other `TestStarting`
```

## A test run more than once

```gherkin
Feature: A test run more than once

  Rule: Each event of a test carries the execution of the test it belongs to, one execution for each time the test is run (R-130)
    Scenario: AC-140 A suite runs a failed test again
      Given suite `RetrySuite`, class `samples.RetrySuite`, an `AnyFunSuite` that overrides `runTest` to call `super.runTest` a second time where the first fails, whose one test, `flaky`, labelled `- flaky`, calls `info("trying")`, then fails the first time it runs and succeeds the second
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "flaky": TestStarting
        test "flaky": InfoProvided(message: "trying")
        test "flaky": TestFailed
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: InfoProvided(message: "trying")
        test "flaky" execution 2: TestSucceeded
        """
      And no other event is assigned to `flaky`

  Rule: A `SuiteStarting`, `TestStarting`, `ScopeOpened` or `TestIgnored` that user code builds and sends opens a new execution of its node where the node has none open (R-130)
    Scenario: AC-156 A suite with no registration tree reports a test twice
      Given suite `PlainSuite` of `test-event-log-structure.md` AC-127, which also overrides `runTests` to call `runTest` twice for test `a` and for no other test
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "a": TestStarting
        test "a": TestSucceeded
        test "a" execution 2: TestStarting
        test "a" execution 2: TestSucceeded
        """
      And no other event is assigned to `a`
```
