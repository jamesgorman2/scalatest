# Consolidated human-readable output for one JVM module: events in the test event log

This part contains the acceptance criteria for what a run given the ScalaTest runner argument that turns the emitter on writes to its test event log on the JVM about its events and messages, for what that run leaves out of its test event log, and for the ScalaTest behaviour that stays unchanged. It is part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## Messages

```gherkin
Feature: Messages

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: A test's messages sit between its start and its close, in the order the test called them, whatever its style or outcome
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
      And the event log holds no other `InfoProvided`, `NoteProvided`, `AlertProvided` or `MarkupProvided` assigned to test `is empty`
      And each message's timestamp is the time the test called it

      Examples:
        | suite                                                                                                                                        | outcome  | closing event |
        | an `AnyFunSpec`                                                                                                                              | succeeds | TestSucceeded |
        | an `AnyFunSpec`                                                                                                                              | fails    | TestFailed    |
        | an `AnyFunSpec` that overrides `runTest` to pass `super.runTest` its `args` with `args.reporter` wrapped in a `Reporter` that passes each event to it unchanged | succeeds | TestSucceeded |
        | a `PathAnyFunSpec`                                                                                                                           | succeeds | TestSucceeded |
        | a `PathAnyFunSpec`                                                                                                                           | fails    | TestFailed    |
        | a `PathAnyFreeSpec`                                                                                                                          | succeeds | TestSucceeded |
        | a `PathAnyFreeSpec`                                                                                                                          | fails    | TestFailed    |

  Rule: A scope's message keeps its place among the scope's children
    Scenario: AC-106 An info between two children of a scope
      Given suite `ScopeInfoFunSpec`, an `AnyFunSpec` whose scope `A Stack`, labelled `A Stack`, declares test `is empty`, labelled `- is empty`, which succeeds, then calls `info("an info in a scope")`, then declares scope `when full`, labelled `when full`, holding test `pops`, labelled `- pops`, which succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        """

  Rule: A message with no `nameInfo` is assigned to the node whose reporter the message is sent to
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

  Rule: A message whose `nameInfo` names a suite and no test is assigned to that suite, whichever reporter the message is sent to
    Scenario Outline: AC-151 A test reports to <reporter>, naming its suite
      Given suite `DirectSuite`, class `samples.DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter`, whose test `t`, labelled `- t`, sends <reporter> an `InfoProvided` with message `direct` and a `nameInfo` naming `DirectSuite` and no test, then succeeds
      When `Framework` runs <top> with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        suite "DirectSuite": InfoProvided(message: "direct")
        test "t": TestSucceeded
        """
      And the event log holds no other `InfoProvided`

      Examples:
        | reporter                                                                                                                                  | top            |
        | the reporter `DirectSuite` keeps                                                                                                          | `DirectSuite`  |
        | the reporter that `DirectSuites`, class `samples.DirectSuites`, a `Suites` with no tests nesting `DirectSuite`, which overrides `run` to keep `args.reporter`, keeps | `DirectSuites` |

  Rule: A message whose `nameInfo` names a test is assigned to that test, whichever reporter the message is sent to, and to the test's execution that is open when the message is sent, else to the test's latest execution, else to a first execution of the test
    Scenario Outline: AC-134 A test reports to <reporter>, naming itself
      Given suite `DirectSuite`, class `samples.DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter`, whose test `t`, labelled `- t`, sends <reporter> an `InfoProvided` with message `direct` and a `nameInfo` naming `DirectSuite` and test `t`, then succeeds
      When `Framework` runs <top> with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": InfoProvided(message: "direct")
        test "t": TestSucceeded
        """
      And the event log holds no other `InfoProvided`

      Examples:
        | reporter                                       | top            |
        | the reporter `DirectSuite` keeps               | `DirectSuite`  |
        | the reporter that `DirectSuites`, class `samples.DirectSuites`, a `Suites` with no tests nesting `DirectSuite`, which overrides `run` to keep `args.reporter`, keeps | `DirectSuites` |

    Scenario: AC-152 The test sends the message the second time it runs
      Given suite `DirectSuite`, class `samples.DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter` and `runTest` to call `super.runTest` twice
      And the test `t` of `DirectSuite`, labelled `- t`, sends the kept reporter an `InfoProvided` with message `direct` and a `nameInfo` naming `DirectSuite` and test `t` only the second time it runs, and succeeds each time it runs
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": TestSucceeded
        test "t" execution 2: TestStarting
        test "t" execution 2: InfoProvided(message: "direct")
        test "t" execution 2: TestSucceeded
        """
      And the event log holds no other `InfoProvided`

    Scenario: AC-153 The message is sent after the test has closed
      Given suite `DirectSuite`, class `samples.DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter` and, after `super.run` returns, to send that reporter an `InfoProvided` with message `direct` and a `nameInfo` naming `DirectSuite` and test `t`
      And the test `t` of `DirectSuite`, labelled `- t`, sends no message and succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "t": TestStarting
        test "t": TestSucceeded
        test "t": InfoProvided(message: "direct")
        """
      And the event log holds no other `InfoProvided`
```

