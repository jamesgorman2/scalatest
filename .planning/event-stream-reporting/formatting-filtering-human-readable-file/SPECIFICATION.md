# Formatting, filtering and human-readable output to a file

Scale: feature.

## Summary

The test command writes human-readable output only to its stdout. It prints every line, always in colour and with no durations, and it takes at most one `-f` argument. A reader who wants the output in a file, uncoloured, with durations, or without the lines of tests that succeeded cannot get it. This feature adds `-fH <path>` and several `-f` arguments on one test command, and the formatting and filtering characters on each `-oH` and `-fH`.

## Plan

The plan is not yet created.

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, whose requirements the epic coverage table cites.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- `-fH <path>`: the test command writes the human-readable output to the file at `<path>` once every run has ended, without Mill's prefix. A relative path resolves against the build's workspace root.
- Any number of `-f` arguments on one test command, each `-fH` and each `-fJ` writing its own file.
- Deleting or emptying each file an `-fH` names before any test starts, and leaving it so where the test command cannot run a selected test module.
- *Formatting characters*, which change how a human-readable output prints its lines: `w` prints it without colour, and `d` prints test durations.
- *Filtering characters*, which omit lines from a human-readable output: `c` omits succeeded tests, `x` ignored tests, `e` pending tests, `o` info messages, `m` markup messages and `r` pending scopes. A scope that holds only tests and scopes that filtering characters omit is omitted too, at any depth.
- Formatting and filtering characters attached to an `-oH` or `-fH`, such as `-oHwc`, each applying only to the output that argument names.
- Accepting filtering characters attached to an `-fJ`, such as `-fJc <path>`.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- Progress output, `P` on an `-o` or `-f`, and what stdout holds when no `-o` is given (§ 9).
- What filtering characters attached to an `-fJ` do to the JSON output (§ 18).
- Formatting and filtering characters that a module presets (§ 17).

## Acceptance criteria

```gherkin
Feature: Output files
  Each `-f` argument names one file that the test command writes once every run has ended.
  `-fH <path>` writes the human-readable output to the file at `<path>`, and `-fJ <path>` writes the JSON output there.
  Mill's prefix is the prefix Mill puts on each line that the test command writes to the console, such as `1] `.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSuite`, an `AnyFunSuite` whose test `pushes` succeeds and whose test `pops` fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion
    And `<path>`, `<other path>` and `<report>` are absolute paths at which nothing exists, unless the scenario says otherwise

  Rule: `-fH <path>` writes the human-readable output to the file at `<path>`, without Mill's prefix

    Scenario: AC-001 The file holds the lines that stdout shows, without Mill's prefix
      When `mill scalatest/test core.test -oHw -fHw <path>` is invoked
      Then the file at `<path>` should hold the lines of the human-readable output on stdout, in the same order, each without Mill's prefix

    Scenario: AC-002 A relative path resolves against the build's workspace root
      Given the build's workspace holds no folder `reports`
      When `mill scalatest/test core.test -fH reports/tests.txt` is invoked
      Then the file `reports/tests.txt` in the build's workspace should hold the human-readable output

    Scenario: AC-003 Under a bare `-o`, only the file holds the human-readable output
      When `mill scalatest/test core.test -o -fH <path>` is invoked
      Then the console should show no consolidated output
      And the file at `<path>` should hold the human-readable output

  Rule: Each `-f` argument writes its own file

    Scenario: AC-004 Two `-fH` arguments
      When `mill scalatest/test core.test -fH <path> -fH <other path>` is invoked
      Then the file at `<path>` should hold the human-readable output
      And the file at `<other path>` should hold the same human-readable output as the file at `<path>`

    Scenario: AC-005 An `-fH` beside an `-fJ`
      When `mill scalatest/test core.test -fH <path> -fJ <report>` is invoked
      Then the file at `<path>` should hold the human-readable output
      And the file at `<report>` should hold JSON output whose root holds the module node `core.test`

    Scenario: AC-006 Two `-fJ` arguments
      When `mill scalatest/test core.test -fJ <report> -fJ <other path>` is invoked
      Then the file at `<report>` should hold JSON output whose root holds the module node `core.test`
      And the file at `<other path>` should hold JSON output whose root holds the module node `core.test`

  Rule: An `-fH` with no path fails the test command before any test starts

    Scenario: AC-007 An `-fH` as the last argument
      When `mill scalatest/test core.test -fH` is invoked
      Then Mill should report that the test command failed, with a message saying that `-fH` takes a path
      And neither `pushes` nor `pops` should have run
      And the console should show no consolidated output

  Rule: Before any test starts, the test command deletes or empties the file that an `-fH` names

    Scenario: AC-008 A file an earlier test command wrote is gone before the first test starts
      Given a file that an earlier test command wrote exists at `<path>`
      And `StackSuite`'s test `pushes` records, when it starts, whether the file at `<path>` holds any content
      When `mill scalatest/test core.test -fH <path>` is invoked
      Then `pushes` should have recorded that the file at `<path>` held no content

    Scenario: AC-009 A test module the test command cannot run leaves no earlier content at the path
      Given a file that an earlier test command wrote exists at `<path>`
      And the build also has the JVM test module `munit.test`, which tests with MUnit
      When `mill scalatest/test core.test munit.test -fH <path>` is invoked
      Then Mill should report that the test command failed, with a message naming `munit.test`
      And nothing that the earlier test command wrote should remain at `<path>`
      And the console should show no consolidated output

  Rule: The test command accepts filtering characters attached to an `-fJ`

    Scenario: AC-010 An `-fJ` with the filtering character `c`
      When `mill scalatest/test core.test -fJc <report>` is invoked
      Then the file at `<report>` should hold JSON output whose root holds the module node `core.test`
