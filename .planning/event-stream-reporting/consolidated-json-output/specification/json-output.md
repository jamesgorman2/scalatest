# Consolidated JSON output: what the JSON output holds

This document contains the acceptance criteria for what the JSON output holds: the root, module and run; suites, scopes and tests, and their order; messages and captured output; throwables; encoding; the consolidation library without Mill; and the format's documentation. It is part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria defines the terms and notation these criteria use.

## The root, the module and the run

```gherkin
Feature: The root, the module and the run in the JSON output
  `<report>` is an absolute path, in a folder that exists, at which nothing exists.
  In a JSON value, each placeholder stands for one whole value: `<time>` any integer, `<ms>` any non-negative integer, `<thread>` any string, `<release>` the version string of the release of this fork under test, `<mill>` the version string of the Mill release running the test command.
  `<line n of F>` is a location object holding `lineNumber` n and either `filePath`, a path whose last segment is F, or `fileName` F, and no other key. `<class C in F>` is a location object holding `className` C and either such a `filePath` or `fileName` F, and no other key.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suite `CalculatorSuite`, class `samples.CalculatorSuite` in `CalculatorSuite.scala`, an `AnyFunSuite` whose one test, `adds two numbers`, registered at line 5, writes `dividing now` followed by `\n` to stdout with `print` and succeeds
    And nothing else in the run writes to stdout or stderr or sends a message

  Rule: The JSON output is the root, holding the release of this fork, Mill and the module selector, then the module, the module's run and the run's suites and tests, each with its times
    Scenario: AC-011 One run of one suite
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the JSON output is, in full:
        """
        {
          "kind": "root",
          "scalaTestVersion": <release>,
          "buildTool": { "name": "Mill", "version": <mill> },
          "moduleSelectors": [{ "selector": "core.test", "modules": ["core.test"] }],
          "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
          "contents": [{
            "kind": "module",
            "name": "core.test",
            "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
            "contents": [{
              "kind": "run",
              "number": 1,
              "configMap": {},
              "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
              "summary": {
                "testsSucceededCount": 1, "testsFailedCount": 0, "testsIgnoredCount": 0, "testsPendingCount": 0,
                "testsCanceledCount": 0, "suitesCompletedCount": 1, "suitesAbortedCount": 0, "scopesPendingCount": 0
              },
              "contents": [{
                "kind": "suite",
                "name": "CalculatorSuite",
                "suiteId": "samples.CalculatorSuite",
                "location": <class samples.CalculatorSuite in CalculatorSuite.scala>,
                "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
                "threadName": <thread>,
                "contents": [{
                  "kind": "test",
                  "name": "adds two numbers",
                  "label": "- adds two numbers",
                  "location": <line 5 of CalculatorSuite.scala>,
                  "outcome": "succeeded",
                  "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
                  "threadName": <thread>,
                  "contents": []
                }]
              }],
              "output": [{ "kind": "stdio", "stream": "stdout", "text": "dividing now\n" }]
            }]
          }]
        }
        """

  Rule: The test command times the root and the module by its own clock, around the run, and no test event log holds those times
    Scenario: AC-012 The root's, module's and run's times
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then in the JSON output the root's `startedAt` is no later than the module's, and the module's is no later than the run's
      And the run's `endedAt` is no later than the module's, and the module's is no later than the root's
      And the root's `duration` is its `endedAt` less its `startedAt`, and so is the module's
      And the run's test event log holds no event for the root or the module

  Rule: The root records each module selector as given, with the names of the test modules it resolved to
    Scenario Outline: AC-013 <module selector>
      Given the build has no test module other than `core.test`
      When the test command is invoked as `mill scalatest/test <module selector> -fJ <report>`
      Then the root of the JSON output holds `"moduleSelectors": [{ "selector": "<module selector>", "modules": ["core.test"] }]`

      Examples:
        | module selector |
        | core.test       |
        | __.test         |

  Rule: A run's config map is written with each value's `toString`
    Scenario: AC-014 A config map entry
      Given the module's own ScalaTest arguments are `-Dgreeting=hello`
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the run node of the JSON output holds `"configMap": { "greeting": "hello" }`
```

