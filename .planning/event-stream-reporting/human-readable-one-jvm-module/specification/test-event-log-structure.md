# Consolidated human-readable output for one JVM module: the run and each suite's structure

This part contains the acceptance criteria for what a run given the ScalaTest runner argument that turns the emitter on writes to its test event log on the JVM about the run and its suites: the run's start and end, and each suite's structure, labels and class name. It is part of [test-event-log.md](test-event-log.md). [../SPECIFICATION.md](../SPECIFICATION.md) § Acceptance criteria defines the notation these criteria use for an event log.

## The run

```gherkin
Feature: The run in its test event log

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: A run's start is in its test event log before any test starts
    Scenario: AC-094 A `Framework` runner is constructed
      When a `Framework` runner is constructed with the runner argument
      Then, before it is given a task, the event log's first event is `run started`
      And that event's PID and process start time are those `ProcessHandle.current()` gives in the run's process
      And the event log holds no `structure`

  Rule: A run's test event log records how the run ended
    Scenario Outline: AC-060 A run that ends <ended>
      Given <the run>
      When <launch> runs it with the runner argument
      Then the event log's last event is `run ended <ended>`

      Examples:
        | the run                                                                                                                                                                      | launch       | ended     |
        | suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds | `Framework`  | completed |
        | suite `SqrtPropSpec`, class `samples.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds | `Runner.run` | completed |
        | suite `AbortSuite`, class `samples.AbortSuite`, an `AnyFunSuite` whose one test, `throws on pop`, labelled `- throws on pop`, throws `OutOfMemoryError("Java heap space")`   | `Runner.run` | aborted   |
        | suite `StopSuite`, class `org.scalatest.samples.StopSuite`, an `AnyFunSuite` mixing in `StopOnFailure`, which is private to ScalaTest's package, whose tests `a` and `b`, labelled `- a` and `- b`, run in that order and `a` fails | `Runner.run` | stopped   |

  Rule: A run that a test's fatal error aborts records the abort against the run and records no abort of the test's suite
    Scenario: AC-120 `Runner.run` aborts on an `OutOfMemoryError`
      Given suite `AbortSuite`, class `samples.AbortSuite`, an `AnyFunSuite` whose one test, `throws on pop`, labelled `- throws on pop`, throws `OutOfMemoryError("Java heap space")`
      When `Runner.run` runs it with the runner argument
      Then the event log holds, in this order:
        """
        test "throws on pop": TestStarting
        run: RunAborted(throwable: java.lang.OutOfMemoryError "Java heap space")
        run ended aborted
        """
      And the event log holds no `SuiteAborted` and no closing event assigned to `throws on pop`

  Rule: A run that aborts before `RunStarting` records the abort against the run, and its test event log contains no `structure`
    Scenario Outline: AC-133 A wrapped suite's constructor throws given an empty config map, by <launch>
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`, holding test `reads`, labelled `- reads`, which succeeds
      And the constructor of `ConfigSpec` throws `IllegalStateException("no db")` where its config map lacks key `db`
      When <launch> runs it with the runner argument
      Then the event log holds, in this order:
        """
        run started
        run: RunAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
        run ended aborted
        """
      And the event log holds no `structure` and no `SuiteAborted`

      Examples:
        | launch                                                     |
        | `Runner.run`, with `-s samples.ConfigSpec`                 |
        | `Runner.run`, with the runpath given with `-R` and no `-s` |

  Rule: A message that no suite, scope or test sends attaches to the run
    Scenario: AC-121 A slowpoke alert
      Given suite `SlowSuite`, class `samples.SlowSuite`, an `AnyFunSuite` whose one test, `sleeps`, labelled `- sleeps`, sleeps 3 seconds, then succeeds
      When `Framework` runs it with the runner argument and `-W 1 1`
      Then the event log holds, in this order:
        """
        test "sleeps": TestStarting
        run: AlertProvided
        test "sleeps": TestSucceeded
        """
      And for each `AlertProvided` the emitter receives before the run ends, the event log holds a `run: AlertProvided` with that alert's message, in the order the emitter received the alerts, such as `Test still running after 1 second, 4 milliseconds: suite name: SlowSuite, test name: sleeps.`
      And no `AlertProvided` is assigned to test `sleeps` or suite `SlowSuite`
      And each line of the run's test event log decodes to one event
```

