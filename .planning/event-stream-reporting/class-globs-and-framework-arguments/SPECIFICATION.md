# Class globs and framework arguments

Scale: feature.

## Summary

The test command takes no class globs or framework arguments, so it can only run a module as Mill's `testForked` runs it with no arguments. This feature has the test command take class globs and framework arguments as Mill 1.3.1's `testOnly` takes them, and run the module with them as `testOnly` or `testForked` would. Each worker receives the environment that Mill's task would give it, including the variables the `mill` launcher sets. The module folder, the folder the test command owns for the module, holds the files Mill's task would write in its task folder, except the JUnit XML report, which the test command never writes.

## Plan

[Plan: class globs and framework arguments](https://github.com/jamesgorman2/scalatest/issues/214)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this specification.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against.
- [../JSON-LAYOUT.md](../JSON-LAYOUT.md) — the layout of the JSON output, including the root's `failures` (§ Root and modules).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Bare `R-nnn` and `AC-nnn` IDs are this document's. The epic's requirements are cited as `../SPECIFICATION.md` R-nnn. Terms are defined in `.docs/ubiquitous-language.md`, among them *worker*, *fork group*, *task folder* and *module folder*.

## Scope

- Class globs and framework arguments, given after the test command's first `--`, for one JVM test module named by one module selector.
- Running the module as Mill 1.3.1's `testOnly` runs it where class globs are given, including where the class globs select no test, and as its `testForked` runs it where no class glob is given.
- The environment each worker of the module receives, including variables the `mill` launcher sets, such as `MILL_PARAMS`.
- The place of the module folder, what it holds, and what an earlier test command left in it.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- More than one module selector or test module, including class globs and framework arguments applying alike to every selected test module (§ 5).
- Scala.js and Native test modules (§ 11, § 12, § 13).
- A module that presets class globs or framework arguments (§ 17).
- The consolidated output of a worker that ends before its tests finish (§ 8).
- Accepting ScalaTest arguments among the test command's own arguments, before its first `--`.

## Requirements

### Arguments

- R-001 The test command reads every argument after its first `--` as Mill's `testOnly` reads its arguments: the arguments before the next `--` are *class globs*, and the arguments after that `--` are *framework arguments*. Where no second `--` follows, every argument after the first `--` is a class glob. Where the test command's arguments hold no `--`, it has no class globs and no framework arguments. The module selector and the test command's output arguments come before the first `--` (`../SPECIFICATION.md` R-055).
- R-002 The test command accepts its framework arguments only where every one of them is either one of the ScalaTest flags `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, followed by the values ScalaTest's runner takes after that flag, or one of those values. Where any framework argument is another flag that ScalaTest's runner recognises, a flag it does not recognise, a listed flag without the values it takes, or an argument that is neither a flag nor a value of a listed flag before it, such as `foo`, the test command fails before any test starts with a message naming that argument, and writes no consolidated output (`../SPECIFICATION.md` R-125a, R-125b).
- R-013 Where a ScalaTest argument, such as `-n`, is among the test command's arguments before its first `--`, or among any of its arguments where they hold no `--`, the test command fails before any test starts with a message naming that argument, and writes no consolidated output (`../SPECIFICATION.md` R-125b).

### Running the module

- R-003 Where at least one class glob is given, the test command runs the module's tests as `mill <module>.testOnly <class globs> -- <framework arguments>` runs them. Only the test classes that at least one class glob matches run. A class glob is matched against a test class's fully qualified name without any trailing `$`. A class glob without `*` matches only that exact name, and each `*` in a class glob matches any run of characters, including none (`../SPECIFICATION.md` R-053a, R-055).
- R-004 Where no class glob is given, the test command runs the module's tests as `mill <module>.testForked <framework arguments>` runs them (`../SPECIFICATION.md` R-053b).
- R-005 Each run of the module receives the module's `testArgsDefault` arguments that are among the ScalaTest flags `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, each with its values, followed by the test command's framework arguments in the order given. Where ScalaTest takes the later of two arguments that set one thing, such as two `-D` arguments for one config map key, the framework argument takes effect over the `testArgsDefault` argument, as it does under Mill's `testOnly` and `testForked` (`../SPECIFICATION.md` R-053a, R-053b, R-142).
- R-006 Where class globs are given and none of them matches a test class that the module's test framework discovers, the test command starts no test of the module and fails. The JSON output's root then holds one failure, whose message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-007 Where class globs are given, at least one of them matches a test class that the module's test framework discovers, every worker of the module has ended, and no run reported any test as succeeded, failed, ignored, pending or canceled or any suite as aborted, the test command fails. The JSON output's root then holds one failure, whose message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-008 Each worker of the module receives the environment that Mill's `testOnly` or `testForked` would give its workers if started from a `mill` command line in the same environment as the test command. Where the module's `propagateEnv` is on, that environment is the one in which the `mill` command line started, overlaid by the module's `forkEnv`. Where `propagateEnv` is off, that environment is the module's `forkEnv` alone. A variable that the `mill` launcher sets from its own arguments, such as `MILL_PARAMS` on Windows, holds the arguments of the `mill` command line that started the test command (`../SPECIFICATION.md` R-053a, R-053b).

### The module folder

- R-009 The module folder is `org.scalatest.mill.TestCommandModule/modules/<module>` in Mill's output folder, where `<module>` is the module's name as Mill writes it in a task name: `core.test`, or `core[3.3.8].test` for the `test` module of the cross module `core` at `3.3.8`. For the build's default output folder, the module `core.test` has the module folder `out/org.scalatest.mill.TestCommandModule/modules/core.test`. Each file that Mill's `testOnly` or `testForked` would write in its task folder, other than its JUnit XML report, is written at the same relative path in the module folder, such as each worker's `sandbox` working directory (`../SPECIFICATION.md` R-110).
- R-012 Each test command deletes every file and folder that an earlier test command left in the module folder. It deletes them before it starts any test of the module, and deletes them also where it starts no test of the module (`../SPECIFICATION.md` R-110).
- R-010 Once a test command has ended, the module folder and every folder inside it hold no JUnit XML report. This holds whatever the module's `testReportXml` is, including where it is `Some(<name>)`, whatever class globs and framework arguments are given, and whether the test command succeeds or fails (`../SPECIFICATION.md` R-047, R-110).
- R-011 The test command creates, changes and deletes nothing in the module's own `testForked` and `testOnly` task folders, such as `out/core/test/testForked.dest`, so a report Mill's own task wrote there stays as it was (`../SPECIFICATION.md` R-060, R-110).

## Acceptance criteria

```gherkin
Feature: Class globs
  After the test command's first `--`, the arguments before any second `--` are class globs, which select the test classes that run, as Mill's `testOnly` selects them.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test`, the module, tests with `com.edc::scalatest` at that release, and its definition names nothing of the plugin
    And the module has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault`, `testReportXml`, `forkEnv` and `propagateEnv` unset unless the scenario sets them
    And the module holds the suites `Alpha1`, `Alpha2` and `Beta1`, the classes `samples.Alpha1`, `samples.Alpha2` and `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast`, tagged `samples.Fast`, and `slow`, both of which succeed
    And the module holds `samples.Fast`, an object extending `org.scalatest.Tag` with the name `samples.Fast`, which is not a test class
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists, and `<arguments>` is what the scenario gives, or nothing
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended
    And the suites run are the suite nodes, under the JSON output's module node or its run nodes, that hold a test whose outcome is not aborted
    And the module folder is `out/org.scalatest.mill.TestCommandModule/modules/core.test` in the build's workspace

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

  Rule: Only the workers that the matched test classes need start
    Scenario Outline: AC-002 Class globs that select one fork group's classes, testBatchFrameworkTasks <batch>
      Given the module's `testForkGrouping` is `Seq(Seq("samples.Beta1"), Seq("samples.Alpha1", "samples.Alpha2"))` and its `testBatchFrameworkTasks` is <batch>
      And each test sends an `info` message holding the system property `user.dir`
      When the test command is invoked with `-- samples.Alpha*`
      Then the JSON output's module node holds exactly one run node, whose `forkGroup` is 1 and which holds suites `Alpha1` and `Alpha2`
      And every `info` message is the path of the folder `sandbox` in the module folder

      Examples:
        | batch   |
        | `false` |
        | `true`  |

    Scenario: AC-003 Class globs that select one class under test parallelism
      Given the module's `testParallelism` is on
      And `Alpha1`'s test `fast` sends an `info` message holding the system property `user.dir`
      When the test command is invoked with `-- samples.Alpha1`
      Then the JSON output's module node holds exactly one run node, which holds suite `Alpha1` only
      And the `info` message is the path of the folder `sandbox` in the module folder

  Rule: Class globs that select no test fail the test command
    Scenario Outline: AC-004 Class glob <glob> matches no test class
      When the test command is invoked with `-- <glob>`
      Then the JSON output's root holds exactly one failure, whose message begins `Test selector does not match any test: <glob>`
      And no file exists in `<markers>`
      And the test command fails

      Examples:
        | glob             |
        | `samples.Gamma*` |
        | `samples.Fast`   |

    Scenario: AC-005 Framework arguments leave no test of the matched classes to run
      When the test command is invoked with `-- samples.Alpha1 -- -z nothing-matches`
      Then the JSON output's root holds exactly one failure, whose message begins `Test selector does not match any test: samples.Alpha1`
      And no file exists in `<markers>`
      And the test command fails
```

```gherkin
Feature: Framework arguments
  After a second `--`, the test command's arguments are framework arguments, which every run of the module receives after the module's own `testArgsDefault`.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test`, the module, tests with `com.edc::scalatest` at that release, and its definition names nothing of the plugin
    And the module has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault`, `testReportXml`, `forkEnv` and `propagateEnv` unset unless the scenario sets them
    And the module holds the suites `Alpha1`, `Alpha2` and `Beta1`, the classes `samples.Alpha1`, `samples.Alpha2` and `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast`, tagged `samples.Fast`, and `slow`, both of which succeed
    And the module holds `samples.Fast`, an object extending `org.scalatest.Tag` with the name `samples.Fast`, which is not a test class
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists, and `<arguments>` is what the scenario gives, or nothing
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended
    And the suites run are the suite nodes, under the JSON output's module node or its run nodes, that hold a test whose outcome is not aborted
    And the tests run in a suite are its tests whose outcome is `succeeded` or `failed`

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
      Then Mill reports that the test command failed, with a message naming `<named>`
      And no file exists in `<markers>`
      And nothing exists at `<report>`

      Examples:
        | argument            | named       |
        | `-oD`               | `-oD`       |
        | `-s samples.Alpha1` | `-s`        |
        | `--unknown`         | `--unknown` |
        | `-n`                | `-n`        |
        | `foo`               | `foo`       |

  Rule: A ScalaTest argument before the first `--` fails the test command
    Scenario: AC-016 A tag filter among the test command's own arguments
      When the test command is invoked with `-n samples.Fast -- samples.Alpha1`
      Then Mill reports that the test command failed, with a message naming `-n`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
```

```gherkin
Feature: The environment of each worker
  Each worker receives the environment that Mill's `testOnly` or `testForked` would give its workers if started from a `mill` command line in the same environment.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test`, the module, tests with `com.edc::scalatest` at that release, and its definition names nothing of the plugin
    And the module has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault`, `testReportXml`, `forkEnv` and `propagateEnv` unset unless the scenario sets them
    And the module holds the suite `Alpha1`, the class `samples.Alpha1`, an `AnyFunSuite` whose test `fast` succeeds
    And the test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists, and `<arguments>` is what the scenario gives

  Rule: The environment in which `mill` starts reaches the workers where propagateEnv is on, overlaid by forkEnv
    Scenario Outline: AC-009 propagateEnv <propagate>
      Given the module's `propagateEnv` is <propagate>
      And the module's `forkEnv` sets `FIXTURE_FORK` to `from-forkEnv` and `FIXTURE_BOTH` to `from-forkEnv`
      And the environment in which `mill` starts sets `FIXTURE_OUTER` to `from-outer` and `FIXTURE_BOTH` to `from-outer`
      And `Alpha1`'s test `fast` sends an `info` message holding the environment variables `FIXTURE_OUTER`, `FIXTURE_FORK` and `FIXTURE_BOTH`, each `absent` where it is not set
      When the test command is invoked with `-- samples.Alpha1`
      Then the `info` message holds `FIXTURE_OUTER` <outer>, `FIXTURE_FORK` `from-forkEnv` and `FIXTURE_BOTH` `from-forkEnv`

      Examples:
        | propagate | outer        |
        | `true`    | `from-outer` |
        | `false`   | `absent`     |

  Rule: A variable the `mill` launcher sets from its own arguments holds the test command's `mill` command line
    Scenario: AC-014 MILL_PARAMS on Windows
      Given the build runs on Windows, where `mill` starts through its `mill.bat` launcher
      And `Alpha1`'s test `fast` sends an `info` message holding the environment variable `MILL_PARAMS`
      When the test command is invoked with `-- samples.Alpha1`
      Then the `info` message is `-j 2 scalatest/test core.test -fJ <report> -- samples.Alpha1`, the arguments `mill` was invoked with
```

```gherkin
Feature: The module folder
  The module folder holds the test command's files for the module, never a JUnit XML report, and nothing an earlier test command left there.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `core.test`, the module, tests with `com.edc::scalatest` at that release, and its definition names nothing of the plugin
    And the module has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault`, `testReportXml`, `forkEnv` and `propagateEnv` unset unless the scenario sets them
    And the module holds the suites `Alpha1`, `Alpha2` and `Beta1`, the classes `samples.Alpha1`, `samples.Alpha2` and `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast`, tagged `samples.Fast`, and `slow`, both of which succeed unless the scenario says otherwise
    And the module holds `samples.Fast`, an object extending `org.scalatest.Tag` with the name `samples.Fast`, which is not a test class
    And the test command is invoked as `mill -j 2 scalatest/test core.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists, and `<arguments>` is what the scenario gives, or nothing
    And the module folder is `out/org.scalatest.mill.TestCommandModule/modules/core.test` in the build's workspace

  Rule: The module folder never holds a JUnit XML report
    Scenario Outline: AC-011 testReportXml <setting>, arguments <arguments>
      Given the module's `testReportXml` is <setting>
      And `Beta1`'s test `slow` fails
      When the test command is invoked with <arguments>
      Then no file in the module folder or in any folder inside it is an XML document whose root element is `testsuites` or `testsuite`

      Examples:
        | setting                     | arguments                 |
        | `Some("test-report.xml")`   | no arguments              |
        | `Some("junit-results.xml")` | `-- samples.Alpha*`       |
        | `Some("test-report.xml")`   | `-- -- -n samples.Fast`   |
        | `Some("test-report.xml")`   | `-- samples.Gamma*`       |
        | `None`                      | `-- * -- -l samples.Fast` |

  Rule: A test command leaves nothing in the module folder from an earlier test command
    Scenario: AC-015 An earlier test command's file, then class globs that match no test class
      Given `Alpha1`'s test `fast` creates the file `leftover.txt` in its working directory when it runs
      And the test command has been invoked with no arguments, and `leftover.txt` exists in a folder inside the module folder
      And the file at `<report>` has since been deleted
      When the test command is invoked with `-- samples.Gamma*`
      Then no file `leftover.txt` exists in the module folder or in any folder inside it

  Rule: The test command leaves the folders of Mill's own test tasks as they were
    Scenario: AC-013 Mill's own report from an earlier testForked
      Given `mill core.test.testForked` has run in the build and written `out/core/test/testForked.dest/test-report.xml`
      When the test command is invoked with `-- samples.Alpha1`
      Then `out/core/test/testForked.dest/test-report.xml` has the content and modification time it had before the test command was invoked
      And no folder `out/core/test/testOnly.dest` exists
```

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-001       | AC-001, AC-006                                 |
| R-002       | AC-006, AC-007, AC-008                         |
| R-003       | AC-001, AC-002, AC-003                         |
| R-004       | AC-006                                         |
| R-005       | AC-007                                         |
| R-006       | AC-004                                         |
| R-007       | AC-005                                         |
| R-008       | AC-009, AC-014                                 |
| R-009       | AC-002, AC-003                                 |
| R-010       | AC-011                                         |
| R-011       | AC-013                                         |
| R-012       | AC-015                                         |
| R-013       | AC-016                                         |