| Requirement                      | Criteria                                               |
|----------------------------------|--------------------------------------------------------|
| R-097                            | AC-105, AC-106, AC-107, AC-151, AC-134, AC-152, AC-153 |
| `../../SPECIFICATION.md` § Scope | AC-106                                                 |

## What events carry

```gherkin
Feature: What the event log keeps of each event

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: Every event the emitter receives, and every part of the event that the consolidated output shows or uses, reaches the event log unaltered
    Scenario Outline: AC-108 Suites of every style under <launch>
      Given a set of suites holding at least one suite of each of the styles `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureAsyncFunSuite`, `AnyPropSpec`, `FixtureAnyPropSpec`, `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`, `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec`, `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`, `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`, `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec` and `RefSpec`
      And the suites hold between them nested suites, scopes of which one is pending, and tests that succeed, fail, are ignored, are pending and are cancelled
      And suites, scopes and tests among them send `info`, `note`, `alert` and `markup` messages, and a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test each send all four
      And the `beforeAll` of one of the suites throws
      And one of the suites is annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` and declares a scope
      And one test writes to stdout and to stderr
      When <launch> runs the suites sequentially with the runner argument
      Then for every event the emitter receives, and every event recorded in an event the emitter receives, the event log holds that event
      And each of these fields of the event in the event log equals that field of the event as the emitter received it: `message`, a `MarkupProvided`'s `text`, `throwable`, `location`, `duration`, `threadName`, `RunStarting`'s `testCount` and the `toString` of each value of its `configMap`, the `summary` of `RunCompleted`, `RunStopped` and `RunAborted`, and `TestFailed`'s `analysis`
      And each throwable in the event log has the class name, message, cause and suppressed throwables of the throwable the emitter received, with the failed code's file and line where it is a `StackDepthException`, and with its stack frames where it is not
      And the `timeStamp` of each event in the event log that is not a message recorded in a test's closing event equals the `timeStamp` of the event as the emitter received it
      And the timestamp of each message recorded in a test's closing event is the time the test called the message
      And the events the emitter receives that are not recorded in another event are in the event log in the order the emitter received them

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

  Rule: A throwable keeps everything the consolidated output shows or uses
    Scenario: AC-110 A cause chain with a suppressed throwable
      Given suite `ThrowSuite`, an `AnyFunSuite` whose test `throws`, labelled `- throws`, throws `IllegalStateException("outer")`, whose cause is `java.io.IOException("inner")` and which suppresses `RuntimeException("closing")`
      When `Framework` runs it with the runner argument
      Then the throwable of `test "throws": TestFailed` is `java.lang.IllegalStateException "outer" suppressing [java.lang.RuntimeException "closing"] caused by java.io.IOException "inner"`
      And each of the three throwables holds every frame of the corresponding thrown object, each frame with the text `StackTraceElement.toString` gives it

    Scenario Outline: AC-111 <throwable>
      Given suite `FailSuite`, an `AnyFunSuite` in file `FailSuite.scala` whose test `t`, labelled `- t`, <does>
      When `Framework` runs it with the runner argument
      Then the event log holds `test "t": TestFailed` with <what>

      Examples:
        | throwable                           | does                                                                                                                           | what                                                                                                                                               |
        | a `StackDepth` exception            | calls `fail("boom")` at line 7                                                                                                 | the throwable `org.scalatest.exceptions.TestFailedException "boom" at FailSuite.scala:7`, whose file pathname is the pathname of `FailSuite.scala` |
        | a property check failure            | runs a `forAll` from `TableDrivenPropertyChecks` whose property fails                                                          | a throwable marked `property check`                                                                                                                |
        | a throwable whose message is `null` | throws `new RuntimeException()`                                                                                                | the throwable `java.lang.RuntimeException null`                                                                                                    |
        | an assertion with analysis          | calls `assert(Point(1, 2) == Point(1, 3))`, where `Point` is a case class, so that the test's `TestFailed` carries an analysis | the `analysis` that the `TestFailed` the emitter receives for the test carries, `Point(y: 2 -> 3)`                                                 |
