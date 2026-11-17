# Consolidated human-readable output for one JVM module: runs

This part contains the acceptance criteria for what the human-readable output shows of a run itself and of the root: the run's line, the output attached to the run, a run that aborts or stops, and the root's line. It is part of [runs-and-test-structure.md](runs-and-test-structure.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log, a run's line, the root's line and the summary line. [layout.md](layout.md) contains the criteria for where each line sits and for each line's text and colour.

## A run's line

```gherkin
Feature: A run's line

  Rule: A run that has output attached, or that aborted or stopped, shows the line `Run <n>:` under its module's line, where `<n>` numbers the module's runs from 1 in the order the runs started. Under the run's line sit the lines for the run's suites, then the run's output, then how the run ended where the run aborted or stopped
    Scenario: AC-035 A test's stdout line is shown under `Run 1:`
      Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
      And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
      And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
      And the test command is invoked as `mill scalatest/test core.test`
      And Mill colours its console output
      And human-readable output is compared without the prefix, such as `1] `, that Mill puts on each line the test command's own task writes
      And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      When the test command runs the module
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Run 1:
            CalculatorSuite:
              - adds two numbers
            [stdout] dividing now
        """

  Rule: The run's messages and the lines captured from the run's process sit in one sequence, in the order the library received them
    Scenario: AC-054 A captured line fed before one of the run's messages comes before that message
      Given the consolidation library is used directly, without Mill, with colour on
      And the library consolidates a run of the module `core.test`
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
        | stderr | `warn: slow`   |
      And the `dividing now` line is fed before the `AlertProvided` event, and the `warn: slow` line after it
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Run 1:
            CalculatorSuite:
              - adds two numbers
            [stdout] dividing now
            [alert] Test still running after 1 minute, 2 seconds: suite name: CalculatorSuite, test name: adds two numbers.
            [stderr] warn: slow
        """

  Rule: Captured stdout and stderr lines interleave in the order they were read
    Scenario: AC-036 Lines of both streams in one sequence
      Given the consolidation library is used directly, without Mill, with colour on
      And the library consolidates a run of the module `core.test`
      And an event log holding `run started`, then `run ended completed`
      And the run's captured output is:
        | stream | line                                    |
        | stderr | `SLF4J: No SLF4J providers were found.` |
        | stdout | `dividing now`                          |
        | stderr | `warn: about to divide`                 |
      And the captured lines are fed in that order, at any points among the event log's events
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Run 1:
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
      Then the lines under `Run 1:` hold `[stdout] dividing now` and a line `[stdout] <text>` whose text ends `Using Serial`
      And each of those two lines is `[stdout] ` followed by nothing but the line written

  Rule: Each stream's captured lines keep their order
    Scenario: AC-075 Many lines on both streams
      Given suite `ChattySuite`, class `samples.ChattySuite`, an `AnyFunSuite` whose one test, `chats`, labelled `- chats`, writes `out 1` to `out 2000` to stdout and `err 1` to `err 2000` to stderr, alternating between the streams, then succeeds
      And nothing else in the run writes to stdout or stderr
      When the test command runs the module
      Then the `[stdout]` lines under `Run 1:` are `out 1` to `out 2000`, in that order
      And the `[stderr]` lines under `Run 1:` are `err 1` to `err 2000`, in that order
```

## Runs that abort or stop

```gherkin
Feature: Runs that abort or stop

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: An aborted run shows the nodes open when it aborted as aborted, its not-run nodes, then `*** RUN ABORTED ***` last under the run's line
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
      And the captured line is fed after the `AlertProvided` event
      When the library consolidates it
      Then, with every stack frame left out and the line `An exception or error caused a run to abort: Java heap space` keeping the message's trailing space, the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Run 1:
            AbortingFunSpec:
              A Stack *** ABORTED ***
                when empty *** ABORTED ***
                  - is empty
                  - throws on pop *** ABORTED ***
                  - has size 0 *** NOT RUN ***
                when full *** NOT RUN ***
                  (1 test not run)
              samples.AbortingFunSpec *** ABORTED ***
            [alert] Test still running after 1 minute, 2 seconds: suite name: AbortingFunSpec, test name: A Stack when empty throws on pop.
            [stderr] OpenJDK 64-Bit Server VM warning: CodeCache is full.
            *** RUN ABORTED ***
              An exception or error caused a run to abort: Java heap space 
              java.lang.OutOfMemoryError: Java heap space
        """

  Rule: A stopped run shows its not-run nodes, and `*** RUN STOPPED ***` last under the run's line
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
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Run 1:
            OuterSuites:
              Inner1Suite:
                - inner one
              Inner2Suite:
                samples.Inner2Suite *** NOT RUN ***
                (1 test not run)
            *** RUN STOPPED ***
        """
```

