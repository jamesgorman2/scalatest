# Formatting, filtering and human-readable output to a file

Scale: feature.

## Summary

The test command writes human-readable output only to its stdout. The test command prints every line of that output, always in colour and with no durations, and takes at most one `-f` argument. A reader who wants the output in a file, uncoloured, with durations, or without the lines of tests that succeeded cannot get it. This feature adds `-fH <path>` and several `-f` arguments on one test command, and the formatting and filtering characters on each output argument that writes human-readable output.

## Plan

[Plan: Formatting, filtering and human-readable output to a file](https://github.com/jamesgorman2/scalatest/issues/270)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, whose requirements the epic coverage table cites.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Terms are defined in `.docs/ubiquitous-language.md`, among them **Output argument**, **Output letter**, **Formatting characters** and **Filtering characters**.

## Scope

- Output arguments. An *output argument* is an `-o` or `-f` argument of the test command. An `-o` names stdout, and an `-f <path>` names the file at `<path>`.
- The output letters each output argument takes. An output argument's *output letters* are its upper-case characters `H`, `P` and `J`, which select human-readable, progress and JSON output. An `-o` takes no output letter, `H`, `P`, or `H` with `P`. An `-f` takes `J`, `H`, `P`, or `H` with `P`. An `-oH` or an `-fH` is an `-o` or an `-f` whose output letters include `H`, such as `-oHP`.
- Accepting `P` on an output argument, such as `-oHP` or `-fP <path>`. An `-oH` or `-fH` writes the human-readable output whether or not its output letters include `P`.
- An unknown output argument: the test command fails before any test starts, naming the argument, as it fails on any other unknown argument. An output argument is unknown where it holds:
  - a set of output letters that the item above does not list, such as `-oJ` or `-fJH <path>`;
  - no output letter, where it is an `-f`, such as `-fw <path>`;
  - any character, upper or lower case, that is not an output letter, a formatting character or a filtering character, such as `-oD` or `-oHC`.
- The output letters, formatting characters and filtering characters on one output argument form a set: their order and repeats do not matter. `-oPH` is therefore `-oHP`, `-fPH <path>` is `-fHP <path>`, and `-fcwH <path>` is `-fHwc <path>`.
- An `-o` with no output letter, such as `-ow`, writes no consolidated output to stdout. The formatting and filtering characters on such an `-o` have no effect.
- `-fH <path>`: the test command writes the human-readable output to the file at `<path>` once every run has ended, without Mill's prefix. *Mill's prefix* is the prefix Mill puts on each line that the test command writes to stdout, such as `1] `. A relative path resolves against the build's workspace root. The test command creates each folder on the path that does not exist. The output in the file is in colour, as ANSI escape codes, unless `w` is on that `-fH`.
- An `-fH` with no path after it fails the test command before any test starts.
- Any number of `-f` arguments on one test command, each `-fH` and each `-fJ` writing its own file. Two `-f` arguments name one file where their paths, each resolved against the build's workspace root, are the same once their `.` and `..` segments are removed. Two `-f` arguments that name one file fail the test command before any test starts, naming the file, as an unknown argument does.
- Deleting each file an `-fH` names before any test starts, and leaving it deleted where the test command cannot run a selected test module.
- An `-fH` file is deleted and written as an `-fJ` file is, by `mill-plugin/plugin/src/org/scalatest/mill/JsonOutputFile.scala` `JsonOutputFile`, and fails the test command where an `-fJ` file would:
  - Where a folder is at the path, or the file there cannot be deleted, the test command fails before any test starts, naming the path, and writes no consolidated output.
  - Where the file cannot be written once every run has ended, the test command fails, naming the path, and still writes every other output.
- The formatting characters on an `-oH` or `-fH`:
  - `w` prints the human-readable output without colour.
  - `d` prints the duration of each node shown that has one, as ScalaTest's `-oD` prints a test's duration: the node's line, a space, then the duration in parentheses, such as `- pushes (12 milliseconds)`. The nodes with a duration are:
    - the root, timed from when the test command starts to when every run has ended. The root's duration is printed only where the root's line is shown.
    - a module whose tests started, timed from when the test command starts the module's tests to when the last of the module's test processes ends;
    - a run whose closing run event (`RunCompleted`, `RunStopped` or `RunAborted`) carries a duration. A run's duration is printed only where the run's line is shown.
    - a suite or test whose closing event carries a duration;
    - a scope whose opening and closing ScalaTest both reported.
- The filtering characters on an `-oH` or `-fH`, each of which names one kind of node or message: `c` names succeeded tests, `x` ignored tests, `e` pending tests, `o` info messages, `m` markup messages and `r` pending scopes. A filtering character omits each message it names.
  - A scope's children are its child scopes and tests. A message is not a child.
  - A node that a filtering character names is omitted where no node beneath it is kept.
  - A node that a filtering character names keeps its line as a header where a node beneath it is kept, so that the kept node keeps its place in the tree. A failed test in a pending scope under `r`, and a suite run inside a succeeded test under `c`, are such kept nodes.
  - A scope all of whose children the filtering characters omit is omitted too, at any depth, whatever the scope's own outcome.
  - A scope that has no children is kept unless a filtering character names it, as `r` names a pending scope.
  - Filtering characters never omit a module, a run or a suite.
  - The messages attached to an omitted node or to a node kept as a header, and any output captured against either node, are omitted.
  - Where a test was executed more than once, `c` names each execution that succeeded.
  - Filtering characters omit lines only. The summary line counts every suite and test, whatever the filtering characters omit.
- Formatting and filtering characters on an `-oH` or `-fH`, such as `-oHwc`, each applying only to the output that argument names.
- Accepting formatting and filtering characters on an `-fJ`, such as `-fJc <path>`. `w` and `d` on an `-fJ` leave the JSON output unchanged.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- Progress output, what `P` on an `-o` or `-f` writes, and what stdout holds when no `-o` is given (§ 9).
- Capturing output against a suite, scope or test, so that output can be omitted with an omitted node (§ 10).
- What filtering characters on an `-fJ` do to the JSON output (§ 18).
- Formatting and filtering characters that a module presets (§ 17).

## Acceptance criteria

```gherkin
Feature: Output files
  An `-fH` argument names a file that receives the human-readable output, and an `-fJ` argument names a file that receives the JSON output, each written once every run has ended.
  Mill's prefix is the prefix Mill puts on each line that the test command writes to stdout, such as `1] `.
  A relative path names a file below the build's workspace root.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSuite`, an `AnyFunSuite` whose test `pushes` succeeds and whose test `pops` fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion

  Rule: An `-fH` writes the human-readable output to the file it names, without Mill's prefix

    Scenario: AC-001 The file holds the lines that stdout shows, without Mill's prefix
      When `mill scalatest/test core.test -oHw -fHw tests.txt` is invoked
      Then the file `tests.txt` should hold the lines of the human-readable output on stdout, in the same order, each without Mill's prefix

    Scenario: AC-002 A relative path whose folder does not exist is written below the build's workspace root
      Given the build's workspace holds no folder `reports`
      When `mill scalatest/test core.test -fH reports/tests.txt` is invoked
      Then the file `reports/tests.txt` below the build's workspace root should hold the human-readable output

    Scenario: AC-003 Under a bare `-o`, only the file holds the human-readable output
      When `mill scalatest/test core.test -o -fH tests.txt` is invoked
      Then stdout should hold no consolidated output
      And the file `tests.txt` should hold the human-readable output

  Rule: Each `-f` argument writes its own file

    Scenario: AC-004 Each of two `-fH` files receives the human-readable output
      When `mill scalatest/test core.test -fH tests.txt -fH other.txt` is invoked
      Then the file `tests.txt` should hold the human-readable output
      And the file `other.txt` should hold the same human-readable output as the file `tests.txt`

    Scenario: AC-005 An `-fH` file receives the human-readable output beside an `-fJ` file's JSON output
      When `mill scalatest/test core.test -fH tests.txt -fJ report.json` is invoked
      Then the file `tests.txt` should hold the human-readable output
      And the file `report.json` should hold JSON output whose root holds the module node `core.test`

    Scenario: AC-006 Each of two `-fJ` files receives the JSON output
      When `mill scalatest/test core.test -fJ report.json -fJ other.json` is invoked
      Then the file `report.json` should hold JSON output whose root holds the module node `core.test`
      And the file `other.json` should hold JSON output whose root holds the module node `core.test`

  Rule: An `-fH` with no path fails the test command before any test starts

    Scenario: AC-007 An `-fH` as the last argument fails the test command before any test starts
      When `mill scalatest/test core.test -fH` is invoked
      Then Mill should report that the test command failed, with a message saying that `-fH` takes a path
      And neither `pushes` nor `pops` should have run
      And stdout should hold no consolidated output

  Rule: Before any test starts, the test command deletes the file that an `-fH` names

    Scenario: AC-008 A file an earlier test command wrote is gone before the first test starts
      Given a file that an earlier test command wrote exists at `tests.txt`
      And `StackSuite`'s test `pushes` records, when it starts, whether a file exists at `tests.txt`
      When `mill scalatest/test core.test -fH tests.txt` is invoked
      Then `pushes` should have recorded that no file existed at `tests.txt`

    Scenario: AC-009 A test module the test command cannot run leaves no file at the path
      Given a file that an earlier test command wrote exists at `tests.txt`
      And the build also has the JVM test module `munit.test`, which tests with MUnit
      When `mill scalatest/test core.test munit.test -fH tests.txt` is invoked
      Then no file should exist at `tests.txt`

  Rule: An `-fH` path at which the test command cannot delete what is there fails the test command before any test starts

    Scenario Outline: AC-029 <obstacle> at the `-fH` path fails the test command before any test starts
      Given <obstacle> exists at `tests.txt`
      When `mill scalatest/test core.test -fH tests.txt` is invoked
      Then Mill should report that the test command failed, with a message naming `tests.txt`
      And neither `pushes` nor `pops` should have run
      And stdout should hold no consolidated output

      Examples: What the test command cannot delete
        | obstacle                                                 |
        | a folder                                                 |
        | a file that the test command has no permission to delete |

  Rule: An `-fH` file that cannot be written fails the test command, while every other output is still written

    Scenario: AC-030 An `-fH` path inside a file fails the test command with the other outputs written
      Given a file exists at `reports`
      When `mill scalatest/test core.test -oH -fH reports/tests.txt -fJ report.json` is invoked
      Then Mill should report that the test command failed, with a message naming `reports/tests.txt`
      And the file `report.json` should hold JSON output whose root holds the module node `core.test`
      And stdout should hold the human-readable output

  Rule: The test command accepts formatting and filtering characters on an `-fJ`

    Scenario Outline: AC-010 An `-fJ` with the character <character> writes the JSON output
      When `mill scalatest/test core.test -fJ<character> report.json` is invoked
      Then the file `report.json` should hold JSON output whose root holds the module node `core.test`

      Examples: Formatting and filtering characters
        | character |
        | c         |
        | w         |
        | d         |

  Rule: Formatting characters on an `-fJ` leave its JSON output unchanged

    Scenario: AC-028 `-fJwd` writes the JSON output that `-fJ` writes
      When `mill scalatest/test core.test -fJwd report.json -fJ other.json` is invoked
      Then the file `report.json` should hold the same JSON output as the file `other.json`
