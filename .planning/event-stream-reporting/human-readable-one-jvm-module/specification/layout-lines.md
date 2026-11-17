# Consolidated human-readable output for one JVM module: the layout's lines

This part contains the acceptance criteria for the text and colour of each scope, test, module, run, abort, count and output line, for the text and colour of failure, cancel and abort messages, for the summary line, and for Mill's prefix. It is part of [layout.md](layout.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix. Each row describes the human-readable output that the library, used directly with colour on and module `core.test`, writes when it consolidates an event log in which the node's suite last registered the node with the label `<label>`, whether or not that label begins `- `, and in which an execution of the node has the row's outcome, or the node has no execution and has the row's outcome. Where a scope or test has several executions, each row describes each execution's line.

A test is ignored where an execution of the test closed with `TestIgnored`. A test that has no execution, is not attributed and is marked ignored is also ignored, and is not aborted with reason *not run* (§ The summary line, AC-194).

| ID     | Node and outcome                                                                                              | Line                       | Colour                  |
|--------|---------------------------------------------------------------------------------------------------------------|----------------------------|-------------------------|
| AC-017 | test, succeeded                                                                                               | `<label>`                  | green                   |
| AC-018 | test, failed                                                                                                  | `<label> *** FAILED ***`   | red                     |
| AC-019 | test, ignored                                                                                                 | `<label> !!! IGNORED !!!`  | yellow                  |
| AC-020 | test, pending                                                                                                 | `<label> (pending)`        | yellow                  |
| AC-021 | test, cancelled                                                                                               | `<label> !!! CANCELED !!!` | yellow                  |
| AC-022 | test, aborted with reason *not run*                                                                           | `<label> *** NOT RUN ***`  | red                     |
| AC-023 | test, aborted after it started                                                                                | `<label> *** ABORTED ***`  | red                     |
| AC-024 | scope, no outcome                                                                                             | `<label>`                  | green                   |
| AC-025 | scope, pending                                                                                                | `<label> (pending)`        | yellow                  |
| AC-026 | scope, aborted with reason *not run*                                                                          | `<label> *** NOT RUN ***`  | label green, suffix red |
| AC-027 | scope, aborted because its suite or its run aborted while it was open                                         | `<label> *** ABORTED ***`  | label green, suffix red |

| Requirement                    | Criteria                                                                               |
|--------------------------------|----------------------------------------------------------------------------------------|
| R-046a                         | AC-019                                                                                 |
| R-095a                         | AC-022, AC-026                                                                         |
| R-095b                         | AC-023, AC-027                                                                         |
| R-098a                         | AC-017, AC-018, AC-019, AC-020, AC-021                                                 |
| R-098b                         | AC-024, AC-025                                                                         |
| R-106a                         | AC-017, AC-018, AC-019, AC-020, AC-021, AC-022, AC-023, AC-024, AC-025, AC-026, AC-027 |
| R-106d                         | AC-018, AC-019, AC-020, AC-021, AC-022, AC-023, AC-025, AC-026, AC-027                 |
| R-106f                         | AC-017, AC-018, AC-019, AC-020, AC-021, AC-022, AC-023, AC-024, AC-025, AC-026, AC-027 |
| `../../JSON-LAYOUT.md` § Nodes | AC-027                                                                                 |

## Module, run, abort, count and output lines

Each row describes the human-readable output that the library, used directly with colour on and module `core.test`, writes when it consolidates an event log containing the row's node or event, with the row's captured line, where the row has one, in the run's captured output. `<class name>` is the class name the run first registered for the suite, and `<module path>` is `core.test`, the module the library is given for the run. `<n>` is the run's number among its module's runs, counted from 1 in the order the runs started; the event log's one run is run 1.

