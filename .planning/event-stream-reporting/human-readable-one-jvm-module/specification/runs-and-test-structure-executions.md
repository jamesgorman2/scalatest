# Consolidated human-readable output for one JVM module: executions

This part contains the acceptance criteria for what the human-readable output shows of a suite, scope or test that ran more than once. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log and its executions. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## Executions

```gherkin
Feature: Nodes that ran more than once

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: A test shows one line for each of its executions, each with that execution's outcome, failure and messages, and the test's executions that ran in one execution of the test's parent sit together at the test's declared position, in the order they started
    Scenario: AC-141 A test that failed, then succeeded when run again
      Given an event log holding:
        """
        run started
        structure
          suite "RetrySuite", class "samples.RetrySuite"
            test "flaky", label "- flaky"
            test "steady", label "- steady"
        suite "RetrySuite": SuiteStarting
        test "flaky": TestStarting
        test "flaky": InfoProvided(message: "first try")
        test "flaky": TestFailed(throwable: java.lang.IllegalStateException "not yet")
        test "steady": TestStarting
        test "steady": TestSucceeded
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: InfoProvided(message: "second try")
        test "flaky" execution 2: TestSucceeded
        suite "RetrySuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `RetrySuite` are:
        """
        RetrySuite:
          - flaky *** FAILED ***
            java.lang.IllegalStateException: not yet
            [info] first try
          - flaky
            [info] second try
          - steady
        """

  Rule: A scope shows one line for each of its executions, together at the scope's declared position and in the order the executions started, and each execution of a scope or test sits under the execution of the parent scope that the child execution ran in
    Scenario: AC-142 A scope and its test ran twice, either side of a sibling scope
      Given an event log holding:
        """
        run started
        structure
          suite "TwiceFunSpec", class "samples.TwiceFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        suite "TwiceFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
        scope "A Queue": ScopeOpened
        test "polls": TestStarting
        test "polls": TestSucceeded
        scope "A Queue": ScopeClosed
        scope "A Stack" execution 2: ScopeOpened
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "TwiceFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `TwiceFunSpec` are:
        """
        TwiceFunSpec:
          A Stack
            - pops *** FAILED ***
              java.lang.IllegalStateException: not empty
          A Stack
            - pops
          A Queue
            - polls
        """

    Scenario: AC-149 Two executions of a scope overlap
      Given an event log holding:
        """
        run started
        structure
          suite "OverlapFunSpec", class "samples.OverlapFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "OverlapFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        scope "A Stack" execution 2: ScopeOpened
        test "pops", in scope "A Stack" execution 1: TestStarting
        test "pops" execution 2, in scope "A Stack" execution 2: TestStarting
        test "pops" execution 2, in scope "A Stack" execution 2: TestSucceeded
        test "pops", in scope "A Stack" execution 1: TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack" execution 2: ScopeClosed
        scope "A Stack": ScopeClosed
        suite "OverlapFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `OverlapFunSpec` are:
        """
        OverlapFunSpec:
          A Stack
            - pops *** FAILED ***
              java.lang.IllegalStateException: not empty
          A Stack
            - pops
        """

  Rule: An execution of a scope shows no line for a child whose executions all ran in other executions of the scope, and a scope or test that has no execution is shown once, under the latest execution of its parent
    Scenario: AC-148 One test ran in the second execution only, and one never started
      Given an event log holding:
        """
        run started
        structure
          suite "PartFunSpec", class "samples.PartFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "peeks", label "- peeks"
              test "grows", label "- grows"
        suite "PartFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        scope "A Stack" execution 2: ScopeOpened
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        test "peeks": TestStarting
        test "peeks": TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "PartFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `PartFunSpec` are:
        """
        PartFunSpec:
          A Stack
            - pops
          A Stack
            - pops
            - peeks
            - grows *** NOT RUN ***
        """

  Rule: A suite shows its name line once for each of its executions, under the execution of the suite's parent that the suite's execution ran in. The executions of a suite that ran in one execution of the suite's parent appear in the order they started, each with the lines of the nodes that ran in it. Where the parent's structure declares the suite, the executions sit together at the suite's declared position. Where the suite has no declared position, each execution takes its place among the parent's other suites with no declared position, by name and then by the order the executions started. A suite execution for which ScalaTest reports no event naming the suite, but in which a node below the suite ran, shows no abort line
    Scenario: AC-143 A suite that a test ran in each of its two executions
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "flaky", label "- flaky"
        suite "OuterSuite": SuiteStarting
        test "flaky": TestStarting
        structure under test "flaky"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "flaky": TestFailed
        test "flaky" execution 2: TestStarting
        scope "A helper" execution 2, in suite "HelperSuite" execution 2: ScopeOpened
        test "helps" execution 2: TestStarting
        test "helps" execution 2: TestSucceeded
        scope "A helper" execution 2: ScopeClosed
        test "flaky" execution 2: TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `OuterSuite` are:
        """
        OuterSuite:
          - flaky *** FAILED ***
            HelperSuite:
              A helper
                - helps
          - flaky
            HelperSuite:
              A helper
                - helps
        """

    Scenario: AC-161 A suite that ran twice in one execution of the test that ran it
      Given an event log holding:
        """
        run started
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
        scope "A helper" execution 2, in suite "HelperSuite" execution 2: ScopeOpened
        test "helps" execution 2: TestStarting
        test "helps" execution 2: TestSucceeded
        scope "A helper" execution 2: ScopeClosed
        test "uses a helper": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuite` are:
        """
        OuterSuite:
          - uses a helper
            HelperSuite:
              A helper
                - helps
            HelperSuite:
              A helper
                - helps
        """

  Rule: A suite that each of two tests ran is two nodes, one under each test's line
    Scenario: AC-159 A suite that two tests each ran
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "first", label "- first"
            test "second", label "- second"
        suite "OuterSuite": SuiteStarting
        test "first": TestStarting
        structure under test "first"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper" #1: ScopeOpened
        test "helps" #1: TestStarting
        test "helps" #1: TestSucceeded
        scope "A helper" #1: ScopeClosed
        test "first": TestSucceeded
        test "second": TestStarting
        structure under test "second"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper" #2: ScopeOpened
        test "helps" #2: TestStarting
        test "helps" #2: TestSucceeded
        scope "A helper" #2: ScopeClosed
        test "second": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuite` are:
        """
        OuterSuite:
          - first
            HelperSuite:
              A helper
                - helps
          - second
            HelperSuite:
              A helper
                - helps
        """
```

## Requirements covered

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-095a      | AC-148, AC-143, AC-161                         |
| R-104       | AC-159                                         |
| R-105       | AC-141, AC-142, AC-149, AC-161                 |
| R-130d      | AC-159, AC-142, AC-149, AC-143, AC-161         |
| R-130e      | AC-141, AC-142, AC-149, AC-148, AC-143, AC-161 |
