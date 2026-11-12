# Consolidated human-readable output for one JVM module: runs and test structure

Acceptance criteria for what the human-readable output shows of a run: which suites, scopes, tests, failures and captured output appear, in what order, and the run's block. Part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria sets the terms these criteria use, *an event log holding* items among them; [layout.md](layout.md) gives where each line sits and its text and colour.

## Order

```gherkin
Feature: The order of suites, scopes and tests

  Rule: A label's top-level suites appear in ascending order by name (R-079)
    Scenario: AC-065 Suites whose names sort apart from their classes and the order they ran in
      Given an event log holding:
        """
        run started
        structure
          suite "ZetaSuite", class "a.ZetaSuite"
            test "zeta", label "- zeta"
        suite "ZetaSuite": SuiteStarting
        test "zeta": TestSucceeded
        suite "ZetaSuite": SuiteCompleted
        structure
          suite "MidSuite", class "m.MidSuite"
            test "mid", label "- mid"
        suite "MidSuite": SuiteStarting
        test "mid": TestSucceeded
        suite "MidSuite": SuiteCompleted
        structure
          suite "AlphaSuite", class "z.AlphaSuite"
            test "alpha", label "- alpha"
        suite "AlphaSuite": SuiteStarting
        test "alpha": TestSucceeded
        suite "AlphaSuite": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the human-readable output is:
        """
        [core.test]
          AlphaSuite:
            - alpha
          MidSuite:
            - mid
          ZetaSuite:
            - zeta
        """

  Rule: Below a top-level suite, a node's children appear in declared order (R-105)
    Scenario: AC-066 Scopes and tests declared out of name order
      Given an event log holding:
        """
        run started
        structure
          suite "OrderFunSpec", class "samples.OrderFunSpec"
            scope "B", label "B"
              test "z", label "- z"
              test "a", label "- a"
            scope "A", label "A"
              test "m", label "- m"
        suite "OrderFunSpec": SuiteStarting
        scope "B": ScopeOpened
        test "z": TestSucceeded
        test "a": TestSucceeded
        scope "B": ScopeClosed
        scope "A": ScopeOpened
        test "m": TestSucceeded
        scope "A": ScopeClosed
        suite "OrderFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `OrderFunSpec` are:
        """
        OrderFunSpec:
          B
            - z
            - a
          A
            - m
        """
```

## Outcomes

```gherkin
Feature: Outcomes

  Rule: No line shows an outcome rolled up from the nodes below it (R-098)
    Scenario: AC-067 A failed test leaves its scope, suite and label without a suffix
      Given an event log holding:
        """
        run started
        structure
          suite "StackFunSpec", class "samples.StackFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
              test "pops", label "- pops"
        suite "StackFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        test "is empty": TestFailed(throwable: java.lang.IllegalStateException "not empty")
        test "pops": TestSucceeded
        scope "A Stack": ScopeClosed
        suite "StackFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the test run label's line is `[core.test]`, the suite's name line `StackFunSpec:` and the scope's line `A Stack`
      And the test's line is `- is empty *** FAILED ***`

  Rule: A suite whose constructor throws is aborted, and the module's other suites still run (R-099)
    Scenario: AC-068 A suite's constructor throws
      Given suite class `samples.BrokenCtorSpec`, an `AnyFunSuite` whose constructor throws `IllegalStateException("no config")`
      And suite `SqrtPropSpec` of `layout.md` AC-002
      When the test command runs the module
      Then the lines for the suite named `BrokenCtorSpec` begin:
        """
        BrokenCtorSpec:
          samples.BrokenCtorSpec *** ABORTED ***
        """
      And a line under `samples.BrokenCtorSpec *** ABORTED ***` is `java.lang.IllegalStateException: no config`
      And the lines for `SqrtPropSpec` are those `layout.md` AC-002 gives
```

## Nodes that share a name

