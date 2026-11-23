# Class globs and framework arguments

Scale: feature.

## Summary

The test command takes no class globs or framework arguments, so it can only run a module as Mill's `testForked` runs it with no arguments. This feature has the test command take class globs and framework arguments as Mill 1.3.1's `testOnly` takes them, and run the module with them as `testOnly` or `testForked` would. One exception: a framework `-P`, `-F`, `-T`, `-S` or `-W` replaces the module's own arguments with the same flag. Each worker receives the environment that Mill's task would give it, including the variables the `mill` launcher sets. The module folder, the folder the test command owns for the module, holds the files Mill's task would write in its task folder, except the JUnit XML report, which the test command never writes.

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
- Running the module as Mill 1.3.1's `testOnly` runs it where class globs are given, including where the class globs select no test, and as Mill 1.3.1's `testForked` runs it where no class glob is given. R-005, not Mill, gives the arguments each run receives.
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
- R-002 The test command accepts its framework arguments only where each framework argument has one of these forms:
  - `-D` with a config map entry joined to it, such as `-Dcolour=blue`;
  - `-P` with a thread count of 1 or more joined to it, such as `-P4`;
  - one of the ScalaTest flags `-n`, `-l`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, followed by the separate values ScalaTest's runner takes after that flag;
  - one of the separate values that ScalaTest's runner takes after the flag before it, such as `samples.Fast` after `-n`.

  Where any framework argument has none of these forms, the test command fails before any test starts with a message naming that argument, and writes no consolidated output (`../SPECIFICATION.md` R-125a, R-125b). Such framework arguments include the ScalaTest flags `-oD` and `-s`, the unknown flag `--unknown`, `-n` with no value after it, `-D` with no config map entry joined to it, `-P` with no thread count joined to it, and `foo`.
- R-013 The test command fails before any test starts, with a message naming the argument, and writes no consolidated output, where any of these arguments is one of the ScalaTest flags `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, alone or with its value joined to it, such as `-n` or `-Dcolour=blue` (`../SPECIFICATION.md` R-125b):
  - an argument before the test command's first `--`;
  - any argument, where the test command's arguments hold no `--`.

### Running the module

- R-003 Where at least one class glob is given, the test command runs the module's tests as `mill <module>.testOnly <class globs> -- <framework arguments>` runs them, except that each run receives the arguments R-005 gives. Only the test classes whose fully qualified name matches at least one class glob run. A class glob without `*` matches only the name equal to it, and each `*` in a class glob matches any run of characters, including none (`../SPECIFICATION.md` R-053a, R-055).
- R-004 Where no class glob is given, the test command runs the module's tests as `mill <module>.testForked <framework arguments>` runs them, except that each run receives the arguments R-005 gives (`../SPECIFICATION.md` R-053b).
- R-005 Each run of the module receives, first, each of the module's `testArgsDefault` arguments that is one of the ScalaTest flags `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, with the values ScalaTest's runner takes with that flag, whether joined to it or after it. The run then receives the test command's framework arguments, in the order given. Where ScalaTest takes the later of two arguments that set the same setting, such as two `-D` arguments for one config map key, the framework argument takes effect over the `testArgsDefault` argument, as it does under Mill's `testOnly` and `testForked`. Where a framework argument is one of the ScalaTest flags `-P`, `-F`, `-T`, `-S` and `-W`, whose setting ScalaTest's runner takes from one argument only, the run receives none of the module's `testArgsDefault` arguments with that flag, so that the framework argument takes effect (`../SPECIFICATION.md` R-053a, R-053b, R-142).
- R-006 Where class globs are given and none of them matches a test class that the module's test framework discovers, the test command starts no test of the module and fails. The test command then attaches one failure to the root of the consolidated output. The failure's message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-007 The test command fails where all of these hold:
  - class globs are given, and at least one of them matches a test class that the module's test framework discovers;
  - every worker of the module has ended;
  - no fork group of the module failed to lay out and no worker of it failed to start or run, so the root holds no failure such as `Laying out fork group 1 of core.test failed: …` or `Starting worker 0 of fork group 1 of core.test failed: …`;
  - no worker's JVM of the module ended without writing its result, as where the JVM died;
  - no run of the module has reported, in its event stream, a test as succeeded, failed, ignored, pending or cancelled, or a suite as aborted.

  These do not count as such a report:
  - a test that the consolidated output shows as ignored only because its suite's structure marks it ignored;
  - a suite or test that the consolidated output shows as aborted only because it has no end event, such as one with reason *not run* (`../SPECIFICATION.md` R-095a, R-095b).

  The test command then attaches one failure to the root of the consolidated output. The failure's message is the one Mill's `testOnly` fails with: `Test selector does not match any test: <class globs>`, with the class globs separated by single spaces, followed by the line `Run discoveredTestClasses to see available tests` (`../SPECIFICATION.md` R-053a, R-098f, R-137).
