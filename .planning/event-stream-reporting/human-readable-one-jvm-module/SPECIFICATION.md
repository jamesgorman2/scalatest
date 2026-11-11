# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest end-to-end path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the test command's stdout.

## Plan

[Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this document are the epic's.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture: the modules this feature starts, their parts (§ Modules), and the boundaries between them.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the feature's non-obvious technical choices.
- [research/jvm-pid-and-start-time.md](research/jvm-pid-and-start-time.md) — which JDKs a Mill 1.x test worker can run on, and which readings of a process's PID and start time match `ProcessHandle`'s.
- [research/mill-plugin-conventions.md](research/mill-plugin-conventions.md) — how Mill 1.x plugins are named, loaded and tested under testkit, and how the Mill internals the plugin drives differ between Mill 1.1.10 and 1.3.1.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- A ScalaTest runner argument on the JVM that turns the emitter on, so the run writes its test event log to a file.
- The consolidation library, producing human-readable output, including each run's block (R-082).
- The Mill plugin's test command. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to its stdout.
- The test command's output arguments `-oH` and a bare `-o` (R-048). With no `-o`, stdout holds human-readable output only.
- The test command fails before any test starts, producing no consolidated output, where:
  - an `-o` holds an output letter other than `H`;
  - an `-o` holds any lowercase character;
  - an `-f` is given;
  - the module's own ScalaTest arguments hold one whose flag ScalaTest's parser does not recognise. The failure names that argument.
- The test command fails where:
  - it is given more than one module selector, or one that resolves to anything other than one JVM test module;
  - the worker dies (R-046 first bullet, "does not finish");
  - the module tests with another framework or another release of this fork (R-059).
- The Mill plugin is built against Mill 1.3.1, which is its one supported Mill release.

### Out of scope

Each is left to the named feature of `../ROADMAP.md`.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters (R-127, R-128), and `-f` for human-readable output (R-049) (§ 16).
- A module that presets a test command's arguments (R-126) (§ 17).
- More than one worker for a module, and a run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, and the module's JUnit XML report (§ 4).
- Running more than one module selector, or a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet), and what the test command shows for a module that tests with another framework or another release of this fork (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- Scala.js and Native (§ 11–15).

## Epic requirements met

Each holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-060, R-004.
- R-010, R-063, R-011, R-074.
- R-096, R-097, R-098, R-099, R-100, R-103, R-104, R-105, R-106 (with no formatting or filtering character), R-107 and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout holds human-readable output); R-053 (second and third bullets, for one worker), R-125; R-092, R-072.
- R-020 (human-readable output), R-027, R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-101, R-021, R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046 (first bullet: a failure, aborted, stopped).
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.
- `../SPECIFICATION.md` § Scope: the JVM's Scala versions, and no compatibility promise on the test event log's format, the consolidation library's API or the switch.

## Acceptance criteria

In these criteria:

- The module is a JVM test module at module path `core.test`, and the test command runs it. Where a criterion instead feeds its run to the consolidation library directly (`TECHNICAL-DESIGN.md` § Testing), the run's test run label is `core.test`.
- A line is given without Mill's prefix (AC-044). A line *sits under* another when it follows it, indented 2 spaces more, with no line between them indented as little as the other. *The lines for* a node are its own lines and every line under them.
- Text and colours the criteria do not fix are ScalaTest 3.2.19's (R-106). Colours hold where Mill colours its own console output (R-124).
- "With every stack frame left out" drops each line under a throwable's first line or `Cause:` line.

### The tree