```

```gherkin
Feature: Formatting characters
  A formatting character on the `-oH` or `-fH` that names a human-readable output changes how that output prints its lines: `w` prints them without colour, and `d` prints the duration of each node shown that has one, in the form ScalaTest's `-oD` prints a test's duration.
  A line is compared without its indentation.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSpec`, an `AnyFunSpec` whose scope `A stack` holds the test `pushes`, which succeeds, and the test `pops`, which fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion

  Rule: A human-readable output is in colour unless `w` is on the argument that names it

    Scenario Outline: AC-011 Human-readable output <where> without `w` is in colour
      When `mill scalatest/test core.test <arguments>` is invoked
      Then in the human-readable output <where>, the line `- pops *** FAILED ***` should be red

      Examples: Each place a human-readable output is written
        | arguments          | where                   |
        | `-oH`              | on stdout               |
        | `-o -fH tests.txt` | in the file `tests.txt` |

    Scenario Outline: AC-012 Human-readable output <where> with `w` has no colour
      When `mill scalatest/test core.test <arguments>` is invoked
      Then no line of the human-readable output <where> should carry a colour

      Examples: Each place a human-readable output is written
        | arguments           | where                   |
        | `-oHw`              | on stdout               |
        | `-o -fHw tests.txt` | in the file `tests.txt` |

  Rule: A human-readable output prints a node's duration only where `d` is on the argument that names it and the node has a duration

    Scenario Outline: AC-013 With `d`, the line for <node> ends with its duration
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>` followed by a space and the duration of <node> in parentheses, such as `<line> (12 milliseconds)`

      Examples: Nodes with a duration
        | node                   | line        |
        | the test `pushes`      | - pushes    |
        | the scope `A stack`    | A stack     |
        | the suite `StackSpec`  | StackSpec:  |
        | the module `core.test` | [core.test] |

    Scenario Outline: AC-014 Without `d`, the line for <node> has no duration
      When `mill scalatest/test core.test -oH` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>`

      Examples: Nodes with a duration
        | node                   | line        |
        | the test `pushes`      | - pushes    |
        | the scope `A stack`    | A stack     |
        | the suite `StackSpec`  | StackSpec:  |
        | the module `core.test` | [core.test] |

    Scenario: AC-031 With `d`, a stopped run's line ends with the run's duration
      Given `core.test` also holds the suite `HaltSuite`, an `AnyFunSuite` whose one test `halts` requests that the run stop
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for the stopped run should be the run's line followed by a space and the run's duration in parentheses, such as `Run 1: (850 milliseconds)`

    Scenario Outline: AC-032 With `d`, the line of <node> stays hidden
      When `mill scalatest/test core.test -oHd` is invoked
      Then the human-readable output on stdout should hold no line starting `<line>`

      Examples: Nodes whose lines are not shown
        | node                                            | line                |
        | a run that completed                            | Run 1:              |
        | the root, which has no error or output attached | Consolidated tests: |

    Scenario Outline: AC-033 With `d`, the line for <node>, which has no duration, is unchanged
      Given <state>
      When `mill scalatest/test core.test -oHd` is invoked
      Then in the human-readable output on stdout, the line for <node> should be `<line>`

      Examples: Nodes without a duration
        | state                                                                       | node                   | line                    |
        | `StackSpec`'s scope `A stack` also holds the test `peeks`, which is ignored | the test `peeks`       | - peeks !!! IGNORED !!! |
        | `core.test` fails to compile                                                | the module `core.test` | [core.test]             |

  Rule: A formatting character changes only the output that its argument names

    Scenario: AC-015 `w` on `-oH` leaves the file's colour
      When `mill scalatest/test core.test -oHw -fH tests.txt` is invoked
      Then no line of the human-readable output on stdout should carry a colour
      And in the human-readable output in the file `tests.txt`, the line `- pops *** FAILED ***` should be red
```

