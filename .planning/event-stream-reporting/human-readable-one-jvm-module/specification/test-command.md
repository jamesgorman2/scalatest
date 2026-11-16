# Consolidated human-readable output for one JVM module: the test command

This document contains the acceptance criteria for the test command: its arguments, how it runs the module, its result, what else reaches the console, and Mill's own test tasks and BSP server beside it. It is part of [../SPECIFICATION.md](../SPECIFICATION.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Output arguments

```gherkin
Feature: The test command's output arguments
  A line of human-readable output is given without the prefix Mill puts on every line the test command's own task writes, such as `1] `.
  Where a scenario does not fix a line's text or colour, the line has the text and colour ScalaTest 3.2.20 prints.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments
    And Mill colours its own console output
    And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
    And nothing else in the run writes to stdout or stderr or sends a message outside a suite

  Rule: With no `-o`, or with `-oH`, the test command writes human-readable output and no other consolidated output to stdout; with a bare `-o`, it writes no consolidated output to stdout
    Scenario Outline: AC-076 <arguments>
      When the test command runs the module with <arguments>
      Then the console holds <consolidated output>
      And the test command succeeds

      Examples:
        | arguments | consolidated output                                                                                                                                                                               |
        | no `-o`   | the human-readable output whose lines are `[core.test]`, `  CalculatorSuite:`, `    - adds two numbers`, `  Output outside any suite:` and `    [stdout] dividing now`, and no other line the test command's own task writes |
        | `-oH`     | the human-readable output whose lines are `[core.test]`, `  CalculatorSuite:`, `    - adds two numbers`, `  Output outside any suite:` and `    [stdout] dividing now`, and no other line the test command's own task writes |
        | `-o`      | no line of human-readable output, and no line holding `dividing now`                                                                                                                              |

  Rule: An output argument other than `-oH` or a bare `-o` fails the test command before any test starts, with no consolidated output
    Scenario Outline: AC-077 <arguments>
      When the test command runs the module with <arguments>, which hold <what>
      Then the test command fails before any test starts
      And the console holds no line of human-readable output
      And no file `report.txt` is written

      Examples:
        | arguments            | what                                                                     |
        | `-oP`                | an output letter other than `H`                                          |
        | `-oJ`                | an output letter other than `H`                                          |
        | `-oHP`               | an output letter other than `H`, beside `H`                              |
        | `-oHd`               | `d`, a lowercase character that asks for durations                       |
        | `-oHy`               | `y`, a lowercase character that neither formats nor filters the output   |
        | `-fH report.txt`     | an `-f`                                                                  |
        | `-oH -fJ report.txt` | an `-f`, beside `-oH`                                                    |
```

| Requirement                       | Criteria |
|-----------------------------------|----------|
| R-048                             | AC-076   |
| R-055                             | AC-076   |
| R-025                             | AC-076   |
| `../SPECIFICATION.md` § Scope     | AC-077   |

## The module's own ScalaTest arguments

```gherkin
Feature: The module's own ScalaTest arguments
  A line of human-readable output is given without the prefix Mill puts on every line the test command's own task writes, such as `1] `.
  Where a scenario does not fix a line's text or colour, the line has the text and colour ScalaTest 3.2.20 prints.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments
    And Mill colours its own console output

  Rule: A module's own ScalaTest argument whose flag the JVM `org.scalatest.tools.Runner` does not recognise fails the test command before any test starts, naming that argument
    Scenario: AC-078 An unrecognised flag
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And the module's own ScalaTest arguments are `-l samples.Slow -x`
      When the test command runs the module
      Then the test command fails before any test starts, and its failure names `-x`
      And the console holds no line of human-readable output

  Rule: A module's own ScalaTest argument whose flag is `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W` reaches the run with its values, even a value that looks like a flag
    Scenario: AC-079 A test-name substring that begins with `-`
      Given suite `FlagSuite`, class `samples.FlagSuite`, an `AnyFunSuite` with tests `parses -y` and `parses -x`, labelled `- parses -y` and `- parses -x`, each succeeding where it runs
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      And the module's own ScalaTest arguments are `-z -y`
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          FlagSuite:
            - parses -y
        """

  Rule: A module's own ScalaTest argument whose flag the JVM `org.scalatest.tools.Runner` recognises, other than `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, is withheld with its values
    Scenario Outline: AC-080 <arguments> has no effect
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And suite `OtherSuite`, class `samples.OtherSuite`, an `AnyFunSuite` whose one test, `other`, labelled `- other`, succeeds
      And the module's own ScalaTest arguments are <arguments>
      When the test command runs the module
      Then the human-readable output is the one the test command writes for these suites when the module has no ScalaTest arguments of its own
      And no line on the console outside the human-readable output holds `adds two numbers` or `Run completed in`

      Examples:
        | arguments               |
        | `-oD`                   |
        | `-eD`                   |
        | `-s samples.OtherSuite` |