| ID     | Line                                                                      | Text                                                                                            | Sits under                                                                                            | Colour                          |
|--------|---------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------|---------------------------------|
| AC-045 | a module's line                                                           | `[<module path>]`                                                                               | `Consolidated tests:` where the human-readable output shows the root; otherwise nothing: not indented | cyan                            |
| AC-046 | a run's line, shown where the run aborted, stopped or has output attached | `Run <n>:`                                                                                      | its module's line, ahead of the lines for the run's suites, which sit under the run's line            | cyan                            |
| AC-012 | a suite's abort line                                                      | `<class name> *** ABORTED ***`, or `<class name> *** NOT RUN ***` where its reason is *not run* | the suite's name line                                                                                 | red                             |
| AC-013 | an aborted suite's abort message lines and its throwable's first line     | ScalaTest 3.2.20's                                                                              | the suite's abort line                                                                                | red                             |
| AC-016 | a collapsed subtree's count line                                          | `(<counts> not run)`, where `<counts>` counts the suites, scopes and tests at any depth below the subtree's root that are aborted with reason *not run*, in that order. An attributed node and an ignored test are not counted. Each count reads `<n> suite`, `<n> scope` or `<n> test`, with `s` added where `<n>` is not 1. Counts are joined by `, `, and a kind with none is left out | its root's line, a suite's name line or a scope's line, after every other line under the root's line | red                             |
| AC-014 | a message attached to the run                                             | `[<kind>] <text>`, where `<kind>` is `info`, `note`, `alert` or `markup`                        | its run's line, after the lines for the run's suites                                                  | yellow for an alert, else green |
| AC-047 | a line captured from stdout                                               | `[stdout] <text>`                                                                               | its run's line, after the lines for the run's suites                                                  | uncoloured                      |
| AC-048 | a line captured from stderr                                               | `[stderr] <text>`                                                                               | its run's line, after the lines for the run's suites                                                  | yellow                          |
| AC-050 | an aborted run's end                                                      | `*** RUN ABORTED ***`                                                                           | its run's line, after every other line under the run's line                                           | red                             |
| AC-015 | a run abort's message lines and its throwable's first line                | ScalaTest 3.2.20's                                                                              | `*** RUN ABORTED ***`                                                                                 | red                             |
| AC-051 | a stopped run's end                                                       | `*** RUN STOPPED ***`                                                                           | its run's line, after every other line under the run's line                                           | red                             |

| Requirement | Criteria                                                                       |
|-------------|--------------------------------------------------------------------------------|
| R-082       | AC-046, AC-014, AC-047, AC-048, AC-050, AC-015, AC-051                         |
| R-095a      | AC-012                                                                         |
| R-096a      | AC-045, AC-046                                                                 |
| R-097a      | AC-014, AC-047, AC-048                                                         |
| R-097b      | AC-014, AC-047, AC-048                                                         |
| R-098c      | AC-012                                                                         |
| R-098d      | AC-050, AC-051                                                                 |
| R-106a      | AC-045, AC-046                                                                 |
| R-106b      | AC-045, AC-046, AC-012, AC-013, AC-016, AC-014, AC-047, AC-048, AC-050, AC-015, AC-051 |
| R-106c      | AC-016                                                                         |
| R-106d      | AC-012, AC-013, AC-050, AC-015, AC-051                                         |
| R-106e      | AC-014, AC-047, AC-048                                                         |
| R-106f      | AC-045, AC-046, AC-012, AC-013, AC-016, AC-014, AC-047, AC-048, AC-050, AC-015, AC-051 |

## Failure, cancel and abort messages

```gherkin
Feature: Failure, cancel and abort messages

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: A message whose throwable is a `StackDepth` ends with the failed code's file and line, and the throwable shows no line of its own
    Scenario: AC-123 A cancelled test's message
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
        test "have size 0": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack" at StackFlatSpec.scala:7)
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for test `have size 0` are:
        """
        - must have size 0 !!! CANCELED !!!
          no stack (StackFlatSpec.scala:7)
          [info] checked
          [note] a note
          [alert] an alert
        """

    Scenario: AC-125 A suite aborted with a `StackDepth` throwable
      Given an event log holding:
        """
        run started
        structure
          suite "SetupFailsSpec", class "samples.SetupFailsSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
              test "write", label "- should write"
        suite "SetupFailsSpec": SuiteStarting
        suite "SetupFailsSpec": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - database unreachable", throwable: org.scalatest.exceptions.TestFailedException "database unreachable" at SetupFailsSpec.scala:5)
        run ended completed
        """
      When the library consolidates it
      Then the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            Exception encountered when invoking run on a nested suite - database unreachable (SetupFailsSpec.scala:5)
          (1 scope, 2 tests not run)
        """

  Rule: A failure's analysis follows its message, after an `Analysis:` line
    Scenario: AC-124 A failure with analysis
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
        test "have size 0": TestFailed(throwable: org.scalatest.exceptions.TestFailedException "Stack(1) did not equal Stack(0)" at StackFlatSpec.scala:9, analysis: ["Stack(size: 1 -> 0)"])
        scope "A Stack": ScopeClosed
        suite "StackFlatSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for test `have size 0` begin:
        """
        - must have size 0 *** FAILED ***
          Stack(1) did not equal Stack(0) (StackFlatSpec.scala:9)
          Analysis:
          Stack(size: 1 -> 0)
          [info] checked
        """
```

