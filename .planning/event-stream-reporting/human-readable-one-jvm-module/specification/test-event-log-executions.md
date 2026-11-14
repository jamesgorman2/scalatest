# Consolidated human-readable output for one JVM module: suites run by other code, and executions, in the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM where a suite, scope or test is run by code other than ScalaTest's own, or more than once: which node each event is assigned to, when a suite's structure is sent again, and the execution each event carries. Part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events, executions and further `structure` events among them.

Each run below is launched outside Mill. In each criterion, no event is assigned to an ancestor of the suite its `suiteId` or `nameInfo` names, or of the test its `testName` or `nameInfo` names.

## A suite that runs another instance

```gherkin
Feature: A suite that runs another instance of itself

  Rule: An instance a suite runs outside its tests that shares the suite's ID is that suite: what it registers is the suite's further structure, and its events are assigned to the suite's own nodes (R-096, R-130)
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

  Rule: A suite a test runs is a nested suite under that test, and has no `SuiteStarting` (R-096, R-130)
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
        structure under test "uses a helper"
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
        structure under test "uses one"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        test "helps" #1: TestStarting
        test "uses one": TestSucceeded
        test "uses two": TestStarting
        structure under test "uses two"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        test "helps" #2: TestStarting
        test "uses two": TestSucceeded
        """
      And it holds no other `structure` holding `HelperSuite`

  Rule: The suites two tests run are two nodes, each with its own executions, whose events carry the execution of each ancestor that they ran in (R-130, R-134)
    Scenario: AC-137 Two tests each run a helper
      Given suite `OuterSuite` of AC-136, with its one test replaced by tests `first` and `second`, labelled `- first` and `- second`, each of which runs a helper, then succeeds
      When `Framework` runs `OuterSuite` with the runner argument
      Then the event log holds, in this order:
        """
        test "first": TestStarting
        structure under test "first"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper" #1: ScopeOpened
        test "helps" #1, in test "first" execution 1: TestStarting
        test "helps" #1, in test "first" execution 1: TestSucceeded
        scope "A helper" #1: ScopeClosed
        test "first": TestSucceeded
        test "second": TestStarting
        structure under test "second"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper" #2: ScopeOpened
        test "helps" #2, in test "second" execution 1: TestStarting
        test "helps" #2, in test "second" execution 1: TestSucceeded
        scope "A helper" #2: ScopeClosed
        test "second": TestSucceeded
        """
      And it holds no other `structure` holding `HelperSuite`, and no event of a second execution of `A helper` or `helps`

  Rule: A suite a test runs in each of the test's executions is one node, with an execution under each of them (R-130, R-134)
    Scenario: AC-162 A test run twice runs a helper each time
      Given suite `RetrySuite` of AC-140, whose test `flaky` also runs a helper before it fails or succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "flaky": TestStarting
        structure under test "flaky"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper", in test "flaky" execution 1: ScopeOpened
        test "helps", in test "flaky" execution 1: TestStarting
        test "helps", in test "flaky" execution 1: TestSucceeded
        scope "A helper", in test "flaky" execution 1: ScopeClosed
        test "flaky": TestFailed
        test "flaky" execution 2: TestStarting
        scope "A helper" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: ScopeOpened
        test "helps" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: TestStarting
        test "helps" execution 2, in test "flaky" execution 2, in suite "HelperSuite" execution 2: TestSucceeded
        scope "A helper" execution 2, in test "flaky" execution 2: ScopeClosed
        test "flaky" execution 2: TestSucceeded
        """
      And it holds no other `structure` holding `HelperSuite`

  Rule: A suite a test runs inside a task on its suite's own `ExecutionContext` is a nested suite under that test (R-096)
    Scenario: AC-167 An async test runs a helper inside a `Future`
      Given suite `AsyncOuterSuite`, class `samples.AsyncOuterSuite`, an `AsyncFunSuite` whose one test, `uses a helper`, labelled `- uses a helper`, runs a helper inside a `Future` on the suite's own `executionContext`, then succeeds
      When `Framework` runs `AsyncOuterSuite` with the runner argument
      Then the event log holds, in this order:
        """
        test "uses a helper": TestStarting
        structure under test "uses a helper"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "uses a helper": TestSucceeded
        """

  Rule: A suite a test runs on a thread the test starts is a nested suite under that test, its events assigned by their own data, so its tests sit directly under it (R-096, R-097)
    Scenario: AC-165 A test runs a helper on a thread of its own
      Given suite `OuterSuite` of AC-136, whose test runs the helper on a thread it starts, and waits for that thread to end before it succeeds
      When `Framework` runs `OuterSuite` with the runner argument
      Then the event log holds, in this order:
        """
        test "uses a helper": TestStarting
        structure under test "uses a helper"
          suite "HelperSuite", class "samples.HelperSuite"
            test "A helper helps", label "- A helper helps"
        test "A helper helps": TestStarting
        test "A helper helps": TestSucceeded
        test "uses a helper": TestSucceeded
        """
      And no `structure` holds a scope under `HelperSuite`
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

  Rule: A suite that starts in one execution of its parent more often than the parent's structure declares it is a further node beside the declared one, and its own `structure` precedes its events (R-096, R-104)
    Scenario: AC-164 A `runNestedSuites` override runs one nested suite twice
      Given suite `OuterSuites` of `test-event-log-structure.md` AC-098, which overrides `runNestedSuites` to call `run(None, args)` twice on its first nested suite and never on its second, sending no event itself
      When `Framework` runs `OuterSuites` with the runner argument
      Then the event log holds the `structure` AC-098 gives, and then, in this order:
        """
        suite "OuterSuites": SuiteStarting
        test "inner one" #1: TestStarting
        test "inner one" #1: TestSucceeded
        structure under suite "OuterSuites"
          suite "Inner1Suite" #2, class "samples.Inner1Suite"
            test "inner one", label "- inner one"
        test "inner one" #2: TestStarting
        test "inner one" #2: TestSucceeded
        suite "OuterSuites": SuiteCompleted
        """
      And it holds no other `structure`

  Rule: Sibling suites sharing a suite ID take their places in the order they start, and a further `structure` precedes the events of one whose tests differ from its place's (R-130)
    Scenario: AC-168 A `runNestedSuites` override runs two suites sharing an ID in reverse order
      Given `ModeSuite`, class `samples.ModeSuite`, an `AnyFunSuite` whose one test is named by its constructor's argument and labelled with `- ` before that name
      And suite `PairSuites`, class `samples.PairSuites`, a `Suites` with no tests nesting `new ModeSuite("fast")` and then `new ModeSuite("slow")`, which overrides `runNestedSuites` to call `run(None, args)` on its nested suites in reverse order, sending no event itself
      When `Framework` runs `PairSuites` with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "PairSuites", class "samples.PairSuites"
            suite "ModeSuite", class "samples.ModeSuite"
              test "fast", label "- fast"
            suite "ModeSuite", class "samples.ModeSuite"
              test "slow", label "- slow"
        suite "PairSuites": SuiteStarting
        structure under suite "PairSuites"
          suite "ModeSuite" #1, class "samples.ModeSuite"
            test "slow", label "- slow"
        test "slow" #2: TestStarting
        test "slow" #2: TestSucceeded
        structure under suite "PairSuites"
          suite "ModeSuite" #2, class "samples.ModeSuite"
            test "fast", label "- fast"
        test "fast" #2: TestStarting
        test "fast" #2: TestSucceeded
        suite "PairSuites": SuiteCompleted
        """

  Rule: A suite run from a top-level suite's `beforeAll` is a nested suite of that suite, whatever reporter it is given (R-096)
    Scenario: AC-166 A suite's `beforeAll` runs a helper
      Given suite `SetupSuite`, class `samples.SetupSuite`, an `AnyFunSuite` mixing in `BeforeAndAfterAll`, whose `beforeAll` calls `new HelperSuite().run(None, args)` with `Args` holding a reporter that drops every event, and whose one test, `works`, labelled `- works`, succeeds
      When `Framework` runs `SetupSuite` with the runner argument
      Then the event log holds, in this order:
        """
        suite "SetupSuite": SuiteStarting
        structure under suite "SetupSuite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "works": TestStarting
        test "works": TestSucceeded
        suite "SetupSuite": SuiteCompleted
        """
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
