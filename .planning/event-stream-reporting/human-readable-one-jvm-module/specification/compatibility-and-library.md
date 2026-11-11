# Consolidated human-readable output for one JVM module: compatibility, the emitter and the library

Acceptance criteria for what holds outside the test command: compatibility with ScalaTest 3.2.19, the test event log a run writes, and the consolidation library without Mill. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use.

## Compatibility with ScalaTest 3.2.19

AC-056 and AC-057 hold on each of Scala 2.11, 2.12, 2.13 and 3.3, with the compatibility fixture compiled for that version and run on this fork's and 3.2.19's builds for it.

Each IntelliJ run mode is a case of these criteria with the emitter off (R-003):

- *forked* mode: AC-056's `Runner.run` rows;
- *BSP* mode, as Metals and Bloop launch runs: AC-056's `Framework` rows;
- *sbt shell* mode: AC-057's row.

In a build that adds the plugin, what an IDE asks of Mill's BSP server is [test-command.md](test-command.md) AC-093.

```gherkin
Feature: Compatibility with ScalaTest 3.2.19

  Background:
    Given the compatibility fixture, compiled against `org.scalatest:scalatest` 3.2.19
    And it runs sequentially, once on 3.2.19 and once on this fork

  Rule: Reporters and the build tool receive what 3.2.19 sends them, with the emitter off or on (R-001, R-002, R-051, R-003)
    Scenario Outline: AC-056 <launch>, emitter <emitter>
      When each run is launched through <launch>, the fork's run given the runner argument only where the emitter is on
      Then <receiver> receives on this fork what 3.2.19 sends it (`../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x)

      Examples:
        | launch                                                                           | emitter | receiver                         |
        | `Runner.run`, its one reporter a `-C` recording reporter compiled against 3.2.19 | off     | the `-C` reporter                |
        | `Runner.run`, its one reporter a `-C` recording reporter compiled against 3.2.19 | on      | the `-C` reporter                |
        | `Framework`, its runner given no other argument                                  | off     | the test-interface event handler |
        | `Framework`, its runner given no other argument                                  | on      | the test-interface event handler |

  Rule: The text ScalaTest's standard-output reporter prints, as IntelliJ's sbt shell mode reads it, is 3.2.19's, with the emitter off or on (R-001, R-002, R-003)
    Scenario Outline: AC-057 `-oDU` through `Framework`, emitter <emitter>
      When each run is launched through `Framework`, its runner given `-oDU`, the fork's run given the runner argument only where the emitter is on
      Then the lines the runner's test-interface loggers receive on this fork are those they receive on 3.2.19, apart from the durations they show

      Examples:
        | emitter |
        | off     |
        | on      |

  Rule: The fork stays binary compatible with ScalaTest 3.2.x (R-004)
    Scenario: AC-058 MiMa against 3.2.19
      When MiMa checks each artifact that both this fork and ScalaTest 3.2.19 publish against 3.2.19's
      Then it reports no binary incompatibility
```

## The test event log

```gherkin
Feature: The test event log a run writes

  Rule: What a run sends does not depend on its reporters (R-063)
    Scenario Outline: AC-059 <reporters>
      Given the compatibility fixture
      When `Runner.run` runs it sequentially with the runner argument, once with no reporter argument and once with <reporters>
      Then the two runs' test event logs hold the same items, apart from timestamps, durations and thread names

      Examples:
        | reporters                                                        |
        | `-oDF`                                                           |
        | `-oNCXEHLOPQRM`, whose event-filter characters drop every event  |
        | `-e` and a `-C` recording reporter                               |

  Rule: A run's test event log records how the run ended (R-074)
    Scenario Outline: AC-060 A run that ends <ended>
      Given <the run>
      When <launch> runs it with the runner argument
      Then the run's test event log records that the run ended <ended>

      Examples:
        | the run                                                                                         | launch       | ended     |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                      | `Framework`  | completed |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                      | `Runner.run` | completed |
        | suite `StackFunSpec`, whose test `throws on pop` throws `OutOfMemoryError("Java heap space")`   | `Runner.run` | aborted   |
        | suite `StopSuite`, which mixes in `StopOnFailure` and whose first test fails                    | `Runner.run` | stopped   |

  Rule: The emitter covers the JVM on each of its Scala versions (`../../SPECIFICATION.md` § Scope)
    Scenario Outline: AC-061 Scala <version>
      Given suite `SqrtPropSpec` of `layout.md` AC-002, compiled with Scala <version>
      When it runs through `Framework` on this fork's build for Scala <version>, with the runner argument
      And its test event log is fed to the consolidation library directly
      Then the human-readable output is the one `layout.md` AC-002 gives

      Examples:
        | version |
        | 2.11    |
        | 2.12    |
        | 2.13    |
        | 3.3     |
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
      Given the test command has run a module holding suite `CalculatorSuite` of `runs-and-test-structure.md` AC-035, with Mill's `--color false`
      When the suite runs through `Framework` outside Mill with the runner argument, and its test event log and the lines it wrote to stdout and stderr are fed to the consolidation library directly, with colour off
      Then the library's human-readable output is the test command's, with Mill's prefix removed from each line

  Rule: The library writes human-readable output only once every run's event stream has ended (R-027)
    Scenario: AC-064 A completed run whose event stream has not ended
      Given a run, fed to the consolidation library directly, that has reported that it completed
      When its event stream has not yet ended
      Then the library has written no human-readable output
      And once the stream ends and the caller ends the consolidation, the library writes the human-readable output
```
