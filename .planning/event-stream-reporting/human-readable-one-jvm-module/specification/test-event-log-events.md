# Consolidated human-readable output for one JVM module: events in the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM about its events and messages, what it leaves out, and the ScalaTest behaviour that stays unchanged. Part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

Each run below is launched outside Mill.

## Messages

```gherkin
Feature: Messages

  Rule: A test's messages sit between its start and its close, in the order the test called them, whatever its style or outcome (R-011, R-097)
    Scenario Outline: AC-105 <suite>: a test that <outcome>
      Given suite `MessagesSpec`, <suite>, whose test `is empty`, labelled `- is empty`, calls `info("checked size")`, `note("a note")`, `alert("an alert")` and `markup("*markup*")` in that order, then <outcome>
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "is empty": TestStarting
        test "is empty": InfoProvided(message: "checked size")
        test "is empty": NoteProvided(message: "a note")
        test "is empty": AlertProvided(message: "an alert")
        test "is empty": MarkupProvided(text: "*markup*")
        test "is empty": <closing event>
        """
      And no other `InfoProvided`, `NoteProvided`, `AlertProvided` or `MarkupProvided` is assigned to test `is empty`
      And each message's timestamp is the time the test called it

      Examples:
        | suite                                                                                                                                        | outcome  | closing event |
        | an `AnyFunSpec`                                                                                                                              | succeeds | TestSucceeded |
        | an `AnyFunSpec`                                                                                                                              | fails    | TestFailed    |
        | an `AnyFunSpec` that overrides `runTest` to pass `super.runTest` its `args` with `args.reporter` wrapped as `ForwardingSuite`'s is in `test-event-log-structure.md` AC-128 | succeeds | TestSucceeded |
        | a `PathAnyFunSpec`                                                                                                                           | succeeds | TestSucceeded |
        | a `PathAnyFunSpec`                                                                                                                           | fails    | TestFailed    |
        | a `PathAnyFreeSpec`                                                                                                                          | succeeds | TestSucceeded |
        | a `PathAnyFreeSpec`                                                                                                                          | fails    | TestFailed    |

  Rule: A scope's message keeps its place among the scope's children (R-097, R-105)
    Scenario: AC-106 An info between two children of a scope
      Given suite `ScopeInfoFunSpec`, an `AnyFunSpec` whose scope `A Stack`, labelled `A Stack`, declares test `is empty`, labelled `- is empty`, which succeeds, then calls `info("an info in a scope")`, then declares scope `when full`, labelled `when full`, holding test `pops`, labelled `- pops`, which succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        """

  Rule: A message with no `nameInfo` is assigned to the node whose reporter it is sent to (R-097)
    Scenario: AC-107 A test reports to its suite's reporter, with no `nameInfo`
      Given suite `DirectSuite`, class `samples.DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter`, whose test `t`, labelled `- t`, sends that reporter an `InfoProvided` with message `direct` and no `nameInfo`, then succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        suite "DirectSuite": InfoProvided(message: "direct")
        test "t": TestSucceeded
        """
      And no `InfoProvided` is assigned to test `t`

  Rule: A message whose `nameInfo` names a suite and no test is assigned to that suite, whichever reporter it is sent to (R-097)
    Scenario Outline: AC-151 A test reports to <reporter>, naming its suite
      Given suite `DirectSuite` of AC-107, whose test `t` gives the `InfoProvided` a `nameInfo` naming `DirectSuite` and no test, and sends it to <reporter>
      When `Framework` runs <top> with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        suite "DirectSuite": InfoProvided(message: "direct")
        test "t": TestSucceeded
        """
      And it holds no other `InfoProvided`

      Examples:
        | reporter                                                                                                                                  | top            |
        | the reporter `DirectSuite` keeps                                                                                                          | `DirectSuite`  |
        | the `args.reporter` that `DirectSuites`, class `samples.DirectSuites`, a `Suites` with no tests nesting `DirectSuite`, keeps in its `run` | `DirectSuites` |

  Rule: A message whose `nameInfo` names a test is assigned to that test, whichever reporter it is sent to, in the test's execution open when it is sent, else the test's latest, else a first (R-097)
    Scenario Outline: AC-134 A test reports to <reporter>, naming itself
      Given suite `DirectSuite` of AC-107, whose test `t` gives the `InfoProvided` a `nameInfo` naming `DirectSuite` and test `t`, and sends it to <reporter>
      When `Framework` runs <top> with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": InfoProvided(message: "direct")
        test "t": TestSucceeded
        """
      And it holds no other `InfoProvided`

      Examples:
        | reporter                                       | top            |
        | the reporter `DirectSuite` keeps               | `DirectSuite`  |
        | the reporter `DirectSuites` of AC-151 keeps    | `DirectSuites` |

    Scenario: AC-152 The test sends the message the second time it runs
      Given suite `DirectSuite` of AC-134's first row, which also overrides `runTest` to call `super.runTest` twice, and whose test `t` sends the message only the second time it runs
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": TestSucceeded
        test "t" execution 2: TestStarting
        test "t" execution 2: InfoProvided(message: "direct")
        test "t" execution 2: TestSucceeded
        """
      And it holds no other `InfoProvided`

    Scenario: AC-153 The message is sent after the test has closed
      Given suite `DirectSuite` of AC-134's first row, whose test `t` sends no message, and whose `run` sends the message after `super.run` returns
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": TestSucceeded
        test "t": InfoProvided(message: "direct")
        """
      And it holds no other `InfoProvided`
```

## What events carry