```

```gherkin
Feature: Formatting characters
  A formatting character attached to the `-oH` or `-fH` that names a human-readable output changes how that output prints its lines.
  `w` prints the output without colour, and `d` prints each test's duration.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSuite`, an `AnyFunSuite` whose test `pushes` succeeds and whose test `pops` fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion
    And `<path>` is an absolute path at which nothing exists

  Rule: A human-readable output is in colour unless `w` is attached to the argument that names it

    Scenario Outline: AC-011 Human-readable output <where> without `w` is in colour
      When `mill scalatest/test core.test <arguments>` is invoked
      Then in the human-readable output <where>, the line `- pops *** FAILED ***` should be red

      Examples: Each place a human-readable output is written
        | arguments        | where                   |
        | `-oH`            | on stdout               |
        | `-o -fH <path>`  | in the file at `<path>` |

    Scenario Outline: AC-012 Human-readable output <where> with `w` has no colour
      When `mill scalatest/test core.test <arguments>` is invoked
      Then no line of the human-readable output <where> should carry a colour

      Examples: Each place a human-readable output is written
        | arguments        | where                   |
        | `-oHw`           | on stdout               |
        | `-o -fHw <path>` | in the file at `<path>` |

  Rule: A human-readable output prints a test's duration only where `d` is attached to the argument that names it

    Scenario: AC-013 A succeeded test's line with `d`
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for test `pushes` should be `- pushes` followed by the test's duration in parentheses, such as `- pushes (12 milliseconds)`

    Scenario: AC-014 A succeeded test's line without `d`
      When `mill scalatest/test core.test -oH` is invoked
      Then in the human-readable output on stdout, the line for test `pushes` should be `- pushes`

  Rule: A formatting character changes only the output that its argument names

    Scenario: AC-015 `w` on `-oH` leaves the file's colour
      When `mill scalatest/test core.test -oHw -fH <path>` is invoked
      Then no line of the human-readable output on stdout should carry a colour
      And in the human-readable output in the file at `<path>`, the line `- pops *** FAILED ***` should be red
