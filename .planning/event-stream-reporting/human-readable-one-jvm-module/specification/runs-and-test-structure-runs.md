# Consolidated human-readable output for one JVM module: runs

This part contains the acceptance criteria for what the human-readable output shows of a run itself: the run's block, the output captured against the run, and a run that aborts or stops. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## A run's block

```gherkin
Feature: A run's block

  Rule: A run's block follows the suites of the run's test run label, and contains the run's messages, then the output captured against the run, then how the run ended where the run aborted or stopped
    Scenario: AC-035 A test's stdout line is shown in the block
      Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
      And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
      And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
      And the test command is invoked as `mill scalatest/test core.test`
      And Mill colours its console output
      And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes
      And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          CalculatorSuite:
            - adds two numbers
          Output outside any suite:
            [stdout] dividing now
        """

    Scenario: AC-054 The run's messages come before its captured output
      Given the consolidation library is used directly, without Mill, with colour on
      And the library consolidates a run under the test run label `core.test`
      And an event log holding:
        """
        run started
        structure
          suite "CalculatorSuite", class "samples.CalculatorSuite"
            test "adds two numbers", label "- adds two numbers"
        suite "CalculatorSuite": SuiteStarting
        test "adds two numbers": TestStarting
        run: AlertProvided(message: "Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.")
        test "adds two numbers": TestSucceeded
        suite "CalculatorSuite": SuiteCompleted
        run ended completed
        """
      And the run's captured output is:
        | stream | line           |
        | stdout | `dividing now` |
      And the `dividing now` line is fed before the `AlertProvided` event
      When the library consolidates it
      Then the run's block is:
        """
        Output outside any suite:
          [alert] Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.
          [stdout] dividing now
        """

  Rule: Captured stdout and stderr lines interleave in the order they were read
    Scenario: AC-036 Lines of both streams in one sequence
      Given the consolidation library is used directly, without Mill, with colour on
      And the library consolidates a run under the test run label `core.test`
      And an event log holding `run started`, then `run ended completed`
      And the run's captured output is:
        | stream | line                                    |
        | stderr | `SLF4J: No SLF4J providers were found.` |
        | stdout | `dividing now`                          |
        | stderr | `warn: about to divide`                 |
      And the captured lines are fed in that order, at any points among the event log's events
      When the library consolidates it
      Then the run's block is:
        """
        Output outside any suite:
          [stderr] SLF4J: No SLF4J providers were found.
          [stdout] dividing now
          [stderr] warn: about to divide
        """
```

## Captured output

```gherkin
Feature: Output captured against the run

  Background:
    Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
    And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
    And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test core.test`
    And Mill colours its console output
    And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes

  Rule: Output captured only against the run is shown without claiming that it is test output or worker output
    Scenario: AC-074 A test's line and the JVM's own line take one form
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And the module's `forkArgs` hold `-XX:+UseSerialGC -Xlog:gc:stdout`, so the worker's JVM writes a line of its own ending `Using Serial` to stdout
      When the test command runs the module
      Then the run's block holds `[stdout] dividing now` and a line `[stdout] <text>` whose text ends `Using Serial`
      And each of those two lines is `[stdout] ` followed by nothing but the line written

  Rule: Each stream's captured lines keep their order
    Scenario: AC-075 Many lines on both streams
      Given suite `ChattySuite`, class `samples.ChattySuite`, an `AnyFunSuite` whose one test, `chats`, labelled `- chats`, writes `out 1` to `out 2000` to stdout and `err 1` to `err 2000` to stderr, alternating between the streams, then succeeds
      And nothing else in the run writes to stdout or stderr
      When the test command runs the module
      Then the run's block's `[stdout]` lines are `out 1` to `out 2000`, in that order
      And the run's block's `[stderr]` lines are `err 1` to `err 2000`, in that order