```gherkin
Feature: Nodes that share a name

  Rule: Nodes that share a name are never merged (R-104)
    Scenario: AC-069 Two scopes share a name
      Given an event log holding:
        """
        run started
        structure
          suite "DupFunSpec", class "samples.DupFunSpec"
            scope "A Stack", label "A Stack"
              test "is empty", label "- is empty"
            scope "A Stack", label "A Stack"
              test "pops", label "- pops"
        suite "DupFunSpec": SuiteStarting
        scope "A Stack" #1: ScopeOpened
        test "is empty": TestSucceeded
        scope "A Stack" #1: ScopeClosed
        scope "A Stack" #2: ScopeOpened
        test "pops": TestSucceeded
        scope "A Stack" #2: ScopeClosed
        suite "DupFunSpec": SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the lines for `DupFunSpec` are:
        """
        DupFunSpec:
          A Stack
            - is empty
          A Stack
            - pops
        """

    Scenario: AC-070 Two suites share a name
      Given an event log holding:
        """
        run started
        structure
          suite "SameSuite", class "a.SameSuite"
            test "in a", label "- in a"
        suite "SameSuite" #1: SuiteStarting
        test "in a": TestSucceeded
        suite "SameSuite" #1: SuiteCompleted
        structure
          suite "SameSuite", class "b.SameSuite"
            test "in b", label "- in b"
        suite "SameSuite" #2: SuiteStarting
        test "in b": TestSucceeded
        suite "SameSuite" #2: SuiteCompleted
        run ended completed
        """
      When the library consolidates it
      Then the human-readable output holds two `SameSuite:` lines
      And the lines for one are `SameSuite:` and `- in a`, and for the other `SameSuite:` and `- in b`
```

## Filtered tests

```gherkin
Feature: Filtered tests

  Rule: A test a filter excludes is shown only as ScalaTest 3.2.19 reports it (R-112)
    Scenario: AC-071 A tag the module's own arguments exclude
      Given suite `SpeedSuite`, class `samples.SpeedSuite`, an `AnyFunSuite` with tests `fast` and `slow`, labelled `- fast` and `- slow`, each succeeding where it runs, of which `slow` is tagged `samples.Slow` and writes `slow ran` to stdout
      And the module's own ScalaTest arguments are `-l samples.Slow`
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SpeedSuite:
            - fast
        """
```

## Failures

```gherkin
Feature: Failures

  Rule: A failure message reaches the human-readable output unchanged (R-010, R-011)
    Scenario: AC-072 A multi-line, non-ASCII failure message
      Given suite `UnicodeSuite`, class `samples.UnicodeSuite`, an `AnyFunSuite` whose test `reports`, labelled `- reports`, calls `fail("first line\nsecond line: ü ß 日本 ✓")` at line 7 of `UnicodeSuite.scala`
      And a run of `UnicodeSuite` through `Framework` with the runner argument, whose test event log is fed to the consolidation library directly
      When the library consolidates it
      Then the lines under `- reports *** FAILED ***` begin:
        """
        first line
        second line: ü ß 日本 ✓ (UnicodeSuite.scala:7)
        """

  Rule: Every suite, scope, test and failure ScalaTest 3.2.19 prints is shown (R-021)
    Scenario: AC-073 The compatibility fixture
      Given the module's suites are the compatibility fixture
      And the file ScalaTest 3.2.19's `-f` reporter writes for the same suites when Mill's `testForked` runs them through `Framework`
      When the test command runs the module with Mill's `--color false`
      Then each line of that file from its first suite's name line and before the first line that begins `Run completed in`, other than a line that begins `+ ` (a message), is a line of the human-readable output, compared with leading spaces removed
      And each line the fixture writes to stdout or stderr is a `[stdout]` or `[stderr]` line of the human-readable output
```

## A run's block

```gherkin
Feature: A run's block

  Rule: A run's block follows its label's suites, and holds the run's messages, then its captured output, then how the run ended (R-012, R-082)
    Scenario: AC-035 A test's stdout line is shown in the block
      Given suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes `dividing now` to stdout and succeeds
      And nothing else in the run writes to stdout or stderr or sends a message outside a suite
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          CalculatorSuite:
            - adds two numbers
          Output outside any suite:
            [stdout] dividing now
        """

    Scenario: AC-054 The run's messages come before its captured output
      Given an event log holding:
        """
        run started
        run: InfoProvided(message: "Using random seed 1234")
        run ended completed
        """
      And the run's captured output is the stdout line `dividing now`, added before the library receives the `InfoProvided`
      When the library consolidates it
      Then the run's block is:
        """
        Output outside any suite:
          [info] Using random seed 1234
          [stdout] dividing now
        """

  Rule: Captured stdout and stderr lines interleave in the order they were read
    Scenario: AC-036 Lines of both streams in one sequence
      Given an event log holding `run started`, then `run ended completed`
      And the run's captured output is, in the order it was read:
        | stream | line                                    |
        | stderr | `SLF4J: No SLF4J providers were found.` |
        | stdout | `dividing now`                          |
        | stderr | `warn: about to divide`                 |
      When the library consolidates it
      Then the run's block is:
        """
        Output outside any suite:
          [stderr] SLF4J: No SLF4J providers were found.
          [stdout] dividing now
          [stderr] warn: about to divide
        """
```