```

| Requirement | Criteria               |
|-------------|------------------------|
| R-125       | AC-078, AC-079, AC-080 |
| R-041       | AC-080                 |
| R-063       | AC-080                 |

## Module selectors

```gherkin
Feature: Module selectors

  Background:
    Given a build whose test modules test with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing any module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command is invoked as `mill scalatest/test`, followed by its module selectors and arguments

  Rule: The test command fails unless it is given one module selector that resolves to one JVM test module
    Scenario Outline: AC-081 <selectors>
      Given the build's test modules are `core.test` and `util.test`, both JVM test modules, and `coreJS.test`, a Scala.js test module
      And each holds suite `AddSuite`, class `samples.discovered.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds
      When the test command is given <selectors>, which are <what>
      Then the test command fails before any test starts
      And the console holds no line of human-readable output

      Examples:
        | selectors                   | what                                                |
        | `core.test` and `util.test` | two module selectors                                |
        | `__.test`                   | one resolving to three test modules                 |
        | `core`                      | one resolving to a module that is not a test module |
        | `coreJS.test`               | one resolving to a Scala.js test module             |
        | `nosuch.test`               | one resolving to no module                          |
        | only `-oH`                  | no module selector                                  |
```

| Requirement                   | Criteria |
|-------------------------------|----------|
| `../SPECIFICATION.md` § Scope | AC-081   |

## Running the module

