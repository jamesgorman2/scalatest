# Consolidated human-readable output for one JVM module: structures that change

Acceptance criteria for what the human-readable output shows of a suite whose structure the event log holds more than once. Part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events, executions and further `structure` events among them; [layout.md](layout.md) gives where each line sits and its text and colour.

Each criterion below feeds the consolidation library an event log.

## A suite with more than one structure

```gherkin
Feature: A suite whose structure the event log holds more than once

  Rule: Every execution of a node is shown at the parent, declared position and label its suite's last structure gives the node (R-105, R-130)
    Scenario: AC-144 A further structure reorders tests, relabels one and moves one under a scope
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

  Rule: A node that its suite's last structure lacks, and that has an execution or holds a node that has one, is shown with the parent, declared position and label the last structure to hold it gave it, after any node the last structure holds at that position (R-021)
    Scenario: AC-145 A test that ran is missing from the last structure
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

    Scenario: AC-150 A scope whose test ran is missing from the last structure
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

  Rule: A node with no execution that its suite's last structure lacks is not shown, and is not aborted with reason *not run* (R-100, R-130)
    Scenario: AC-146 A test only the first structure holds never starts
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

    Scenario: AC-147 A test only the first structure holds is not counted in a collapsed subtree
      Given the event log of AC-146, with every event after its second `structure` replaced by:
        """
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
