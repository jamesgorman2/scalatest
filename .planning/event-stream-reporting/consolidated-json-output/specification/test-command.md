# Consolidated JSON output: the test command

This document contains the acceptance criteria for the `-fJ` argument: which output arguments the test command accepts and rejects, when it deletes and writes the file, and its result. It is part of [../SPECIFICATION.md](../SPECIFICATION.md), whose § Acceptance criteria defines the terms and notation these criteria use.

## Accepted arguments

```gherkin
Feature: The `-fJ` argument
  `<report>` and `<other>` are absolute paths, each in a folder that exists, at which nothing exists unless a scenario says otherwise.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes the line `dividing now` to stdout and succeeds

  Rule: `-fJ` writes the JSON output to the file at the path that follows it, and `-o` keeps its meaning beside it
    Scenario Outline: AC-001 <arguments>
      When the test command is invoked as `mill scalatest/test core.test <arguments>`
      Then the file at `<report>` holds a JSON object whose `kind` is `root`, and which holds a test node named `adds two numbers` whose `outcome` is `succeeded`
      And the console holds <console>
      And the test command succeeds

      Examples:
        | arguments              | console                                                                 |
        | `-fJ <report>`         | the human-readable output, whose lines include `- adds two numbers`     |
        | `-oH -fJ <report>`     | the human-readable output, whose lines include `- adds two numbers`     |
        | `-fJ <report> -oH`     | the human-readable output, whose lines include `- adds two numbers`     |
        | `-o -fJ <report>`      | no line of human-readable output, and no line holding `dividing now`   |

  Rule: The argument after `-fJ` is the file's path, and a module selector may come before or after the two
    Scenario: AC-002 The module selector follows the path
      When the test command is invoked as `mill scalatest/test -fJ <report> core.test`
      Then the root of the JSON output in the file at `<report>` holds `"moduleSelectors": [{ "selector": "core.test", "modules": ["core.test"] }]`
      And the test command succeeds

  Rule: The JSON output replaces whatever the file held
    Scenario: AC-003 A file from an earlier test command
      Given the file at `<report>` holds the text `left over`
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the file at `<report>` holds one JSON object, whose `kind` is `root`, and nothing else

  Rule: The JSON output is written once every run has ended, and no file is at the path while the run executes
    Scenario: AC-004 A test that looks for the file while it runs
      Given the file at `<report>` holds the text `left over`
      And suite `ProbeSuite`, class `samples.ProbeSuite`, an `AnyFunSuite` whose one test, `finds no report`, succeeds only where nothing exists at the path its config map gives under `report`
      And the module's own ScalaTest arguments are `-Dreport=<report>`
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the JSON output in the file at `<report>` holds a test node named `finds no report` whose `outcome` is `succeeded`

  Rule: The JSON output is written whatever the tests' outcomes, and a failing test still fails the test command
    Scenario: AC-005 A failing test
      Given suite `FailSuite`, class `samples.FailSuite`, an `AnyFunSuite` whose one test, `subtracts`, fails
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the JSON output in the file at `<report>` holds a test node named `subtracts` whose `outcome` is `failed`
      And the test command fails
```

| Requirement                   | Criteria                       |
|-------------------------------|--------------------------------|
| R-049a                        | AC-001, AC-002                 |
| R-055                         | AC-001, AC-002                 |
| R-020                         | AC-001                         |
| R-077                         | AC-003, AC-004                 |
| R-027                         | AC-004                         |
| `../SPECIFICATION.md` § Scope | AC-001, AC-002, AC-003, AC-005 |

## Rejected arguments

