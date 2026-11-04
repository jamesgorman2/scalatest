# Structured JSON from `Runner`

Scale: feature.

## Summary

Under `org.scalatest.tools.Runner` on the JVM, the JSON that `-fJ`, `-oJ` and `-eJ` write today does not say where an event sits in the test structure, so a reader cannot rebuild the nesting once suites or tests run in parallel. This feature makes every `J` spelling under `Runner` write JSON from which the run's test structure can be rebuilt, in every execution mode, while every existing reporter receives exactly what ScalaTest 3.2.19 sends it. It publishes the first version of the documented JSON format.

## Plan

[Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/12)

## Written against

- [Structured test reporting](../SPECIFICATION.md), the epic specification. `epic R-nnn` below is its requirement R-nnn; `R-nnn` and `AC-nnn` alone are this document's.
- [Structured test reporting: architecture](../ARCHITECTURE.md), § Modules § ScalaTest core and § The compatibility boundary, which fix how the structure travels.
- [Roadmap](../ROADMAP.md), whose features take the work § Out of scope names.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

Under `Runner` on the JVM, on Scala 2.11, 2.12, 2.13 and 3.3:

- The JSON written by `-fJ <file>`, `-oJ` and `-eJ` gains the run ID on every object, the node each object attaches to and that node's parent, and each node's declared position among its siblings.
- A new reporter argument naming a directory (epic R-071), called *the directory argument* below.
- The documented JSON format, first version.

`Runner` means every run that `Runner.run` or `Runner.main` starts, whoever calls it, IntelliJ's forked mode and the Ant task included, and every run and rerun the GUI reporter starts.

Execution modes:

- sequential;
- `-P`, with and without suite sorting (`-PS`);
- suites mixing in `ParallelTestExecution`;
- async suites, with and without parallel async test execution;
- suites mixing in `RandomTestOrder`.

Suite sources: `-s` (with `-t`, `-z` and `-i`), `-w`, `-m`, discovery on the runpath (with `-q`), `-A`, `-j` and `-b`; and nested suites, run in sequence, through the distributor, and under `StepwiseNestedSuiteExecution`.

### Out of scope

- The `Framework`'s paths, and `-oJ` passed to it: [Roadmap](../ROADMAP.md) § Structured output under the JVM `Framework`, in process, and § Structured output under sbt's forked mode.
- Test output capture and its chunk objects in the JSON: § Test output capture under `Runner`.
- Results output, tree assembly and the default format of plain `-o` and `-e`: § Progress-then-results output from `Runner`.
- Open-node tracking, epic R-075's `RunAborted`, and documenting how a reader recognises the runs of epic R-076 and R-077: § Runs that lose nodes end in `RunAborted`.
- Scala.js and Scala Native.

## Acceptance criteria

```gherkin
Feature: Structured JSON from Runner

  Background:
    Given a fixture of suites that includes:
      | every ScalaTest style trait, sync and async                                  |
      | a scope nested in a scope, and one scope text used twice in one suite        |
      | nested suites, two of which share a suiteId                                  |
      | info, alert, note and markup inside a test, in a scope outside any test, and in a suite outside any scope |
      | a test that fails with a throwable carrying a cause chain                    |
      | a suite that aborts while it runs                                            |
    And a recording reporter, compiled against ScalaTest 3.2.19 as Provided, as IntelliJ's forked-mode reporter is, passed with -C
```

### J spellings

Epic R-030, R-031.