```

| Requirement                      | Criteria                       |
|----------------------------------|--------------------------------|
| `../../SPECIFICATION.md` § Scope | AC-108, AC-109, AC-110, AC-111 |

## Captured output

```gherkin
Feature: Captured output

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: The event log contains no captured output, and what a test writes reaches its process's stdout and stderr
    Scenario: AC-112 A test writes to both streams
      Given suite `BothStreamsSuite`, class `samples.BothStreamsSuite`, an `AnyFunSuite` whose test `writes`, labelled `- writes`, writes `dividing now` to stdout and `warn: careful` to stderr, then succeeds
      When `Framework` runs it with the runner argument, in a JVM of its own
      Then the event log holds no captured output
      And the JVM's stdout holds `dividing now`, and the JVM's stderr holds `warn: careful`
```

| Requirement                      | Criteria |
|----------------------------------|----------|
| `../../SPECIFICATION.md` § Scope | AC-112   |

## Reporters

```gherkin
Feature: The event log and reporters

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: A run's test event log does not depend on the run's reporters
    Scenario Outline: AC-059 <reporters>
      Given a set of suites holding at least one suite of each of the styles `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureAsyncFunSuite`, `AnyPropSpec`, `FixtureAnyPropSpec`, `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`, `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec`, `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`, `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`, `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec` and `RefSpec`
      And the suites hold between them nested suites, scopes of which one is pending, and tests that succeed, fail, are ignored, are pending and are cancelled
      And suites, scopes and tests among them send `info`, `note`, `alert` and `markup` messages, and a `PathAnyFunSpec` test and a `PathAnyFreeSpec` test each send all four
      And the `beforeAll` of one of the suites throws
      And one of the suites is annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` and declares a scope
      And one test writes to stdout and to stderr
      When `Runner.run` runs the suites sequentially with the runner argument, once with no reporter argument and once with <reporters>
      Then the two runs' test event logs hold the same events, apart from timestamps, durations and thread names

      Examples:
        | reporters                                                        |
        | `-oDF`                                                           |
        | `-oNCXEHLOPQRM`, which drops every event a filter character can drop |
        | `-e` and a `-C` recording reporter                               |
```

| Requirement                      | Criteria |
|----------------------------------|----------|
| `../../SPECIFICATION.md` § Scope | AC-059   |

## Unchanged behaviour