```gherkin
Feature: Filtering characters
  A filtering character on the `-oH` or `-fH` that names a human-readable output omits lines from that output: `c` succeeded tests, `x` ignored tests, `e` pending tests, `o` info messages, `m` markup messages and `r` pending scopes.
  The summary line is the last line of the human-readable output.
  A line is compared without its indentation or colour.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSpec`, an `AnyFunSpec` with these scopes and tests:
      | scope                   | test         | what the test does                                                             |
      | `A stack`               | `pushes`     | sends the note `pushed`, then succeeds                                         |
      | `A stack`               | `pops`       | sends the info `checked`, the markup `popped` and the note `noted`, then fails |
      | `A stack`               | `peeks`      | is ignored                                                                     |
      | `A stack`               | `clears`     | is pending                                                                     |
      | `when empty` in `A set` | `has size 0` | succeeds                                                                       |
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

    Scenario: AC-039 The filtering character `r` omits a pending scope
      Given `StackSpec` also holds the scope `A queue`, which is pending and holds no test
      When `mill scalatest/test core.test -oHr` is invoked
      Then the human-readable output on stdout should hold no line `A queue (pending)`
      And the human-readable output on stdout should hold the line `- pops *** FAILED ***`

    Scenario: AC-017 Without a filtering character, every line is shown
      Given `StackSpec` also holds the scope `A queue`, which is pending and holds no test
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

  Rule: The messages attached to an omitted node, or to a node kept as a header, are omitted

    Scenario: AC-034 Under `c`, the note `pushed` attached to the omitted test `pushes` is omitted
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `[note] pushed`

    Scenario: AC-040 Under `c`, the info `built` attached to the omitted scope `when empty` is omitted
      Given `StackSpec`'s scope `when empty` sends the info `built` outside its test
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `[info] built`

    Scenario: AC-041 Under `c`, the info `checked` attached to the kept test `pops` is kept
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `[info] checked`

    Scenario: AC-045 Under `r`, the info attached to a pending scope kept as a header is omitted
      Given `StackSpec` also holds the scope `A heap`, which is pending and holds only the test `shrinks`, which fails
      And `StackSpec`'s scope `A heap` sends the info `heaped` outside its test
      When `mill scalatest/test core.test -oHr` is invoked
      Then the human-readable output on stdout should hold no line `[info] heaped`

  Rule: A node that a filtering character names keeps its line as a header where a node beneath it is kept

    Scenario: AC-046 Under `r`, a pending scope holding a failed test keeps its line above that test
      Given `StackSpec` also holds the scope `A heap`, which is pending and holds only the test `shrinks`, which fails
      When `mill scalatest/test core.test -oHr` is invoked
      Then the human-readable output on stdout should hold the line `A heap (pending)`
      And the line `- shrinks *** FAILED ***` should appear under the line `A heap (pending)`, indented one level deeper

    Scenario: AC-047 Under `c`, a succeeded test that runs a suite keeps its line above that suite
      Given `core.test` also holds the suite `OuterSpec`, an `AnyFunSpec` whose test `delegates` runs the suite `InnerSuite`, then succeeds
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `- delegates`
      And the line `InnerSuite:` should appear under the line `- delegates`, indented one level deeper

    Scenario: AC-048 Under `rx`, a pending scope whose only test is ignored is omitted
      Given `StackSpec` also holds the scope `A heap`, which is pending and holds only the test `skips`, which is ignored
      When `mill scalatest/test core.test -oHrx` is invoked
      Then the human-readable output on stdout should hold no line `A heap (pending)`

  Rule: A scope is omitted, at any depth, where filtering characters omit all of its child scopes and tests, whatever its own outcome

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

    Scenario: AC-042 Under `c`, a pending scope whose one test succeeded is omitted
      Given `StackSpec` also holds the scope `A heap`, which is pending and holds only the test `grows`, which succeeds
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold no line `A heap (pending)`

    Scenario: AC-036 Under `c`, a scope with no children is kept
      Given `StackSpec` also holds the scope `A queue`, which is pending and holds no test
      When `mill scalatest/test core.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `A queue (pending)`

  Rule: Filtering characters never omit a module, a run or a suite

    Scenario Outline: AC-038 Under `c`, the line `<line>` of a node holding only succeeded tests is kept
      Given the build also has the JVM test module `queue.test`, which tests with `com.edc::scalatest` at that release and holds only the suite `QueueSpec`, an `AnyFunSpec` whose one test `enqueues` requests that the run stop, then succeeds
      When `mill scalatest/test core.test queue.test -oHc` is invoked
      Then the human-readable output on stdout should hold the line `<line>`

      Examples: Nodes that filtering characters never omit
        | line         |
        | [queue.test] |
        | Run 1:       |
        | QueueSpec:   |

  Rule: Filtering characters omit lines, never counts

    Scenario: AC-022 The summary line under `c` still counts the succeeded tests
      When `mill scalatest/test core.test -oHc` is invoked
      Then the summary line on stdout should count 2 succeeded tests

  Rule: A filtering character changes only the output that its argument names

    Scenario: AC-023 `c` on `-oH` leaves the file's succeeded tests
      When `mill scalatest/test core.test -oHc -fH tests.txt` is invoked
      Then the human-readable output on stdout should hold no line `- pushes`
      And the human-readable output in the file `tests.txt` should hold the line `- pushes`
```

