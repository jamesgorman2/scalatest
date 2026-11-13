# Consolidated human-readable output for one JVM module: compatibility and the library

Acceptance criteria for what holds outside the test command: compatibility with ScalaTest 3.2.20, and the consolidation library without Mill. [test-event-log.md](test-event-log.md) gives what the test event log holds. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use.

## Compatibility with ScalaTest 3.2.20

Each IntelliJ run mode is a case of these criteria with the emitter off (R-003):

- *forked* mode: AC-056's `Runner.run` rows;
- *BSP* mode, as Metals and Bloop launch runs: AC-056's `Framework` rows;
- *sbt shell* mode: AC-057's row.

In a build that adds the plugin, what an IDE asks of Mill's BSP server is [test-command.md](test-command.md) AC-093.

```gherkin
Feature: Compatibility with ScalaTest 3.2.20

  Background:
    Given the compatibility fixture, compiled with Scala 3.3 against `org.scalatest:scalatest` 3.2.20
    And it runs sequentially, once on 3.2.20 and once on this fork

  Rule: Reporters and the build tool receive what 3.2.20 sends them, with the emitter off or on (R-001, R-002, R-051, R-003)
    Scenario Outline: AC-056 <launch>, emitter <emitter>
      When each run is launched through <launch>, the fork's run given the runner argument only where the emitter is on
      Then <receiver> receives on this fork what 3.2.20 sends it (`../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x)

      Examples:
        | launch                                                                           | emitter | receiver                         |
        | `Runner.run`, its one reporter a `-C` recording reporter compiled against 3.2.20 | off     | the `-C` reporter                |
        | `Runner.run`, its one reporter a `-C` recording reporter compiled against 3.2.20 | on      | the `-C` reporter                |
        | `Framework`, its runner given no other argument                                  | off     | the test-interface event handler |
        | `Framework`, its runner given no other argument                                  | on      | the test-interface event handler |

  Rule: The text ScalaTest's standard-output reporter prints, as IntelliJ's sbt shell mode reads it, is 3.2.20's, with the emitter off or on (R-001, R-002, R-003)
    Scenario Outline: AC-057 `-oDU` through `Framework`, emitter <emitter>
      When each run is launched through `Framework`, its runner given `-oDU`, the fork's run given the runner argument only where the emitter is on
      Then the lines the runner's test-interface loggers receive on this fork are those they receive on 3.2.20, apart from what `test-event-log-events.md` AC-114 exempts

      Examples:
        | emitter |
        | off     |
        | on      |

  Rule: Each artifact of the fork that ScalaTest 3.2.20 also publishes stays binary compatible with ScalaTest 3.2.x (R-004)
    Scenario: AC-058 MiMa against 3.2.20
      When MiMa checks each artifact that both this fork and ScalaTest 3.2.20 publish against 3.2.20's
      Then it reports no binary incompatibility
```

## The consolidation library without Mill

```gherkin
Feature: The consolidation library without Mill

  Rule: The consolidation library is published and depends on no Mill artifact (R-023)
    Scenario: AC-062 Its dependencies
      When the consolidation library's published artifact's dependencies are resolved, transitively
      Then none has group `com.lihaoyi` and a name beginning `mill-`

  Rule: The library writes the same human-readable output without Mill as under the test command (R-113)
    Scenario: AC-063 One run, with and without Mill
      Given the test command has run a module holding suite `CalculatorSuite` of `runs-and-test-structure-runs.md` AC-035, with Mill's `--color false`
      When the suite runs through `Framework` outside Mill with the runner argument, and its test event log, decoded with core's codec, and the lines it wrote to stdout and stderr are fed to the consolidation library directly, with colour off
      Then the library's human-readable output is the test command's, with Mill's prefix removed from each line

  Rule: The library writes human-readable output only once every run's event stream has ended (R-027)
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
