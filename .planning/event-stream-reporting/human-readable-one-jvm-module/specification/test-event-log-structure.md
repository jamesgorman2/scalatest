# Consolidated human-readable output for one JVM module: the run and each suite's structure

Acceptance criteria for what a run given the runner argument writes to its test event log on the JVM about the run and its suites: the run's start and end, and each suite's structure, labels and class name. Part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria sets the terms these criteria use, *an event log holding* events among them.

Each run below is launched outside Mill.

## The run

```gherkin
Feature: The run in its test event log

  Rule: A run's start is in its test event log before any test starts (R-046)
    Scenario: AC-094 A `Framework` runner is constructed
      When a `Framework` runner is constructed with the runner argument
      Then, before it is given a task, the event log's first event is `run started`
      And that event's PID and process start time are those `ProcessHandle.current()` gives in the run's process
      And the event log holds no `structure`

  Rule: A run's test event log records how the run ended (R-074)
    Scenario Outline: AC-060 A run that ends <ended>
      Given <the run>
      When <launch> runs it with the runner argument
      Then the event log's last event is `run ended <ended>`

      Examples:
        | the run                                                                                                                                                                      | launch       | ended     |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                                                                                                   | `Framework`  | completed |
        | suite `SqrtPropSpec` of `layout.md` AC-002                                                                                                                                   | `Runner.run` | completed |
        | suite `AbortSuite`, class `samples.AbortSuite`, an `AnyFunSuite` whose one test, `throws on pop`, labelled `- throws on pop`, throws `OutOfMemoryError("Java heap space")`   | `Runner.run` | aborted   |
        | suite `StopSuite`, class `org.scalatest.samples.StopSuite`, an `AnyFunSuite` mixing in `StopOnFailure`, which is private to ScalaTest's package, whose tests `a` and `b`, labelled `- a` and `- b`, run in that order and `a` fails | `Runner.run` | stopped   |

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
      And it holds no `SuiteAborted` and no closing event assigned to `throws on pop`

  Rule: A run that aborts before `RunStarting` records the abort against the run, and holds no `structure` (R-074)
    Scenario Outline: AC-133 A wrapped suite's constructor throws given an empty config map, by <launch>
      Given suite `ConfigSpec` of AC-132
      When <launch> runs it with the runner argument
      Then the event log holds, in this order:
        """
        run started
        run: RunAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
        run ended aborted
        """
      And it holds no `structure` and no `SuiteAborted`

      Examples:
        | launch                                                     |
        | `Runner.run`, with `-s samples.ConfigSpec`                 |
        | `Runner.run`, with the runpath given with `-R` and no `-s` |

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
      And no `AlertProvided` is assigned to test `sleeps` or suite `SlowSuite`
      And each line of its test event log decodes to one event
```

## Each suite's structure