| Requirement | Criteria               |
|-------------|------------------------|
| R-106c      | AC-125                 |
| R-106d      | AC-123, AC-124, AC-125 |
| R-106e      | AC-123, AC-124         |

## The summary line

The summary line is the last line of the human-readable output, after every other line, the root's lines included. It is not indented. Its text is:

`Suites: <completed> of <suites expected> completed, <aborted> aborted. Tests: <run> of <tests expected> run: succeeded <succeeded>, failed <failed>, canceled <canceled>, ignored <ignored>, pending <pending>`

Each count is a decimal number, taken over every suite and test of the node tree, nested suites and the suites a test runs included. A suite or test counts once for each of its executions, and once where it has no execution. An attributed suite or test is not counted. A scope, a run, a module and the root are not counted, whatever their outcomes.

| Count               | What it counts                                                                            |
|---------------------|-------------------------------------------------------------------------------------------|
| `<suites expected>` | every suite                                                                               |
| `<completed>`       | each execution of a suite that has no outcome                                             |
| `<aborted>`         | each execution of a suite that is aborted                                                 |
| `<tests expected>`  | every test that is not ignored                                                            |
| `<run>`             | each execution of a test that succeeded, failed, was cancelled or is pending              |
| `<succeeded>`       | each execution of a test that succeeded                                                   |
| `<failed>`          | each execution of a test that failed                                                      |
| `<canceled>`        | each execution of a test that was cancelled                                               |
| `<ignored>`         | every test that is ignored                                                                |
| `<pending>`         | each execution of a test that is pending                                                  |

- A suite aborted with reason *not run* counts in `<suites expected>` alone. `<suites expected>` is therefore `<completed>`, `<aborted>` and the suites not run, together.
- A test is ignored, for these counts, in each execution of the test that closed with `TestIgnored`, and where the test has no execution and is marked ignored (`../SPECIFICATION.md` § Acceptance criteria). An ignored test counts in `<ignored>` alone, in neither `<run>` nor `<tests expected>`. That holds whether or not the test's suite started, and whether or not the human-readable output shows a line for the test.
- A test that is marked ignored and has an execution that did not close with `TestIgnored` counts as that execution's outcome counts, and not in `<ignored>`.
- A test aborted with reason *not run*, and an execution of a test aborted after it started, each count in `<tests expected>` alone. `<run>` is the sum of `<succeeded>`, `<failed>`, `<canceled>` and `<pending>`, and `<tests expected>` less `<run>` is the tests not run or aborted.

Each of these parts of the summary line, its word and its count together with the space between them, has the colour the human-readable output gives a line of that kind, whatever the count, 0 included:

| Part                    | Colour | As                      |
|-------------------------|--------|-------------------------|
| `<aborted> aborted`     | red    | a suite's abort line    |
| `succeeded <succeeded>` | green  | a succeeded test's line |
| `failed <failed>`       | red    | a failed test's line    |
| `canceled <canceled>`   | yellow | a cancelled test's line |
| `ignored <ignored>`     | yellow | an ignored test's line  |
| `pending <pending>`     | yellow | a pending test's line   |

Every other character of the summary line is cyan, as ScalaTest 3.2.20 colours the count lines of its end-of-run summary: `Suites: <completed> of <suites expected> completed, `, the `. Tests: <run> of <tests expected> run: ` that follows `<aborted> aborted`, and each `, ` between two parts.