| Requirement | Criteria               |
|-------------|------------------------|
| R-028       | AC-011                 |
| R-084       | AC-011                 |
| R-096a      | AC-011                 |
| R-104       | AC-011                 |
| R-074a      | AC-011                 |
| R-098d      | AC-011                 |
| R-097a      | AC-011                 |
| R-097b      | AC-011                 |
| R-103       | AC-011, AC-012         |
| R-055       | AC-013                 |
| R-011       | AC-014                 |

## Suites, scopes and tests

Each row is a criterion. Its module, under the Background of the Feature above, holds instead of `CalculatorSuite` the one suite `RowSuite`, class `samples.RowSuite` in `RowSuite.scala`, written as the row gives, and any suite the row names besides. The test command is invoked as `mill scalatest/test core.test -fJ <report>`. The JSON output then holds the row's values. A node named in the row is the one node of that `kind` and `name` in the JSON output.

| ID     | `RowSuite`                                                                                                                                                                          | The JSON output holds                                                                                                                                                                                                                                                                         |
|--------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC-015 | an `AnyFunSuite` whose test `adds`, at line 7, runs `val sum = 2; assert(sum == 3)`                                                                                                 | test `adds` holding `"outcome": "failed"`, `"message": "2 did not equal 3"` and `"throwable": { "id": 1, "className": "org.scalatest.exceptions.TestFailedException", "message": "2 did not equal 3", "location": <line 7 of RowSuite.scala> }`, the `throwable` in full                       |
| AC-016 | an `AnyFunSuite` whose test `adds` runs `cancel("no database")`                                                                                                                     | test `adds` holding `"outcome": "canceled"` and `"message": "no database"`, and a `throwable` holding `"className": "org.scalatest.exceptions.TestCanceledException"`                                                                                                                       |
| AC-017 | an `AnyFunSuite` whose test `adds` is registered with `ignore`                                                                                                                      | test `adds` holding `"outcome": "ignored"`, `endedAt` and `threadName`, with no `startedAt` and no `duration`                                                                                                                                                                                  |
| AC-018 | an `AnyFunSuite` whose test `adds` runs `pending`                                                                                                                                   | test `adds` holding `"outcome": "pending"`                                                                                                                                                                                                                                                    |
| AC-019 | an `AnyFunSpec` holding `describe("A Stack") { it("pops") { } }`, the `describe` at line 6                                                                                          | suite `RowSuite` whose `contents` is one scope, holding `"name": "A Stack"`, `"label": "A Stack"`, `"location": <line 6 of RowSuite.scala>`, `startedAt`, `endedAt` and a `duration` equal to its `endedAt` less its `startedAt`, with no `outcome` and no `threadName`, and whose `contents` is one test holding `"name": "pops"` and `"label": "- pops"` |
| AC-020 | an `AnyFunSpec` holding `describe("A Queue") { pending }`                                                                                                                           | scope `A Queue` holding `"outcome": "pending"`                                                                                                                                                                                                                                                |
| AC-021 | an `AnyFunSuite` with `BeforeAndAfterAll`, whose `beforeAll` throws `new IllegalStateException("no fixture")`, and whose one test is `adds`                                          | suite `RowSuite` holding `"outcome": "aborted"` and a `throwable` holding `"className": "java.lang.IllegalStateException"`, `"message": "no fixture"` and `"stackTrace": <frames>`; and test `adds` holding `"outcome": "aborted"` and `"reason": "notRun"`, with no `startedAt`, `message` or `throwable` |
| AC-022 | `class RowSuite extends Suites(new InnerSuite)`, where `InnerSuite`, class `samples.InnerSuite`, annotated `@DoNotDiscover`, is an `AnyFunSuite` whose one test `adds` succeeds      | a run node whose `contents` is one suite, `RowSuite`, whose `contents` is one suite, `InnerSuite`, whose `contents` is one test, `adds`                                                                                                                                                        |
| AC-023 | an `AnyFunSuite` whose `runTest` override calls `super.runTest` twice, and whose one test `adds` succeeds                                                                           | suite `RowSuite` whose `contents` is two tests, each holding `"name": "adds"` and `"outcome": "succeeded"`, the first `startedAt` no later than the second                                                                                                                                      |
| AC-024 | an `AnyFunSuite` whose test `talks` calls `info("one")`, `note("two")`, `alert("three")` and `markup("four")`, in that order                                                         | test `talks` whose `output` is four items, holding in order `"kind": "info", "message": "one"`, `"kind": "note", "message": "two"`, `"kind": "alert", "message": "three"` and `"kind": "markup", "message": "four"`, each with an `at`, and none with a `threadName`                           |
| AC-025 | an `AnyFunSuite` whose test `talks` runs `info("away")` on a thread other than its own and waits for it                                                                             | test `talks` whose `output` is one item, holding `"kind": "info"`, `"message": "away"` and a `threadName` other than test `talks`'s `threadName`                                                                                                                                             |
| AC-026 | an `AnyFunSuite` whose test `throws` builds `e = new RuntimeException("outer", new java.io.IOException("inner"))`, calls `e.addSuppressed(new IllegalStateException("closing"))` and throws `e` | test `throws` holding `"outcome": "failed"` and, in full, `"throwable": { "id": 1, "className": "java.lang.RuntimeException", "message": "outer", "stackTrace": <frames>, "cause": { "id": 2, "className": "java.io.IOException", "message": "inner", "stackTrace": <frames> }, "suppressed": [{ "id": 3, "className": "java.lang.IllegalStateException", "message": "closing", "stackTrace": <frames> }] }` |
| AC-027 | an `AnyFunSuite` whose test `loops` builds `a = new RuntimeException("a")` and `b = new RuntimeException("b", a)`, calls `a.initCause(b)` and throws `b`                            | test `loops` holding, in full, `"throwable": { "id": 1, "className": "java.lang.RuntimeException", "message": "b", "stackTrace": <frames>, "cause": { "id": 2, "className": "java.lang.RuntimeException", "message": "a", "stackTrace": <frames>, "cause": { "ref": 1 } } }`                 |
| AC-028 | an `AnyFunSuite` whose test `throws` throws `new RuntimeException()`                                                                                                                | test `throws` whose `throwable` has no `message` key, and in whose `stackTrace` each frame holds `className` and `methodName` and no frame holds `"isNative": false`                                                                                                                          |
| AC-029 | an `AnyFunSuite` whose `suiteName` is `ZebraSuite`, with one test that succeeds; beside it, suite `AlphaSuite`, class `samples.ZSuite`, an `AnyFunSuite` with one test that succeeds | a run node whose `contents` is suite `AlphaSuite` then suite `ZebraSuite`                                                                                                                                                                                                                     |
| AC-030 | an `AnyFunSuite` whose tests `subtracts` and `adds` are registered in that order                                                                                                    | suite `RowSuite` whose `contents` is test `subtracts` then test `adds`                                                                                                                                                                                                                        |

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-098a      | AC-015, AC-016, AC-017, AC-018                 |
| R-098b      | AC-019, AC-020                                 |
| R-098c      | AC-021                                         |
| R-095a      | AC-021                                         |
| R-130a      | AC-021                                         |
| R-130c      | AC-021, AC-022                                 |
| R-130d      | AC-022                                         |
| R-130e      | AC-023                                         |
| R-103       | AC-017, AC-019                                 |
| R-097a      | AC-024                                         |
| R-097b      | AC-024, AC-025                                 |
| R-043       | AC-024                                         |
| R-011       | AC-015, AC-026, AC-027                         |
| R-105       | AC-029, AC-030                                 |
| R-028       | AC-015 to AC-030                               |