- R-008 Each worker of the module receives the environment that a worker of Mill's `testOnly` or `testForked` receives when that task is started from a `mill` command line in the environment in which the test command's `mill` command line started:
  - where the module's `propagateEnv` is on, the environment in which `mill` started, overlaid by the module's `allForkEnv`;
  - where the module's `propagateEnv` is off, the module's `allForkEnv` alone.

  Unless the module overrides `allForkEnv`, the module's `allForkEnv` holds the module's `forkEnv` and these variables, which Mill 1.3.1 adds to it:
  - `PATH`: the `bin` folder of the module's Java home, followed by the `PATH` in which `mill` started;
  - `MILL_WORKSPACE_ROOT`: the build's workspace folder;
  - `MILL_TEST_RESOURCE_DIR`: the module's resource folders, separated by `;`.

  Where the module's `propagateEnv` is on, each worker receives the variables that the `mill` launcher set for the `mill` command line that started the test command, not those of the command that started a running Mill daemon. For example, on Windows `mill.bat` sets `MILL_PARAMS` to that command line's arguments, omitting a leading launcher option such as `--no-daemon` (`../SPECIFICATION.md` R-053a, R-053b).

### The module folder

- R-009 The module folder is `org.scalatest.mill.TestCommandModule/modules/<module>` in Mill's output folder, where `<module>` is the module's name as Mill writes it in a task name: `core.test`, or `core.3_3_8.test` for the `test` module of the cross module `core` at `3.3.8`. A module selector can also name that module `core[3.3.8].test`. For the build's default output folder, the module `core.test` has the module folder `out/org.scalatest.mill.TestCommandModule/modules/core.test`. Each file and folder that Mill's `testOnly` or `testForked` would write in its task folder, other than its JUnit XML report, is written at the same relative path in the module folder. Each worker's working directory `sandbox` is one such folder (`../SPECIFICATION.md` R-110).
- R-012 Once a test command has resolved the module from its module selector, it deletes every file and folder that an earlier test command left in the module folder. It deletes them before it starts any test of the module, and also where it starts none, as where no class glob matches a test class (R-006). A test command that fails on its arguments (R-002, R-013), or otherwise before it has resolved the module, creates, changes and deletes nothing in the module folder (`../SPECIFICATION.md` R-110).
- R-010 Once a test command has ended, whether it succeeded or failed, the module folder and every folder inside it hold no JUnit XML report, whatever the module's `testReportXml` is and whatever class globs and framework arguments are given (`../SPECIFICATION.md` R-047, R-110).
- R-011 The test command creates neither of the module's `testForked` and `testOnly` task folders, such as `out/core/test/testForked.dest`, and creates, changes and deletes nothing in either where it exists (`../SPECIFICATION.md` R-060, R-110).

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

  Rule: Only the workers that the matched test classes need are started
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

  Rule: A suite that aborts counts as a test result
    Scenario: AC-017 The only matched suite aborts before its tests run
      Given the module also holds the suite `Broken1`, the class `samples.Broken1`, an `AnyFunSuite` mixing in `BeforeAndAfterAll` with one test `fast`, whose `beforeAll` throws an exception
      When the test command is invoked with `-- samples.Broken1`
      Then the JSON output holds the suite `Broken1` with the outcome `aborted` and a `message`
      And the JSON output's root holds no failure whose message begins `Test selector does not match any test`
      And the test command fails

  Rule: A worker's JVM that dies is not reported as class globs that match no test
    Scenario: AC-022 The JVM running the only matched suite dies before its tests run
      Given the module also holds the suite `Halting1`, the class `samples.Halting1`, an `AnyFunSuite` mixing in `BeforeAndAfterAll` with one test `fast`, whose `beforeAll` halts the JVM
      When the test command is invoked with `-- samples.Halting1`
      Then the JSON output's root holds no failure whose message begins `Test selector does not match any test`
      And the test command fails