## Captured output

```gherkin
Feature: Output captured against the run

  Rule: Output captured against the run is shown without claiming that it is test output or worker output (R-086)
    Scenario: AC-074 A test's line and the JVM's own line take one form
      Given suite `CalculatorSuite` of AC-035, whose test writes `dividing now` to stdout
      And the module's `forkArgs` hold `-XX:+UseSerialGC -Xlog:gc:stdout`, so the worker's JVM writes a line of its own ending `Using Serial` to stdout
      When the test command runs the module
      Then the run's block holds `[stdout] dividing now` and a line `[stdout] <text>` whose text ends `Using Serial`
      And each of those two lines is `[stdout] ` followed by nothing but the line written

  Rule: Each stream's captured lines keep their order (R-043)
    Scenario: AC-075 Many lines on both streams
      Given suite `ChattySuite`, class `samples.ChattySuite`, an `AnyFunSuite` whose one test, `chats`, labelled `- chats`, writes `out 1` to `out 2000` to stdout and `err 1` to `err 2000` to stderr, alternating between the streams, then succeeds
      When the test command runs the module
      Then the run's block's `[stdout]` lines are `out 1` to `out 2000`, in that order
      And its `[stderr]` lines are `err 1` to `err 2000`, in that order
```

## Runs that abort or stop

```gherkin
Feature: Runs that abort or stop

  Rule: An aborted run shows its aborted and not-run nodes, then `*** RUN ABORTED ***` last in its block (R-082, R-099, R-100)
    Scenario: AC-037 A test's fatal error aborts its suite and the run
      Given an event log holding:
        """
        run started
        run: InfoProvided(message: "Using random seed 1234")
        structure
          suite "StackFunSpec", class "samples.StackFunSpec"
            scope "A Stack", label "A Stack"
              scope "when empty", label "when empty"
                test "is empty", label "- is empty"
                test "throws on pop", label "- throws on pop"
                test "has size 0", label "- has size 0"
              scope "when full", label "when full"
                test "pops last pushed", label "- pops last pushed"
        suite "StackFunSpec": SuiteStarting
        scope "A Stack": ScopeOpened
        scope "when empty": ScopeOpened
        test "is empty": TestSucceeded
        test "throws on pop": TestStarting
        suite "StackFunSpec": SuiteAborted(throwable: java.lang.OutOfMemoryError "Java heap space")
        run: RunAborted(message: "An exception or error caused a run to abort: Java heap space", throwable: java.lang.OutOfMemoryError "Java heap space")
        run ended aborted
        """
      And the run's captured output is the stderr line `OpenJDK 64-Bit Server VM warning: CodeCache is full.`
      When the library consolidates it
      Then, with every stack frame left out, the human-readable output is:
        """
        [core.test]
          StackFunSpec:
            A Stack *** ABORTED ***
              when empty *** ABORTED ***
                - is empty
                - throws on pop *** ABORTED ***
                - has size 0 *** NOT RUN ***
              when full *** NOT RUN ***
                (1 test not run)
            samples.StackFunSpec *** ABORTED ***
              java.lang.OutOfMemoryError: Java heap space
          Output outside any suite:
            [info] Using random seed 1234
            [stderr] OpenJDK 64-Bit Server VM warning: CodeCache is full.
            *** RUN ABORTED ***
              An exception or error caused a run to abort: Java heap space
              java.lang.OutOfMemoryError: Java heap space
        """

  Rule: A stopped run shows its not-run nodes, and `*** RUN STOPPED ***` in its block (R-082, R-101)
    Scenario: AC-038 A run stops between two nested suites
      Given an event log holding:
        """
        run started
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
        suite "OuterSuites": SuiteCompleted
        run ended stopped
        """
      When the library consolidates it
      Then the human-readable output is:
        """
        [core.test]
          OuterSuites:
            Inner1Suite:
              - inner one
            Inner2Suite:
              samples.Inner2Suite *** NOT RUN ***
              (1 test not run)
          Output outside any suite:
            *** RUN STOPPED ***
        """
```
