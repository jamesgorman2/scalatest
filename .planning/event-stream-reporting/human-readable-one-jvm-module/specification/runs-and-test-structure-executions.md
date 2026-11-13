# Consolidated human-readable output for one JVM module: executions and structures that change

Acceptance criteria for what the human-readable output shows of a suite, scope or test that ran more than once, and of a suite whose structure the event log holds more than once. Part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events, executions and further `structure` events among them; [layout.md](layout.md) gives where each line sits and its text and colour.

Each criterion below feeds the consolidation library an event log.

## Executions

```gherkin
Feature: Nodes that ran more than once

  Rule: A test shows one line for each of its executions, each with that execution's outcome, failure and messages; those that ran in one execution of its parent sit together at the test's declared position, in the order they started (R-105, R-131, R-133)
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

  Rule: A scope shows one line for each of its executions, together at the scope's declared position and in the order they started, and each execution of a scope or test sits under the execution of its parent scope that it ran in (R-131, R-133, R-134)
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
        test "pops", parent execution 1: TestStarting
        test "pops" execution 2, parent execution 2: TestStarting
        test "pops" execution 2, parent execution 2: TestSucceeded
        test "pops", parent execution 1: TestFailed(throwable: java.lang.IllegalStateException "not empty")
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

  Rule: A scope's execution shows no line for a node with executions but none in it, and a scope or test with no execution is shown once, under its parent's latest execution (R-100, R-131)
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

  Rule: A suite shows its name line once however many times it ran, and no abort line where no event is assigned to it but a node below it has an execution (R-100, R-132)
    Scenario: AC-143 A suite that two tests each ran
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "first", label "- first"
            test "second", label "- second"
        suite "OuterSuite": SuiteStarting
        test "first": TestStarting
        structure under suite "OuterSuite"
          suite "HelperSuite", class "samples.HelperSuite"
            scope "A helper", label "A helper"
              test "helps", label "- helps"
        scope "A helper": ScopeOpened
        test "helps": TestStarting
        test "helps": TestSucceeded
        scope "A helper": ScopeClosed
        test "first": TestSucceeded
        test "second": TestStarting
        scope "A helper" execution 2: ScopeOpened
        test "helps" execution 2: TestStarting
        test "helps" execution 2: TestSucceeded
        scope "A helper" execution 2: ScopeClosed
        test "second": TestSucceeded
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OuterSuite` are:
        """
        OuterSuite:
          - first
          - second
          HelperSuite:
            A helper
              - helps
            A helper
              - helps
        """
```

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