```

## Runs that abort or stop

```gherkin
Feature: Runs that abort or stop

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run under the test run label `core.test`

  Rule: An aborted run shows the nodes open when it aborted as aborted, its not-run nodes, then `*** RUN ABORTED ***` last in its block
    Scenario: AC-037 A test's fatal error aborts the run
      Given an event log holding:
        """
        run started
        structure
          suite "AbortingFunSpec", class "samples.AbortingFunSpec"
            scope "A Stack", label "A Stack"
              scope "when empty", label "when empty"
                test "is empty", label "- is empty"
                test "throws on pop", label "- throws on pop"
                test "has size 0", label "- has size 0"
              scope "when full", label "when full"
                test "pops last pushed", label "- pops last pushed"
        suite "AbortingFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        scope "when empty": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "throws on pop": TestStarting
        run: AlertProvided(message: "Test still running after 1 minute, 2 seconds: suite name: AbortingFunSpec, test name: A Stack when empty throws on pop.")
        run: RunAborted(message: "An exception or error caused a run to abort: Java heap space ", throwable: java.lang.OutOfMemoryError "Java heap space")
        run ended aborted
        """
      And the run's captured output is:
        | stream | line                                                   |
        | stderr | `OpenJDK 64-Bit Server VM warning: CodeCache is full.` |
      And the captured line is fed at any point among the event log's events
      When the library consolidates it
      Then, with every stack frame left out and the line `An exception or error caused a run to abort: Java heap space` keeping the message's trailing space, the human-readable output is:
        """
        [core.test]
          AbortingFunSpec:
            A Stack *** ABORTED ***
              when empty *** ABORTED ***
                - is empty
                - throws on pop *** ABORTED ***
                - has size 0 *** NOT RUN ***
              when full *** NOT RUN ***
                (1 test not run)
            samples.AbortingFunSpec *** ABORTED ***
          Output outside any suite:
            [alert] Test still running after 1 minute, 2 seconds: suite name: AbortingFunSpec, test name: A Stack when empty throws on pop.
            [stderr] OpenJDK 64-Bit Server VM warning: CodeCache is full.
            *** RUN ABORTED ***
              An exception or error caused a run to abort: Java heap space 
              java.lang.OutOfMemoryError: Java heap space
        """

  Rule: A stopped run shows its not-run nodes, and `*** RUN STOPPED ***` in its block
    Scenario: AC-038 A run stops between two nested suites
      Given an event log holding:
        """
        run started
        structure
          suite "OuterSuites", class "samples.OuterSuites"
            suite "Inner1Suite", class "samples.Inner1Suite"
              test "inner one", label "- inner one"
            suite "Inner2Suite", class "samples.Inner2Suite"
              test "inner two", label "- inner two"
        suite "OuterSuites": SuiteStarting
        suite "Inner1Suite": SuiteStarting
        test "inner one": TestStarting
        test "inner one": TestSucceeded
        suite "Inner1Suite": SuiteCompleted
        suite "OuterSuites": SuiteCompleted
        run: RunStopped
        run ended stopped
        """
      When the library consolidates it
      Then the human-readable output is:
        """
        [core.test]
          OuterSuites:
            Inner1Suite:
              - inner one
            Inner2Suite:
              samples.Inner2Suite *** NOT RUN ***
              (1 test not run)
          Output outside any suite:
            *** RUN STOPPED ***
        """
```

## Requirements covered

| Requirement                       | Criteria                               |
|-----------------------------------|----------------------------------------|
| R-011                             | AC-036                                 |
| R-012                             | AC-035, AC-054, AC-036                 |
| R-042                             | AC-035, AC-074, AC-075                 |
| R-043                             | AC-075                                 |
| R-074                             | AC-037, AC-038                         |
| R-082                             | AC-035, AC-054, AC-036, AC-037, AC-038 |
| R-086                             | AC-074                                 |
| R-100                             | AC-037                                 |
| R-101                             | AC-038                                 |
| R-107                             | AC-037, AC-038                         |
| `../../JSON-LAYOUT.md` § Nodes    | AC-037                                 |
