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
  - the worker dies, whether or not a run existed in it (R-046 first and second bullets);
  - the module tests with another framework or another release of this fork. Unlike R-059 requires, the failure need not come before any test starts or name the module, and consolidated output may still be written.
- The test command supports Mill 1.3.1 only.

### Out of scope

Each is left to the named feature of `../ROADMAP.md`.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters (R-127, R-128), and `-f` for human-readable output (R-049) (§ 16).
- A module that presets a test command's arguments (R-126) (§ 17).
- More than one worker for a module, and a run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, and the module's JUnit XML report (§ 4).
- Running more than one module selector, or a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet), and failing a module that tests with another framework or another release of this fork before any test starts, naming it, with no consolidated output (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- Scala.js and Native (§ 11–15).

## Epic requirements met

Each holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-060, R-004.
- R-010, R-063, R-011, R-074.
- R-096, R-097, R-098, R-099, R-100, R-104, R-105, R-106 (with no formatting or filtering character), R-107 and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout holds human-readable output); R-053 (second bullet), R-125; R-092, R-072.
- R-020 (human-readable output), R-027, R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-101, R-021, R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046 (first and second bullets; for a run that does not finish and a process in which no run existed, only that the test command fails).
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.
- `../SPECIFICATION.md` § Scope: the JVM's Scala versions, and no compatibility promise on the test event log's format, the consolidation library's API or the switch.

## Acceptance criteria

In these criteria:

- The module is a JVM test module at module path `core.test`, and the test command runs it. Some criteria instead feed a run to the consolidation library directly, without Mill (R-023, R-113); the run's test run label is then `core.test`.
- A line is given without Mill's prefix (AC-044). A line *sits under* another when it follows it, indented 2 spaces more, with no line between them indented as little as the other. *The lines for* a node are its own lines and every line under them.
- Text and colours the criteria do not fix are ScalaTest 3.2.19's (R-106). Colour is on (R-124).
- "With every stack frame left out" drops each line under a throwable's first line or `Cause:` line.

### The tree

```gherkin
Feature: The tree of the human-readable output

  Rule: The output is its test run labels, with every other line under them (R-078, R-129)
    Scenario: AC-002 A module's label heads its suites
      Given the module is at Mill's default `testLogLevel`
      And suite `SqrtPropSpec`, an `AnyPropSpec` whose one property, `sqrt of a square is the root`, succeeds
      And the run writes nothing to stdout or stderr, sends no message outside a suite, and completes
      When the test command runs the module
      Then the human-readable output is:
        """
        [core.test]
          SqrtPropSpec:
            - sqrt of a square is the root
        """

  Rule: Every suite shows its name line (R-106)
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

### Where a node's lines sit

Every line sits under the line it belongs to (R-106), with *sits under* as defined above. Where that leaves a line's place open, it sits as follows.

| ID     | Line                                                                                                      | Sits under                                                         |
|--------|-----------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| AC-007 | a suite's message that no scope holds                                                                     | the suite's name line                                              |
| AC-009 | a test's failure or cancel message lines and analysis lines, its throwable's first line, and its messages | the test's line                                                    |
| AC-010 | each frame line of a throwable or cause, `...` included                                                   | the throwable's first line, or the cause's `Cause:` line           |
| AC-011 | a cause's `Cause:` line                                                                                   | the line that the first line of the throwable it causes sits under |

### Scope and test lines

A scope's or test's line is its label followed by its outcome's suffix.

| ID     | Node and outcome                                                   | Line                       | Colour                 |
|--------|--------------------------------------------------------------------|----------------------------|------------------------|
| AC-017 | test, succeeded                                                    | `<label>`                  | green                  |
| AC-018 | test, failed                                                       | `<label> *** FAILED ***`   | red                    |
| AC-019 | test, ignored                                                      | `<label> !!! IGNORED !!!`  | yellow                 |
| AC-020 | test, pending                                                      | `<label> (pending)`        | yellow                 |
| AC-021 | test, cancelled                                                    | `<label> !!! CANCELED !!!` | yellow                 |
| AC-022 | test, aborted with reason *not run*                                | `<label> *** NOT RUN ***`  | red                    |
| AC-023 | test, aborted after it started                                     | `<label> *** ABORTED ***`  | red                    |
| AC-024 | scope, no outcome                                                  | `<label>`                  | green                  |
| AC-025 | scope, pending                                                     | `<label> (pending)`        | yellow                 |
| AC-026 | scope, aborted with reason *not run*                               | `<label> *** NOT RUN ***`  | label green, suffix red |
| AC-027 | scope, aborted because its suite aborted while it was open (R-099) | `<label> *** ABORTED ***`  | label green, suffix red |

### Lines that are not a node's

| ID     | Line                                                         | Text                                                                                                     | Sits under                                             | Colour             |
|--------|--------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|--------------------------------------------------------|--------------------|
| AC-045 | a test run label                                             | `[<module path>]`                                                                                        | nothing: it is not indented                            | cyan               |
| AC-012 | a suite's abort line                                         | `<class name> *** ABORTED ***`, or `<class name> *** NOT RUN ***` where its reason is *not run*          | the suite's name line                                  | red                |
| AC-013 | an aborted suite's abort message lines and its throwable's first line | 3.2.19's                                                                                        | the suite's abort line                                 | red                |
| AC-016 | a collapsed subtree's count line                             | AC-055's                                                                                                 | its root's line: a suite's name line or a scope's line | red                |
| AC-046 | a run's block heading                                        | `Output outside any suite:`                                                                              | its test run label                                     | cyan               |
| AC-014 | a message attached to the run                                | AC-028 to AC-031's                                                                                       | the block's heading                                    | AC-028 to AC-031's |
| AC-047 | a line captured from stdout                                  | `[stdout] <text>`                                                                                        | the block's heading                                    | uncoloured         |
| AC-048 | a line captured from stderr                                  | `[stderr] <text>`                                                                                        | the block's heading                                    | yellow             |
| AC-050 | an aborted run's end                                         | `*** RUN ABORTED ***`                                                                                    | the block's heading                                    | red                |
| AC-015 | a run abort's message lines and its throwable's first line   | 3.2.19's                                                                                                 | `*** RUN ABORTED ***`                                  | red                |
| AC-051 | a stopped run's end                                          | `*** RUN STOPPED ***`                                                                                    | the block's heading                                    | red                |

### Messages

| ID     | Kind   | Line              | Colour |
|--------|--------|-------------------|--------|
| AC-028 | info   | `[info] <text>`   | green  |
| AC-029 | note   | `[note] <text>`   | green  |
| AC-030 | alert  | `[alert] <text>`  | yellow |
| AC-031 | markup | `[markup] <text>` | green  |

```gherkin
Feature: Where messages sit

  Rule: A message sits under the node it attaches to (R-097), after its test's failure or cancel lines, in the order the run emitted it
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
      Given in suite `StackFlatSpec`, test `- must have size 0` calls `info("checked")`, `note("a note")` and `alert("an alert")` in that order, then fails
      When the test command runs the module
      Then the last three of the lines for `- must have size 0 *** FAILED ***` are:
        """
        [info] checked
        [note] a note
        [alert] an alert
        """
      And no line before `- must have size 0 *** FAILED ***` holds `a note` or `an alert`

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