On the test command's stdout the summary line carries Mill's prefix, as every other line of the human-readable output does (§ Mill's prefix and colour, AC-044).

```gherkin
Feature: The summary line
  The summary line is `Suites: <completed> of <suites expected> completed, <aborted> aborted. Tests: <run> of <tests expected> run: succeeded <succeeded>, failed <failed>, canceled <canceled>, ignored <ignored>, pending <pending>`.
  A suite or test counts once for each of its executions, and once where it has no execution. An attributed suite or test is not counted.
  A test is ignored in each execution of the test that closed with `TestIgnored`, and where the test has no execution and the latest `structure` that contains the test marks the test ignored. An ignored test counts in `<ignored>` alone, in neither `<run>` nor `<tests expected>`.
  A part of the summary line is a word and its count, with the space between them, such as `failed 1` or `1 aborted`.

  Background:
    Given the consolidation library is used directly, without Mill, with colour on
    And the library consolidates a run of the module `core.test`

  Rule: The human-readable output ends with one unindented summary line, which counts the suites and tests expected, the suites that completed and aborted, the tests run under each outcome, and the tests ignored, and in which each part has the colour of a line of its kind and every other character is cyan
    Scenario: AC-171 A run with a test of every outcome, an aborted suite and a suite not run
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "pops", label "- pops"
            test "peeks", label "- peeks", ignored
            test "grows", label "- grows"
            test "shrinks", label "- shrinks"
        structure
          suite "QueueFunSuite", class "samples.QueueFunSuite"
            test "enqueues", label "- enqueues"
            test "dequeues", label "- dequeues"
        structure
          suite "HeapFunSuite", class "samples.HeapFunSuite"
            test "sifts", label "- sifts"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        test "pushes": TestSucceeded
        test "pops": TestStarting
        test "pops": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "peeks": TestIgnored
        test "grows": TestStarting
        test "grows": TestPending
        test "shrinks": TestStarting
        test "shrinks": TestCanceled(throwable: org.scalatest.exceptions.TestCanceledException "no stack")
        suite "StackFunSuite": SuiteCompleted
        suite "QueueFunSuite": SuiteStarting
        test "enqueues": TestStarting
        test "enqueues": TestSucceeded
        suite "QueueFunSuite": SuiteAborted(throwable: java.lang.IllegalStateException "queue closed")
        run ended completed
        """
      When the library consolidates it
      Then the last line of the human-readable output is the following line, which is not indented and follows the last of the lines for `core.test`:
        """
        Suites: 1 of 3 completed, 1 aborted. Tests: 6 of 8 run: succeeded 3, failed 1, canceled 1, ignored 1, pending 1
        """
      And in that line `1 aborted` and `failed 1` are red, `succeeded 3` is green, and `canceled 1`, `ignored 1` and `pending 1` are yellow
      And every other character of that line is cyan

    Scenario: AC-175 A run that aborts while a suite and a test are open
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "pops", label "- pops"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        run ended aborted
        """
      When the library consolidates it
      Then the last line of the human-readable output is the following line, which is not indented:
        """
        Suites: 0 of 1 completed, 1 aborted. Tests: 1 of 3 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
        """
      And in that line `1 aborted` and `failed 0` are red, `succeeded 1` is green, and `canceled 0`, `ignored 0` and `pending 0` are yellow
      And every other character of that line is cyan

    Scenario: AC-176 A test that runs twice counts twice
      Given an event log holding:
        """
        run started
        structure
          suite "RetrySuite", class "samples.RetrySuite"
            test "flaky", label "- flaky"
        suite "RetrySuite": SuiteStarting
        test "flaky": TestStarting
        test "flaky": TestFailed(throwable: java.lang.IllegalStateException "not yet")
        test "flaky" execution 2: TestStarting
        test "flaky" execution 2: TestSucceeded
        suite "RetrySuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the last line of the human-readable output is the following line, which is not indented:
        """
        Suites: 1 of 1 completed, 0 aborted. Tests: 2 of 2 run: succeeded 1, failed 1, canceled 0, ignored 0, pending 0
        """
      And in that line `0 aborted` and `failed 1` are red, `succeeded 1` is green, and `canceled 0`, `ignored 0` and `pending 0` are yellow
      And every other character of that line is cyan

  Rule: A part of the summary line keeps its colour where its count is 0, so the summary line of a run in which nothing failed still has red parts
    Scenario: AC-174 A run whose tests succeed, are ignored or are pending
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "pushes", label "- pushes"
            test "peeks", label "- peeks", ignored
            test "grows", label "- grows"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pushes": TestStarting
        test "pushes": TestSucceeded
        test "peeks": TestIgnored
        test "grows": TestStarting
        test "grows": TestPending
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the last line of the human-readable output is the following line, which is not indented and follows the last of the lines for `core.test`:
        """
        Suites: 1 of 1 completed, 0 aborted. Tests: 3 of 3 run: succeeded 2, failed 0, canceled 0, ignored 1, pending 1
        """
      And in that line `0 aborted` and `failed 0` are red, `succeeded 2` is green, and `canceled 0`, `ignored 1` and `pending 1` are yellow
      And every other character of that line is cyan

  Rule: A test that has no execution and is marked ignored is ignored: it is not aborted with reason *not run*, and it counts in `<ignored>` alone
    Scenario: AC-194 A marked test with no execution, in a suite that completed
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "peeks", label "- peeks", ignored
            test "pops", label "- pops"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `StackFunSuite` are:
        """
        StackFunSuite:
          - is empty
          - peeks !!! IGNORED !!!
          - pops *** NOT RUN ***
        """
      And `- peeks !!! IGNORED !!!` is yellow
      And the last line of the human-readable output is:
        """
        Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 2 run: succeeded 1, failed 0, canceled 0, ignored 1, pending 0
        """

    Scenario: AC-195 A marked test in a suite that did not start
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
        structure
          suite "HeapFunSuite", class "samples.HeapFunSuite"
            test "sifts", label "- sifts"
            test "merges", label "- merges", ignored
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `HeapFunSuite` are:
        """
        HeapFunSuite:
          samples.HeapFunSuite *** NOT RUN ***
          (1 test not run)
        """
      And the line `(1 test not run)` counts `sifts`, and does not count `merges`, which is ignored
      And the last line of the human-readable output is:
        """
        Suites: 1 of 2 completed, 0 aborted. Tests: 1 of 2 run: succeeded 1, failed 0, canceled 0, ignored 1, pending 0
        """

  Rule: A test whose execution closed with `TestIgnored` is ignored, whether or not it is marked ignored
    Scenario: AC-196 A `TestIgnored` for a test that no `structure` marks ignored
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "is empty", label "- is empty"
            test "peeks", label "- peeks"
        suite "StackFunSuite": SuiteStarting
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "peeks": TestIgnored
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `StackFunSuite` are:
        """
        StackFunSuite:
          - is empty
          - peeks !!! IGNORED !!!
        """
      And the last line of the human-readable output is:
        """
        Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 1, pending 0
        """

  Rule: A test that is marked ignored and ran has its execution's outcome, and is not counted as ignored
    Scenario: AC-197 A marked test that succeeded
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSuite", class "samples.StackFunSuite"
            test "peeks", label "- peeks", ignored
        suite "StackFunSuite": SuiteStarting
        test "peeks": TestStarting
        test "peeks": TestSucceeded
        suite "StackFunSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `StackFunSuite` are:
        """
        StackFunSuite:
          - peeks
        """
      And `- peeks` is green
      And the last line of the human-readable output is:
        """
        Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0
        """
```

| Requirement | Criteria                                                       |
|-------------|----------------------------------------------------------------|
| R-046a      | AC-194                                                         |
| R-095a      | AC-194, AC-195                                                 |
| R-098a      | AC-194, AC-196, AC-197                                         |
| R-106c      | AC-195                                                         |
| R-141       | AC-171, AC-174, AC-175, AC-176, AC-194, AC-195, AC-196, AC-197 |
| R-106f      | AC-171, AC-174, AC-175, AC-176                                 |

## Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Background:
    Given a Mill 1.3.1 build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release under test
    And the build's `build.mill` header adds the Mill plugin, `com.edc::scalatest-mill` at that release, to its `mvnDeps`
    And `core.test` has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test core.test`

  Rule: On stdout, each line of the human-readable output carries Mill's prefix
    Scenario: AC-044 Every line of the human-readable output is prefixed
      Given the module is at Mill's default `testLogLevel`
      And suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      And Mill colours its console output
      When the test command runs the module
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation
      And the last line of the human-readable output on stdout, with every ANSI escape sequence removed, is that prefix followed by `Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0`
      And in that line `0 aborted` and `failed 0` are red, `succeeded 1` is green, `canceled 0`, `ignored 0` and `pending 0` are yellow, and every other character after the prefix is cyan

  Rule: Human-readable output on stdout is coloured whether or not Mill colours its own console output
    Scenario: AC-053 The same output with Mill's colour on and off
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      When the test command runs the module once with Mill's `--color true` and once with Mill's `--color false`
      Then each of the two human-readable outputs, with Mill's prefix and every ANSI escape sequence removed from each line, and apart from its summary line, is:
        """
        [core.test]
          Run 1:
            CalculatorSuite:
              - adds two numbers
            [stdout] dividing now
        """
      And in each of the two human-readable outputs, `[core.test]` and `Run 1:` are cyan, `- adds two numbers` is green and `[stdout] dividing now` is uncoloured
```

| Requirement | Criteria |
|-------------|----------|
| R-106f      | AC-053   |
| R-106h      | AC-044   |
| R-141       | AC-044   |