- AC-113 Compared with ScalaTest 3.2.20, no class of `org.scalatest.events` on this fork gains, loses or changes a field or constructor parameter, even where MiMa accepts the change. This fork adds no class to `org.scalatest.events` and removes none from it.
- AC-114 In a run not given the ScalaTest runner argument that turns the emitter on, every `-o`, `-e` and `-f` reporter that the run's arguments configure, `J` reporters included, writes what the same reporter writes on ScalaTest 3.2.20 for the same suites and the same other arguments. Where `Framework` runs the suites, such a reporter writes through `Framework`'s test-interface loggers. Any difference in a character, colour or line is a failure, apart from what the comparison in `../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x leaves out, which includes a `J` reporter's `stackTraces` and `depth`. In text, each run of *stack-frame lines*, the `at ` lines printed for a throwable's frames, counts as one line, whatever that run of lines contains.
- AC-169 Where a run's arguments configure no reporter, the reporter ScalaTest uses when no reporter is configured behaves as follows:
  - in a run not given the ScalaTest runner argument that turns the emitter on, the reporter writes what it writes on ScalaTest 3.2.20 for the same suites and the same arguments, through `Framework`'s test-interface loggers where `Framework` runs the suites. Any difference in a character, colour or line is a failure, apart from what the comparison in `../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x leaves out. In text, each run of stack-frame lines, the `at ` lines printed for a throwable's frames, counts as one line, whatever that run of lines contains;
  - in a run given that runner argument, the reporter prints nothing.
- AC-115 For an argument list that does not contain the ScalaTest runner argument that turns the emitter on, `Runner.run` and `Framework` on this fork accept the list where ScalaTest 3.2.20's `Runner.run` and `Framework` accept it. They reject the list where 3.2.20's reject it, with 3.2.20's message.
- AC-170 For an argument list that contains the ScalaTest runner argument that turns the emitter on, `Runner.run` and `Framework` on this fork behave as follows:
  - where the list gives the runner argument once, with a file after it, they accept the list if ScalaTest 3.2.20's `Runner.run` and `Framework` accept the list without the runner argument, and otherwise reject it with the message 3.2.20's `Runner.run` and `Framework` give for the list without the runner argument;
  - where the list gives the runner argument with no file after it, or more than once, they reject the list with a message of this fork's own, which says what is wrong with the runner argument.
- AC-116 On Scala.js and on Native, this fork takes no runner argument that turns the emitter on. On each of those runtimes, every reporter and the build tool's test-interface event handler receive what ScalaTest 3.2.20 sends them, as `../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x defines it, for a set of suites compiled for that runtime. The build tool's test-interface loggers receive the lines that they receive on 3.2.20 for those suites, apart from what that section's comparison leaves out. In those lines, each run of stack-frame lines, the `at ` lines printed for a throwable's frames, counts as one line, whatever that run of lines contains. The set of suites contains:
  - at least one suite of each of these styles that the build for that runtime ships: `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureAsyncFunSuite`, `AnyPropSpec`, `FixtureAnyPropSpec`, `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`, `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec`, `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`, `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`, `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec` and `RefSpec`;
  - nested suites;
  - scopes, one of them pending;
  - tests that succeed, fail, are ignored, are pending and are cancelled;
  - `info`, `note`, `alert` and `markup` messages from suites, scopes and tests, and all four from a `PathAnyFunSpec` test and from a `PathAnyFreeSpec` test;
  - a suite whose `beforeAll` throws;
  - a test that writes to stdout and to stderr.
- AC-117 A run on this fork that is not given the ScalaTest runner argument that turns the emitter on writes no test event log. That run creates no file that the same run on ScalaTest 3.2.20 does not create. While that run executes, `Thread.getAllStackTraces` in its process reports no thread that it does not report for the same run on 3.2.20.

| Requirement                                     | Criteria                               |
|-------------------------------------------------|----------------------------------------|
| R-001                                           | AC-113, AC-114, AC-169, AC-116, AC-117 |
| R-002                                           | AC-169                                 |
| `../../SPECIFICATION.md` § Scope § Out of scope | AC-115, AC-170                         |
