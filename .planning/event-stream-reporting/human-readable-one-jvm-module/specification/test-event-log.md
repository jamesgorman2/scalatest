# Consolidated human-readable output for one JVM module: the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM, item by item, and for the ScalaTest behaviour that stays unchanged. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* items among them.

Each run below is launched outside Mill.

## The run

```gherkin
Feature: The run in its test event log

  Rule: A run's start is in its test event log before any test starts (R-046)
    Scenario: AC-094 A `Framework` runner is constructed
      When a `Framework` runner is constructed with the runner argument
      Then, before it is given a task, the event log's first item is `run started`
      And that item's PID and process start time are those `ProcessHandle.current()` gives in the run's process
      And the event log holds no `structure`

  Rule: A run's test event log records how the run ended (R-074)
    Scenario Outline: AC-060 A run that ends <ended>
      Given <the run>
      When <launch> runs it with the runner argument
      Then the event log's last item is `run ended <ended>`

      Examples:
        | the run                                                                                                                                                                      | launch       | ended     |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                                                                                                   | `Framework`  | completed |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                                                                                                   | `Runner.run` | completed |
        | suite `AbortSuite`, class `samples.AbortSuite`, an `AnyFunSuite` whose one test, `throws on pop`, labelled `- throws on pop`, throws `OutOfMemoryError("Java heap space")`   | `Runner.run` | aborted   |
        | suite `StopSuite`, class `samples.StopSuite`, an `AnyFunSuite` mixing in `StopOnFailure`, whose tests `a` and `b`, labelled `- a` and `- b`, run in that order and `a` fails | `Runner.run` | stopped   |

  Rule: A run that a test's fatal error aborts records the abort against the run, and no abort of the test's suite (R-074)
    Scenario: AC-120 `Runner.run` aborts on an `OutOfMemoryError`
      Given suite `AbortSuite` of AC-060
      When `Runner.run` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "throws on pop": TestStarting
        run: RunAborted(throwable: java.lang.OutOfMemoryError "Java heap space")
        run ended aborted
        """
      And it holds no `SuiteAborted` and no closing event tagged with `throws on pop`

  Rule: A message no suite, scope or test sends attaches to the run (R-097)
    Scenario: AC-121 A slowpoke alert
      Given suite `SlowSuite`, class `samples.SlowSuite`, an `AnyFunSuite` whose one test, `sleeps`, labelled `- sleeps`, sleeps 3 seconds, then succeeds
      When `Framework` runs it with the runner argument, `-W 1 1` and a `-C` recording reporter
      Then the event log holds, in this order:
        """
        test "sleeps": TestStarting
        run: AlertProvided
        test "sleeps": TestSucceeded
        """
      And it holds a `run: AlertProvided` for each `AlertProvided` the reporter receives, in the same order and with the same message, such as `Test still running after 1 second, 4 milliseconds: suite name: SlowSuite, test name: sleeps.`
      And no `AlertProvided` is tagged with test `sleeps` or suite `SlowSuite`
      And each line of its test event log decodes to one item
```

## Each suite's structure