```gherkin
  Rule: Each J spelling writes the run's JSON, and nothing else does

    Scenario Outline: AC-001 A J spelling writes the run's JSON
      Given the fixture runs with <argument>
      When the run finishes
      Then <destination> holds one JSON object for every event the recording reporter received

      Examples:
        | argument                         | destination        |
        | -fJ out.json                     | out.json           |
        | -oJ                              | stdout             |
        | -eJ                              | stderr             |
        | the directory argument with dir  | dir/<run ID>.json  |

    Scenario: AC-002 Several J spellings in one run
      Given the fixture runs with -fJ a.json, -eJ and the directory argument with dir
      When the run finishes
      Then a.json, stderr and dir/<run ID>.json hold the same objects

    Scenario: AC-003 No J spelling
      Given the fixture runs with -oD and -f out.txt
      When the run finishes
      Then no JSON is written anywhere

  Rule: The directory argument gives each run its own file (epic R-071)

    Scenario Outline: AC-004 Runs that share a directory
      Given two runs of the fixture, <how>, each with the directory argument with dir
      When both finish
      Then dir holds two files, each named for its run's run ID and holding only that run's objects

      Examples:
        | how                                |
        | one after the other in one JVM     |
        | at the same time in two JVMs       |
```

### Existing reporters

Epic R-002, R-003, R-057.

```gherkin
  Rule: J spellings change nothing an existing reporter receives

    Scenario: AC-005 Events reaching -C reporters
      Given the fixture runs under any execution mode in § Scope with the recording reporter, -fJ out.json and -eJ
      When it is compared with the same run under ScalaTest 3.2.19 with the recording reporter and no J spelling
      Then the recording reporter receives what epic R-002 requires for a sequential run and epic R-057 for every other mode

    Scenario Outline: AC-006 Output of 3.2-format reporters
      Given the fixture runs sequentially with <reporter> and -fJ out.json
      When it is compared with the same run under ScalaTest 3.2.19 with <reporter> alone
      Then <reporter>'s output is the same, apart from timestamps, durations and thread names

      Examples:
        | reporter      |
        | -oDU          |
        | -f out.txt    |
        | -u dir        |
```

### Test structure

Epic R-010, R-012 to R-014, R-032.

```gherkin
  Rule: A reader rebuilds the test structure from the run's JSON alone

    Scenario: AC-007 Containment under every execution mode
      Given the fixture runs under any execution mode in § Scope with -fJ out.json
      When a reader rebuilds the test structure from out.json alone
      Then every test is under its innermost scope, every scope under its enclosing scope or its suite, every nested suite under its parent suite, and every top-level suite under the run
      And each node's events are in the order the node emitted them
      And each node's children can be put in the order their parent declares them

    Scenario: AC-022 The run's children
      Given a run with -s A, -s B and -w for a package holding C and D, and -fJ out.json
      When a reader rebuilds the test structure from out.json
      Then the run's children, in declared order, are A, B and the suite through which Runner runs the discovered suites
      And C and D are children of that suite

    Scenario: AC-008 Repeated names get distinct nodes
      Given a run with -s for one suite class twice, and the fixture's repeated scope text and shared suiteId
      When a reader rebuilds the test structure from the run's JSON
      Then each suite run, each scope and each nested suite is a node with an ID no other node in the run has

    Scenario Outline: AC-009 Info, alert, note and markup attach to the node that emitted them
      Given the fixture runs under any execution mode in § Scope with -fJ out.json
      When a reader rebuilds the test structure from out.json
      Then an object emitted <where> attaches to <node>

      Examples:
        | where                          | node      |
        | inside a test                  | the test  |
        | in a scope, outside any test   | the scope |
        | in a suite, outside any scope  | the suite |

    Scenario: AC-010 A suite that bypasses ScalaTest's suite-running code
      Given a suite whose own run method reports tests and a nested suite through the reporter it was given
      When it runs with -fJ out.json
      Then every event it reported is in out.json, attached to that suite or an ancestor of it
      And those events are in the order the suite reported them

    Scenario: AC-011 A forwarding reporter that keeps the event
      Given a suite whose own reporter wrapper passes each event on unchanged
      When it runs under any execution mode in § Scope with -fJ out.json
      Then out.json holds one object for each event

    Scenario Outline: AC-023 A forwarding reporter that drops or replaces an event
      Given a suite whose own reporter wrapper <change> a test's event
      When it runs with -fJ out.json
      Then out.json holds <objects>

      Examples:
        | change                         | objects                                                                   |
        | drops                          | the original, attached to the test                                        |
        | replaces with a new Event      | the original, attached to the test, and the replacement, attached to the suite or an ancestor |

  Rule: The run is the root of its structure (epic R-033)

    Scenario: AC-012 Run-level events
      Given the fixture runs with discovery and -fJ out.json
      When the run finishes
      Then every object in out.json carries the run's run ID
      And DiscoveryStarting, DiscoveryCompleted, RunStarting and RunCompleted attach to the run, which has no parent

    Scenario: AC-013 A run that aborts before it starts
      Given a run with -s for a class that cannot be loaded, and -fJ out.json
      When the run finishes
      Then out.json holds its RunAborted, attached to the run

    Scenario Outline: AC-014 Distinct run IDs within one JVM
      Given <runs> in one JVM, each with -oJ
      Then the run IDs on their objects differ

      Examples:
        | runs                                     |
        | two calls of Runner.run, in sequence     |
        | two calls of Runner.run, at the same time |
        | two GUI runs                             |
        | a GUI run and a rerun of one of its tests |
```