```

```gherkin
Feature: Framework arguments
  After a second `--`, the test command's arguments are framework arguments, which every run of the module receives after the module's own `testArgsDefault`. A framework `-P`, `-F`, `-T`, `-S` or `-W` replaces the module's arguments that have the same flag.

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

  Rule: A framework argument takes effect over the module's own argument for the same setting
    Scenario Outline: AC-007 A config map key given by both, testBatchFrameworkTasks <batch>
      Given the module's `testArgsDefault` is `Seq("-Dcolour=red")` and its `testBatchFrameworkTasks` is <batch>
      And `Alpha1`'s test `fast` sends an `info` message holding the config map's value for `colour`
      When the test command is invoked with `-- samples.Alpha1 -- -Dcolour=blue`
      Then the `info` message is `blue`
      And the test command succeeds

      Examples:
        | batch   |
        | `false` |
        | `true`  |

    Scenario: AC-018 A span scale factor given by both
      Given the module's `testArgsDefault` is `Seq("-F", "2")`
      And `Alpha1`'s test `fast` sends an `info` message holding the span scale factor
      When the test command is invoked with `-- samples.Alpha1 -- -F 3`
      Then the `info` message is `3.0`
      And the test command succeeds

  Rule: The module's own argument takes effect where no framework argument sets the same setting
    Scenario: AC-021 A tag filter given by the module alone, beside a span scale factor given by a framework argument
      Given the module's `testArgsDefault` is `Seq("-l", "samples.Fast")`
      When the test command is invoked with `-- -- -F 3`
      Then the suites run are exactly `Alpha1`, `Alpha2` and `Beta1`
      And in each of those suites, the tests run are exactly `slow`
      And the test command succeeds

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
        | `-P`                | `-P`        |
        | `foo`               | `foo`       |

  Rule: A ScalaTest argument before the first `--` fails the test command
    Scenario Outline: AC-016 A tag filter among the test command's own arguments, arguments <arguments>
      When the test command is invoked with `<arguments>`
      Then Mill reports that the test command failed, with a message naming `-n`
      And no file exists in `<markers>`
      And nothing exists at `<report>`

      Examples:
        | arguments                           |
        | `-n samples.Fast -- samples.Alpha1` |
        | `-n samples.Fast`                   |
```

```gherkin
Feature: The environment of each worker
  Each worker receives the environment that Mill's `testOnly` or `testForked` would give its workers if that task were started from a `mill` command line in the same environment.

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
      And `Alpha1`'s test `fast` sends an `info` message holding the environment variables `FIXTURE_OUTER`, `FIXTURE_FORK`, `FIXTURE_BOTH` and `MILL_WORKSPACE_ROOT`, each `absent` where it is not set
      When the test command is invoked with `-- samples.Alpha1`
      Then the `info` message holds `FIXTURE_OUTER` <outer>, `FIXTURE_FORK` `from-forkEnv`, `FIXTURE_BOTH` `from-forkEnv` and `MILL_WORKSPACE_ROOT` the absolute path of the build's workspace

      Examples:
        | propagate | outer        |
        | `true`    | `from-outer` |
        | `false`   | `absent`     |

  Rule: Where propagateEnv is on, each worker receives the variables the `mill` launcher set for the test command's `mill` command line
    Scenario: AC-014 MILL_PARAMS on Windows
      Given the build runs on Windows, where `mill` starts through its `mill.bat` launcher
      And the module's `propagateEnv` is `true`
      And `Alpha1`'s test `fast` sends an `info` message holding the environment variable `MILL_PARAMS`
      When the test command is invoked with `-- samples.Alpha1`
      Then the `info` message is `-j 2 scalatest/test core.test -fJ <report> -- samples.Alpha1`, the arguments `mill` was invoked with