```gherkin
Feature: What the event log keeps of each event

  Rule: Every event a run reports, and all of it that the consolidated output shows or uses, reaches the event log unaltered (R-010, R-011)
    Scenario Outline: AC-108 The compatibility fixture under <launch>
      Given the compatibility fixture
      When <launch> runs it sequentially with the runner argument and a `-C` recording reporter
      Then for every event the reporter receives, and every event recorded in one, the event log holds that event
      And each field of it that the consolidated output shows or uses equals the reporter's: each field `../../JSON-LAYOUT.md` § Nodes, § Messages, § Throwables, § Locations and § Run records fold into a node, message, throwable, location or run record, and `TestFailed`'s `analysis`
      And the one exception is the timestamp of a message recorded in a test's closing event, which AC-105 sets
      And the events the reporter receives that are not recorded in another are in the event log in the order the reporter received them

      Examples:
        | launch       |
        | `Runner.run` |
        | `Framework`  |

    Scenario Outline: AC-109 Text <text>
      Given suite `TextSuite`, an `AnyFunSuite` whose test `reports`, labelled `- reports`, calls `info(<text>)` and then `fail(<text>)`
      When `Framework` runs it with the runner argument
      Then the event log holds `test "reports": InfoProvided` and `test "reports": TestFailed`, each with message <text> exactly as the test passed it

      Examples:
        | text                                       |
        | `"first line\nsecond line"`                |
        | `"ü ß 日本 ✓"`                              |
        | `"a lone surrogate \uD800"`                |
        | `"tab\tand carriage return\r"`             |
        | `""`                                       |

  Rule: A throwable keeps everything the consolidated output shows or uses (R-011)
    Scenario: AC-110 A cause chain with a suppressed throwable
      Given suite `ThrowSuite`, an `AnyFunSuite` whose test `throws`, labelled `- throws`, throws `IllegalStateException("outer")`, whose cause is `java.io.IOException("inner")` and which suppresses `RuntimeException("closing")`
      When `Framework` runs it with the runner argument
      Then the throwable of `test "throws": TestFailed` is `java.lang.IllegalStateException "outer" suppressing [java.lang.RuntimeException "closing"] caused by java.io.IOException "inner"`
      And each of the three holds every frame of the thrown object, each with the text `StackTraceElement.toString` gives it

    Scenario Outline: AC-111 <throwable>
      Given suite `FailSuite`, an `AnyFunSuite` in file `FailSuite.scala` whose test `t`, labelled `- t`, <does>
      When `Framework` runs it with the runner argument
      Then `test "t": TestFailed` <holds>

      Examples:
        | throwable                                 | does                                                                         | holds                                                                                                                                                       |
        | a `StackDepth` exception                  | calls `fail("boom")` at line 7                                               | the throwable `org.scalatest.exceptions.TestFailedException "boom" at FailSuite.scala:7`, which also holds the pathname of `FailSuite.scala` |
        | a property check failure                  | runs a `forAll` from `TableDrivenPropertyChecks` whose property fails         | a throwable marked `property check`                                                                                                                         |
        | a throwable whose message is `null`       | throws `new RuntimeException()`                                              | the throwable `java.lang.RuntimeException null`                                                                                                            |
        | an assertion with analysis                | calls `assert(Point(1, 2) == Point(1, 3))`, `Point` being a case class, for which 3.2.19's `TestFailed` carries an analysis | the `analysis` that a `-C` recording reporter in the same run receives on the test's `TestFailed`                                                         |
```

## Captured output

```gherkin
Feature: Captured output

  Rule: The event log holds no captured output; what a test writes reaches its process's stdout and stderr (R-010, R-012)
    Scenario: AC-112 A test writes to both streams
      Given suite `BothStreamsSuite`, class `samples.BothStreamsSuite`, an `AnyFunSuite` whose test `writes`, labelled `- writes`, writes `dividing now` to stdout and `warn: careful` to stderr, then succeeds
      When `Framework` runs it with the runner argument, in a JVM of its own
      Then the event log holds no captured output
      And the JVM's stdout holds `dividing now` and its stderr `warn: careful`
```

## Reporters

```gherkin
Feature: The event log and reporters

  Rule: What a run sends does not depend on its reporters (R-063)
    Scenario Outline: AC-059 <reporters>
      Given the compatibility fixture
      When `Runner.run` runs it sequentially with the runner argument, once with no reporter argument and once with <reporters>
      Then the two runs' test event logs hold the same events, apart from timestamps, durations and thread names

      Examples:
        | reporters                                                        |
        | `-oDF`                                                           |
        | `-oNCXEHLOPQRM`, which drops every event a filter character can drop |
        | `-e` and a `-C` recording reporter                               |
```

## Unchanged behaviour

These hold on this fork against ScalaTest 3.2.19, with the emitter off and on (R-001, R-002, R-051).

- AC-113 No class of `org.scalatest.events` gains, loses or changes a field or constructor parameter, and no event class is added or removed. Any such change violates it, even an added field MiMa accepts.
- AC-114 Every `-o`, `-e` and `-f` reporter the run's arguments configure, `J` reporters included, writes what 3.2.19 writes for the same events, through `Framework`'s test-interface loggers where `Framework` runs it. A difference in any character, colour or line violates it. The one exception is the reporter ScalaTest uses when no reporter is configured, which with the emitter on prints nothing (R-041).
- AC-115 `Runner.run` and `Framework` accept every argument list 3.2.19's accept, and reject every one 3.2.19's reject with 3.2.19's message. The runner argument is the only argument they newly accept.
- AC-116 On Scala.js and Native, which take no runner argument, for the compatibility fixture compiled for each, every reporter and the build tool's test-interface event handler and loggers receive what 3.2.19 sends them.
- AC-117 A run not given the runner argument writes no test event log, and creates no file and starts no thread that 3.2.19 does not.
