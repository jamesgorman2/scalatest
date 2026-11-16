# Consolidated human-readable output for one JVM module: the layout's messages

This part contains the acceptance criteria for the text and colour of messages, and for where messages sit among a node's lines. It is part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Messages

Each row describes the human-readable output that the library, used directly with colour on and test run label `core.test`, writes when it consolidates an event log containing the message's event, `InfoProvided`, `NoteProvided`, `AlertProvided` or `MarkupProvided`, with `<text>` its `message`, or for markup its `text`.

| ID     | Kind   | Line              | Colour |
|--------|--------|-------------------|--------|
| AC-028 | info   | `[info] <text>`   | green  |
| AC-029 | note   | `[note] <text>`   | green  |
| AC-030 | alert  | `[alert] <text>`  | yellow |
| AC-031 | markup | `[markup] <text>` | green  |

```gherkin
Feature: Where messages sit

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

  Rule: Messages sit under the node they attach to, after a test's failure or cancel lines, in the order the run reported them
    Scenario: AC-032 A test's messages sit under it, notes and alerts included
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": InfoProvided(message: "checked size")
        test "is empty": NoteProvided(message: "a note")
        test "is empty": AlertProvided(message: "an alert")
        test "is empty": MarkupProvided(text: "*markup*")
        test "is empty": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for test `is empty` are:
        """
        - is empty
          [info] checked size
          [note] a note
          [alert] an alert
          [markup] *markup*
        """
      And no line before `- is empty` holds `a note` or `an alert`

    Scenario: AC-033 A failed test's messages follow its failure
      Given an event log holding:
        """
        run started
        structure
          suite "StackFlatSpec", class "samples.StackFlatSpec"
            scope "A Stack", label "A Stack"
              test "have size 0", label "- must have size 0"
        suite "StackFlatSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "have size 0": TestStarting
        test "have size 0": InfoProvided(message: "checked")
        test "have size 0": NoteProvided(message: "a note")
        test "have size 0": AlertProvided(message: "an alert")
        test "have size 0": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for test `have size 0` are:
        """
        - must have size 0 *** FAILED ***
          java.lang.IllegalStateException: not empty
          [info] checked
          [note] a note
          [alert] an alert
        """
      And no line before `- must have size 0 *** FAILED ***` holds `a note` or `an alert`

  Rule: A suite's or scope's message sits after the last of the node's children, in declared order, that started in the message's execution of the node before the run reported the message, and before the next child. Messages between the same two children keep the order the run reported them in
    Scenario: AC-034 A scope's message sits among its children
      Given an event log holding:
        """
        run started
        structure
          suite "ScopeInfoFunSpec", class "samples.ScopeInfoFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              scope "when full", label "when full"
                test "pops", label "- pops"
        suite "ScopeInfoFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "when full": ScopeClosed
        scope "A Stack": ScopeClosed
        suite "ScopeInfoFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `A Stack` are:
        """
        A Stack
          - is empty
          [info] an info in a scope
          when full
            - pops
        """

  Rule: Under a test's line, the test's failure or cancel lines come first, then the test's messages and the suites the test ran. Each message sits after the last suite that started in the message's execution of the test before the run reported the message, and before the next suite
    Scenario: AC-160 A test's messages sit among the suites it ran
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuite", class "samples.OuterSuite"
            test "uses a helper", label "- uses a helper"
        suite "OuterSuite": SuiteStarting
        test "uses a helper": TestStarting
        test "uses a helper": InfoProvided(message: "before")
        structure under test "uses a helper"
          suite "HelperSuite", class "samples.HelperSuite"
            test "helps", label "- helps"
        test "helps": TestStarting
        test "helps": TestSucceeded
        test "uses a helper": InfoProvided(message: "after")
        test "uses a helper": TestFailed(throwable: java.lang.IllegalStateException "not helped")
        suite "OuterSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for test `uses a helper` are:
        """
        - uses a helper *** FAILED ***
          java.lang.IllegalStateException: not helped
          [info] before
          HelperSuite:
            - helps
          [info] after
        """

  Rule: A message's throwable sits under the message
    Scenario: AC-122 An info with a throwable and its cause
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": InfoProvided(message: "checked size", throwable: java.lang.IllegalStateException "size unknown" caused by java.io.IOException "closed")
        test "is empty": NoteProvided(message: "a note")
        test "is empty": AlertProvided(message: "an alert")
        test "is empty": MarkupProvided(text: "*markup*")
        test "is empty": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then, with every stack frame left out, the lines for test `is empty` begin:
        """
        - is empty
          [info] checked size
            java.lang.IllegalStateException: size unknown
            Cause: java.io.IOException: closed
          [note] a note
        """
```

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-097       | AC-032, AC-033, AC-034, AC-160                 |
| R-106       | AC-028, AC-029, AC-030, AC-031, AC-160, AC-122 |