```

```gherkin
Feature: The module folder
  The module folder holds the test command's files for the module and never a JUnit XML report. Once a test command has resolved the module, the module folder holds nothing an earlier test command left there.

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

  Rule: A test command that resolves the module leaves nothing in the module folder from an earlier test command
    Scenario: AC-015 An earlier test command's file, then class globs that match no test class
      Given `Alpha1`'s test `fast` creates the file `leftover.txt` in its working directory when it runs
      And the test command has been invoked with no arguments, and `leftover.txt` exists in a folder inside the module folder
      And the file at `<report>` has since been deleted
      When the test command is invoked with `-- samples.Gamma*`
      Then no file `leftover.txt` exists in the module folder or in any folder inside it

  Rule: A test command that fails on its arguments leaves the module folder as it was
    Scenario: AC-019 An earlier test command's file, then a framework argument the test command does not accept
      Given `Alpha1`'s test `fast` creates the file `leftover.txt` in its working directory when it runs
      And the test command has been invoked with no arguments, and `leftover.txt` exists in a folder inside the module folder
      And the file at `<report>` has since been deleted
      When the test command is invoked with `-- -- foo`
      Then the test command fails with a message naming `foo`
      And the module folder and every folder inside it hold the files they held before the test command was invoked, `leftover.txt` among them, each with the content it had

  Rule: The test command leaves the folders of Mill's own test tasks as they were
    Scenario: AC-013 Mill's own report from an earlier testForked
      Given `mill core.test.testForked` has run in the build and written `out/core/test/testForked.dest/test-report.xml`
      And no folder `out/core/test/testOnly.dest` exists
      When the test command is invoked with `-- samples.Alpha1`
      Then `out/core/test/testForked.dest/test-report.xml` has the content and modification time it had before the test command was invoked
      And no folder `out/core/test/testOnly.dest` exists

  Rule: The module folder is named as Mill writes the module in a task name
    Scenario: AC-020 The test module of a cross module
      Given the module is instead the `test` module of the cross module `core` at `3.3.8`, which Mill names `core.3_3_8.test` in a task name
      And `Alpha1`'s test `fast` creates the file `working-directory.txt` in its working directory when it runs
      When the test command is invoked as `mill -j 2 scalatest/test core[3.3.8].test -- samples.Alpha1`
      Then the test command succeeds
      And `out/org.scalatest.mill.TestCommandModule/modules` holds one folder, `core.3_3_8.test`
      And `working-directory.txt` exists in the folder `sandbox` of that folder
```

| Requirement | Criteria                                       |
|-------------|------------------------------------------------|
| R-001       | AC-001, AC-006                                 |
| R-002       | AC-006, AC-007, AC-008, AC-018, AC-021         |
| R-003       | AC-001, AC-002, AC-003                         |
| R-004       | AC-006, AC-021                                 |
| R-005       | AC-007, AC-018, AC-021                         |
| R-006       | AC-004                                         |
| R-007       | AC-005, AC-017, AC-022                         |
| R-008       | AC-009, AC-014                                 |
| R-009       | AC-002, AC-003, AC-020                         |
| R-010       | AC-011                                         |
| R-011       | AC-013                                         |
| R-012       | AC-015, AC-019                                 |
| R-013       | AC-016                                         |
