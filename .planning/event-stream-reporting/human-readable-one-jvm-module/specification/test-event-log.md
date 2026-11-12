# Consolidated human-readable output for one JVM module: the test event log

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM, item by item, and for the ScalaTest behaviour that stays unchanged. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* items among them.

Each run below is launched outside Mill.

## The run

```gherkin
Feature: The run in its test event log

  Rule: A run's start is in its test event log before any test starts (R-071, R-080)
    Scenario: AC-094 A `Framework` runner is constructed
      When a `Framework` runner is constructed with the runner argument
      Then, before it is given a task, the event log's first item is `run started`
      And that item's PID and process start time are those `ProcessHandle.current()` gives in the run's process
      And the event log holds no `structure`

    Scenario: AC-095 A run given no task
      When a `Framework` runner is constructed with the runner argument, and its `done` is called without it being given a task
      Then the event log holds no `structure` and no item tagged with a suite, scope or test
      And its last item is `run ended completed`

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
        test "inner one": TestSucceeded
        suite "Inner1Suite": SuiteCompleted
        suite "Inner2Suite": SuiteStarting
        test "inner two": TestSucceeded
        suite "Inner2Suite": SuiteCompleted
        suite "OuterSuites": SuiteCompleted
        """

    Scenario: AC-099 Suites `Runner` discovers are top-level
      Given suites `CalculatorSuite`, class `samples.CalculatorSuite`, and `SqrtPropSpec`, class `samples.SqrtPropSpec`, on the runpath
      When `Runner.run` runs with the runner argument, the runpath given with `-R` and no `-s`, so that a `DiscoverySuite` runs them
      Then each of `CalculatorSuite` and `SqrtPropSpec` has its own `structure`, whose root is that suite
      And no `structure` holds a `DiscoverySuite`, and its `SuiteStarting` and `SuiteCompleted` are tagged with `run`

    Scenario: AC-100 Tests run in parallel by `Runner`
      Given suite `ParFunSuite`, class `samples.ParFunSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose tests `a` and `b`, labelled `- a` and `- b`, succeed
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParFunSuite`
      Then the event log holds one `structure` holding `ParFunSuite`, and none holds a `DistributedTestRunnerSuite`
      And each of `a` and `b` has its `TestStarting` and `TestSucceeded` tagged with its own node

  Rule: A suite's structure holds only what the filter keeps, in declared order (R-105, R-112)
    Scenario: AC-101 A tag excluded, a test ignored
      Given suite `SpeedSuite`, class `samples.SpeedSuite`, an `AnyFunSuite` declaring tests `fast`, `slow` and `later`, labelled `- fast`, `- slow` and `- later`, of which `slow` is tagged `samples.Slow` and `later` is declared with `ignore`
      When `Framework` runs it with the runner argument and `-l samples.Slow`
      Then the event log's `structure` for `SpeedSuite` is:
        """
        structure
          suite "SpeedSuite", class "samples.SpeedSuite"
            test "fast", label "- fast"
            test "later", label "- later"
        """
      And it holds `test "later": TestIgnored`, and no item is tagged with `slow`

    Scenario: AC-102 Tests in random order
      Given suite `ShuffledSuite`, class `samples.ShuffledSuite`, an `AnyFunSuite` mixing in `RandomTestOrder`, registering tests `a`, `b` and `c`, labelled `- a`, `- b` and `- c`, in that order
      When `Framework` runs it with the runner argument
      Then its `structure` holds `a`, `b` and `c` in that order, whatever order the tests ran in
```

## Labels

In AC-103, a style named without `Any`, `Async` or `Path`, such as `FunSuite` or `FixtureFunSuite`, is a variant the build generates from the `Any` trait; a row holds for it on each Scala version whose build ships it.

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
        | `RefSpec`                                                                                                                      | ``object `s` { def `t u`: Unit = () }``, a test method's name holding a space as `RefSpec` requires | `scope "s", label "s"`, holding `test "t u", label "- t u"`                                         |
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
        | case                                     | suite                                                                                                                                                  | launch                                        | name             | class                    | throwable                                                                      |
        | `beforeAll` throws                       | suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, of AC-097                                                                                       | `Framework`                                   | `SetupFailsSpec` | `samples.SetupFailsSpec` | `java.lang.RuntimeException "database unreachable"`                            |
        | a nested suite's `beforeAll` throws      | suite `OuterSuites`, class `samples.OuterSuites`, a `Suites` with no tests nesting suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, of AC-097  | `Framework`                                   | `SetupFailsSpec` | `samples.SetupFailsSpec` | `java.lang.RuntimeException "database unreachable"`                            |
        | its constructor throws                   | suite class `samples.BrokenCtorSpec`, whose constructor throws `IllegalStateException("no config")`                                                    | `Framework`                                   | `BrokenCtorSpec` | `samples.BrokenCtorSpec` | `java.lang.IllegalStateException "no config"`                                  |
        | its constructor throws under discovery   | suite class `samples.BrokenCtorSpec`, whose constructor throws `IllegalStateException("no config")`, on the runpath                                     | `Runner.run`, with `-R` and no `-s`           | `BrokenCtorSpec` | `samples.BrokenCtorSpec` | a `java.lang.RuntimeException` whose cause is `java.lang.IllegalStateException "no config"` |