## The root

```gherkin
Feature: The root

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`
    And an event log holding:
      """
      run started
      structure
        suite "SqrtPropSpec", class "samples.SqrtPropSpec"
          test "sqrt of a square is the root", label "- sqrt of a square is the root"
      suite "SqrtPropSpec": SuiteStarting
      test "sqrt of a square is the root": TestStarting
      test "sqrt of a square is the root": TestSucceeded
      suite "SqrtPropSpec": SuiteCompleted
      run ended completed
      """

  Rule: Where output is attached to the root, the human-readable output begins with the unindented line `Consolidated tests:`. Under that line sit the lines for each module, then the output attached to the root
    Scenario: AC-172 A runner that is not a run writes a line
      Given the root's captured output is:
        | stream | line                |
        | stdout | `discovering tests` |
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        Consolidated tests:
          [core.test]
            SqrtPropSpec:
              - sqrt of a square is the root
          [stdout] discovering tests
        """
      And `Consolidated tests:` and `[core.test]` are cyan, and `[stdout] discovering tests` is uncoloured
      And the consolidation state the library gives is *ended*

  Rule: Where a failure is attached to the root, the human-readable output begins with the unindented line `Consolidated tests:`. The last line under that line is `*** CONSOLIDATED TESTS ABORTED ***`, with the failure's message under it. The failure leaves the consolidation state failed
    Scenario: AC-173 A runner that is not a run fails
      Given a failure attached to the root whose message is `test discovery failed`
      When the library consolidates it
      Then the human-readable output, apart from its summary line, is:
        """
        Consolidated tests:
          [core.test]
            SqrtPropSpec:
              - sqrt of a square is the root
          *** CONSOLIDATED TESTS ABORTED ***
            test discovery failed
        """
      And `Consolidated tests:` and `[core.test]` are cyan, and `*** CONSOLIDATED TESTS ABORTED ***` and `test discovery failed` are red
      And the consolidation state the library gives is *ended, failed*
```

## Requirements covered

| Requirement                    | Criteria                                               |
|--------------------------------|--------------------------------------------------------|
| R-043                          | AC-054, AC-036, AC-075                                 |
| R-072                          | AC-172, AC-173                                         |
| R-074b                         | AC-037, AC-038                                         |
| R-076                          | AC-074                                                 |
| R-082                          | AC-035, AC-054, AC-036, AC-037, AC-038, AC-172, AC-173 |
| R-087                          | AC-074                                                 |
| R-095a                         | AC-037, AC-038                                         |
| R-095b                         | AC-037                                                 |
| R-097a                         | AC-035, AC-054, AC-036, AC-074, AC-075                 |
| R-097b                         | AC-035, AC-074, AC-172                                 |
| R-098d                         | AC-037, AC-038                                         |
| R-098f                         | AC-173                                                 |
| R-101                          | AC-038                                                 |
| R-106a                         | AC-035, AC-172                                         |
| R-106b                         | AC-035, AC-037, AC-038, AC-172, AC-173                 |
| R-106c                         | AC-037, AC-038                                         |
| R-106d                         | AC-173                                                 |
| R-106e                         | AC-172                                                 |
| R-106f                         | AC-172, AC-173                                         |
| R-137                          | AC-173                                                 |
| `../../JSON-LAYOUT.md` § Nodes | AC-037                                                 |