## The same tree as the human-readable output

```gherkin
Feature: The JSON output and the human-readable output hold one node tree
  `<report>` is an absolute path, in a folder that exists, at which nothing exists.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suites of the module, between them, hold nested suites, scopes nested in scopes, a pending scope, tests that succeed, fail, are cancelled, are ignored and are pending, and a suite annotated `@WrapWith(classOf[ConfigMapWrapperSuite])` with a scope, whose scopes and tests two instances of the suite register
    And no suite aborts

  Rule: Each suite, scope and test of the JSON output is a line of the human-readable output, in the same order
    Scenario: AC-031 Walking both outputs
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then visiting the suites, scopes and tests of the JSON output depth-first, each node before its `contents` in order, gives each suite's `name` and each scope's and test's `label` and `outcome` in the order the human-readable output prints the lines for those suites, scopes and tests

  Rule: The module's own ScalaTest reporter arguments do not change the JSON output
    Scenario: AC-032 Reporter arguments on the module
      Given the JSON output of the test command invoked as `mill scalatest/test core.test -fJ <report>`
      When the module's own ScalaTest arguments are set to `-oDW` and the test command is invoked again the same way
      Then the JSON output is the same, apart from the values of `startedAt`, `endedAt`, `duration`, `at` and `threadName`
```