```gherkin
Feature: Each suite's structure

  Rule: A suite's structure precedes the suite's first ScalaTest event, and every ScalaTest event is assigned to the node that emitted it (R-096, R-105)
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
      And no event before the `structure` is assigned to the suite or a node in it

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
      And no event is assigned to `A repo`, `read` or `write`

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
      Given suite `AddSuite`, class `samples.discovered.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds, and suite `SqrtPropSpec` of `layout.md` AC-002 with class `samples.discovered.SqrtPropSpec`, both on the runpath
      When `Runner.run` runs with the runner argument, the runpath given with `-R` and no `-s`, so that a `DiscoverySuite` runs them
      Then each of `AddSuite` and `SqrtPropSpec` has its own `structure`, whose root is that suite
      And no `structure` holds a `DiscoverySuite`, and the `DiscoverySuite`'s `SuiteStarting` and `SuiteCompleted` are assigned to `run`

    Scenario: AC-100 Tests run in parallel by `Runner`
      Given suite `ParFunSuite`, class `samples.ParFunSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose tests `a` and `b`, labelled `- a` and `- b`, succeed
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParFunSuite`
      Then the event log holds one `structure` holding `ParFunSuite`, and none holds a `DistributedTestRunnerSuite`
      And it holds exactly one `TestStarting` and one `TestSucceeded` assigned to each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`
      And each line of its test event log decodes to one event

    Scenario: AC-128 A suite wraps its reporter in its own
      Given suite `ForwardingSuite`, class `samples.ForwardingSuite`, an `AnyFunSuite` whose tests `a` and `b`, labelled `- a` and `- b`, succeed, and which overrides `run` to pass `super.run` its `args` with `args.reporter` wrapped in a `Reporter` that passes each event to it unchanged
      When `Framework` runs it with the runner argument
      Then the event log holds exactly one `TestStarting` and one `TestSucceeded` assigned to each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`

    Scenario: AC-129 The events of tests in random order are assigned to their own nodes
      Given suite `ShuffledSuite` of AC-102
      When `Framework` runs it with the runner argument
      Then the event log holds exactly one `TestStarting` and one `TestSucceeded` assigned to each of `a`, `b` and `c`, and no other `TestStarting` or `TestSucceeded`

    Scenario Outline: AC-130 A suite run through `ConfigMapWrapperSuite`, by <launch>
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`, holding test `reads`, labelled `- reads`, which succeeds
      When <launch> runs it with the runner argument
      Then the event log holds, in this order:
        """
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "reads", label "- reads"
        suite "ConfigSpec": SuiteStarting
        test "reads": TestStarting
        test "reads": TestSucceeded
        """
      And it holds one `structure` for `ConfigSpec`

      Examples:
        | launch                                                     |
        | `Framework`                                                |
        | `Runner.run`, with `-s samples.ConfigSpec`                 |
        | `Runner.run`, with the runpath given with `-R` and no `-s` |

  Rule: Where the instance of a suite that runs registers a tree other than the structure the event log holds for the suite, a further `structure` for the suite precedes that instance's first scope or test event (R-096, R-130)
    Scenario: AC-131 What a suite run through `ConfigMapWrapperSuite` registers depends on its config map
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`, holding test `remote`, labelled `- remote`, when its config map holds key `db`, and test `local`, labelled `- local`, when it does not, each succeeding
      When `Framework` runs it with the runner argument and `-Ddb=on`
      Then the event log holds, in this order:
        """
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "local", label "- local"
        suite "ConfigSpec": SuiteStarting
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
            scope "A config", label "A config"
              test "remote", label "- remote"
        scope "A config": ScopeOpened
        test "remote": TestStarting
        test "remote": TestSucceeded
        suite "ConfigSpec": SuiteCompleted
        """
      And it holds no other `structure`
      And no event is assigned to `local`, and none but `SuiteStarting` and `SuiteCompleted` to suite `ConfigSpec`

  Rule: A suite, scope or test that a further `structure` holds keeps the node ID an earlier `structure` for the suite gave it (R-130)
    Scenario: AC-154 A scope and a test that both structures hold
      Given suite `ConfigSpec` of AC-131, whose scope `A config` also holds test `reads`, labelled `- reads`, whatever its config map
      When `Framework` runs it with the runner argument and `-Ddb=on`
      Then in the further `structure` for `ConfigSpec`, each of suite `ConfigSpec`, scope `A config` and test `reads` has the node ID the first `structure` gives it
      And `remote` has a node ID the first `structure` gives no node

  Rule: A `DistributedTestRunnerSuite`'s abort is assigned to the one test it ran (R-096)
    Scenario: AC-126 The suite running one test in parallel throws
      Given suite `ParAbortSuite`, class `samples.ParAbortSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose one test is `a`, labelled `- a`, and which overrides `run` to throw `RuntimeException("no fixture")` when given a test name, as the `DistributedTestRunnerSuite` running `a` gives it
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParAbortSuite`
      Then the event log holds `test "a": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - no fixture", throwable: java.lang.RuntimeException "no fixture")`
      And no `SuiteAborted` is assigned to suite `ParAbortSuite` or to `run`

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
      And it holds `test "later": TestIgnored`, and no event is assigned to `slow`

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

```gherkin
Feature: Labels

  Rule: A scope's or test's label is the text ScalaTest 3.2.20's formatter gives the node's `ScopeOpened` or closing event, without its leading indentation, whether or not the node runs (R-106)
    Scenario Outline: AC-103 <styles>: <declaration>
      Given a suite of each of <styles>, declaring <declaration>
      When `Framework` runs each with the runner argument
      Then each suite's `structure` holds, below the suite, <nodes>

      Examples:
        | styles                                                                                          | declaration                                                                                         | nodes                                                                                               |
        | `AnyFunSuite`, `AsyncFunSuite`, `FixtureAnyFunSuite`, `FixtureAsyncFunSuite`                    | `test("t")`                                                                                         | `test "t", label "- t"`                                                                             |
        | `AnyPropSpec`, `FixtureAnyPropSpec`                                                            | `property("t")`                                                                                     | `test "t", label "- t"`                                                                             |
        | `AnyFunSpec`, `AsyncFunSpec`, `FixtureAnyFunSpec`, `FixtureAsyncFunSpec`, `PathAnyFunSpec`      | `describe("s") { it("t") }`                                                                         | `scope "s", label "s"`, holding `test "t", label "- t"`                                             |
        | `AnyFreeSpec`, `AsyncFreeSpec`, `FixtureAnyFreeSpec`, `FixtureAsyncFreeSpec`, `PathAnyFreeSpec` | `"s" - { "t" in }`                                                                                  | `scope "s", label "s"`, holding `test "t", label "- t"`                                             |
        | `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`                    | `"s" when { "u" should { "t" in } }`                                                                | `scope "s", label "s"`, holding `scope "u", label "when u"`, holding `test "t", label "- should t"` |
        | `AnyWordSpec`, `AsyncWordSpec`, `FixtureAnyWordSpec`, `FixtureAsyncWordSpec`                    | `"s" can { "t" in }`                                                                                | `scope "s", label "s"`, holding `test "t", label "- can t"`                                         |
        | `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`                    | `"s" should "t" in; it must "u" in`                                                                 | `scope "s", label "s"`, holding `test "t", label "- should t"` and `test "u", label "- must u"`     |
        | `AnyFlatSpec`, `AsyncFlatSpec`, `FixtureAnyFlatSpec`, `FixtureAsyncFlatSpec`                    | `behavior of "s"; it can "t" in`                                                                    | `scope "s", label "s"`, holding `test "t", label "- can t"`                                         |
        | `AnyFeatureSpec`, `FixtureAnyFeatureSpec`                                                       | `Feature("s") { Scenario("t") }`                                                                    | `scope "s", label "Feature: s"`, holding `test "t", label "Scenario: t"`                            |
        | `AsyncFeatureSpec`, `FixtureAsyncFeatureSpec`                                                   | `Feature("s") { Scenario("t") }`                                                                    | `scope "s", label "Feature: s"`, holding `test "t", label "- Scenario: t"`                          |
        | `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec`        | `Feature("s") { ignore("t") }`                                                                      | `scope "s", label "Feature: s"`, holding `test "t", label "- Scenario: t"`                          |
        | `RefSpec`                                                                                       | ``object `s v` { def `t u`: Unit = () }``, the scope's object name and the test method's name each holding a space, as `RefSpec` requires of both | `scope "s v", label "s v"`, holding `test "t u", label "- t u"`                                     |
```