```gherkin
Feature: Running the module
  A line of human-readable output is given without the prefix Mill puts on every line the test command's own task writes, such as `1] `.
  Where a scenario does not fix a line's text or colour, the line has the text and colour ScalaTest 3.2.20 prints.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments
    And Mill colours its own console output

  Rule: A test sees the environment and working directory Mill's `testForked` gives it
    Scenario: AC-082 A `forkEnv` variable and the working directory with test sandboxing off
      Given the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
      And the module's `forkEnv` sets `FIXTURE_ENV` to `from-forkEnv`, and its `testSandboxWorkingDir` is false
      And suite `WhereSuite`, class `samples.WhereSuite`, an `AnyFunSuite` whose one test, `reports where`, labelled `- reports where`, writes `env=<the value of FIXTURE_ENV>` and `cwd=<its working directory>` to stdout
      When the test command runs the module, and `mill core.test.testForked` runs it
      Then the run's block holds `[stdout] env=from-forkEnv`
      And the `cwd=` line it holds is the one `testForked` wrote

  Rule: The test command runs a module on Scala 3.3 or later whose definition names nothing from the plugin
    Scenario Outline: AC-083 The plugin is added to a build on Scala <version>
      Given the build does not add the plugin, its `build.mill` header has no `mvnDeps`, and the module is built with Scala <version>
      And the module is at Mill's default `testLogLevel`
      And its one suite is `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      When a header line `//| mvnDeps:` holding the one entry `com.edc::scalatest-mill`, at the release of this fork that `core.test` tests with, is added to its `build.mill`, with nothing else in the build changed, and the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SqrtPropSpec:
            - sqrt of a square is the root
        """

      Examples:
        | version |
        | 3.3     |
        | 3.9     |
```

| Requirement                         | Criteria |
|-------------------------------------|----------|
| R-053                               | AC-082   |
| R-092                               | AC-083   |
| `../../SPECIFICATION.md` § Scope    | AC-083   |

## The test command's result

```gherkin
Feature: The test command's result

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments

  Rule: Of runs that complete, the test command fails where one reports a failure, and not otherwise
    Scenario Outline: AC-084 <tests>
      Given suite `ResultSuite`, class `samples.ResultSuite`, an `AnyFunSuite` mixing in `BeforeAndAfterAll`, in which <tests>
      When the test command runs the module
      Then the test command <result>

      Examples:
        | tests                                                                                                                                          | result   |
        | test `succeeds`, labelled `- succeeds`, succeeds                                                                                               | succeeds |
        | test `ignored`, labelled `- ignored`, is ignored, `pending`, labelled `- pending`, is pending, and `succeeds`, labelled `- succeeds`, succeeds | succeeds |
        | test `cancelled`, labelled `- cancelled`, is cancelled, and `succeeds`, labelled `- succeeds`, succeeds                                        | fails    |
        | test `fails`, labelled `- fails`, fails, and `succeeds`, labelled `- succeeds`, succeeds                                                       | fails    |
        | test `succeeds`, labelled `- succeeds`, succeeding where it runs, and `beforeAll` throws                                                       | fails    |

  Rule: A worker that dies fails the test command, whether or not a run existed in it
    Scenario Outline: AC-085 <how the worker dies>
      Given <how the worker dies>
      When the test command runs the module
      Then the test command fails

      Examples:
        | how the worker dies                                                                                                                                                                                                       |
        | test `exits`, labelled `- exits`, of suite `ExitSuite`, class `samples.ExitSuite`, an `AnyFunSuite`, calls `System.exit(0)`                                                                                               |
        | the module holds suite `AddSuite`, class `samples.discovered.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds, and its `forkArgs` hold `-XX:+NoSuchOption`, so the worker's JVM exits before any run exists in it |

  Rule: The test command's failure lists no test
    Scenario: AC-086 A failed test is not named outside the human-readable output
      Given suite `StackFlatSpec`, class `samples.StackFlatSpec`, an `AnyFlatSpec` declaring `"A Stack" must "have size 0" in`: test `have size 0`, labelled `- must have size 0`, which fails
      When the test command runs the module
      Then the test command fails
      And no line on the console outside the human-readable output holds `have size 0`
```

```gherkin
Feature: A module that does not test with this fork

  Background:
    Given a build whose JVM test module is `core.test`, the module
    And the build adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments

  Rule: A module that does not test with this fork fails the test command
    Scenario Outline: AC-087 <framework>
      Given the module tests with <framework>
      And its one suite, `samples.FrameworkSuite`, written for <framework>, has one test, `runs`, which succeeds
      When the test command runs the module
      Then the test command fails

      Examples:
        | framework                                      |
        | MUnit                                          |
        | uTest                                          |
        | `org.scalatest:scalatest` 3.2.20               |
```

```gherkin
Feature: The consolidation state of a run that ends

  Rule: A run that ends aborted or stopped leaves the consolidation state failed, and one that completes without a failure does not
    Scenario: AC-157 A run that ends completed
      Given an event log holding:
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
      Then the consolidation state the library gives is *ended*

    Scenario: AC-157 A run that ends aborted
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
      When the library consolidates it
      Then the consolidation state the library gives is *ended, failed*

    Scenario: AC-157 A run that ends stopped
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
      Then the consolidation state the library gives is *ended, failed*
```

| Requirement                   | Criteria                       |
|-------------------------------|--------------------------------|
| R-046                         | AC-084, AC-085, AC-157, AC-086 |
| `../SPECIFICATION.md` § Scope | AC-087                         |

## The console

```gherkin
Feature: What else reaches the console
  A line of human-readable output is given without the prefix Mill puts on every line the test command's own task writes, such as `1] `.
  Where a scenario does not fix a line's text or colour, the line has the text and colour ScalaTest 3.2.20 prints.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And the test command runs the module when invoked as `mill scalatest/test core.test`, followed by any arguments
    And Mill colours its own console output

  Rule: A run reaches the console only through the human-readable output
    Scenario: AC-088 A failing test that writes to both streams
      Given suite `QueueFlatSpec`, class `samples.QueueFlatSpec`, an `AnyFlatSpec` declaring `"A Queue" should "be empty" in` and then `it must "have size 0" in`: tests `be empty` and `have size 0`, labelled `- should be empty` and `- must have size 0`
      And `be empty` succeeds, and `have size 0` writes `dividing now` to stdout and `warn: careful` to stderr, then fails
      When the test command runs the module
      Then no line on the console, in the human-readable output or outside it, holds any of:
        | what                           | such as                                                         |
        | ScalaTest's end-of-run summary | `Run completed in`, `Tests: succeeded`, `*** 1 TEST FAILED ***` |
        | Mill's list of failed tests    | `1 tests failed:`                                               |
      And no line on the console outside the human-readable output holds any of:
        | what                                                   | such as                                  |
        | a line a ScalaTest reporter prints for a suite or test | `QueueFlatSpec:`, `- should be empty`    |
        | Mill's progress for the module's test classes          | `samples.QueueFlatSpec`, `1/1 completed` |
        | a line the run wrote                                   | `dividing now`, `warn: careful`          |

  Rule: A runner that is not a run adds nothing to the output or the result
    Scenario: AC-089 Mill's discovery runner
      Given the module is at Mill's default `testLogLevel`
      And its one suite is `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      When the test command runs the module
      Then the test command succeeds
      And the human-readable output holds one `SqrtPropSpec:` line and no `Output outside any suite:` line

  Rule: Output of another goal on the same `mill` command line is neither captured nor changed
    Scenario: AC-090 A task named before the test command
      Given the module holds suite `AddSuite`, class `samples.discovered.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds
      And the build's task `banner` writes `building banner` to stdout
      When one `mill` command runs `banner` and then the test command over the module
      Then the console holds `building banner` outside the human-readable output, with the prefix Mill gives `banner`'s lines
      And no line of the human-readable output holds `building banner`

  Rule: The human-readable output shows only the runs its test command started
    Scenario: AC-091 A second test command after a test is fixed
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and has been changed to fail
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      And the test command has run the module
      When the test is changed back to succeed, and the test command runs the module again
      Then the second human-readable output is:
        """
        [core.test]
          CalculatorSuite:
            - adds two numbers
          Output outside any suite:
            [stdout] dividing now
        """
      And the second test command succeeds
```

| Requirement | Criteria       |
|-------------|----------------|
| R-040       | AC-088         |
| R-002       | AC-088         |
| R-044       | AC-088         |
| R-045       | AC-088         |
| R-072       | AC-089         |
| R-050       | AC-090         |
| R-020       | AC-091         |

## Mill's own test tasks and BSP server

```gherkin
Feature: Mill's own test tasks and BSP server

  Background:
    Given a build whose JVM test module `core.test` tests with `com.edc::scalatest` at the release of this fork under test
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And a build that adds the plugin is that build with the Mill plugin, at that release, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, and no module's definition changed
    And `core.test` holds suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
    And `core.test` holds suite `StackFlatSpec`, class `samples.StackFlatSpec`, an `AnyFlatSpec` declaring `"A Stack" must "have size 0" in`: test `have size 0`, labelled `- must have size 0`, which fails

  Rule: In a build that adds the plugin, Mill's test tasks behave as they do without it, and produce no consolidated output
    Scenario Outline: AC-092 `mill <task>`
      When `mill <task>` runs in a build that adds the plugin, and in the same build without it
      Then Mill's result is the same under both
      And so is its console output, apart from durations and the lines Mill writes while it compiles the build itself
      And neither console holds a line `[core.test]` or `Output outside any suite:`

      Examples:
        | task                                         |
        | `core.test`                                  |
        | `core.test.testForked`                       |
        | `core.test.testOnly samples.CalculatorSuite` |
        | `core.test.testCached`                       |
        | `core.test.testLocal`                        |
        | `__.test`                                    |

  Rule: In a build that adds the plugin, Mill's BSP server answers an IDE's requests as it does without it
    Scenario Outline: AC-093 <request>
      Given a BSP client holds one session with Mill's BSP server, from initialization until the server closes the connection
      And in that session the client sends each of `workspace/buildTargets`, `buildTarget/scalaTestClasses` for `core.test`, `buildTarget/jvmTestEnvironment` for `core.test` and `buildTarget/test` for `core.test`
      When the session runs in a build that adds the plugin, and in the same build without it
      Then the client receives the same response to <request> under both, apart from durations, timestamps and task IDs
      And the client receives the same notifications in the session under both, apart from durations, timestamps and task IDs
      And no notification under either holds `[core.test]` or `Output outside any suite:`

      Examples:
        | request                                          |
        | `workspace/buildTargets`                         |
        | `buildTarget/scalaTestClasses` for `core.test`   |
        | `buildTarget/jvmTestEnvironment` for `core.test` |
        | `buildTarget/test` for `core.test`               |
```

| Requirement | Criteria |
|-------------|----------|
| R-060       | AC-092   |
| R-054       | AC-092   |
| R-003       | AC-093   |

On Mill 1.3.1, Mill's BSP server answers every `buildTarget/test` request with `Internal error.` and runs no test, with or without the plugin, because of a bug in Mill itself. AC-093's `buildTarget/test` row therefore shows only that the plugin does not change that failure, not that a test run an IDE starts over BSP is unchanged.
