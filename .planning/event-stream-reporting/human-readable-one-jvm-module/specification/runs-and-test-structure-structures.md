# Consolidated human-readable output for one JVM module: registrations that change

This part contains the acceptance criteria for what the human-readable output shows of a suite that registers its scopes and tests more than once. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log, its executions and its further `structure` events. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## A suite that registers its scopes and tests more than once

```gherkin
Feature: A suite that registers its scopes and tests more than once

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

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

  Rule: A scope or test that only an earlier registration of its suite contained, and that ran or contains a node that ran, is shown with the parent, declared position and label that the latest registration containing the scope or test gave it, after any node that the suite's last registration contains at that position
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

  Rule: A scope or test that only an earlier registration of its suite contained, and that never ran, is not shown, is not counted among the nodes a collapsed subtree hides, and is not aborted with reason *not run*
    Scenario: AC-146 A test only the first registration contains never starts
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
        """

    Scenario: AC-147 A test only the first registration contains is not counted in a collapsed subtree
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
        suite "ConfigSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for `ConfigSpec` are:
        """
        ConfigSpec:
          samples.ConfigSpec *** ABORTED ***
            java.lang.RuntimeException: database unreachable
          (1 scope, 1 test not run)
        """
```

## Requirements covered

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-021       | AC-145, AC-150                                 |
| R-100       | AC-145, AC-146, AC-147                         |
| R-105       | AC-144                                         |
| R-107       | AC-147                                         |
| R-130       | AC-144, AC-145, AC-150, AC-146, AC-147         |
| R-131       | AC-145                                         |
