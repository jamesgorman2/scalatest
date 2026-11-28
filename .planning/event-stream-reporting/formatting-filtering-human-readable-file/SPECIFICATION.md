# Formatting, filtering and human-readable output to a file

Scale: feature.

## Summary

The test command writes human-readable output only to its stdout. It prints every line, always in colour and with no durations, and it takes at most one `-f` argument. A reader who wants the output in a file, uncoloured, with durations, or without the lines of tests that succeeded cannot get it. This feature adds `-fH <path>` and several `-f` arguments on one test command, and the formatting and filtering characters on each `-oH` and `-fH`.

## Plan

[Plan: Formatting, filtering and human-readable output to a file](https://github.com/jamesgorman2/scalatest/issues/270)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, whose requirements the epic coverage table cites.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Terms are defined in `.docs/ubiquitous-language.md`, among them **Formatting characters** and **Filtering characters**.

## Scope

- The output letters each output argument takes. An `-o` takes no output letter, `H`, `P`, or `H` with `P`. An `-f` takes `J`, `H`, `P`, or `H` with `P`.
- An unknown output argument: the test command fails before any test starts, naming the argument, as it fails on any other unknown argument. An output argument is unknown where it holds:
  - a set of output letters that the item above does not list, such as `-oJ` or `-fJH <path>`;
  - no output letter, where it is an `-f`, such as `-fw <path>`;
  - any character, upper or lower case, that is not an output letter, a formatting character or a filtering character, such as `-oD` or `-oHC`.
- The output letters, formatting characters and filtering characters on one output argument form a set: their order and repeats do not matter. `-oPH` is therefore `-oHP`, `-fPH <path>` is `-fHP <path>`, and `-fcwH <path>` is `-fHwc <path>`.
- An `-o` with no output letter, such as `-ow`, writes no consolidated output to the console. Its characters have no effect.
- `-fH <path>`: the test command writes the human-readable output to the file at `<path>` once every run has ended, without Mill's prefix. A relative path resolves against the build's workspace root. The output in the file is in colour, as ANSI escape codes, unless `w` is given.
- Any number of `-f` arguments on one test command, each `-fH` and each `-fJ` writing its own file. Two `-f` arguments that name one file fail the test command before any test starts, naming the file, as an unknown argument does.
- Deleting or emptying each file an `-fH` names before any test starts, and leaving it so where the test command cannot run a selected test module.
- An `-fH` file that the test command cannot delete or write, handled as an `-fJ` file is:
  - Where a folder is at the path, or the file there cannot be deleted, the test command fails before any test starts, naming the path, and writes no consolidated output.
  - Where the file cannot be written once every run has ended, the test command fails, naming the path, and still writes every other output. The file holds the whole human-readable output or nothing. Where the test command cannot delete a partly written file, the failure says so.
- The formatting characters on an `-oH` or `-fH`:
  - `w` prints the human-readable output without colour.
  - `d` prints the duration of each node shown that has one, as ScalaTest's `-oD` prints a test's duration: the node's line, a space, then the duration in parentheses, such as `- pushes (12 milliseconds)`. The nodes with a duration are:
    - a test or suite whose closing event carries a duration;
    - a scope whose start and end were both recorded;
    - a module whose tests started, timed from when the test command starts the module's tests to when the last of the module's test processes ends;
    - a run whose closing run event (`RunCompleted`, `RunStopped` or `RunAborted`) carries a duration. A run's duration is printed only where the run's line is shown.
- The filtering characters on an `-oH` or `-fH`: `c` omits succeeded tests, `x` ignored tests, `e` pending tests, `o` info messages, `m` markup messages and `r` pending scopes.
  - A scope's children are its child scopes and tests. Messages are not children.
  - A scope all of whose children the filtering characters omit is omitted too, at any depth. A scope with no children is not omitted for that reason.
  - Filtering characters never omit a module, a run or a suite.
  - The messages and output attached to an omitted node are omitted with it.
  - Where a test was executed more than once, `c` omits each execution that succeeded.
- Formatting and filtering characters attached to an `-oH` or `-fH`, such as `-oHwc`, each applying only to the output that argument names.
- Accepting formatting and filtering characters attached to an `-fJ`, such as `-fJc <path>`. `w` and `d` leave the JSON output unchanged.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- Progress output, what `P` on an `-o` or `-f` writes, and what stdout holds when no `-o` is given (§ 9).
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

  Rule: An `-fH` path at which the test command cannot delete what is there fails the test command before any test starts

    Scenario Outline: AC-029 <obstacle> at the `-fH` path fails the test command before any test starts
      Given <obstacle> exists at `<path>`
      When `mill scalatest/test core.test -fH <path>` is invoked
      Then Mill should report that the test command failed, with a message naming `<path>`
      And neither `pushes` nor `pops` should have run
      And the console should show no consolidated output

      Examples: What the test command cannot delete
        | obstacle                                                 |
        | a folder                                                 |
        | a file that the test command has no permission to delete |

  Rule: An `-fH` file that cannot be written fails the test command, while every other output is still written

    Scenario: AC-030 An `-fH` path under a file fails the test command with the other outputs written
      Given the parent of `<path>` is a file
      When `mill scalatest/test core.test -fH <path> -fJ <report>` is invoked
      Then Mill should report that the test command failed, with a message naming `<path>`
      And the file at `<report>` should hold JSON output whose root holds the module node `core.test`
      And stdout should hold the human-readable output

  Rule: The test command accepts formatting and filtering characters attached to an `-fJ`

    Scenario Outline: AC-010 An `-fJ` with the character <character> writes the JSON output
      When `mill scalatest/test core.test -fJ<character> <report>` is invoked
      Then the file at `<report>` should hold JSON output whose root holds the module node `core.test`

      Examples: Formatting and filtering characters
        | character |
        | c         |
        | w         |
        | d         |

  Rule: Formatting characters attached to an `-fJ` leave its JSON output unchanged

    Scenario: AC-028 `-fJwd` writes the JSON output that `-fJ` writes
      When `mill scalatest/test core.test -fJwd <report> -fJ <other path>` is invoked
      Then the file at `<report>` should hold the same JSON output as the file at `<other path>`
```

```gherkin
Feature: Formatting characters
  A formatting character attached to the `-oH` or `-fH` that names a human-readable output changes how that output prints its lines.
  `w` prints the output without colour, and `d` prints the duration of each node shown that has one, in the form ScalaTest's `-oD` prints a test's duration.

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

  Rule: A human-readable output prints a node's duration only where `d` is attached to the argument that names it and the node has a duration

    Scenario Outline: AC-013 With `d`, the line for <node> ends with its duration
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>` followed by a space and the duration of <node> in parentheses, such as `<line> (12 milliseconds)`

      Examples: Nodes with a duration
        | node                   | line        |
        | the test `pushes`      | - pushes    |
        | the suite `StackSuite` | StackSuite: |
        | the module `core.test` | [core.test] |

    Scenario Outline: AC-014 Without `d`, the line for <node> has no duration
      When `mill scalatest/test core.test -oH` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>`

      Examples: Nodes with a duration
        | node                   | line        |
        | the test `pushes`      | - pushes    |
        | the suite `StackSuite` | StackSuite: |
        | the module `core.test` | [core.test] |

    Scenario: AC-031 With `d`, a stopped run's line ends with the run's duration
      Given `core.test` also holds the suite `HaltSuite`, an `AnyFunSuite` whose one test `halts` requests that the run stop
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for the stopped run should be the run's line followed by a space and the run's duration in parentheses, such as `Run 1: (850 milliseconds)`

    Scenario: AC-032 With `d`, a run whose line is not shown prints no duration
      When `mill scalatest/test core.test -oHd` is invoked
      Then the human-readable output on stdout should hold no line for a run, such as `Run 1:`

    Scenario Outline: AC-033 With `d`, the line for <node>, which has no duration, is unchanged
      Given <state>
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>`

      Examples: Nodes without a duration
        | state                                                       | node                   | line                    |
        | `StackSuite` also holds the test `peeks`, which is ignored  | the test `peeks`       | - peeks !!! IGNORED !!! |
        | `core.test` fails to compile                                | the module `core.test` | [core.test]             |

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
      | `A stack`               | `pushes`     | writes `pushing` to stdout and sends the note `pushed`, then succeeds          |
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

    Scenario: AC-037 Under `c`, each succeeded execution of a test is omitted
      Given `core.test` also holds the suite `RetrySpec`, an `AnyFunSpec` whose test `retries` is executed twice in one execution of the suite, failing first and succeeding second
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `- retries *** FAILED ***`
      But the human-readable output on stdout should hold no line `- retries`

  Rule: The messages and output attached to an omitted node are omitted with it

    Scenario Outline: AC-034 Under `c`, <line> attached to the omitted test `pushes` is omitted
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no <line>

      Examples: Output and messages attached to `pushes`
        | line                         |
        | line containing `pushing`    |
        | line `[note] pushed`         |

  Rule: A scope is omitted, at any depth, where filtering characters omit all of its child scopes and tests

    Scenario: AC-020 Nested scopes whose one test succeeded are omitted under `c`
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `A set`
      And the human-readable output on stdout should hold no line `when empty`

    Scenario: AC-021 A scope with a test left after `c` is kept
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `A stack`

    Scenario: AC-035 Under `c`, a scope whose only test succeeded is omitted though it holds a message
      Given `StackSpec`'s scope `when empty` sends the info `built` outside its test
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `when empty`

    Scenario: AC-036 Under `c`, a scope with no child scope or test is kept
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `A queue (pending)`

  Rule: Filtering characters never omit a module or a suite

    Scenario Outline: AC-038 Under `c`, the line `<line>` of a node holding only succeeded tests is kept
      Given the build also has the JVM test module `queue.test`, which tests with `com.edc::scalatest` at that release and holds only the suite `QueueSpec`, an `AnyFunSpec` whose one test `enqueues` succeeds
      When `mill scalatest/test core.test queue.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `<line>`

      Examples: Nodes that filtering characters never omit
        | line         |
        | [queue.test] |
        | QueueSpec:   |

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

```gherkin
Feature: Output arguments
  The test command's output arguments are `-o`, which names the console, and `-f <path>`, which names the file at `<path>`.
  Each holds output letters, `H` for human-readable output, `P` for progress output and, on `-f` only, `J` for JSON output, and may also hold formatting and filtering characters.
  An `-o` holds no output letter, `H`, `P`, or `H` with `P`, and an `-f` holds `J`, `H`, `P`, or `H` with `P`.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSuite`, an `AnyFunSuite` whose test `pushes` succeeds and whose test `pops` fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion
    And `<path>` and `<other path>` are absolute paths at which nothing exists

  Rule: An output argument that holds anything but the output letters, formatting characters and filtering characters the test command takes fails the test command before any test starts

    Scenario Outline: AC-024 The output argument <argument> fails the test command before any test starts
      When `mill scalatest/test core.test <arguments>` is invoked
      Then Mill should report that the test command failed, with a message naming `<argument>`
      And neither `pushes` nor `pops` should have run
      And the console should show no consolidated output

      Examples: Output arguments the test command does not take
        | arguments    | argument |
        | -oJ          | -oJ      |
        | -oD          | -oD      |
        | -oHz         | -oHz     |
        | -fJH <path>  | -fJH     |
        | -fHC <path>  | -fHC     |
        | -fw <path>   | -fw      |

  Rule: The output letters and characters of one output argument form a set, whose order and repeats do not matter

    Scenario Outline: AC-025 `<variant>` writes the output that `-fHwc` writes
      When `mill scalatest/test core.test -fHwc <path> <variant> <other path>` is invoked
      Then the file at `<other path>` should hold the same human-readable output as the file at `<path>`

      Examples: Orders and repeats of the letters and characters of `-fHwc`
        | variant  |
        | -fHcw    |
        | -fcwH    |
        | -fHHwwcc |

  Rule: An `-o` with no output letter writes no consolidated output to the console, whatever characters it holds

    Scenario Outline: AC-026 `-o<character>` writes no consolidated output to the console
      When `mill scalatest/test core.test -o<character>` is invoked
      Then the console should show no consolidated output

      Examples: A formatting character and a filtering character
        | character |
        | w         |
        | c         |

  Rule: Two `-f` arguments that name one file fail the test command before any test starts

    Scenario Outline: AC-027 `<arguments>` fails the test command before any test starts
      When `mill scalatest/test core.test <arguments>` is invoked
      Then Mill should report that the test command failed, with a message naming `<path>`
      And neither `pushes` nor `pops` should have run
      And the console should show no consolidated output

      Examples: Two `-f` arguments naming one file
        | arguments             |
        | -fH <path> -fH <path> |
        | -fH <path> -fJ <path> |
```

Each epic requirement this feature addresses, with the criteria that address it:

| Epic requirement             | Criteria                                                                       |
|------------------------------|--------------------------------------------------------------------------------|
| `../SPECIFICATION.md` R-138  | AC-010, AC-011, AC-012, AC-013, AC-016, AC-019, AC-024, AC-025, AC-026, AC-028 |
| `../SPECIFICATION.md` R-139  | AC-015, AC-023                                                                 |
| `../SPECIFICATION.md` R-127a | AC-016, AC-017, AC-018, AC-019, AC-022, AC-034, AC-037, AC-038                 |
| `../SPECIFICATION.md` R-127b | AC-020, AC-021, AC-035, AC-036                                                 |
| `../SPECIFICATION.md` R-106c | AC-016, AC-017, AC-020, AC-021, AC-037, AC-038                                 |
| `../SPECIFICATION.md` R-106f | AC-011, AC-012, AC-015                                                         |
| `../SPECIFICATION.md` R-106g | AC-013, AC-014, AC-031, AC-032, AC-033                                         |
| `../SPECIFICATION.md` R-049a | AC-001, AC-002, AC-003, AC-007, AC-024, AC-025                                 |
| `../SPECIFICATION.md` R-049b | AC-004, AC-005, AC-006, AC-027                                                 |
| `../SPECIFICATION.md` R-055  | AC-001, AC-003, AC-004, AC-011, AC-012, AC-016, AC-023, AC-026, AC-028         |
| `../SPECIFICATION.md` R-020  | AC-001, AC-003, AC-005, AC-030                                                 |
| `../SPECIFICATION.md` R-077  | AC-008, AC-029                                                                 |
| `../SPECIFICATION.md` R-059  | AC-009                                                                         |