| Requirement                            | Criteria |
|----------------------------------------|----------|
| R-020                                  | AC-031   |
| R-105                                  | AC-031   |
| R-130a, R-130b, R-130c, R-130d, R-130f | AC-031   |
| R-063                                  | AC-032   |

## Runs and the root fed directly to the library

```gherkin
Feature: Runs and the root fed directly to the consolidation library
  A run fed directly to the library is given to the consolidation library without Mill, as the events of its test event log in the order listed and the lines captured from its process, under module `core.test`, with module selector `core.test` resolving to `core.test`, no build tool, and no start or end events for the root or the module.
  `runStarted`, `structure` and `runEnded` are the events this fork adds to a test event log; every other event is named by its `org.scalatest.events` class.
  In a JSON value, `<time>` is any integer, `<ms>` any non-negative integer and `<thread>` any string, each standing for one whole value.

  Rule: A run node records how the run ended
    Scenario Outline: AC-033 A run that ended <how>
      Given a run fed directly to the library whose events are `runStarted`, `RunStarting`, <closing event> and `runEnded` marking the run <how>
      When the library writes the JSON output
      Then the run node <holds>

      Examples:
        | how       | closing event                      | holds                                                   |
        | completed | `RunCompleted`                     | has no `outcome`                                        |
        | stopped   | `RunStopped`                       | holds `"outcome": "stopped"`                            |
        | aborted   | `RunAborted` with message `boom`   | holds `"outcome": "aborted"` and `"message": "boom"`    |

  Rule: A run node's output holds its messages and captured lines in the order the library received them, each line with its terminator
    Scenario: AC-034 A message between two captured lines
      Given a run fed directly to the library whose events are `runStarted`, `RunStarting`, `InfoProvided` with message `between` and no suite, `RunCompleted` and `runEnded` marking the run completed
      And the line `before\n` captured from stdout is fed before the `InfoProvided`, and the line `after\r\n` captured from stderr after it
      When the library writes the JSON output
      Then the run node's `output` is, in full:
        """
        [
          { "kind": "stdio", "stream": "stdout", "text": "before\n" },
          { "kind": "info", "message": "between", "at": <time>, "threadName": <thread> },
          { "kind": "stdio", "stream": "stderr", "text": "after\r\n" }
        ]
        """

  Rule: A test open when its suite aborts is aborted, ended by the suite's abort, with no message of its own
    Scenario: AC-035 A suite aborts during a test
      Given a run fed directly to the library whose events are `runStarted`, `RunStarting`, a `structure` of suite `S`, class `samples.S`, holding test `t` labelled `- t`, then `SuiteStarting` of `S`, `TestStarting` of `t`, `SuiteAborted` of `S` with message `boom`, `RunCompleted` and `runEnded` marking the run completed
      When the library writes the JSON output
      Then suite `S` holds `"outcome": "aborted"` and `"message": "boom"`
      And test `t` holds `"outcome": "aborted"`, a `startedAt`, the `SuiteAborted`'s time as its `endedAt`, and a `duration` equal to its `endedAt` less its `startedAt`, with no `message`

  Rule: Output and failures attached to the root are written on the root
    Scenario: AC-036 Discovery fails
      Given a consolidation started directly, without Mill, to which the line `discovering\n` captured from stderr and the failure `Discovery failed` are attached for the root
      When the library writes the JSON output
      Then the root holds, in order, `"outcome": "aborted"`, `"failures": [{ "message": "Discovery failed" }]` and `"output": [{ "kind": "stdio", "stream": "stderr", "text": "discovering\n" }]`

  Rule: The root has a build tool and times only where the library's caller gives them
    Scenario: AC-037 No build tool and no root or module events
      Given a run fed directly to the library whose events are `runStarted`, `RunStarting`, `RunCompleted` and `runEnded` marking the run completed
      When the library writes the JSON output
      Then the root has no `buildTool`, `startedAt`, `endedAt` or `duration` key
      And the module has no `startedAt`, `endedAt` or `duration` key
```

