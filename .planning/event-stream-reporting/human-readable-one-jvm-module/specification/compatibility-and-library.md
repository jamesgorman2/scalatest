# Consolidated human-readable output for one JVM module: compatibility and the library

This document contains the acceptance criteria for behaviour outside the test command: compatibility with ScalaTest 3.2.20, and the consolidation library without Mill. It is part of [../SPECIFICATION.md](../SPECIFICATION.md). [test-event-log.md](test-event-log.md) contains the criteria for what a run writes to its test event log. [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Compatibility with ScalaTest 3.2.20

```gherkin
Feature: Compatibility with ScalaTest 3.2.20
  A receiver receives what 3.2.20 sends it when it receives the events 3.2.20 sends it, in the same order, with the same field values apart from timestamps, durations and thread names.
  A throwable is compared on its class, its message and its chain of causes, and, where it records the file and line of the code that failed, on that file name and line number. Its stack frames are not compared.

  Background:
    Given a set of suites holding at least one suite of each of these styles:
      | styles                                                                                               |
      | `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite` and `FixtureAsyncFunSuite`                     |
      | `AnyPropSpec` and `FixtureAnyPropSpec`                                                               |
      | `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec` and `PathAnyFunSpec`       |
      | `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec` and `PathAnyFreeSpec`  |
      | `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec` and `FixtureAsyncWordSpec`                     |
      | `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec` and `FixtureAsyncFlatSpec`                     |
      | `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec` and `FixtureAsyncFeatureSpec`         |
      | `RefSpec`                                                                                            |
    And between them, the suites hold:
      | what                                                                                                                                                  |
      | nested suites                                                                                                                                         |
      | scopes, one of them pending                                                                                                                           |
      | tests that succeed, fail, are ignored, are pending and are cancelled                                                                                  |
      | `info`, `note`, `alert` and `markup` messages from suites, scopes and tests, and all four from a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test |
      | a suite whose `beforeAll` throws                                                                                                                      |
      | a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, with a scope                                                                           |
      | a test that writes to stdout and to stderr                                                                                                            |
    And the suites are compiled with Scala 3.3 against `org.scalatest:scalatest` 3.2.20
    And the suites run sequentially on the JVM, once on 3.2.20 and once on this fork
    And the runner argument is the ScalaTest runner argument that turns the emitter on and names the file to which the run writes its test event log

  Rule: With the emitter off, reporters and the build tool receive what 3.2.20 sends them, and with the emitter on, the build tool still receives what 3.2.20 sends it
    Scenario Outline: AC-056 <launch>, emitter <emitter>
      When each run is launched through <launch>
      And this fork's run <runner argument>
      Then <receiver> receives on this fork what 3.2.20 sends it

      Examples:
        | launch                                                                           | emitter | runner argument                   | receiver                         |
        | `Runner.run`, its one reporter a `-C` recording reporter compiled against 3.2.20 | off     | is not given the runner argument  | the `-C` reporter                |
        | `Framework`, with no other argument given to its runner                          | off     | is not given the runner argument  | the test-interface event handler |
        | `Framework`, with no other argument given to its runner                          | on      | is also given the runner argument | the test-interface event handler |

  Rule: With the emitter off, the text ScalaTest's standard-output reporter prints, as IntelliJ's sbt shell mode reads it, is 3.2.20's
    Scenario: AC-057 `-oDU` through `Framework`, emitter off
      When each run is launched through `Framework`, with `-oDU` given to its runner
      And this fork's run is not given the runner argument
      Then the lines the runner's test-interface loggers receive on this fork are those they receive on 3.2.20, apart from the durations the lines show
      And each run of stack-frame lines, the `at ` lines printed for a throwable's frames, counts in that comparison as one line, whatever it holds

  Rule: Each artifact of the fork that ScalaTest 3.2.20 also publishes stays binary compatible with ScalaTest 3.2.x
    Scenario: AC-058 MiMa against 3.2.20
      When MiMa checks each artifact that both this fork and ScalaTest 3.2.20 publish against 3.2.20's
      Then it reports no binary incompatibility
```

Each mode in which IntelliJ's Scala plugin runs ScalaTest is a case of these criteria with the emitter off:

- *forked* mode: AC-056's `Runner.run` row;
- *BSP* mode, as Metals and Bloop launch runs: AC-056's `Framework` row with the emitter off;
- *sbt shell* mode: AC-057.

| Requirement | Criteria       |
|-------------|----------------|
| R-001       | AC-056, AC-057 |
| R-051       | AC-056         |
| R-003       | AC-056, AC-057 |
| R-004       | AC-058         |

## The consolidation library without Mill

```gherkin
Feature: The consolidation library without Mill
  Where a scenario does not fix a line's text or colour, the line has the text and colour ScalaTest 3.2.20 prints.

  Rule: The consolidation library is published and depends on no Mill artifact
    Scenario: AC-062 Its dependencies
      When the consolidation library's published artifact's dependencies are resolved, transitively
      Then none has group `com.lihaoyi` and a name beginning `mill-`

  Rule: The library writes the same human-readable output without Mill as under the test command
    Scenario: AC-063 One run, with and without Mill
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      And a build whose JVM test module `core.test` holds the suite and tests with `com.edc::scalatest` at the release of this fork under test
      And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
      And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
      And the test command, invoked as `mill scalatest/test core.test` with Mill's `--color false`, has run `core.test`
      When the suite runs through `Framework` outside Mill, given the ScalaTest runner argument that turns the emitter on and names the file to which the run writes its test event log
      And the events this fork reads back from that test event log, and the lines the run wrote to stdout and stderr, are fed to the consolidation library directly, under test run label `core.test`, with colour off
      Then the library's human-readable output is the test command's, with the prefix Mill puts on every line the test command's own task writes removed from each line

  Rule: The library writes human-readable output only once every run's event stream has ended
    A run's event stream ends when the caller ends it, not at its `runEnded` event.

    Background:
      Given an event log holding:
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
      And the run is fed to the consolidation library under test run label `core.test`, with colour on

    Scenario: AC-064 A completed run whose event stream is held open
      When every event has been fed and the event stream is held open
      Then, before the stream ends, the library has written no human-readable output

    Scenario: AC-118 A completed run whose event stream has ended
      Given every event has been fed and the event stream has ended
      When the caller ends the consolidation
      Then the library writes the human-readable output:
        """
        [core.test]
          SqrtPropSpec:
            - sqrt of a square is the root
        """
```

| Requirement | Criteria       |
|-------------|----------------|
| R-023       | AC-062         |
| R-113       | AC-063         |
| R-027       | AC-064, AC-118 |
