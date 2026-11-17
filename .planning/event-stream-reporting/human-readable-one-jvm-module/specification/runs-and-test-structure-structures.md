# Consolidated human-readable output for one JVM module: registrations that change, and tests that ran at another place

This part contains the acceptance criteria for what the human-readable output shows of a suite that registers its scopes and tests more than once, and of a registered test that ran at a place other than the one that registered it. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log, its executions and its further `structure` events. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## A suite that registers its scopes and tests more than once

```gherkin
Feature: A suite that registers its scopes and tests more than once
  A test is attributed where the test has no execution and another test in the same event log, with the same suite ID and the same test name, has an execution.
  A test's suite ID is the suite ID of the nearest suite above the test.

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: Every execution of a scope or test is shown at the parent, declared position and label that the last registration of its suite's scopes and tests gives the scope or test
    Scenario: AC-144 A later registration reorders tests, relabels one and moves one under a scope
      Given an event log holding:
        """
        run started
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "peeks", label "- peeks"
            test "A Stack grows", label "- A Stack grows"
        suite "StackSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "peeks": TestStarting
        test "peeks": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "grows", label "- grows"
              test "peeks", label "- peeks"
              test "pops", label "- must pop"
        scope "A Stack" execution 2: ScopeOpened
        test "grows": TestStarting
        test "grows": TestSucceeded
        test "pops" execution 2: TestStarting
        test "pops" execution 2: TestSucceeded
        scope "A Stack" execution 2: ScopeClosed
        suite "StackSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `StackSpec` are:
        """
        StackSpec:
          A Stack
            - peeks
            - must pop *** FAILED ***
              java.lang.IllegalStateException: not empty
          A Stack
            - grows
            - must pop
        """

  Rule: A scope or test that only an earlier registration of its suite contained is shown with the parent, declared position and label that the latest registration containing the scope or test gave it, after any node that the suite's last registration contains at that position
    Scenario: AC-145 A test that ran is missing from the last registration
      Given an event log holding:
        """
        run started
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
              test "is empty", label "- is empty"
        suite "ShrinkFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "peeks", label "- peeks"
        suite "ShrinkFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `ShrinkFunSpec` are:
        """
        ShrinkFunSpec:
          A Stack
            - is empty
            - pops
            - peeks *** NOT RUN ***
        """

    Scenario: AC-150 A scope whose test ran is missing from the last registration
      Given an event log holding:
        """
        run started
        structure
          suite "DropFunSpec", class "samples.DropFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        suite "DropFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "DropFunSpec", class "samples.DropFunSpec"
            scope "A Queue", label "A Queue"
              test "polls", label "- polls"
        scope "A Queue": ScopeOpened
        test "polls": TestStarting
        test "polls": TestSucceeded
        scope "A Queue": ScopeClosed
        suite "DropFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `DropFunSpec` are:
        """
        DropFunSpec:
          A Queue
            - polls
          A Stack
            - pops
        """

  Rule: A test that only an earlier registration of its suite contained, that has no execution, and that is neither attributed nor marked ignored by that registration, is aborted with reason *not run*
    Scenario: AC-146 A test only the first registration contains never starts, and no test of its suite ID and test name runs
      Given an event log holding:
        """
        run started
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "local", label "- local"
        suite "ConfigSpec": SuiteStarting
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "remote", label "- remote"
        scope "A config": ScopeOpened
        test "remote": TestStarting
        test "remote": TestSucceeded
        scope "A config": ScopeClosed
        suite "ConfigSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `ConfigSpec` are:
        """
        ConfigSpec:
          A config
            - remote
            - local *** NOT RUN ***
        """
```

## A registered test that ran at another place

```gherkin
Feature: A registered test that ran at another place
  A test is attributed where the test has no execution and another test in the same event log, with the same suite ID and the same test name, has an execution, wherever in the tree that other test sits.
  A test's suite ID is the suite ID of the nearest suite above the test.
  A scope or suite is attributed where the scope or suite has not started, at least one test is below it, and every test below it is attributed.

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: An attributed test, scope or suite is not aborted with reason *not run*, and shows no line
    Scenario: AC-177 Two sibling suites sharing a suite ID run in reverse order, each running the test the other registered
      Given an event log holding:
        """
        run started
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
        run ended completed
        """
      When the library consolidates it
      Then the lines for `PairSuites` are:
        """
        PairSuites:
          ModeSuite:
            - slow
          ModeSuite:
            - fast
        """

    Scenario: AC-178 A suite registered at two places runs at one
      Given an event log holding:
        """
        run started
        structure
          suite "TwinSuites", class "samples.TwinSuites"
            suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
            suite "InnerSuite", class "samples.InnerSuite"
              scope "A Stack", label "A Stack"
                test "pops", label "- pops"
        suite "TwinSuites": SuiteStarting
        suite "InnerSuite" #1: SuiteStarting
        scope "A Stack" #1: ScopeOpened
        test "pops" #1: TestStarting
        test "pops" #1: TestSucceeded
        scope "A Stack" #1: ScopeClosed
        suite "InnerSuite" #1: SuiteCompleted
        suite "TwinSuites": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `TwinSuites` are:
        """
        TwinSuites:
          InnerSuite:
            A Stack
              - pops
        """

  Rule: A suite that has not started, and below which a test is not attributed, is aborted with reason *not run*, whatever other tests below it are attributed, and the line counting the nodes not run below it leaves the attributed tests out
    Scenario: AC-179 A suite registered at two places runs at one, and the other place registers a test whose test name never runs
      Given an event log holding:
        """
        run started
        structure
          suite "UnevenSuites", class "samples.UnevenSuites"
            suite "InnerSuite", class "samples.InnerSuite"
              test "pops", label "- pops"
            suite "InnerSuite", class "samples.InnerSuite"
              test "pops", label "- pops"
              test "peeks", label "- peeks"
        suite "UnevenSuites": SuiteStarting
        suite "InnerSuite" #1: SuiteStarting
        test "pops" #1: TestStarting
        test "pops" #1: TestSucceeded
        suite "InnerSuite" #1: SuiteCompleted
        suite "UnevenSuites": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `UnevenSuites` are:
        """
        UnevenSuites:
          InnerSuite:
            - pops
          InnerSuite:
            samples.InnerSuite *** NOT RUN ***
            (1 test not run)
        """
      And the line `(1 test not run)` counts `peeks`, and does not count the second `pops`, which is attributed
```

## Requirements covered

| Requirement | Criteria                       |
|-------------|--------------------------------|
| R-095a      | AC-145, AC-146, AC-179         |
| R-105       | AC-144                         |
| R-106c      | AC-179                         |
| R-130b      | AC-144, AC-145, AC-150, AC-146 |
| R-130c      | AC-145, AC-150, AC-146         |
| R-130f      | AC-144, AC-146, AC-177, AC-178, AC-179 |