### Fidelity

Epic R-016, R-017, R-018, R-031.

```gherkin
  Rule: The JSON loses nothing ScalaTest 3.2.19 writes, and nothing epic R-016 to R-018 require

    Scenario: AC-015 3.2.19's fields
      Given the fixture runs with -fJ out.json, and again under ScalaTest 3.2.19 with -fJ old.json
      When each object is parsed
      Then each object in out.json carries every field its counterpart in old.json carries, with the same name and meaning

    Scenario Outline: AC-016 Text is preserved exactly
      Given a test that fails with a message containing <text>
      When it runs with -fJ out.json and with -oJ to a console whose encoding cannot represent it
      Then a standard JSON parser reading either output gets the message exactly

      Examples:
        | text                           |
        | a newline and a tab            |
        | another control character      |
        | a quotation mark and a backslash |
        | a character outside ASCII      |
        | an unpaired surrogate          |

    Scenario: AC-017 Throwables
      Given the fixture's test whose throwable has a cause chain
      When it runs with -fJ out.json
      Then its TestFailed object holds the throwable's class name, message and stack trace, each cause's class name, message and stack trace in chain order, and the event's location

    Scenario: AC-018 Durations
      Given the fixture runs with -fJ out.json
      Then every object ending a test that ran, or a suite, carries that node's duration

    Scenario: AC-019 A suite that aborts
      Given the fixture's suite that aborts while it runs
      When it runs under any execution mode in § Scope with -fJ out.json
      Then its SuiteAborted object attaches to that suite

    Scenario Outline: AC-024 A suite whose constructor throws
      Given a suite whose constructor throws, run through <source> with -fJ out.json
      When the run finishes
      Then out.json holds <result>, as ScalaTest 3.2.19 reports it

      Examples:
        | source    | result                                                                   |
        | -s        | the run's RunAborted, attached to the run, and no object of any other suite |
        | discovery | the suite's SuiteAborted, attached to that suite                          |

  Rule: Output survives a dead process and shares streams cleanly (epic R-035, R-064)

    Scenario: AC-020 The JVM dies mid-run
      Given a run with -fJ out.json and the directory argument with dir, in which a test halts the JVM
      When the JVM has died
      Then out.json and dir/<run ID>.json each hold, as complete objects, every event reported before the halt, except that the last may be truncated or missing

    Scenario: AC-021 -oJ beside a -C reporter writing to stdout
      Given the fixture runs under -P with -oJ and a -C reporter that prints lines to stdout
      When the run finishes
      Then every JSON object on stdout occupies whole lines that no other line splits
```

## Documentation

Epic R-037.

- R-001 The JSON format document describes every object type and field the `J` spellings write under `Runner`, including the run ID, node, parent and declared position, and how to rebuild a run's test structure, each node's event order and its children's declared order.
- R-002 A reader written from the document alone, without reading ScalaTest's source, rebuilds AC-007's test structure from the fixture's JSON.
