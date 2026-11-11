# Consolidated human-readable output for one JVM module: the test command

Acceptance criteria for the test command: its arguments, how it runs the module, its result, what else reaches the console, and Mill's own test tasks and BSP server beside it. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use.

## Output arguments

```gherkin
Feature: The test command's output arguments

  Rule: With no `-o`, or with `-oH`, stdout holds human-readable output and no other consolidated output; a bare `-o` leaves it without consolidated output (R-048, R-055)
    Scenario Outline: AC-076 <arguments>
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035
      When the test command runs the module with <arguments>
      Then the console holds <consolidated output>
      And the test command succeeds

      Examples:
        | arguments | consolidated output                                                                          |
        | no `-o`   | the human-readable output AC-035 gives, and no other line the test command's own task writes |
        | `-oH`     | the human-readable output AC-035 gives, and no other line the test command's own task writes |
        | `-o`      | no line of human-readable output, and no line holding `dividing now`                         |

  Rule: An output argument this feature does not take fails the test command before any test starts, with no consolidated output (`../SPECIFICATION.md` § Scope)
    Scenario Outline: AC-077 <arguments>
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035
      When the test command runs the module with <arguments>, which hold <what>
      Then the test command fails before any test starts
      And the console holds no line of human-readable output
      And no file `report.txt` is written

      Examples:
        | arguments            | what                                           |
        | `-oP`                | an output letter other than `H`                |
        | `-oJ`                | an output letter other than `H`                |
        | `-oHP`               | an output letter other than `H`, beside `H`    |
        | `-oHd`               | a lowercase character that R-127 names         |
        | `-oHy`               | a lowercase character that R-127 does not name |
        | `-fH report.txt`     | an `-f`                                        |
        | `-oH -fJ report.txt` | an `-f`, beside `-oH`                          |
```

## The module's own ScalaTest arguments

```gherkin
Feature: The module's own ScalaTest arguments

  Rule: An argument whose flag R-125's runner does not recognise fails the test command before any test starts, naming it (R-125)
    Scenario: AC-078 An unrecognised flag
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035
      And the module's own ScalaTest arguments are `-l samples.Slow -y`
      When the test command runs the module
      Then the test command fails before any test starts, and its failure names `-y`
      And the console holds no line of human-readable output

  Rule: An argument R-125 allows reaches the run with its values, even a value that looks like a flag (R-125)
    Scenario: AC-079 A test-name substring that begins with `-`
      Given suite `FlagSuite`, an `AnyFunSuite` with tests `parses -y` and `parses -x`, each succeeding where it runs
      And the module's own ScalaTest arguments are `-z -y`
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          FlagSuite:
            - parses -y
        """

  Rule: Every other argument is withheld with its values (R-125, R-041)
    Scenario Outline: AC-080 <arguments> has no effect
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035, and suite `OtherSuite`, whose one test, `other`, succeeds
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

## Module selectors

```gherkin
Feature: Module selectors

  Rule: The test command fails unless it is given one module selector that resolves to one JVM test module (`../SPECIFICATION.md` § Scope)
    Scenario Outline: AC-081 <selectors>
      Given the build's test modules are `core.test` and `util.test`, both JVM test modules, and `coreJS.test`, a Scala.js test module
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
```

## Running the module

```gherkin
Feature: Running the module

  Rule: A test sees the environment and working directory Mill's `testForked` gives it (R-053)
    Scenario: AC-082 A `forkEnv` variable and the working directory with test sandboxing off
      Given the module's `forkEnv` sets `FIXTURE_ENV` to `from-forkEnv`, and its `testSandboxWorkingDir` is false
      And suite `WhereSuite`, whose one test writes `env=<the value of FIXTURE_ENV>` and `cwd=<its working directory>` to stdout
      When the test command runs the module, and `mill core.test.testForked` runs it
      Then the run's block holds `[stdout] env=from-forkEnv`
      And the `cwd=` line it holds is the one `testForked` wrote

  Rule: The test command runs a module whose definition names nothing from the plugin, on each of the JVM's Scala versions (R-092, `../../SPECIFICATION.md` § Scope)
    Scenario Outline: AC-083 The plugin is added to a build on Scala <version>
      Given a build without the plugin, whose module `core.test`, built with Scala <version>, holds suite `SqrtPropSpec` of `layout.md` AC-002
      When the plugin is added to the build, with no module's definition changed, and the test command runs the module
      Then the human-readable output is the one `layout.md` AC-002 gives

      Examples:
        | version |
        | 2.11    |
        | 2.12    |
        | 2.13    |
        | 3.3     |