```gherkin
Feature: Output arguments
  The test command's output arguments are `-o`, which names stdout, and `-f`, which names the file at the path that follows it.
  Each holds output letters, `H` for human-readable output, `P` for progress output and, on `-f` only, `J` for JSON output, and may also hold formatting and filtering characters.
  An `-o` holds no output letter, `H`, `P`, or `H` with `P`, and an `-f` holds `J`, `H`, `P`, or `H` with `P`.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test` tests with `com.edc::scalatest` at that release and holds the suite `StackSuite`, an `AnyFunSuite` whose test `pushes` succeeds and whose test `pops` fails
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion

  Rule: An output argument whose output letters are not a set its kind takes, or that holds a character other than an output letter, a formatting character or a filtering character, fails the test command before any test starts

    Scenario Outline: AC-024 The output argument <argument> fails the test command before any test starts
      When `mill scalatest/test core.test <arguments>` is invoked
      Then Mill should report that the test command failed, with a message naming `<argument>`
      And neither `pushes` nor `pops` should have run
      And stdout should hold no consolidated output

      Examples: Output arguments the test command does not take
        | arguments      | argument |
        | -oJ            | -oJ      |
        | -oD            | -oD      |
        | -oHz           | -oHz     |
        | -fJH tests.txt | -fJH     |
        | -fHC tests.txt | -fHC     |
        | -fw tests.txt  | -fw      |

  Rule: An output argument whose output letters include `H` writes the human-readable output, whether or not they include `P`

    Scenario Outline: AC-043 `<arguments>` writes the human-readable output to <where>
      When `mill scalatest/test core.test <arguments>` is invoked
      Then <where> should hold the human-readable output

      Examples: Output arguments holding `H` with `P`
        | arguments         | where                |
        | -oHP              | stdout               |
        | -o -fHP tests.txt | the file `tests.txt` |
        | -o -fPH tests.txt | the file `tests.txt` |

  Rule: The test command accepts an output argument whose only output letter is `P`

    Scenario Outline: AC-044 The tests run under `<arguments>`
      When `mill scalatest/test core.test <arguments>` is invoked
      Then the test `pushes` should have run

      Examples: Output arguments holding only `P`
        | arguments     |
        | -oP           |
        | -fP tests.txt |

  Rule: The output letters and characters of one output argument form a set, whose order and repeats do not matter

    Scenario Outline: AC-025 `<variant>` writes the output that `-fHwc` writes
      When `mill scalatest/test core.test -fHwc tests.txt <variant> other.txt` is invoked
      Then the file `other.txt` should hold the same human-readable output as the file `tests.txt`

      Examples: Orders and repeats of the letters and characters of `-fHwc`
        | variant  |
        | -fHcw    |
        | -fcwH    |
        | -fHHwwcc |

  Rule: An `-o` with no output letter writes no consolidated output to stdout, whatever characters it holds

    Scenario Outline: AC-026 `-o<character>` writes no consolidated output to stdout
      When `mill scalatest/test core.test -o<character>` is invoked
      Then stdout should hold no consolidated output

      Examples: A formatting character and a filtering character
        | character |
        | w         |
        | c         |

  Rule: Two `-f` arguments whose paths resolve to one file fail the test command before any test starts

    Scenario Outline: AC-027 Two `-f` arguments that name one file fail the test command, naming the file
      When `mill scalatest/test core.test <arguments>` is invoked
      Then Mill should report that the test command failed, with a message naming `tests.txt`
      And neither `pushes` nor `pops` should have run
      And stdout should hold no consolidated output

      Examples: Two `-f` arguments naming one file
        | arguments                              |
        | -fH tests.txt -fH tests.txt            |
        | -fH tests.txt -fJ tests.txt            |
        | -fH tests.txt -fJ reports/../tests.txt |