| Requirement | Criteria       |
|-------------|----------------|
| R-074a      | AC-033         |
| R-098d      | AC-033         |
| R-043       | AC-034         |
| R-011       | AC-034         |
| R-097a      | AC-034, AC-036 |
| R-095b      | AC-035         |
| R-103       | AC-035, AC-037 |
| R-098f      | AC-036         |

## The library with and without Mill

```gherkin
Feature: The same JSON output with and without Mill
  `<report>` is an absolute path, in a folder that exists, at which nothing exists.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, writes `dividing now` followed by `\n` to stdout with `print` and succeeds

  Rule: Given the same run and inputs, the library writes the same JSON output without Mill as under the test command
    Scenario: AC-038 Replaying the run
      Given the test command was invoked as `mill scalatest/test core.test -fJ <report>`
      When the run's test event log is fed directly to the consolidation library, without Mill, with the `text` of each item of the run node's `output` in the file at `<report>` as a line captured from that item's `stream`, module `core.test`, module selector `core.test` resolving to `core.test`, build tool `Mill` at the version the file's `buildTool` gives, and start and end events for the root and the module at the times the file gives them
      Then the library's JSON output is the JSON output in the file at `<report>`
```

| Requirement | Criteria |
|-------------|----------|
| R-113       | AC-038   |

## Encoding

```gherkin
Feature: Encoding of the JSON output
  `<report>` is an absolute path, in a folder that exists, at which nothing exists.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset

  Rule: Text is written verbatim, as UTF-8 JSON, with a lone surrogate escaped
    Scenario: AC-039 A message holding characters JSON must escape
      Given suite `EscapeSuite`, class `samples.EscapeSuite`, an `AnyFunSuite` whose test `escapes` calls `info` with the Java string `"tab\t quote\" slash\\ bell\u0007 é 😀 lone\uD800 end"`
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the file at `<report>` is valid UTF-8 and holds no `null`
      And the file writes the lone surrogate as the escape `\uD800`, in either case
      And the `message` of test `escapes`'s one `output` item, decoded as JSON text into UTF-16, is that Java string
```

| Requirement | Criteria |
|-------------|----------|
| R-011       | AC-039   |
| R-028       | AC-039   |

## The format's documentation

```gherkin
Feature: The JSON output's documentation

  Rule: The Mill plugin's README links the JSON output's documentation, which names every key
    Scenario: AC-040 Reading the documentation
      Given the release of this fork under test
      When an agent reads `mill-plugin/README.md`
      Then it links `mill-plugin/JSON.md`
      And `mill-plugin/JSON.md` names each key of the root, a module, a run node, a suite, scope or test, a message, a piece of captured output, a throwable and a location, with what the key holds and when it is absent
```

| Requirement                   | Criteria |
|-------------------------------|----------|
| `../SPECIFICATION.md` § Scope | AC-040   |