```

## Messages

```gherkin
Feature: Messages

  Rule: A test's messages keep the order the test called them, whichever its outcome (R-011, R-097)
    Scenario Outline: AC-105 A test that <outcome>
      Given in suite `MessagesFunSpec`, an `AnyFunSpec`, test `is empty`, labelled `- is empty`, calls `info("checked size")`, `note("a note")`, `alert("an alert")` and `markup("*markup*")` in that order, then <outcome>
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "is empty": InfoProvided(message: "checked size")
        test "is empty": NoteProvided(message: "a note")
        test "is empty": AlertProvided(message: "an alert")
        test "is empty": MarkupProvided(text: "*markup*")
        """

      Examples:
        | outcome  |
        | succeeds |
        | fails    |

  Rule: A scope's message keeps its place among the scope's children (R-097, R-105)
    Scenario: AC-106 An info between two children of a scope
      Given suite `StackFunSpec`, an `AnyFunSpec` whose scope `A Stack`, labelled `A Stack`, declares test `is empty`, labelled `- is empty`, which succeeds, then calls `info("an info in a scope")`, then declares scope `when full`, labelled `when full`, holding test `pops`, labelled `- pops`, which succeeds
      When `Framework` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "is empty": TestSucceeded
        scope "A Stack": InfoProvided(message: "an info in a scope")
        scope "when full": ScopeOpened
        """

  Rule: A message sent past its node's reporter attaches to the nearest node that receives it (R-097)
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
    Scenario: AC-108 The compatibility fixture
      Given the compatibility fixture
      When `Runner.run` runs it sequentially with the runner argument and a `-C` recording reporter
      Then for every event the reporter receives, and every event recorded in one, the event log holds that event
      And each field of it that the consolidated output shows or uses equals the reporter's: each field `../../JSON-LAYOUT.md` folds into a node, message, throwable, location or run record, and `TestFailed`'s `analysis`
      And the events the reporter receives that are not recorded in another are in the event log in the order the reporter received them

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
      Then the throwable of `test "throws": TestFailed` is `java.lang.IllegalStateException "outer"`, with cause `java.io.IOException "inner"` and suppressed `java.lang.RuntimeException "closing"`
      And each of the three holds every frame of the thrown object, each with its class name, method name, file name, line number and whether it is native, and the text `StackTraceElement.toString` gives it

    Scenario Outline: AC-111 <throwable>
      Given suite `FailSuite`, an `AnyFunSuite` in file `FailSuite.scala` whose test `t`, labelled `- t`, <does>
      When `Framework` runs it with the runner argument
      Then `test "t": TestFailed` <holds>

      Examples:
        | throwable                                 | does                                                                         | holds                                                                                                                                                       |
        | a `StackDepth` exception                  | calls `fail("boom")` at line 7                                               | the throwable `org.scalatest.exceptions.TestFailedException "boom"`, marked as a `StackDepth`, with failed code file name `FailSuite.scala`, the file's pathname and line number 7 |
        | a property check failure                  | runs a `forAll` from `TableDrivenPropertyChecks` whose property fails         | a throwable marked as a `PropertyCheckFailedException`                                                                                                     |
        | a throwable whose message is `null`       | throws `new RuntimeException()`                                              | the throwable `java.lang.RuntimeException`, with no message                                                                                                |
        | an assertion with analysis                | calls `assert(Point(1, 2) == Point(1, 3))`, `Point` being a case class, for which 3.2.19's `TestFailed` carries an analysis | the `analysis` that a `-C` recording reporter in the same run receives on the test's `TestFailed`                                                         |
```

## Captured output

```gherkin
Feature: Captured output

  Rule: The event log holds no captured output; what a test writes reaches its process's stdout and stderr (R-010, R-012)
    Scenario: AC-112 A test writes to both streams
      Given suite `CalculatorSuite`, an `AnyFunSuite` whose test `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and `warn: careful` to stderr, then succeeds
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
        | `-oNCXEHLOPQRM`, whose event-filter characters drop every event  |
        | `-e` and a `-C` recording reporter                               |
```

## Unchanged behaviour

These hold on this fork against ScalaTest 3.2.19, with the emitter off and on (R-001, R-002, R-051).

- AC-113 No class of `org.scalatest.events` gains, loses or changes a field or constructor parameter, and no event class is added or removed. The structure, labels, class names, node tags and run start this feature adds exist only in the test event log. Any such change violates it, including an added field MiMa (`compatibility-and-library.md` AC-058) accepts.
- AC-114 For every event and every combination of its configuration characters, the text and colour `StringReporter` formats are 3.2.19's. Every `-o`, `-e` and `-f` reporter, `J` reporters included, and `Framework`'s test-interface loggers write what 3.2.19 writes for the same events. A difference in any character, colour or line violates it.
- AC-115 `Runner.run` and `Framework` accept every argument list 3.2.19's accept, and reject every one 3.2.19's reject with 3.2.19's message. The runner argument is the only argument they newly accept.
- AC-116 On Scala.js and Native, for the compatibility fixture compiled for each, every reporter and the build tool's test-interface event handler and loggers receive what 3.2.19 sends them.
- AC-117 A run not given the runner argument writes no test event log, and creates no file and starts no thread that 3.2.19 does not.