```gherkin
Feature: The tree of the human-readable output

  Rule: The output is its test run labels, with every other line under them (R-078)
    Scenario: AC-002 A module's label heads its suites
      Given suite `SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SqrtPropSpec:
            - sqrt of a square is the root
        """

  Rule: A suite with no tests that nests suites shows its name line
    Scenario: AC-003 Nested suites sit under the suite that nests them
      Given suite `OuterSuites`, with no tests, nesting `Inner1Suite` and `Inner2Suite`, whose one test each, `inner one` and `inner two`, succeeds
      When the test command runs the module
      Then the lines for `OuterSuites` are:
        """
        OuterSuites:
          Inner1Suite:
            - inner one
          Inner2Suite:
            - inner two
        """

  Rule: A scope's or test's line shows its label, with its style's control words
    Scenario: AC-004 Labels of a word spec and a feature spec
      Given suite `SetWordSpec`, an `AnyWordSpec` that ScalaTest 3.2.19 prints with scope labels `A Set` and `when empty` and test label `- should have size 0`, each nested in the one before
      And suite `ShopFeatureSpec`, an `AnyFeatureSpec` that it prints with scope label `Feature: Checkout` and, nested in it, test label `Scenario: paying by card`
      And both tests succeed
      When the test command runs the module
      Then the lines for `SetWordSpec` and `ShopFeatureSpec` are:
        """
        SetWordSpec:
          A Set
            when empty
              - should have size 0
        ShopFeatureSpec:
          Feature: Checkout
            Scenario: paying by card
        """
```

### Where each line sits

Each line sits under the line beside it.

| ID     | Line                                                                                                            | Sits under                                                    |
|--------|-----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| AC-005 | a top-level suite's name line; the run's block heading                                                          | its test run label                                            |
| AC-006 | a nested suite's name line                                                                                      | the name line of the suite that nests it                      |
| AC-007 | a suite's scope, test or message that no scope holds                                                            | the suite's name line                                         |
| AC-008 | a scope's scope, test or message                                                                                | the scope's line                                              |
| AC-009 | a test's failure or cancel message lines and analysis lines, its throwable's first line, and its messages       | the test's line                                               |
| AC-010 | each frame line of a throwable or cause, `...` included                                                         | the throwable's first line, or the cause's `Cause:` line      |
| AC-011 | a cause's `Cause:` line                                                                                         | the line that the first line of the throwable it causes sits under |
| AC-012 | a suite's `<class name> *** ABORTED ***` or `<class name> *** NOT RUN ***` line                                 | the suite's name line                                         |
| AC-013 | an aborted suite's abort message lines and its throwable's first line                                           | the suite's `<class name> *** ABORTED ***` line               |
| AC-014 | each line of the run's block other than its heading                                                             | the block's heading                                           |
| AC-015 | a run abort's message lines and its throwable's first line                                                      | `*** RUN ABORTED ***`                                         |
| AC-016 | a collapsed subtree's count line (AC-040 to AC-043)                                                             | its root's line: a suite's name line or a scope's line        |

### Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix, in the colour given, except that an aborted scope's label stays green and only its suffix takes the colour. AC-022, AC-023, AC-026 and AC-027 are met in AC-037 and AC-038, where the run is fed to the consolidation library directly.

| ID     | Node and outcome                                            | Line                       | Colour                    |
|--------|-------------------------------------------------------------|----------------------------|---------------------------|
| AC-017 | test, succeeded                                             | `<label>`                  | green                     |
| AC-018 | test, failed                                                | `<label> *** FAILED ***`   | red                       |
| AC-019 | test, ignored                                               | `<label> !!! IGNORED !!!`  | yellow                    |
| AC-020 | test, pending                                               | `<label> (pending)`        | yellow                    |
| AC-021 | test, cancelled                                             | `<label> !!! CANCELED !!!` | yellow                    |
| AC-022 | test, aborted with reason *not run*                         | `<label> *** NOT RUN ***`  | red                       |
| AC-023 | test, aborted after it started                              | `<label> *** ABORTED ***`  | red                       |
| AC-024 | scope, no outcome                                           | `<label>`                  | green                     |
| AC-025 | scope, pending                                              | `<label> (pending)`        | yellow                    |
| AC-026 | scope, aborted with reason *not run*                        | `<label> *** NOT RUN ***`  | red                       |
| AC-027 | scope, aborted because its suite aborted while it was open (R-099) | `<label> *** ABORTED ***` | red                |

### Messages

| ID     | Kind   | Line              | Colour |
|--------|--------|-------------------|--------|
| AC-028 | info   | `[info] <text>`   | green  |
| AC-029 | note   | `[note] <text>`   | green  |
| AC-030 | alert  | `[alert] <text>`  | yellow |
| AC-031 | markup | `[markup] <text>` | green  |

```gherkin
Feature: Where messages sit

  Rule: A message sits under the node it attaches to (R-097), in the order the run emitted it
    Scenario: AC-032 A test's messages sit under it, notes and alerts included
      Given in suite `StackFunSpec`, test `- is empty` calls `info("checked size")`, `note("a note")`, `alert("an alert")` and `markup("*markup*")` in that order, then succeeds
      When the test command runs the module
      Then the lines for `- is empty` are:
        """
        - is empty
          [info] checked size
          [note] a note
          [alert] an alert
          [markup] *markup*
        """
      And no line before `- is empty` holds `a note` or `an alert`

    Scenario: AC-033 A failed test's messages follow its failure
      Given in suite `StackFlatSpec`, test `- must have size 0` calls `info("checked")`, then fails
      When the test command runs the module
      Then `[info] checked` is the last of the lines for `- must have size 0 *** FAILED ***`

    Scenario: AC-034 A scope's message sits among its children
      Given in suite `StackFunSpec`, scope `A Stack` emits `info("an info in a scope")` before its scope `when empty` starts
      When the test command runs the module
      Then the lines for `A Stack` begin:
        """
        A Stack
          [info] an info in a scope
          when empty
        """
```

### A run's block

The block follows its test run label's suites, under the heading `Output outside any suite:`. Its lines, in order, are the messages attached to the run (AC-028 to AC-031), the output captured against the run, and how the run ended (AC-037, AC-038). A captured line is `[stdout] <text>` or `[stderr] <text>`.

```gherkin
Feature: A run's block

  Rule: Mill's `Running Test Class` lines give a run no block (R-129, R-082)
    Scenario: AC-001 A silent run at Mill's default test log level shows no block
      Given a JVM test module at Mill's default `testLogLevel`, whose tests and suites write nothing to stdout or stderr and send no message outside a suite, and whose run completes
      When the test command runs the module
      Then the human-readable output shows no block for the run
      And no line of the human-readable output reads "Running Test Class " followed by one of the module's test class names

  Rule: Output captured against the run is in the block, after the suites (R-012, R-082)
    Scenario: AC-035 A test's stdout line is shown in the block
      Given suite `CalculatorSuite`, whose one test, `adds two numbers`, writes `dividing now` to stdout and succeeds
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

  Rule: Captured stdout and stderr lines interleave in the order they were read (R-043)
    Scenario: AC-036 Lines of both streams in one sequence
      Given a run, fed to the consolidation library directly, that completes and whose captured output was read as: stderr `SLF4J: No SLF4J providers were found.`, stdout `dividing now`, stderr `warn: about to divide`
      When the library writes human-readable output
      Then the run's block is:
        """
        Output outside any suite:
          [stderr] SLF4J: No SLF4J providers were found.
          [stdout] dividing now
          [stderr] warn: about to divide
        """
```

### Runs that abort or stop

```gherkin
Feature: Runs that abort or stop

  Rule: An aborted run shows its aborted and not-run nodes, then `*** RUN ABORTED ***` last in its block (R-082, R-099, R-100)
    Scenario: AC-037 A test's fatal error aborts its suite and the run
      Given a run, fed to the consolidation library directly, holding suite `StackFunSpec`, class `samples.StackFunSpec`, whose scope `A Stack` holds scope `when empty`, with tests `- is empty` and `- throws on pop`, and then scope `when full`, with test `- pops last pushed`
      And the info message `Using random seed 1234` is attached to the run, and the run's captured stderr held `OpenJDK 64-Bit Server VM warning: CodeCache is full.`
      And `- is empty` succeeded, then `- throws on pop` threw `OutOfMemoryError("Java heap space")`, which aborted `StackFunSpec` while `A Stack` and `when empty` were open and aborted the run with message `An exception or error caused a run to abort: Java heap space`
      When the library writes human-readable output
      Then, with every stack frame left out, the human-readable output is:
        """
        [core.test]
          StackFunSpec:
            A Stack *** ABORTED ***
              when empty *** ABORTED ***
                - is empty
                - throws on pop *** ABORTED ***
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
      Given a run, fed to the consolidation library directly, holding suite `OuterSuites`, with no tests, nesting `Inner1Suite`, with test `- inner one`, and `Inner2Suite`, class `samples.Inner2Suite`, with test `- inner two`
      And `- inner one` succeeded, then the run stopped before `Inner2Suite` started
      When the library writes human-readable output
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