## A suite's class name

A suite's class name is the one its first `structure` gives it, and every further `structure` for the suite carries the same (`test-event-log-executions.md` AC-135). It is the name `Class.getName` gives the runtime class of the suite instance the run was given: for a suite annotated `@WrapWith` with any wrapper but `ConfigMapWrapperSuite`, the wrapper's class. Two kinds of suite take another class:

- a suite whose constructor throws takes the class that failed to construct;
- a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` takes the annotated class (`../SPECIFICATION-DECISIONS.md` "A suite run through `ConfigMapWrapperSuite` carries the class name…").

```gherkin
Feature: A suite's class name

  Rule: A suite's structure carries its class name, whether or not the suite aborts (R-099)
    Scenario Outline: AC-104 <case>
      Given <suite>
      When <launch> runs it with the runner argument
      Then the event log holds a `structure` holding `suite "<name>", class "<class>"`, and later `suite "<name>": SuiteAborted(throwable: <throwable>)`

      Examples:
        | case                                                            | suite                                                                                                                                 | launch                              | name             | class                    | throwable                                                                                       |
        | a nested suite's `beforeAll` throws                             | suite `SetupFailsSuites`, class `samples.SetupFailsSuites`, a `Suites` with no tests nesting suite `SetupFailsSpec` of AC-097         | `Framework`                         | `SetupFailsSpec` | `samples.SetupFailsSpec` | `java.lang.RuntimeException "database unreachable"`                                             |
        | its constructor throws                                          | suite class `samples.BrokenCtorSpec` of `runs-and-test-structure-nodes.md` AC-068                                                     | `Framework`                         | `BrokenCtorSpec` | `samples.BrokenCtorSpec` | `java.lang.IllegalStateException "no config"`                                                   |
        | its constructor throws under discovery                          | suite class `samples.discovered.BrokenCtorSpec`, as the row above, on the runpath                                                      | `Runner.run`, with `-R` and no `-s` | `BrokenCtorSpec` | `samples.discovered.BrokenCtorSpec` | `java.lang.RuntimeException caused by java.lang.IllegalStateException "no config"`              |
        | a wrapped suite's `beforeAll` throws                            | suite `ConfigSpec` of AC-130, also mixing in `BeforeAndAfterAll`, whose `beforeAll` throws `RuntimeException("database unreachable")` | `Framework`                         | `ConfigSpec`     | `samples.ConfigSpec`     | `java.lang.RuntimeException "database unreachable"`                                             |
        | a wrapped suite's constructor throws given the run's config map | suite `ConfigSpec` of AC-130, whose constructor throws `IllegalStateException("no db")` where its config map holds key `db`           | `Framework`, with `-Ddb=on`         | `ConfigSpec`     | `samples.ConfigSpec`     | `java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db"` |

  Rule: A suite run through `ConfigMapWrapperSuite` whose class throws when constructed with an empty config map is aborted in the test event log alone (R-002, R-099)
    Scenario: AC-132 A wrapped suite's constructor throws given an empty config map
      Given suite `ConfigSpec` of AC-130, whose constructor throws `IllegalStateException("no db")` where its config map lacks key `db`
      When `Framework` runs it with the runner argument
      Then its task's `execute` throws a `java.lang.reflect.InvocationTargetException` caused by that `IllegalStateException`, and no reporter receives an event for `ConfigSpec`
      And the event log holds, in this order:
        """
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
        suite "ConfigSpec": SuiteStarting
        suite "ConfigSpec": SuiteAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
        run ended completed
        """
```
