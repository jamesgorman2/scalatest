# Class globs, framework arguments and JUnit XML

Scale: feature.

## Summary

The test command takes no class globs or framework arguments, so it can only run a module as Mill's `testForked` runs it with no arguments, and it writes no JUnit XML report. This feature has the test command take class globs and framework arguments as Mill 1.3.1's `testOnly` takes them, run the module as `testOnly` or `testForked` would run it with them, and write the module's JUnit XML report in the folder the test command owns for the module.

## Plan

[Plan: class globs, framework arguments and JUnit XML](https://github.com/jamesgorman2/scalatest/issues/214)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this specification.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture, including the arguments the test command gives each run (§ Data flow).
- [../JSON-LAYOUT.md](../JSON-LAYOUT.md) — the layout of the JSON output, including the root's `failures` (§ Root and modules).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Bare `R-nnn` and `AC-nnn` IDs are this document's. The epic's requirements are cited as `../SPECIFICATION.md` R-nnn. Terms are defined in `.docs/ubiquitous-language.md`, among them *worker*, *fork group* and *task folder*.

## Scope

- Class globs and framework arguments, given after the test command's first `--`, for one JVM test module named by one module selector.
- Running the module as Mill 1.3.1's `testOnly` runs it where class globs are given, and as its `testForked` runs it where none are, including where the class globs select no test.
- The environment each worker of the module receives, including variables the `mill` launcher sets, such as `MILL_PARAMS`.
- The place of the *module's folder*: the folder the test command owns for a test module, holding every file Mill's `testOnly` or `testForked` would write in its task folder.
- The module's JUnit XML report, written in the module's folder.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- More than one module selector or test module, including class globs that match test classes in some selected modules and not in others (§ 5).
- Scala.js and Native test modules (§ 11, § 12, § 13).
- A module that presets class globs or framework arguments (§ 17).
- The consolidated output of a worker that ends before its tests finish (§ 8).
- ScalaTest arguments given among the test command's own arguments, before its first `--`. The test command fails on them, as on any other argument it does not take.
- One JUnit XML report covering more than one module, or written from the consolidated output.

## Requirements

### Arguments

- R-001 The test command reads every argument after its first `--` as Mill's `testOnly` reads its arguments: the arguments before the next `--` are *class globs*, and the arguments after that `--` are *framework arguments*. Where no second `--` follows, every argument after the first `--` is a class glob. Where the test command's arguments hold no `--`, it has no class globs and no framework arguments. The module selector and the test command's output arguments come before the first `--` (`../SPECIFICATION.md` R-055).
- R-002 The test command accepts a framework argument only where it is one of the ScalaTest flags `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, with the values ScalaTest's runner takes after that flag. Where any framework argument is another flag that ScalaTest's runner recognises, a flag it does not recognise, or a listed flag without the value it takes, the test command fails before any test starts with a message naming that argument, and writes no consolidated output (`../SPECIFICATION.md` R-125a, R-125b).

### Running the module

- R-003 Where at least one class glob is given, the test command runs the module's tests as `mill <module>.testOnly <class globs> -- <framework arguments>` runs them. Only the test classes that at least one class glob matches run. A class glob is matched against a test class's fully qualified name without any trailing `$`. A class glob without `*` matches only that exact name, and each `*` in a class glob matches any run of characters, including none (`../SPECIFICATION.md` R-053a, R-055).
- R-004 Where no class glob is given, the test command runs the module's tests as `mill <module>.testForked <framework arguments>` runs them (`../SPECIFICATION.md` R-053b).
- R-005 Each run of the module receives the module's `testArgsDefault` arguments that the test command keeps, followed by the test command's framework arguments in the order given. Where ScalaTest lets a later argument override an earlier one, as with two `-D` arguments for one key, a framework argument therefore overrides a `testArgsDefault` argument, as under Mill's `testOnly` (`../SPECIFICATION.md` R-053a, R-053b, R-142).
- R-006 Where class globs are given and none of them matches a test class that the module's test framework discovers, the test command starts no test of the module and fails. The JSON output's root then holds one failure, whose message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-007 Where class globs are given, every worker of the module has ended, and no run reported any test as succeeded, failed, ignored, pending or canceled or any suite as aborted, the test command fails. The JSON output's root then holds one failure, whose message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-008 Each worker of the module receives the environment that Mill's `testOnly` or `testForked` gives its workers when the same `mill` command line starts it. Where the module's `propagateEnv` is on, that is the environment in which the `mill` command line started, overlaid by the module's `forkEnv`. Where `propagateEnv` is off, that is the module's `forkEnv` alone. A variable the `mill` launcher sets from its own command line, such as `MILL_PARAMS` on Windows, therefore holds the command line that started the test command (`../SPECIFICATION.md` R-053a, R-053b).

### The module's folder and the JUnit XML report

- R-009 The module's folder is `org.scalatest.mill.TestCommandModule/modules/<module>` in Mill's output folder, where `<module>` is the module's Mill module path as Mill writes it, such as `core.test`. For the build's default output folder, the module `core.test` has the folder `out/org.scalatest.mill.TestCommandModule/modules/core.test`. Each file that Mill's `testOnly` or `testForked` would write in its task folder is written at the same relative path in the module's folder, such as each worker's `sandbox` working directory (`../SPECIFICATION.md` R-110).
- R-010 The test command writes the module's JUnit XML report in the module's folder where every condition below holds, whether or not a test failed:
  - the module's `testReportXml` is `Some(<name>)`, and the report is written to the file `<name>` in the module's folder;
  - every worker of the module that started ended after its tests finished;
  - where class globs are given, at least one of them matches a test class that the module's test framework discovers, and some run reported a test as succeeded, failed, ignored, pending or canceled or a suite as aborted.

  The report holds the test suites and test cases, with their statuses and failure messages, that Mill's `testOnly` or `testForked` writes for the same module, class globs and framework arguments, apart from the `time` and `timestamp` attributes and the order of elements. Where any condition does not hold, the test command writes no JUnit XML report for the module (`../SPECIFICATION.md` R-047, R-110).
- R-011 The test command creates, changes and deletes nothing in the module's own `testForked` and `testOnly` task folders, such as `out/core/test/testForked.dest`, so a report Mill's own task wrote there stays as it was (`../SPECIFICATION.md` R-060, R-110).

## Acceptance criteria

Terms the criteria share:

- *The build* is a Mill 1.3.1 build whose JVM test module `core.test`, *the module*, tests with `com.edc::scalatest` at the release of this fork under test. The build adds the Mill plugin at that release as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`, and the module's definition names nothing of the plugin. Unless a scenario says otherwise, the module has `testParallelism` off, `testSandboxWorkingDir` on, and `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault`, `testReportXml` and `propagateEnv` unset.
- The module holds suites `Alpha1`, `Alpha2` and `Beta1`. *Suite `<S>`* is class `samples.<S>`, an `AnyFunSuite` with two tests, `fast`, tagged `samples.Fast`, and `slow`, both of which succeed unless a scenario says otherwise. The module also holds `samples.Fast`, an object extending `org.scalatest.Tag` with the name `samples.Fast`, which is not a test class. `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<S>.<test>` in `<markers>`.
- The test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report> <arguments>`, where `<report>` is an absolute path at which nothing exists and `<arguments>` is what the scenario gives, or nothing. *The JSON output* is the JSON value parsed from the file at `<report>`. *The suites run* are the suite nodes under the JSON output's module node, run nodes included, that hold a test with an outcome other than aborted. *The tests run* in a suite are its tests whose outcome is `succeeded` or `failed`.
- *The module's folder* is `out/org.scalatest.mill.TestCommandModule/modules/core.test` in the build's workspace. *Mill's report for `<command>`* is the file `test-report.xml` that `mill core.test.<command>`, run in the same build, writes in its task folder, such as `out/core/test/testOnly.dest` for `testOnly samples.Alpha*`. Two JUnit XML reports *match* where they are equal apart from their `time` and `timestamp` attributes and the order of their elements.

```gherkin
Feature: Class globs
  After the test command's first `--`, the arguments before any second `--` are class globs, which select the test classes that run, as Mill's `testOnly` selects them.

  Rule: Only the test classes a class glob matches run
    Scenario Outline: AC-001 Class globs <globs>
      When the test command is invoked with `-- <globs>`
      Then the suites run are exactly <suites>
      And the test command succeeds

      Examples:
        | globs                          | suites                |
        | `samples.Alpha1`               | `Alpha1`              |
        | `samples.Alpha*`               | `Alpha1` and `Alpha2` |
        | `*1`                           | `Alpha1` and `Beta1`  |
        | `samples.*a2`                  | `Alpha2`              |
        | `samples.Beta1 samples.Alpha2` | `Alpha2` and `Beta1`  |

  Rule: Fork groups whose classes no class glob matches start no worker
    Scenario Outline: AC-002 Class globs that select one fork group's classes, testBatchFrameworkTasks <batch>
      Given the module's `testForkGrouping` is `Seq(Seq("samples.Beta1"), Seq("samples.Alpha1", "samples.Alpha2"))` and its `testBatchFrameworkTasks` is <batch>
      And each test sends an `info` message holding the system property `user.dir`
      When the test command is invoked with `-- samples.Alpha*`
      Then the module node holds exactly one run, whose `forkGroup` is 1 and which holds suites `Alpha1` and `Alpha2`
      And every `info` message is the path of the folder `sandbox` in the module's folder

      Examples:
        | batch   |
        | `false` |
        | `true`  |

    Scenario: AC-003 Class globs that select one class under test parallelism
      Given the module's `testParallelism` is on
      And `Alpha1`'s test `fast` sends an `info` message holding the system property `user.dir`
      When the test command is invoked with `-- samples.Alpha1`
      Then the module node holds exactly one run, which holds suite `Alpha1` only
      And the `info` message is the path of the folder `sandbox` in the module's folder

  Rule: Class globs that select no test fail the test command
    Scenario Outline: AC-004 Class glob <glob> matches no test class
      When the test command is invoked with `-- <glob>`
      Then the JSON output's root holds exactly one failure, whose message begins `Test selector does not match any test: <glob>`
      And no file exists in `<markers>`
      And no file `test-report.xml` exists in the module's folder
      And the test command fails

      Examples:
        | glob             |
        | `samples.Gamma*` |
        | `samples.Fast`   |

    Scenario: AC-005 Framework arguments leave no test of the matched classes to run
      When the test command is invoked with `-- samples.Alpha1 -- -z nothing-matches`
      Then the JSON output's root holds exactly one failure, whose message begins `Test selector does not match any test: samples.Alpha1`
      And no file exists in `<markers>`
      And no file `test-report.xml` exists in the module's folder
      And the test command fails
```

```gherkin
Feature: Framework arguments
  After a second `--`, the test command's arguments are framework arguments, which every run of the module receives after the module's own `testArgsDefault`.

  Rule: Framework arguments reach every run, with or without class globs
    Scenario Outline: AC-006 Arguments <arguments>
      When the test command is invoked with `<arguments>`
      Then the suites run are exactly <suites>
      And in each of those suites, the tests run are exactly <tests>
      And the test command succeeds

      Examples:
        | arguments                      | suites                         | tests  |
        | `-- -- -n samples.Fast`        | `Alpha1`, `Alpha2` and `Beta1` | `fast` |
        | `-- -- -l samples.Fast`        | `Alpha1`, `Alpha2` and `Beta1` | `slow` |
        | `-- samples.Alpha* -- -z slow` | `Alpha1` and `Alpha2`          | `slow` |

  Rule: A framework argument follows the module's own arguments
    Scenario: AC-007 A config map key given by both
      Given the module's `testArgsDefault` is `Seq("-Dcolour=red")`
      And `Alpha1`'s test `fast` sends an `info` message holding the config map's value for `colour`
      When the test command is invoked with `-- samples.Alpha1 -- -Dcolour=blue`
      Then the `info` message is `blue`

  Rule: The test command accepts only the framework arguments it passes on
    Scenario Outline: AC-008 Framework argument <argument>
      When the test command is invoked with `-- -- <argument>`
      Then Mill reports that the test command failed, with a message naming `<flag>`
      And no file exists in `<markers>`
      And nothing exists at `<report>`

      Examples:
        | argument            | flag        |
        | `-oD`               | `-oD`       |
        | `-s samples.Alpha1` | `-s`        |
        | `--unknown`         | `--unknown` |
        | `-n`                | `-n`        |
```

```gherkin
Feature: The environment of each worker
  Each worker receives the environment Mill's `testOnly` gives its workers when the same `mill` command line starts it.

  Rule: The environment of the `mill` command line reaches the workers where propagateEnv is on
    Scenario Outline: AC-009 propagateEnv <propagate>
      Given the module's `propagateEnv` is <propagate> and its `forkEnv` sets `FIXTURE_ENV` to `from-forkEnv`
      And the environment in which `mill` starts sets `MILL_PARAMS` to `-j 2 scalatest/test core.test -- samples.Alpha1`
      And `Alpha1`'s test `fast` sends an `info` message holding the environment variables `MILL_PARAMS` and `FIXTURE_ENV`, each `absent` where it is not set
      When the test command is invoked with `-- samples.Alpha1`
      Then the `info` message holds `MILL_PARAMS` <params> and `FIXTURE_ENV` `from-forkEnv`

      Examples:
        | propagate | params                                            |
        | `true`    | `-j 2 scalatest/test core.test -- samples.Alpha1` |
        | `false`   | `absent`                                          |
```

```gherkin
Feature: The module's JUnit XML report
  The test command writes the module's JUnit XML report in the module's folder, as Mill's `testOnly` or `testForked` writes it in its task folder.

  Rule: The report matches the report of the Mill task the test command runs as
    Scenario Outline: AC-010 Arguments <arguments>
      Given `Beta1`'s test `slow` fails
      When the test command is invoked with <arguments>
      Then the file `test-report.xml` in the module's folder matches Mill's report for `<command>`

      Examples:
        | arguments                 | command                         |
        | no arguments              | `testForked`                    |
        | `-- samples.Alpha*`       | `testOnly samples.Alpha*`       |
        | `-- -- -n samples.Fast`   | `testForked -n samples.Fast`    |
        | `-- * -- -l samples.Fast` | `testOnly * -- -l samples.Fast` |

  Rule: The module's testReportXml names the report
    Scenario Outline: AC-011 testReportXml <setting>
      Given the module's `testReportXml` is <setting>
      When the test command is invoked
      Then the module's folder holds exactly the JUnit XML report files <files>

      Examples:
        | setting                     | files               |
        | `Some("junit-results.xml")` | `junit-results.xml` |
        | `None`                      | none                |

  Rule: A worker that ends before its tests finish leaves no report
    Scenario: AC-012 A test halts its worker
      Given `Alpha1`'s test `fast` halts its JVM with `Runtime.getRuntime.halt(1)`
      When the test command is invoked
      Then no file `test-report.xml` exists in the module's folder

  Rule: The test command leaves the folders of Mill's own test tasks as they were
    Scenario: AC-013 Mill's own report from an earlier testForked
      Given `mill core.test.testForked` has run in the build and written `out/core/test/testForked.dest/test-report.xml`
      When the test command is invoked with `-- samples.Alpha1`
      Then `out/core/test/testForked.dest/test-report.xml` has the content and modification time it had before the test command was invoked
      And no folder `out/core/test/testOnly.dest` exists
```

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-001       | AC-001, AC-006, AC-010                         |
| R-002       | AC-006, AC-007, AC-008                         |
| R-003       | AC-001, AC-002, AC-003, AC-010                 |
| R-004       | AC-006, AC-010                                 |
| R-005       | AC-007                                         |
| R-006       | AC-004                                         |
| R-007       | AC-005                                         |
| R-008       | AC-009                                         |
| R-009       | AC-002, AC-003, AC-010                         |
| R-010       | AC-004, AC-005, AC-010, AC-011, AC-012         |
| R-011       | AC-013                                         |