### Collapsed subtrees

A collapsed subtree (R-107) shows its root's lines, then one count line (AC-016). The count line counts every suite, scope and test at any depth below the root: suites, then scopes, then tests, each pluralised, leaving out a kind with none.

```gherkin
Feature: Collapsed subtrees

  Rule: A suite whose tests never started collapses into its abort (R-100, R-107)
    Scenario: AC-039 A suite's `beforeAll` throws
      Given suite `SetupFailsSpec`, class `samples.SetupFailsSpec`, an `AnyWordSpec` whose scope `A repo` holds tests `- should read` and `- should write`
      And its `beforeAll` throws `RuntimeException("database unreachable")`
      When the test command runs the module
      Then, with every stack frame left out, the lines for `SetupFailsSpec` are:
        """
        SetupFailsSpec:
          samples.SetupFailsSpec *** ABORTED ***
            java.lang.RuntimeException: database unreachable
          (1 scope, 2 tests not run)
        """
```

| ID     | Below the collapsed root    | Count line                             |
|--------|-----------------------------|----------------------------------------|
| AC-040 | 1 scope and 2 tests         | `(1 scope, 2 tests not run)`           |
| AC-041 | 1 test                      | `(1 test not run)`                     |
| AC-042 | 2 suites, 1 scope, 3 tests  | `(2 suites, 1 scope, 3 tests not run)` |
| AC-043 | nothing                     | none                                   |

### Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Rule: On stdout, each line carries Mill's prefix (R-050)
    Scenario: AC-044 Every line of the human-readable output is prefixed
      When the test command runs the module
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on each line of the test command's task, such as `1] `, ahead of the line's indentation
```

| ID     | Line                                                                                   | Colour     |
|--------|----------------------------------------------------------------------------------------|------------|
| AC-045 | a test run label, `[<module path>]`                                                    | cyan       |
| AC-046 | the run's block heading, `Output outside any suite:`                                   | cyan       |
| AC-047 | a `[stdout]` line                                                                      | uncoloured |
| AC-048 | a `[stderr]` line                                                                      | yellow     |
| AC-049 | a suite's `*** ABORTED ***` or `*** NOT RUN ***` line, with its abort message and throwable | red   |
| AC-050 | `*** RUN ABORTED ***`, with its message and throwable                                  | red        |
| AC-051 | `*** RUN STOPPED ***`                                                                  | red        |
| AC-052 | a collapsed subtree's count line                                                       | red        |

```gherkin
Feature: Colour off

  Rule: Colour changes no text (R-124)
    Scenario: AC-053 The same text with colour on and off
      Given the module of AC-035
      When the test command runs it once with Mill's `--color true` and once with `--color false`
      Then the second human-readable output holds no ANSI escape sequence
      And it equals the first with every ANSI escape sequence removed
```