```gherkin
Feature: Output arguments the test command rejects
  `<report>` and `<other>` are absolute paths, each in a folder that exists, at which nothing exists unless a scenario says otherwise.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes the line `dividing now` to stdout and succeeds
    And the file at `<report>` holds the text `left over`

  Rule: An `-f` other than one `-fJ` followed by a path, or an `-o` other than `-oH` or a bare `-o`, fails the test command before any test starts, and leaves every file untouched
    Scenario Outline: AC-006 <arguments>
      When the test command is invoked as `mill scalatest/test core.test <arguments>`, which hold <what>
      Then the test command fails before any test starts, and its failure names `<rejected>`
      And the console holds no line of human-readable output
      And the file at `<report>` holds the text `left over`
      And nothing exists at `<other>`

      Examples:
        | arguments                       | rejected | what                                          |
        | `-fH <report>`                  | `-fH`    | an output letter other than `J`               |
        | `-fP <report>`                  | `-fP`    | an output letter other than `J`               |
        | `-fHP <report>`                 | `-fHP`   | output letters other than `J`                 |
        | `-fJH <report>`                 | `-fJH`   | an output letter other than `J`, beside `J`   |
        | `-fJc <report>`                 | `-fJc`   | a lowercase character                         |
        | `-f <report>`                   | `-f`     | no output letter                              |
        | `-fJ <report> -fJ <other>`      | `-fJ`    | a second `-f`                                 |
        | `-oJ -fJ <other>`               | `-oJ`    | an `-o` output letter other than `H`          |
        | `-oP -fJ <other>`               | `-oP`    | an `-o` output letter other than `H`          |

    Scenario: AC-007 `-fJ` with nothing after it
      When the test command is invoked as `mill scalatest/test core.test -fJ`
      Then the test command fails before any test starts, and its failure names `-fJ`
      And the console holds no line of human-readable output
      And the file at `<report>` holds the text `left over`
```

| Requirement                   | Criteria       |
|-------------------------------|----------------|
| R-049a                        | AC-006, AC-007 |
| `../SPECIFICATION.md` § Scope | AC-006, AC-007 |

## Deleting and writing the file

```gherkin
Feature: Deleting and writing the `-fJ` file
  `<report>` is an absolute path, in a folder that exists, at which nothing exists unless a scenario says otherwise.

  Background:
    Given a build whose JVM test module `core.test`, the module, tests with `com.edc::scalatest` at the release of this fork under test
    And the build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, without changing the module's definition
    And every JVM test module of the build has test parallelism off, one fork group and `testBatchFrameworkTasks` unset
    And suite `CalculatorSuite`, class `samples.CalculatorSuite`, an `AnyFunSuite` whose one test, `adds two numbers`, labelled `- adds two numbers`, writes the line `dividing now` to stdout and succeeds

  Rule: Once its arguments are accepted, the test command deletes the file before it resolves its module selectors, so a test command that then fails before any test starts leaves no file
    Scenario Outline: AC-008 <module selector>
      Given the build also has <other module>
      And the file at `<report>` holds the text `left over`
      When the test command is invoked as `mill scalatest/test <module selector> -fJ <report>`
      Then the test command fails before any test starts
      And the console holds no line of human-readable output
      And nothing exists at `<report>`

      Examples:
        | module selector | other module                                                                                     |
        | `nothing.test`  | no other module, so that `nothing.test` names no module                                          |
        | `util.test`     | JVM test module `util.test`, which tests with MUnit                                              |
        | `__.test`       | JVM test module `util.test`, which tests with this fork, so that `__.test` names two test modules |

  Rule: A file the test command cannot delete fails the test command before any test starts, naming the path
    Scenario: AC-009 A folder at the path
      Given a folder at `<report>` holding the file `keep.txt`
      When the test command is invoked as `mill scalatest/test core.test -fJ <report>`
      Then the test command fails before any test starts, and its failure names `<report>`
      And the console holds no line of human-readable output
      And the folder at `<report>` still holds the file `keep.txt`

  Rule: A file the test command cannot write fails the test command after the runs end, naming the path, and the human-readable output is still written
    Scenario: AC-010 A path inside a regular file
      Given a regular file at the absolute path `<blocker>`
      When the test command is invoked as `mill scalatest/test core.test -fJ <blocker>/report.json`
      Then the console holds the human-readable output, whose lines include `- adds two numbers`
      And the test command fails, and its failure names `<blocker>/report.json`
```

| Requirement                   | Criteria               |
|-------------------------------|------------------------|
| R-077                         | AC-008                 |
| R-059                         | AC-008                 |
| `../SPECIFICATION.md` § Scope | AC-008, AC-009, AC-010 |