```gherkin
Feature: Each suite's structure

  Rule: A suite's structure, after filtering, precedes the suite's first event, and every event is tagged with the node that emitted it (R-096, R-097, R-105)
    Scenario: AC-096 A suite whose tests run
      Given suite `StackFunSpec`, class `samples.StackFunSpec`, an `AnyFunSpec` declaring scope `A Stack`, labelled `A Stack`, holding scope `when empty`, labelled `when empty`, holding tests `is empty` and `pops`, labelled `- is empty` and `- pops`, each succeeding
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "StackFunSpec", class "samples.StackFunSpec"
            scope "A Stack", label "A Stack"
              scope "when empty", label "when empty"
                test "is empty", label "- is empty"
                test "pops", label "- pops"
        suite "StackFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        scope "when empty": ScopeOpened
        test "is empty": TestStarting
        test "is empty": TestSucceeded
        test "pops": TestStarting
        test "pops": TestSucceeded
        scope "when empty": ScopeClosed
        scope "A Stack": ScopeClosed
        suite "StackFunSpec": SuiteCompleted
        """
      And no item before the `structure` is tagged with the suite or a node in it

    Scenario: AC-097 A suite whose `beforeAll` throws
      Given suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, an `AnyWordSpec` declaring `"A repo" should { "read" in …; "write" in … }`: scope `A repo`, labelled `A repo`, holding tests `read` and `write`, labelled `- should read` and `- should write`
      And its `beforeAll` throws `RuntimeException("database unreachable")`
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "SetupFailsSpec", class "samples.SetupFailsSpec"
            scope "A repo", label "A repo"
              test "read", label "- should read"
              test "write", label "- should write"
        suite "SetupFailsSpec": SuiteStarting
        suite "SetupFailsSpec": SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")
        """
      And no item is tagged with `A repo`, `read` or `write`

    Scenario: AC-098 Nested suites sit under the suite that nests them
      Given suite `OuterSuites`, class `samples.OuterSuites`, a `Suites` with no tests, nesting `Inner1Suite`, class `samples.Inner1Suite`, and `Inner2Suite`, class `samples.Inner2Suite`, each an `AnyFunSuite` whose one test, `inner one` and `inner two`, labelled `- inner one` and `- inner two`, succeeds
      When `Framework` runs `OuterSuites` with the runner argument
      Then the event log holds, in this order:
        """
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
        suite "Inner2Suite": SuiteStarting
        test "inner two": TestStarting
        test "inner two": TestSucceeded
        suite "Inner2Suite": SuiteCompleted
        suite "OuterSuites": SuiteCompleted
        """

    Scenario: AC-099 Suites `Runner` discovers are top-level
      Given suite `AddSuite`, class `samples.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds, and suite `SqrtPropSpec` of `layout.md` AC-002, both on the runpath
      When `Runner.run` runs with the runner argument, the runpath given with `-R` and no `-s`, so that a `DiscoverySuite` runs them
      Then each of `AddSuite` and `SqrtPropSpec` has its own `structure`, whose root is that suite
      And no `structure` holds a `DiscoverySuite`, and the `DiscoverySuite`'s `SuiteStarting` and `SuiteCompleted` are tagged with `run`

    Scenario: AC-100 Tests run in parallel by `Runner`
      Given suite `ParFunSuite`, class `samples.ParFunSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose tests `a` and `b`, labelled `- a` and `- b`, succeed
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParFunSuite`
      Then the event log holds one `structure` holding `ParFunSuite`, and none holds a `DistributedTestRunnerSuite`
      And it holds exactly one `TestStarting` and one `TestSucceeded` tagged with each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`
      And each line of its test event log decodes to one item

    Scenario: AC-128 A suite wraps its reporter in its own
      Given suite `ForwardingSuite`, class `samples.ForwardingSuite`, an `AnyFunSuite` whose tests `a` and `b`, labelled `- a` and `- b`, succeed, and which overrides `run` to pass `super.run` its `args` with `args.reporter` wrapped in a `Reporter` whose `apply` passes each event unchanged to `args.reporter`
      When `Framework` runs it with the runner argument
      Then the event log holds exactly one `TestStarting` and one `TestSucceeded` tagged with each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`

    Scenario: AC-129 Tests in random order are tagged with their own nodes
      Given suite `ShuffledSuite` of AC-102
      When `Framework` runs it with the runner argument
      Then the event log holds exactly one `TestStarting` and one `TestSucceeded` tagged with each of `a`, `b` and `c`, and no other `TestStarting` or `TestSucceeded`

  Rule: A `DistributedTestRunnerSuite`'s abort is tagged with the one test it ran (R-096, R-097)
    Scenario: AC-126 The suite running one test in parallel throws
      Given suite `ParAbortSuite`, class `samples.ParAbortSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose one test is `a`, labelled `- a`, and which overrides `run` to throw `RuntimeException("no fixture")` when given a test name, as the `DistributedTestRunnerSuite` running `a` gives it
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParAbortSuite`
      Then the event log holds `test "a": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - no fixture", throwable: java.lang.RuntimeException "no fixture")`
      And no `SuiteAborted` is tagged with suite `ParAbortSuite` or with `run`

  Rule: A suite's structure holds only what the filter keeps, in declared order (R-105, R-112)
    Scenario: AC-101 A tag excluded, a test ignored
      Given suite `FilteredSuite`, class `samples.FilteredSuite`, an `AnyFunSuite` declaring tests `fast`, `slow` and `later`, labelled `- fast`, `- slow` and `- later`, of which `slow` is tagged `samples.Slow` and `later` is declared with `ignore`
      When `Framework` runs it with the runner argument and `-l samples.Slow`
      Then the event log's `structure` for `FilteredSuite` is:
        """
        structure
          suite "FilteredSuite", class "samples.FilteredSuite"
            test "fast", label "- fast"
            test "later", label "- later"
        """
      And it holds `test "later": TestIgnored`, and no item is tagged with `slow`

    Scenario: AC-102 Tests in random order
      Given suite `ShuffledSuite`, class `samples.ShuffledSuite`, an `AnyFunSuite` mixing in `RandomTestOrder`, registering tests `a`, `b` and `c`, labelled `- a`, `- b` and `- c`, in that order, each succeeding
      When `Framework` runs it with the runner argument
      Then its `structure` holds `a`, `b` and `c` in that order, whatever order the tests ran in

    Scenario: AC-127 A suite with no registration tree
      Given suite `PlainSuite`, class `samples.PlainSuite`, a `Suite` that overrides `testNames` as `Seq("b", "a", "c")`, `tags` to tag `c` with `samples.Slow`, and `runTest` to report `TestStarting` and `TestSucceeded` for the test it is given
      When `Framework` runs it with the runner argument and `-l samples.Slow`
      Then the event log's `structure` for `PlainSuite` is:
        """
        structure
          suite "PlainSuite", class "samples.PlainSuite"
            test "b", label "- b"
            test "a", label "- a"
        """