```gherkin
Feature: A run's block

  Rule: A run's block follows its label's suites, and holds the run's messages, then its captured output, then how the run ended (R-012, R-082)
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

    Scenario: AC-054 The run's messages come before its captured output
      Given a run, fed to the consolidation library directly, that completes, whose captured stdout line `dividing now` is added before the info message `Using random seed 1234` is attached to the run
      When the library writes human-readable output
      Then the run's block is:
        """
        Output outside any suite:
          [info] Using random seed 1234
          [stdout] dividing now
        """

  Rule: Captured stdout and stderr lines interleave in the order they were read
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
      Given a run, fed to the consolidation library directly, holding suite `StackFunSpec`, class `samples.StackFunSpec`, whose scope `A Stack` holds scope `when empty`, with tests `- is empty`, `- throws on pop` and `- has size 0`, and then scope `when full`, with test `- pops last pushed`
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

A collapsed subtree (R-107) shows its root's lines, then its count line (AC-016).

- AC-055 A count line reads `(<counts> not run)`. `<counts>` gives how many suites, then scopes, then tests sit at any depth below the root, each as `<n> suite`, `<n> scope` or `<n> test` with `s` added where `<n>` is not 1, joined by `, `, leaving out a kind with none.

| ID     | Below the collapsed root    | Count line                             |
|--------|-----------------------------|----------------------------------------|
| AC-042 | 2 suites, 1 scope, 3 tests  | `(2 suites, 1 scope, 3 tests not run)` |
| AC-043 | nothing                     | none                                   |

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

### Mill's prefix and colour

```gherkin
Feature: Mill's prefix and colour

  Rule: On stdout, each line carries Mill's prefix (R-106)
    Scenario: AC-044 Every line of the human-readable output is prefixed
      When the test command runs the module
      Then each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, such as `1] `, ahead of the line's indentation

  Rule: Colour changes no text (R-124)
    Scenario: AC-053 The same text with colour on and off
      Given the module of AC-035
      When the test command runs it once with Mill's `--color true` and once with `--color false`
      Then the second human-readable output holds no ANSI escape sequence
      And it equals the first with every ANSI escape sequence removed
```