```

```gherkin
Feature: Filtering characters
  A filtering character attached to the `-oH` or `-fH` that names a human-readable output omits lines from that output.
  `c` omits succeeded tests, `x` ignored tests, `e` pending tests, `o` info messages, `m` markup messages and `r` pending scopes.
  The summary line is the last line of the human-readable output.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSpec`, an `AnyFunSpec` with these scopes and tests:
      | scope                   | test         | what the test does                                                             |
      | `A stack`               | `pushes`     | succeeds                                                                       |
      | `A stack`               | `pops`       | sends the info `checked`, the markup `popped` and the note `noted`, then fails |
      | `A stack`               | `peeks`      | is ignored                                                                     |
      | `A stack`               | `clears`     | is pending                                                                     |
      | `when empty` in `A set` | `has size 0` | succeeds                                                                       |
    And `StackSpec` also holds the scope `A queue`, which is pending and holds no test
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion

  Rule: Each filtering character omits the lines of one kind

    Scenario Outline: AC-016 The filtering character <character> omits <line>
      When `mill scalatest/test core.test -oH<character>` is invoked
      Then the human-readable output on stdout should hold no line <line>
      And the human-readable output on stdout should hold the line `- pops *** FAILED ***`

      Examples: Filtering characters and the line each omits
        | character | line                      |
        | c         | `- pushes`                |
        | x         | `- peeks !!! IGNORED !!!` |
        | e         | `- clears (pending)`      |
        | o         | `[info] checked`          |
        | m         | `[markup] popped`         |
        | r         | `A queue (pending)`       |

    Scenario: AC-017 Without a filtering character, every line is shown
      When `mill scalatest/test core.test -oH` is invoked
      Then the human-readable output on stdout should hold each of these lines:
        | line                      |
        | `- pushes`                |
        | `- peeks !!! IGNORED !!!` |
        | `- clears (pending)`      |
        | `[info] checked`          |
        | `[markup] popped`         |
        | `A queue (pending)`       |

    Scenario: AC-018 The filtering character `o` keeps a note
      When `mill scalatest/test core.test -oHo` is invoked
      Then the human-readable output on stdout should hold the line `[note] noted`

    Scenario: AC-019 Two filtering characters on one argument each omit their lines
      When `mill scalatest/test core.test -oHcx` is invoked
      Then the human-readable output on stdout should hold no line `- pushes`
      And the human-readable output on stdout should hold no line `- peeks !!! IGNORED !!!`

  Rule: A scope that holds only tests and scopes that filtering characters omit is omitted, at any depth

    Scenario: AC-020 Nested scopes whose one test succeeded are omitted under `c`
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `A set`
      And the human-readable output on stdout should hold no line `when empty`

    Scenario: AC-021 A scope with a test left after `c` is kept
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `A stack`

  Rule: Filtering characters omit lines, never counts

    Scenario: AC-022 The summary line under `c` still counts the succeeded tests
      When `mill scalatest/test core.test -oHc` is invoked
      Then the summary line on stdout should count 2 succeeded tests

  Rule: A filtering character changes only the output that its argument names

    Scenario: AC-023 `c` on `-oH` leaves the file's succeeded tests
      Given `<path>` is an absolute path at which nothing exists
      When `mill scalatest/test core.test -oHc -fH <path>` is invoked
      Then the human-readable output on stdout should hold no line `- pushes`
      And the human-readable output in the file at `<path>` should hold the line `- pushes`
```

Each epic requirement this feature addresses, with the criteria that address it:

| Epic requirement             | Criteria                                                   |
|------------------------------|------------------------------------------------------------|
| `../SPECIFICATION.md` R-138  | AC-010, AC-011, AC-012, AC-013, AC-016, AC-019             |
| `../SPECIFICATION.md` R-139  | AC-015, AC-023                                             |
| `../SPECIFICATION.md` R-127a | AC-016, AC-017, AC-018, AC-019, AC-022                     |
| `../SPECIFICATION.md` R-127b | AC-020, AC-021                                             |
| `../SPECIFICATION.md` R-106c | AC-016, AC-017, AC-020, AC-021                             |
| `../SPECIFICATION.md` R-106f | AC-011, AC-012, AC-015                                     |
| `../SPECIFICATION.md` R-106g | AC-013, AC-014                                             |
| `../SPECIFICATION.md` R-049a | AC-001, AC-002, AC-003, AC-007                             |
| `../SPECIFICATION.md` R-049b | AC-004, AC-005, AC-006                                     |
| `../SPECIFICATION.md` R-055  | AC-001, AC-003, AC-004, AC-011, AC-012, AC-016, AC-023     |
| `../SPECIFICATION.md` R-020  | AC-001, AC-003, AC-005                                     |
| `../SPECIFICATION.md` R-077  | AC-008                                                     |
| `../SPECIFICATION.md` R-059  | AC-009                                                     |