```

## The test command's result

```gherkin
Feature: The test command's result

  Rule: Of runs that complete, the test command fails where one reports a failure, and not otherwise (R-046)
    Scenario Outline: AC-084 <tests>
      Given suite `ResultSuite`, an `AnyFunSuite` in which <tests>
      When the test command runs the module
      Then the test command <result>

      Examples:
        | tests                                                                                              | result   |
        | every test succeeds                                                                                | succeeds |
        | test `ignored` is ignored, `pending` is pending and `cancelled` is cancelled, and the rest succeed | succeeds |
        | test `fails` fails                                                                                 | fails    |
        | `beforeAll` throws                                                                                 | fails    |

  Rule: A worker that dies fails the test command, whether or not a run existed in it (R-046)
    Scenario Outline: AC-085 <how the worker dies>
      Given <how the worker dies>
      When the test command runs the module
      Then the test command fails

      Examples:
        | how the worker dies                                                                                     |
        | a test of suite `ExitSuite` calls `System.exit(0)`                                                      |
        | the module's `forkArgs` hold `-XX:+NoSuchOption`, so the worker's JVM exits before any run exists in it |

  Rule: The test command's failure lists no test (R-046)
    Scenario: AC-086 A failed test is not named outside the human-readable output
      Given suite `StackFlatSpec`, whose test `must have size 0` fails
      When the test command runs the module
      Then the test command fails
      And no line on the console outside the human-readable output holds `must have size 0`

  Rule: A module that tests with another framework, or with another release of this fork, fails the test command (`../SPECIFICATION.md` § Scope)
    Scenario Outline: AC-087 <framework>
      Given the module tests with <framework>
      When the test command runs the module
      Then the test command fails

      Examples:
        | framework                                      |
        | MUnit                                          |
        | uTest                                          |
        | `org.scalatest:scalatest` 3.2.19               |
        | a release of this fork other than the plugin's |
```

## The console

```gherkin
Feature: What else reaches the console

  Rule: A run reaches the console only through the human-readable output (R-040, R-041, R-044, R-045)
    Scenario: AC-088 A failing test that writes to both streams
      Given suite `StackFlatSpec`, of class `samples.StackFlatSpec`, whose test `is empty` succeeds, and whose test `must have size 0` writes `dividing now` to stdout and `warn: careful` to stderr, then fails
      When the test command runs the module
      Then no line on the console, in the human-readable output or outside it, holds any of:
        | what                           | such as                                                         |
        | ScalaTest's end-of-run summary | `Run completed in`, `Tests: succeeded`, `*** 1 TEST FAILED ***` |
        | Mill's list of failed tests    | `1 tests failed:`                                               |
        | Mill's per-class test result   | `Test result:`                                                  |
      And no line on the console outside the human-readable output holds any of:
        | what                                                   | such as                                  |
        | a line a ScalaTest reporter prints for a suite or test | `StackFlatSpec:`, `- is empty`           |
        | Mill's progress for the module's test classes          | `samples.StackFlatSpec`, `1/1 completed` |
        | a line the run wrote                                   | `dividing now`, `warn: careful`          |

  Rule: A runner that is not a run adds nothing to the output or the result (R-072)
    Scenario: AC-089 Mill's discovery runner
      Given suite `SqrtPropSpec` of `layout.md` AC-002
      When the test command runs the module
      Then the test command succeeds
      And the human-readable output holds one `SqrtPropSpec:` line and no `Output outside any suite:` line

  Rule: Output of another goal on the same `mill` command line is neither captured nor changed (R-050)
    Scenario: AC-090 A task named before the test command
      Given the build's task `banner` writes `building banner` to stdout
      When one `mill` command runs `banner` and then the test command over the module
      Then the console holds `building banner` outside the human-readable output, with the prefix Mill gives `banner`'s lines
      And no line of the human-readable output holds `building banner`

  Rule: The human-readable output shows only the runs its test command started (R-020)
    Scenario: AC-091 A second test command after a test is fixed
      Given the test command has run the module while the test of suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035 failed
      When the test is changed to succeed, and the test command runs the module again
      Then the second human-readable output is the one AC-035 gives
      And the second test command succeeds
```

## Mill's own test tasks and BSP server

```gherkin
Feature: Mill's own test tasks and BSP server

  Rule: In a build that adds the plugin, Mill's test tasks behave as they do without it, and produce no consolidated output (R-060, R-054)
    Scenario Outline: AC-092 `mill <task>`
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035, and suite `StackFlatSpec` of AC-086
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

  Rule: In a build that adds the plugin, Mill's BSP server answers an IDE's requests as it does without it (R-003)
    Scenario Outline: AC-093 <request>
      Given suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035, and suite `StackFlatSpec` of AC-086
      When a BSP client connected to Mill's BSP server sends <request>, in a build that adds the plugin, and in the same build without it
      Then the client receives the same response and notifications under both, apart from durations, timestamps and task IDs
      And no notification under either holds `[core.test]` or `Output outside any suite:`

      Examples:
        | request                                          |
        | `workspace/buildTargets`                         |
        | `buildTarget/scalaTestClasses` for `core.test`   |
        | `buildTarget/jvmTestEnvironment` for `core.test` |
        | `buildTarget/test` for `core.test`               |
```