```

Each epic requirement this feature addresses, with the criteria that address it:

| Epic requirement             | Criteria                                                                                                               |
|------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `../SPECIFICATION.md` R-138  | AC-010, AC-011, AC-012, AC-013, AC-016, AC-019, AC-024, AC-025, AC-026, AC-028, AC-039                                 |
| `../SPECIFICATION.md` R-139  | AC-015, AC-023                                                                                                         |
| `../SPECIFICATION.md` R-127a | AC-016, AC-017, AC-018, AC-019, AC-022, AC-034, AC-037, AC-038, AC-039, AC-040, AC-041, AC-045, AC-046, AC-047, AC-048 |
| `../SPECIFICATION.md` R-127b | AC-020, AC-021, AC-035, AC-036, AC-042, AC-046                                                                         |
| `../SPECIFICATION.md` R-106c | AC-016, AC-017, AC-020, AC-021, AC-037, AC-038, AC-039, AC-046, AC-047, AC-048                                         |
| `../SPECIFICATION.md` R-106f | AC-011, AC-012, AC-015                                                                                                 |
| `../SPECIFICATION.md` R-106g | AC-013, AC-014, AC-031, AC-032, AC-033                                                                                 |
| `../SPECIFICATION.md` R-049a | AC-001, AC-002, AC-003, AC-007, AC-024, AC-025, AC-043, AC-044                                                         |
| `../SPECIFICATION.md` R-049b | AC-004, AC-005, AC-006, AC-027                                                                                         |
| `../SPECIFICATION.md` R-055  | AC-001, AC-003, AC-004, AC-011, AC-012, AC-016, AC-023, AC-026, AC-028, AC-043                                         |
| `../SPECIFICATION.md` R-020  | AC-001, AC-003, AC-005, AC-030                                                                                         |
| `../SPECIFICATION.md` R-077  | AC-008, AC-029                                                                                                         |
| `../SPECIFICATION.md` R-059  | AC-009                                                                                                                 |