```

## Labels

In AC-103, the criterion holds for a style named without `Any`, `Async` or `Path`, such as `FunSuite`, on each Scala version whose build ships that style.

```gherkin
Feature: Labels

  Rule: A scope's or test's label is the text ScalaTest 3.2.19's formatter gives the node's `ScopeOpened` or closing event, without its leading indentation, whether or not the node runs (R-106)
    Scenario Outline: AC-103 <styles>: <declaration>
      Given a suite of each of <styles>, declaring <declaration>
      When `Framework` runs each with the runner argument
      Then each suite's `structure` holds, below the suite, <nodes>

      Examples:
        | styles                                                                                                                         | declaration                                                                                         | nodes                                                                                               |
        | `AnyFunSuite`, `FunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureFunSuite`, `FixtureAsyncFunSuite`                    | `test("t")`                                                                                         | `test "t", label "- t"`                                                                             |
        | `AnyPropSpec`, `PropSpec`, `AsyncPropSpec`, `FixtureAnyPropSpec`, `FixturePropSpec`, `FixtureAsyncPropSpec`                    | `property("t")`                                                                                     | `test "t", label "- t"`                                                                             |
        | `AnyFunSpec`, `FunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`        | `describe("s") { it("t") }`                                                                         | `scope "s", label "s"`, holding `test "t", label "- t"`                                             |
        | `AnyFreeSpec`, `FreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec` | `"s" - { "t" in }`                                                                                  | `scope "s", label "s"`, holding `test "t", label "- t"`                                             |
        | `AnyWordSpec`, `WordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureWordSpec`, `FixtureAsyncWordSpec`                    | `"s" when { "u" should { "t" in } }`                                                                | `scope "s", label "s"`, holding `scope "u", label "when u"`, holding `test "t", label "- should t"` |
        | `AnyWordSpec`, `WordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureWordSpec`, `FixtureAsyncWordSpec`                    | `"s" can { "t" in }`                                                                                | `scope "s", label "s"`, holding `test "t", label "- can t"`                                         |
        | `AnyFlatSpec`, `FlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureFlatSpec`, `FixtureAsyncFlatSpec`                    | `"s" should "t" in; it must "u" in`                                                                 | `scope "s", label "s"`, holding `test "t", label "- should t"` and `test "u", label "- must u"`     |
        | `AnyFlatSpec`, `FlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureFlatSpec`, `FixtureAsyncFlatSpec`                    | `behavior of "s"; it can "t" in`                                                                    | `scope "s", label "s"`, holding `test "t", label "- can t"`                                         |
        | `AnyFeatureSpec`, `FeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureFeatureSpec`                                                 | `Feature("s") { Scenario("t") }`                                                                    | `scope "s", label "Feature: s"`, holding `test "t", label "Scenario: t"`                            |
        | `AsyncFeatureSpec`, `FixtureAsyncFeatureSpec`                                                                                  | `Feature("s") { Scenario("t") }`                                                                    | `scope "s", label "Feature: s"`, holding `test "t", label "- Scenario: t"`                          |
        | `AnyFeatureSpec`, `FeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureFeatureSpec`, `FixtureAsyncFeatureSpec`  | `Feature("s") { ignore("t") }`                                                                      | `scope "s", label "Feature: s"`, holding `test "t", label "- Scenario: t"`                          |
        | `RefSpec`                                                                                                                      | ``object `s v` { def `t u`: Unit = () }``, the scope's object name and the test method's name each holding a space, as `RefSpec` requires of both | `scope "s v", label "s v"`, holding `test "t u", label "- t u"`                                     |