| Requirement                      | Criteria                       |
|----------------------------------|--------------------------------|
| R-097b                           | AC-121                         |
| `../../SPECIFICATION.md` § Scope | AC-094, AC-060, AC-120, AC-133 |

## Each suite's structure

```gherkin
Feature: Each suite's structure

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: A suite's structure precedes the suite's first ScalaTest event, and every ScalaTest event is assigned to the node that emitted it
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
      Given suite `AddSuite`, class `samples.discovered.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds, and suite `SqrtPropSpec`, class `samples.discovered.SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, labelled `- sqrt of a square is the root`, succeeds, both on the runpath
      When `Runner.run` runs with the runner argument, the runpath given with `-R` and no `-s`, so that a `DiscoverySuite` runs them
      Then each of `AddSuite` and `SqrtPropSpec` has its own `structure`, whose root is that suite
      And no `structure` holds a `DiscoverySuite`, and the `DiscoverySuite`'s `SuiteStarting` and `SuiteCompleted` are assigned to `run`

    Scenario: AC-100 Tests run in parallel by `Runner`
      Given suite `ParFunSuite`, class `samples.ParFunSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose tests `a` and `b`, labelled `- a` and `- b`, succeed
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParFunSuite`
      Then the event log holds one `structure` holding `ParFunSuite`, and no `structure` holds a `DistributedTestRunnerSuite`
      And the event log holds exactly one `TestStarting` and one `TestSucceeded` assigned to each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`
      And each line of the run's test event log decodes to one event

    Scenario: AC-128 A suite wraps its reporter in its own
      Given suite `ForwardingSuite`, class `samples.ForwardingSuite`, an `AnyFunSuite` whose tests `a` and `b`, labelled `- a` and `- b`, succeed, and which overrides `run` to pass `super.run` its `args` with `args.reporter` wrapped in a `Reporter` that passes each event to it unchanged
      When `Framework` runs it with the runner argument
      Then the event log holds exactly one `TestStarting` and one `TestSucceeded` assigned to each of `a` and `b`, and no other `TestStarting` or `TestSucceeded`

    Scenario: AC-129 The events of tests in random order are assigned to their own nodes
      Given suite `ShuffledSuite`, class `samples.ShuffledSuite`, an `AnyFunSuite` mixing in `RandomTestOrder`, registering tests `a`, `b` and `c`, labelled `- a`, `- b` and `- c`, in that order, each succeeding
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
      And the event log holds one `structure` for `ConfigSpec`

      Examples:
        | launch                                                     |
        | `Framework`                                                |
        | `Runner.run`, with `-s samples.ConfigSpec`                 |
        | `Runner.run`, with the runpath given with `-R` and no `-s` |

  Rule: Where the suite instance that runs registers a tree other than the one the event log's `structure` for the suite contains, a further `structure` for the suite precedes the first scope or test event of that instance
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
      And the event log holds no other `structure`
      And no event is assigned to `local`, and the only events assigned to suite `ConfigSpec` are its `SuiteStarting` and `SuiteCompleted`

  Rule: A suite, scope or test that a further `structure` contains keeps the node path an earlier `structure` for the suite gave it
    Scenario: AC-154 A scope and a test that both structures contain
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`
      And scope `A config` holds test `reads`, labelled `- reads`, whatever the config map of `ConfigSpec`
      And scope `A config` also holds test `remote`, labelled `- remote`, when the config map of `ConfigSpec` holds key `db`, and test `local`, labelled `- local`, when it does not
      And each of the tests of `ConfigSpec` succeeds
      When `Framework` runs it with the runner argument and `-Ddb=on`
      Then in the further `structure` for `ConfigSpec`, each of suite `ConfigSpec`, scope `A config` and test `reads` has the node path the first `structure` gives it
      And `remote` has a node path the first `structure` gives no node

  Rule: A `DistributedTestRunnerSuite`'s abort is assigned to the one test it ran
    Scenario: AC-126 The suite running one test in parallel throws
      Given suite `ParAbortSuite`, class `samples.ParAbortSuite`, an `AnyFunSuite` mixing in `ParallelTestExecution`, whose one test is `a`, labelled `- a`, and which overrides `run` to throw `RuntimeException("no fixture")` when given a test name, as the `DistributedTestRunnerSuite` running `a` gives it
      When `Runner.run` runs it with the runner argument, `-P2` and `-s samples.ParAbortSuite`
      Then the event log holds `test "a": SuiteAborted(message: "Exception encountered when invoking run on a nested suite - no fixture", throwable: java.lang.RuntimeException "no fixture")`
      And no `SuiteAborted` is assigned to suite `ParAbortSuite` or to `run`

  Rule: A suite's structure contains only what the filter keeps, in declared order
    Scenario: AC-101 A tag excluded, a test ignored
      Given suite `FilteredSuite`, class `samples.FilteredSuite`, an `AnyFunSuite` declaring tests `fast`, `slow` and `later`, labelled `- fast`, `- slow` and `- later`, of which `slow` is tagged `samples.Slow` and `later` is declared with `ignore`
      When `Framework` runs it with the runner argument and `-l samples.Slow`
      Then the event log's `structure` for `FilteredSuite` is:
        """
        structure
          suite "FilteredSuite", class "samples.FilteredSuite"
            test "fast", label "- fast"
            test "later", label "- later", ignored
        """
      And the event log holds `test "later": TestIgnored`, and no event is assigned to `slow`

    Scenario: AC-102 Tests in random order
      Given suite `ShuffledSuite`, class `samples.ShuffledSuite`, an `AnyFunSuite` mixing in `RandomTestOrder`, registering tests `a`, `b` and `c`, labelled `- a`, `- b` and `- c`, in that order, each succeeding
      When `Framework` runs it with the runner argument
      Then the event log's `structure` for `ShuffledSuite` holds `a`, `b` and `c` in that order, whatever order the tests ran in

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

  Rule: A `structure` marks ignored each test it contains that ScalaTest's filter says the suite ignores, and no other test: a test tagged `org.scalatest.Ignore`, as a test declared with `ignore` and every test of a suite annotated `@Ignore` are, that the filter does not drop
    Scenario: AC-190 Every test of a suite annotated `@Ignore`
      Given suite `ShelvedSuite`, class `samples.ShelvedSuite`, an `AnyFunSuite` annotated `@Ignore`, declaring tests `a` and `b`, labelled `- a` and `- b`
      When `Framework` runs it with the runner argument
      Then the event log's `structure` for `ShelvedSuite` is:
        """
        structure
          suite "ShelvedSuite", class "samples.ShelvedSuite"
            test "a", label "- a", ignored
            test "b", label "- b", ignored
        """
      And the event log holds `test "a": TestIgnored` and `test "b": TestIgnored`, and no `TestStarting`

    Scenario: AC-191 A test tagged `org.scalatest.Ignore` in a suite with no registration tree
      Given suite `PlainSuite`, class `samples.PlainSuite`, a `Suite` that overrides `testNames` as `Seq("b", "a", "c")`, `tags` to tag `a` with `org.scalatest.Ignore`, and `runTest` to report `TestStarting` and `TestSucceeded` for the test it is given
      When `Framework` runs it with the runner argument
      Then the event log's `structure` for `PlainSuite` is:
        """
        structure
          suite "PlainSuite", class "samples.PlainSuite"
            test "b", label "- b"
            test "a", label "- a", ignored
            test "c", label "- c"
        """
      And the event log holds `test "a": TestIgnored`, and no `TestStarting` is assigned to `a`

    Scenario: AC-192 An ignored test that the filter drops is not in the structure
      Given suite `FilteredSuite`, class `samples.FilteredSuite`, an `AnyFunSuite` declaring tests `fast` and `later`, labelled `- fast` and `- later`, of which `later` is declared with `ignore` and tagged `samples.Slow`
      When `Framework` runs it with the runner argument and `-l samples.Slow`
      Then the event log's `structure` for `FilteredSuite` is:
        """
        structure
          suite "FilteredSuite", class "samples.FilteredSuite"
            test "fast", label "- fast"
        """
      And the event log holds no `TestIgnored`

    Scenario: AC-193 A further `structure` marks an ignored test that only the suite instance that runs registers
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`
      And scope `A config` holds test `remote`, labelled `- remote` and declared with `ignore`, when the config map of `ConfigSpec` holds key `db`, and test `local`, labelled `- local`, which succeeds, when it does not
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
              test "remote", label "- remote", ignored
        test "remote": TestIgnored
        suite "ConfigSpec": SuiteCompleted
        """
```

| Requirement                      | Criteria                                                                       |
|----------------------------------|--------------------------------------------------------------------------------|
| R-096a                           | AC-099, AC-100, AC-126                                                         |
| R-098a                           | AC-101, AC-190, AC-191, AC-193                                                 |
| R-130a                           | AC-096, AC-097, AC-098, AC-130, AC-102, AC-190, AC-191                         |
| R-130b                           | AC-131, AC-154, AC-193                                                         |
| R-130d                           | AC-096, AC-097, AC-098, AC-099, AC-100, AC-128, AC-129, AC-130, AC-131, AC-126 |
| `../../SPECIFICATION.md` § Scope | AC-096, AC-097, AC-098, AC-130, AC-101, AC-102, AC-127, AC-192                 |

## Labels

```gherkin
Feature: Labels

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: A scope's or test's label is the text ScalaTest 3.2.20's formatter gives the node's `ScopeOpened` or closing event, without its leading indentation, whether or not the node runs
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
        | `AnyFeatureSpec`, `AsyncFeatureSpec`, `FixtureAnyFeatureSpec`, `FixtureAsyncFeatureSpec`        | `Feature("s") { ignore("t") }`                                                                      | `scope "s", label "Feature: s"`, holding `test "t", label "- Scenario: t", ignored`                 |
        | `RefSpec`                                                                                       | ``object `s v` { def `t u`: Unit = () }``, the scope's object name and the test method's name each holding a space, as `RefSpec` requires of both | `scope "s v", label "s v"`, holding `test "t u", label "- t u"`                                     |
```

| Requirement                      | Criteria |
|----------------------------------|----------|
| `../../SPECIFICATION.md` § Scope | AC-103   |

## A suite's class name

```gherkin
Feature: A suite's class name

  Background:
    Given each run is launched outside Mill, on the JVM
    And *the runner argument* is the ScalaTest runner argument that turns the emitter on and names the file that the run writes its test event log to
    And *the event log* is the test event log that the run writes to that file

  Rule: Every `structure` that contains a suite gives it the same class name, as `Class.getName` names the class, whether or not the suite aborts: where the suite's constructor throws, the class that failed to construct; where the suite is annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, the annotated class; otherwise, the runtime class of the suite instance the run was given
    Scenario Outline: AC-104 <case>
      Given <suite>
      When <launch> runs it with the runner argument
      Then the event log holds a `structure` holding suite `<name>`, and every `structure` that holds suite `<name>` gives it class name <class>
      And the event log holds, in this order, a `structure` holding suite `<name>` and `suite "<name>": <end>`

      Examples:
        | case                                                                            | suite                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | launch                              | name             | class                               | end                                                                                                                      |
        | a suite that completes                                                          | suite `AddSuite`, class `samples.AddSuite`, an `AnyFunSuite` whose one test, `adds`, labelled `- adds`, succeeds                                                                                                                                                                                                                                                                                                                                                                                          | `Framework`                         | `AddSuite`       | `samples.AddSuite`                  | `SuiteCompleted`                                                                                                         |
        | a suite annotated `@WrapWith` with a wrapper other than `ConfigMapWrapperSuite` | suite `StackSpec`, class `samples.StackSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[samples.DelegatingSuite])`, declaring scope `A Stack`, labelled `A Stack`, holding test `pops`, labelled `- pops`, which succeeds; `samples.DelegatingSuite` is a `Suite` whose constructor takes the annotated class and builds an instance of it, gives that instance's `suiteName`, `suiteId` and `testNames` as its own, and overrides `run` to call that instance's `run` with the arguments it was given | `Framework`                         | `StackSpec`      | `samples.DelegatingSuite`           | `SuiteCompleted`                                                                                                         |
        | a nested suite's `beforeAll` throws                                             | suite `SetupFailsSuites`, class `samples.SetupFailsSuites`, a `Suites` with no tests nesting suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, an `AnyWordSpec` declaring `"A repo" should { "read" in …; "write" in … }`: scope `A repo`, labelled `A repo`, holding tests `read` and `write`, labelled `- should read` and `- should write`; the `beforeAll` of `SetupFailsSpec` throws `RuntimeException("database unreachable")`                                                                | `Framework`                         | `SetupFailsSpec` | `samples.SetupFailsSpec`            | `SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")`                                             |
        | a suite's constructor throws                                                    | suite class `samples.BrokenCtorSpec`, an `AnyFunSuite` whose constructor throws `IllegalStateException("no config")`                                                                                                                                                                                                                                                                                                                                                                                      | `Framework`                         | `BrokenCtorSpec` | `samples.BrokenCtorSpec`            | `SuiteAborted(throwable: java.lang.IllegalStateException "no config")`                                                   |
        | a suite's constructor throws under discovery                                    | suite class `samples.discovered.BrokenCtorSpec`, an `AnyFunSuite` whose constructor throws `IllegalStateException("no config")`, on the runpath                                                                                                                                                                                                                                                                                                                                                           | `Runner.run`, with `-R` and no `-s` | `BrokenCtorSpec` | `samples.discovered.BrokenCtorSpec` | `SuiteAborted(throwable: java.lang.RuntimeException caused by java.lang.IllegalStateException "no config")`              |
        | a wrapped suite's `beforeAll` throws                                            | suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` and mixing in `BeforeAndAfterAll`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`, holding test `reads`, labelled `- reads`, which succeeds; the `beforeAll` of `ConfigSpec` throws `RuntimeException("database unreachable")`                                                                                                       | `Framework`                         | `ConfigSpec`     | `samples.ConfigSpec`                | `SuiteAborted(throwable: java.lang.RuntimeException "database unreachable")`                                             |
        | a wrapped suite's constructor throws given the run's config map                 | suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]` and throws `IllegalStateException("no db")` where its config map holds key `db`, declaring scope `A config`, labelled `A config`, holding test `reads`, labelled `- reads`, which succeeds                                                                                                                                            | `Framework`, with `-Ddb=on`         | `ConfigSpec`     | `samples.ConfigSpec`                | `SuiteAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")` |

  Rule: A suite run through `ConfigMapWrapperSuite` whose class throws when constructed with an empty config map is aborted in the test event log, and the build tool's test-interface event handler receives no event for it
    Scenario: AC-132 A wrapped suite's constructor throws given an empty config map
      Given suite `ConfigSpec`, class `samples.ConfigSpec`, an `AnyFunSpec` annotated `@WrapWith(classOf[ConfigMapWrapperSuite])`, whose constructor takes a `Map[String, Any]`, declaring scope `A config`, labelled `A config`, holding test `reads`, labelled `- reads`, which succeeds
      And the constructor of `ConfigSpec` throws `IllegalStateException("no db")` where its config map lacks key `db`
      When `Framework` runs it with the runner argument
      Then the `execute` of the task for `ConfigSpec` throws a `java.lang.reflect.InvocationTargetException` caused by that `IllegalStateException`, and the build tool's test-interface event handler receives no event for `ConfigSpec`
      And the event log holds, in this order:
        """
        structure
          suite "ConfigSpec", class "samples.ConfigSpec"
        suite "ConfigSpec": SuiteStarting
        suite "ConfigSpec": SuiteAborted(throwable: java.lang.reflect.InvocationTargetException caused by java.lang.IllegalStateException "no db")
        run ended completed
        """
```

| Requirement                      | Criteria       |
|----------------------------------|----------------|
| R-051                            | AC-132         |
| `../../SPECIFICATION.md` § Scope | AC-104, AC-132 |
