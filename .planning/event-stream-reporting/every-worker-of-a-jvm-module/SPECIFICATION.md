# Every worker of a JVM module

Scale: feature.

## Summary

The test command runs a JVM test module in one worker, whatever the module's `testParallelism`, and fails where the module has more than one fork group with test classes. This feature runs one JVM test module in every worker that Mill 1.3.1's own `testForked` starts for it, and consolidates each worker's run as a run node under the module. The human-readable output shows a run only where the run has output attached, the run aborted or stopped, or the run was assigned test classes and ran none of them; the JSON output keeps every run.

## Plan

[Plan: every worker of a JVM module](https://github.com/jamesgorman2/scalatest/issues/159)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this specification.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this specification are the epic's.
- [../JSON-LAYOUT.md](../JSON-LAYOUT.md) — the layout of the JSON output, including each run node's `number` and `forkGroup` (§ Run nodes).
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture, including how the test command starts each worker and gives each worker's run to the consolidation library.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Terms are defined in `.docs/ubiquitous-language.md`, among them *worker*, *fork group* and *assigned test classes*.

## Scope

- Running one JVM test module, named by one module selector, in every worker that Mill 1.3.1's `testForked` starts for that module, as the module's `testForkGrouping`, `testParallelism` and `testBatchFrameworkTasks` and Mill's test queue decide. The test command no longer fails on a module with more than one fork group.
- One run node for each worker's run, including the run of a worker given no task.
- Each run node's `number` and `forkGroup`, and the order of a module's run nodes, in the JSON output, as `../JSON-LAYOUT.md` § Tree and § Run nodes define them.
- The fork group and worker index the test command gives each run. The fork group is the position of the run's fork group among the groups the module's `testForkGrouping` gives, counting every group it gives. The *worker index* is the index, counted from 0, under which Mill's test queue starts the worker within its fork group, as in the worker's folder name `worker-<i>`. A fork group that Mill runs in one worker has only worker index 0.
- Which runs the human-readable output shows, and where it shows the suites of the runs it does not show.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- Class globs and Mill's `testOnly` (§ 4).
- More than one module selector or module (§ 5).
- Mill releases other than 1.3.1 (§ 6).
- `--halt-on-failure` (§ 7).
- A worker that dies (§ 8).
- A run whose process exits with a failure after the run completes (§ 8).
- The test command giving the consolidation library each run's assigned test classes (§ 8).
- Showing a test class that was assigned to a worker and that no run reported (§ 8).
- Showing a test class left in a fork group's queue (§ 8).
- Progress output (§ 9).
- Output captured against a suite, scope or test (§ 10).

## Epic requirements met

This feature meets each requirement below for one module selector and one JVM test module run in every worker Mill 1.3.1's `testForked` starts for it, and with the consolidation library called directly. Where a requirement is followed by a parenthesis, this feature meets only the part the parenthesis names.

- R-053b (fork grouping, test parallelism, the test queue and `testBatchFrameworkTasks`), R-096a (several runs in a module), R-105 (a module's runs and the suites of the runs the human-readable output does not show).
- R-144, R-074a (each of a module's runs), R-074b (each of a module's runs).
- R-043, R-087 (the output of each worker, on that worker's run only).
- R-046c, R-137 (a failure in any worker).
- R-028 (`forkGroup`, and several run nodes in a module), R-113 (runs given with no fork group).

## Acceptance criteria

Terms the criteria share:

- *The build* is a Mill 1.3.1 build whose JVM test module `core.test`, *the module*, tests with `com.edc::scalatest` at the release of this fork under test. The build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, and the module's definition names nothing of the plugin. Unless a scenario says otherwise, the module has `testSandboxWorkingDir` on and `testBatchFrameworkTasks` unset.
- *Suite `<S>`* is class `samples.<S>`, an `AnyFunSuite` with one test, `t`, labelled `- t`, which succeeds, unless a scenario says otherwise.
- The test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report>`, where `<report>` is an absolute path at which nothing exists, unless a scenario says otherwise. *The JSON output* is the JSON value parsed from the file at `<report>`. *The module's runs* are the run nodes in the `contents` of the JSON output's module node.
- *The human-readable output* is what the test command writes to its stdout, compared without colour codes and without the prefix, such as `1] `, that Mill puts on each line, and *apart from its summary line* means every line but its last. A line is shown with its leading spaces.
- Where a scenario uses *the library directly*, the consolidation library is called without Mill, with colour off, and is given runs of the module `core.test`. Each run is given its fork group and worker index where the scenario states them. Each run is given its events in the order the scenario lists them:
  - `suite <S> runs` is a structure for suite `<S>` with one test, `t`, labelled `- t`, followed by `SuiteStarting`, `TestStarting`, `TestSucceeded` and `SuiteCompleted`; `suite <S> runs test <u>` is the same with a test `<u>`, labelled `- <u>`, in place of `t`;
  - `stdout "<line>"` is a line captured from the run's process's stdout;
  - `run ended <how>` is the run's `runEnded` event, where `<how>` is `completed`, `aborted` or `stopped`, after `RunCompleted`, `RunAborted` or `RunStopped` respectively.

  Every run begins with its `runStarted` event and `RunStarting`. Once its events are fed, each run gives the library its *assigned classes*: the test classes its worker claimed from Mill's test queue or, under `testBatchFrameworkTasks`, the whole class list its worker was given. A run's assigned classes are the classes of the suites it runs unless a scenario says otherwise.

```gherkin
Feature: Every worker of a JVM module
  The test command runs the module in the workers Mill's own `testForked` starts for it, and gives each worker's run its own run node.

  Background:
    Given the build, whose module holds suites `A1`, `A2`, `B1` and `B2`
    And the module's `testForkGrouping` is `Seq(Seq("samples.B1", "samples.B2"), Seq("samples.A1", "samples.A2"))`

  Rule: Each fork group's suites run only in that fork group's workers, and in as many workers as Mill's own `testForked` can start for that group
    Scenario Outline: AC-001 testParallelism <parallelism>, testBatchFrameworkTasks <batch>, testSandboxWorkingDir <sandbox>
      Given the module's `testParallelism` is <parallelism>, its `testBatchFrameworkTasks` is <batch> and its `testSandboxWorkingDir` is <sandbox>
      When the test command is invoked
      Then each of `B1` and `B2` is in the `contents` of at least one of the module's runs whose `forkGroup` is 0, and in no run whose `forkGroup` is 1
      And each of `A1` and `A2` is in the `contents` of at least one of the module's runs whose `forkGroup` is 1, and in no run whose `forkGroup` is 0
      And for each `forkGroup`, the module has <runs> runs with that `forkGroup`
      And the test command succeeds

      Examples:
        | parallelism | batch   | sandbox | runs          |
        | `false`     | `false` | `true`  | exactly 1     |
        | `true`      | `false` | `true`  | either 1 or 2 |
        | `true`      | `false` | `false` | either 1 or 2 |
        | `false`     | `true`  | `true`  | exactly 1     |
        | `true`      | `true`  | `true`  | exactly 1     |

  Rule: A test that fails in any worker fails the test command, and every worker's runs are still in the output
    Scenario: AC-002 A failing test in the second fork group
      Given the module's `testParallelism` is `true`
      And `A2`'s test `t` fails
      When the test command is invoked
      Then the JSON output holds a test `t` in suite `A2` whose `outcome` is `failed`
      And each of `A1`, `B1` and `B2` is in the `contents` of one of the module's runs
      And the test command fails

  Rule: A module's runs are numbered by fork group, then by worker index, whatever order they start and end in
    Scenario: AC-003 The first fork group's run ends last
      Given the module's `testParallelism` is `false`
      And `B1`'s test `t` sleeps for 3 seconds before it succeeds
      When the test command is invoked
      Then the module's runs are, in order, a run whose `number` is 1 and `forkGroup` is 0, then a run whose `number` is 2 and `forkGroup` is 1
      And the run whose `number` is 1 holds suites `B1` and `B2`, and the run whose `number` is 2 holds suites `A1` and `A2`

    Scenario: AC-004 Two workers in each fork group
      Given the module's `testParallelism` is `true`
      And each suite's test `t` sleeps for 3 seconds, then sends an `info` message holding the system property `user.dir`
      When the test command is invoked as `mill -j 4 scalatest/test core.test -fJ <report>`
      Then for each `forkGroup`, the module has exactly two runs with that `forkGroup`
      And within each of the module's runs, every `info` message is a path holding one and the same folder `worker-<i>`
      And of the two runs with each `forkGroup`, the one with the lower `number` has the lower `<i>`
```

```gherkin
Feature: A worker given no task
  A worker that Mill starts with no test class to run is still a run.

  Rule: A module with no test class has one run, which the human-readable output does not show
    Scenario: AC-005 No test class
      Given the build, whose module holds no test class
      When the test command is invoked
      Then the module's runs are exactly one run, whose `number` is 1 and `forkGroup` is 0, whose `contents` is `[]` and which has no `outcome`
      And the human-readable output, apart from its summary line, is:
        """
        [core.test]
        """
      And the test command succeeds
```

```gherkin
Feature: Runs in the human-readable output
  A run's line, `Run <n>:`, is shown only where the run aborted or stopped, was assigned test classes and ran none of them, or has output attached to the run itself, such as its worker's stdout.
  The suites of every run whose line is not shown form one list directly under the module's line, ordered by name. A suite that ran more than once appears once for each time it ran, in order of its runs' numbers.
  Each run whose line is shown follows that list, in order of number, holding its own suites.

  Rule: A run that has output attached shows its line; a run that only ran suites does not
    Scenario: AC-006 One worker writes to stdout
      Given the build, whose module holds suites `Zeta` and `Alpha`
      And the module's `testForkGrouping` is `Seq(Seq("samples.Zeta"), Seq("samples.Alpha"))` and its `testParallelism` is `false`
      And `Zeta`'s test `t` writes the line `hello` to stdout
      And nothing else in either run writes to stdout or stderr
      When the test command is invoked
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Alpha:
            - t
          Run 1:
            Zeta:
              - t
            [stdout] hello
        """

  Rule: A failing test does not show its run's line
    Scenario: AC-007 A failing test in one of two workers
      Given the build, whose module holds suites `Zeta` and `Alpha`
      And the module's `testForkGrouping` is `Seq(Seq("samples.Zeta"), Seq("samples.Alpha"))` and its `testParallelism` is `false`
      And `Zeta`'s test `t` fails
      And nothing in either run writes to stdout or stderr
      When the test command is invoked
      Then the human-readable output holds no line beginning `Run `
      And the line `  Alpha:` comes before the line `  Zeta:`, each directly under `[core.test]`
      And the test command fails

  Rule: A run that aborted or stopped shows its line
    Scenario Outline: AC-008 A run that <how>
      Given the library directly
      And run 1, fork group 0, worker index 0: `suite Beta runs`, `run ended completed`
      And run 2, fork group 0, worker index 1: `suite Alpha runs`, `run ended <how>`
      When the library consolidates them
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Beta:
            - t
          Run 2:
            Alpha:
              - t
            <line>
        """

      Examples:
        | how     | line                  |
        | aborted | `*** RUN ABORTED ***` |
        | stopped | `*** RUN STOPPED ***` |

  Rule: A run assigned test classes that ran none of them shows its line; a run assigned none that ran none does not show its line
    Scenario: AC-009 Assigned classes that did not run
      Given the library directly
      And run 1, fork group 0, worker index 0: `suite Beta runs`, `run ended completed`
      And run 2, fork group 0, worker index 1: `run ended completed`, with assigned classes `samples.Alpha`
      And run 3, fork group 0, worker index 2: `run ended completed`, with no assigned class
      When the library consolidates them
      Then the human-readable output holds the line `  Run 2:` and no line `  Run 1:` or `  Run 3:`
      And the line `  Beta:` sits directly under `[core.test]`

  Rule: The suites of runs that are not shown form one list by name, which lists a suite once for each run it ran in; shown runs follow that list in order of number
    Scenario: AC-010 Three hidden runs and two shown
      Given the library directly
      And run 1, fork group 0, worker index 0: `suite Zeta runs`, `suite Alpha runs`, `run ended completed`
      And run 2, fork group 0, worker index 1: `suite Mid runs`, `stdout "two"`, `run ended completed`
      And run 3, fork group 1, worker index 0: `suite Alpha runs test u`, `suite Beta runs`, `run ended completed`
      And run 10, fork group 1, worker index 7: `suite Last runs`, `suite Kilo runs`, `stdout "ten"`, `run ended completed`
      And runs 4 to 9, fork group 1, worker indexes 1 to 6: each `run ended completed`, with no assigned class
      And run 3's `Alpha` starts before run 1's `Alpha`
      And the runs are given to the library in the order 10, 3, 9, 8, 7, 6, 5, 4, 2, 1
      When the library consolidates them
      Then the human-readable output, apart from its summary line, is:
        """
        [core.test]
          Alpha:
            - t
          Alpha:
            - u
          Beta:
            - t
          Zeta:
            - t
          Run 2:
            Mid:
              - t
            [stdout] two
          Run 10:
            Kilo:
              - t
            Last:
              - t
            [stdout] ten
        """
```

```gherkin
Feature: Runs in the JSON output
  The JSON output keeps every run node, in order of number, each holding its own suites.

  Rule: Every run node is kept, in order of number, with its own suites and its fork group
    Scenario: AC-011 Runs given out of order
      Given the library directly
      And run 1, fork group 0, worker index 0: `suite Zeta runs`, `suite Alpha runs`, `run ended completed`
      And run 2, fork group 0, worker index 1: `suite Mid runs`, `stdout "two"`, `run ended completed`
      And run 3, fork group 1, worker index 0: `suite Alpha runs`, `suite Beta runs`, `run ended completed`
      And the runs are given to the library in the order 3, 2, 1
      When the library consolidates them
      Then the module's `contents` is, in order:
        | number | forkGroup | contents, in order | output       |
        | 1      | 0         | Alpha, Zeta        | absent       |
        | 2      | 0         | Mid                | stdout `two` |
        | 3      | 1         | Alpha, Beta        | absent       |
      And each run node writes `number` then `forkGroup` as its second and third keys

  Rule: A run given no fork group has no `forkGroup`, and a module's runs given none are numbered in the order they are given
    Scenario: AC-012 Runs without a fork group
      Given the library directly
      And a run with no fork group and no worker index: `suite Beta runs`, `run ended completed`
      And then a run with no fork group and no worker index: `suite Alpha runs`, `run ended completed`
      When the library consolidates them
      Then the module's `contents` is a run whose `number` is 1, holding `Beta`, then a run whose `number` is 2, holding `Alpha`
      And neither run node has a `forkGroup` key

  Rule: The output of each worker is on that worker's run alone, in the order the worker wrote it
    Scenario: AC-013 Two workers each write to stdout
      Given the build, whose module holds suites `A1` and `B1`
      And the module's `testForkGrouping` is `Seq(Seq("samples.B1"), Seq("samples.A1"))` and its `testParallelism` is `false`
      And `B1`'s test `t` writes the lines `B 1` to `B 3` to stdout, and `A1`'s test `t` writes the lines `A 1` to `A 3` to stdout
      When the test command is invoked
      Then the `stdout` text of the `output` of the run whose `forkGroup` is 0 holds the lines `B 1` to `B 3`, in order, and no line beginning `A `
      And the `stdout` text of the `output` of the run whose `forkGroup` is 1 holds the lines `A 1` to `A 3`, in order, and no line beginning `B `
```

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-053b      | AC-001, AC-004, AC-005                         |
| R-096a      | AC-001, AC-005, AC-011                         |
| R-105       | AC-003, AC-004, AC-006, AC-007, AC-010, AC-011 |
| R-144       | AC-005, AC-006, AC-007, AC-008, AC-009, AC-010 |
| R-074a      | AC-005, AC-011                                 |
| R-074b      | AC-008                                         |
| R-043       | AC-013                                         |
| R-087       | AC-006, AC-013                                 |
| R-046c      | AC-002, AC-007                                 |
| R-137       | AC-002, AC-007                                 |
| R-028       | AC-001, AC-003, AC-011, AC-012                 |
| R-113       | AC-012                                         |
