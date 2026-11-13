# Consolidated human-readable output for one JVM module: executions and structures that change

Acceptance criteria for what the human-readable output shows of a scope or test that ran more than once, and of a suite whose structure the event log holds more than once. Part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events, executions and further `structure` events among them; [layout.md](layout.md) gives where each line sits and its text and colour.

Each criterion below feeds the consolidation library an event log.

## Executions

```gherkin
Feature: Nodes that ran more than once

  Rule: A test shows one line for each of its executions, each with that execution's outcome, failure and messages, together at the test's declared position and in the order they started (R-105, R-131)
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

  Rule: A scope shows one line for each of its executions, and each execution of a test sits under the execution of its scope that it ran in (R-131)
    Scenario: AC-142 A suite whose scope and test ran twice
      Given an event log holding:
        """
        run started
        structure
          suite "TwiceFunSpec", class "samples.TwiceFunSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "TwiceFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
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
        """

  Rule: A suite shows its name line once, however many executions its scopes and tests have (R-132)
    Scenario: AC-143 A suite whose scope and test ran twice
      Given the event log of AC-142
      When the library consolidates it
      Then the human-readable output holds one `TwiceFunSpec:` line
```

## A suite with more than one structure

```gherkin
Feature: A suite whose structure the event log holds more than once

  Rule: A node is shown at the parent, declared position and label its suite's last structure gives it (R-105, R-130)
    Scenario: AC-144 A further structure moves a test under a scope and changes its label
      Given an event log holding:
        """
        run started
        structure
          suite "StackSpec", class "samples.StackSpec"
            test "A Stack pops", label "- A Stack pops"
        suite "StackSpec": SuiteStarting
        structure
          suite "StackSpec", class "samples.StackSpec"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        scope "A Stack": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "StackSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `StackSpec` are:
        """
        StackSpec:
          A Stack
            - pops
        """

  Rule: A node with an execution that its suite's last structure lacks is shown where the last structure to hold it put it (R-021)
    Scenario: AC-145 A test that ran is missing from the last structure
      Given an event log holding:
        """
        run started
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "pops", label "- pops"
        suite "ShrinkFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        structure
          suite "ShrinkFunSpec", class "samples.ShrinkFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
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