```

## A suite's class name

```gherkin
Feature: A suite's class name

  Rule: A suite's class name is the class the run loaded for it, however the suite aborts (R-099)
    Scenario Outline: AC-104 <case>
      Given <suite>
      When <launch> runs it with the runner argument
      Then the event log holds a `structure` holding `suite "<name>", class "<class>"`, and later `suite "<name>": SuiteAborted(throwable: <throwable>)`

      Examples:
        | case                                   | suite                                                                                                                         | launch                              | name             | class                    | throwable                                                                          |
        | a nested suite's `beforeAll` throws    | suite `SetupFailsSuites`, class `samples.SetupFailsSuites`, a `Suites` with no tests nesting suite `SetupFailsSpec` of AC-097 | `Framework`                         | `SetupFailsSpec` | `samples.SetupFailsSpec` | `java.lang.RuntimeException "database unreachable"`                                |
        | its constructor throws                 | suite class `samples.BrokenCtorSpec` of `runs-and-test-structure.md` AC-068                                                   | `Framework`                         | `BrokenCtorSpec` | `samples.BrokenCtorSpec` | `java.lang.IllegalStateException "no config"`                                      |
        | its constructor throws under discovery | suite class `samples.BrokenCtorSpec` of the row above, on the runpath                                                         | `Runner.run`, with `-R` and no `-s` | `BrokenCtorSpec` | `samples.BrokenCtorSpec` | `java.lang.RuntimeException caused by java.lang.IllegalStateException "no config"` |
```

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
      And no other `InfoProvided`, `NoteProvided`, `AlertProvided` or `MarkupProvided` is tagged with test `is empty`
      And each message's timestamp is the time the test called it

      Examples:
        | suite                                                                                                                                        | outcome  | closing event |
        | an `AnyFunSpec`                                                                                                                              | succeeds | TestSucceeded |
        | an `AnyFunSpec`                                                                                                                              | fails    | TestFailed    |
        | an `AnyFunSpec` that overrides `runTest` to pass `super.runTest` its `args` with `args.reporter` wrapped as `ForwardingSuite`'s is in AC-128 | succeeds | TestSucceeded |
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

  Rule: A message a test sends directly to a reporter an ancestor was given, rather than through the reporter the test was given, attaches to that ancestor (R-097)
    Scenario: AC-107 A test reports to its suite's reporter
      Given suite `DirectSuite`, an `AnyFunSuite` that overrides `run` to keep `args.reporter`, whose test `t`, labelled `- t`, sends that reporter an `InfoProvided` with message `direct`, then succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds `suite "DirectSuite": InfoProvided(message: "direct")`
      And no `InfoProvided` is tagged with test `t`
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
      Then the two runs' test event logs hold the same items, apart from timestamps, durations and thread names

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
