# Many modules in one test command

Scale: feature.

## Summary

This feature has the test command take several module selectors. One test command runs the tests of every test module they select, with one consolidated output. Each selected module's tests wait for every selected module's dependencies to resolve, but not for any other selected module to compile. Each selected module's tests run whatever happens to the other selected modules.

## Plan

[Plan: many modules in one test command](https://github.com/jamesgorman2/scalatest/issues/240)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — how the test command checks every selected module before any test starts, and runs each module's tests inside its nested evaluation.
- [../../../mill-plugin/consolidated.schema.json](../../../mill-plugin/consolidated.schema.json) — the JSON schema of the JSON output, including the root's `moduleSelectors` and `contents` and a module's `outcome`, `failures` and `output` (`$defs/root`, `$defs/moduleSelector`, `$defs/module` and `$defs/failure`).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Bare `AC-nnn` IDs are this document's. The epic's requirements are cited as `../SPECIFICATION.md` R-nnn. Terms are defined in `.docs/ubiquitous-language.md`, among them *module selector*, *class glob*, *framework argument* and *module folder*.

## Scope

- Several module selectors on one test command, each resolved to the test modules it names, and each test module the module selectors resolve to selected once.
- The module nodes of the consolidated output and the JSON root's `moduleSelectors` when several test modules are selected.
- Running every selected JVM test module's tests in one test command, whether or not Mill's `--keep-going` is given:
  - each module's tests wait for every selected module's dependencies to resolve, but not for any other selected module to compile;
  - each module's tests run whatever happens to the other selected modules.
- Each failure the test command can attribute to one selected test module, shown on that module's node in the JSON and human-readable outputs. Such failures include:
  - the module's class globs matching no test;
  - the module's tests not starting, where the module fails to compile, its dependencies fail to resolve, or another task its tests depend on fails.
- Once the tests have run, the test command's failure naming exactly the selected test modules that failed, unless its JSON output cannot be written.
- Where the test command cannot run a selected test module, its failure also naming each other selected test module whose tests are known, before any test starts, not to start, such as one whose dependencies fail to resolve. The failure gives each such module's own failure.
- What Mill's test discovery writes for a selected test module, shown on that module's node in the JSON and human-readable outputs.
- The class globs and framework arguments applying alike to every selected test module.
- Failing the test command before any test starts where the test command is given no module selector, or a module selector resolves to no test module.
- Failing the test command before any test starts where a module selector resolves to:
  - a test module that does not test with the release of this fork the Mill plugin belongs to;
  - a Scala.js or Native test module;
  - a test module whose `testArgsDefault` holds an argument ScalaTest does not recognise.
- The test command not running where a task Mill must complete before it, such as another goal on the same `mill` command line, fails first.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- `--halt-on-failure` (§ 7).
- Running the tests of Scala.js and Native test modules (§ 11, § 12, § 13).
- A module that presets module selectors (§ 17).
- The warning on an unsupported Mill release (§ 6).
- The consolidated output of a worker that ends before its tests finish (§ 8).
- Progress output (§ 9).
- The test command's behaviour is undefined where another Mill launcher that shares the daemon changes a task that a selected test module's tests depend on while the test command runs.

## Acceptance criteria

```gherkin
Feature: Several module selectors
  The test command takes one or more module selectors.
  Each module node's name is the module's name as Mill writes it in a task name, such as `core.test`.
  Module names are ordered by comparing them with Java's `String.compareTo`.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And each of those modules has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault` and `testReportXml` unset
    And `alpha.test` holds the suites `Alpha1` and `Alpha2`, whose classes are `samples.Alpha1` and `samples.Alpha2`, and `beta.test` holds the suite `Beta1`, whose class is `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast` and `slow`, both of which succeed
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, appends one line to the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 scalatest/test <selectors> -fJ <report>` unless the scenario states its own `mill` command line
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended

  Rule: A module selector is a separate argument before the test command's first `--` that is neither an output argument, such as `-fJ`, nor the path that follows `-fJ`
    Scenario: AC-017 Module selectors on either side of an output argument
      When `mill -j 2 scalatest/test alpha.test -fJ <report> beta.test` is invoked
      Then the JSON output's root holds exactly the module nodes `alpha.test` and `beta.test`, in that order
      And the JSON output's root `moduleSelectors` holds exactly two entries, whose `selector` values are `alpha.test` and `beta.test`, in that order
      And the test command succeeds

  Rule: The test command fails before any test starts where it is given no module selector
    Scenario: AC-018 Only an output argument
      When `mill -j 2 scalatest/test -fJ <report>` is invoked
      Then Mill reports that the test command failed, with a message saying that the test command was given no module selector
      And no file exists in `<markers>`
      And nothing exists at `<report>`
      And Mill's console shows no consolidated output

  Rule: Each test module that at least one module selector resolves to runs its tests once and has one module node
    Scenario: AC-001 Module selectors alpha.test __.test
      When the test command is invoked with the module selectors `alpha.test __.test`
      Then the JSON output's root holds exactly the module nodes `alpha.test` and `beta.test`, in that order
      And each of the files `Alpha1.fast`, `Alpha1.slow`, `Alpha2.fast`, `Alpha2.slow`, `Beta1.fast` and `Beta1.slow` in `<markers>` holds exactly one line
      And the test command succeeds

  Rule: A module selector that resolves to test modules and to modules that are not test modules selects only its test modules
    Scenario: AC-013 A module selector that resolves to test modules and other modules
      Given the build's modules `alpha` and `beta`, which hold `alpha.test` and `beta.test`, are JVM modules that are not test modules
      When the test command is invoked with the module selector `__`
      Then the JSON output's root holds exactly the module nodes `alpha.test` and `beta.test`, in that order
      And the JSON output's root `moduleSelectors` holds exactly one entry, whose `selector` is `__` and whose `modules` holds exactly `alpha.test` and `beta.test`
      And the test command succeeds

  Rule: The JSON root records each module selector as given, in the order given, with each test module it resolved to, ordered by name
    A test module is recorded under every module selector that resolved to it, although it is selected once.

    Scenario: AC-002 Two module selectors that share a test module
      When the test command is invoked with the module selectors `alpha.test __.test`
      Then the JSON output's root `moduleSelectors` holds exactly two entries
      And the first entry's `selector` is `alpha.test` and its `modules` holds exactly `alpha.test`
      And the second entry's `selector` is `__.test` and its `modules` holds exactly `alpha.test` and `beta.test`, in that order

    Scenario: AC-014 A module selector's test modules are ordered by name
      Given the build also has the JVM test modules `B1.test`, `b10.test` and `b2.test`, each testing with `com.edc::scalatest` at the release under test and holding one `AnyFunSuite` whose one test succeeds
      When the test command is invoked with the module selector `__.test`
      Then the JSON output's root `moduleSelectors` holds exactly one entry, whose `selector` is `__.test`
      And that entry's `modules` holds exactly `B1.test`, `alpha.test`, `b10.test`, `b2.test` and `beta.test`, in that order

  Rule: Module nodes are ordered by name, whatever the order of the module selectors
    Scenario: AC-003 Module names that sort by character code
      Given the build also has the JVM test modules `B1.test`, `b10.test` and `b2.test`, each testing with `com.edc::scalatest` at the release under test and holding one `AnyFunSuite` whose one test succeeds
      When the test command is invoked with the module selectors `b2.test beta.test b10.test alpha.test B1.test`
      Then the JSON output's root holds exactly the module nodes `B1.test`, `alpha.test`, `b10.test`, `b2.test` and `beta.test`, in that order

  Rule: Each selected test module's workers run in the folder `sandbox` of that module's own module folder
    A module's module folder is the folder whose name is the module's name, as Mill writes it in a task name, inside the folder `org.scalatest.mill.TestCommandModule/modules` of Mill's output folder.

    Scenario: AC-004 The working directory of each module's tests
      Given `Alpha1`'s test `fast` and `Beta1`'s test `fast` each send an `info` message holding the system property `user.dir`
      When the test command is invoked with the module selectors `alpha.test beta.test`
      Then in the JSON output, the `output` of `Alpha1`'s test `fast` holds an `info` message whose `message` is the path of the folder `sandbox` in `out/org.scalatest.mill.TestCommandModule/modules/alpha.test` in the build's workspace
      And in the JSON output, the `output` of `Beta1`'s test `fast` holds an `info` message whose `message` is the path of the folder `sandbox` in `out/org.scalatest.mill.TestCommandModule/modules/beta.test` in the build's workspace
```

```gherkin
Feature: Every selected module runs, whatever happens to the others
  The test command runs the tests of every selected test module, whether or not Mill's `--keep-going` is given.
  The test command runs the tests of every selected test module where another selected test module's tests cannot start, where another selected test module has a test that fails, and where another selected test module fails the test command in any other way.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And `alpha.test` holds the suites `Alpha1` and `Alpha2`, whose classes are `samples.Alpha1` and `samples.Alpha2`, and `beta.test` holds the suite `Beta1`, whose class is `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast` and `slow`, both of which succeed unless the scenario says otherwise
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And each test, once it has failed, also creates the file `<suite>.<test>.failed` in `<markers>`
    And the test command is invoked as `mill -j 2 <flags> scalatest/test <selectors> -fJ <report>` unless the scenario states its own `mill` command line
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended

  Rule: A failing test in one module fails the test command and does not stop another module's tests
    In AC-005, `beta.test` compiles only after `Alpha1`'s test `slow` has failed, so that `Beta1`'s tests start after that failure.
    A failing test fails no Mill task, so both rows take the same path through the test command. Together they show that another module's tests still start after a test has failed, whether or not Mill's `--keep-going` is given.

    Scenario Outline: AC-005 A test of alpha.test fails, flags <flags>
      Given `Alpha1`'s test `slow` fails
      And `beta.test`'s compile depends on a task that ends once the file `Alpha1.slow.failed` exists in `<markers>`, and fails if that file does not exist within 600 seconds
      When the test command is invoked with the flags <flags> and the module selectors `alpha.test beta.test`
      Then the files `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the test command fails

      Examples:
        | flags          |
        | none           |
        | `--keep-going` |

  Rule: A module whose tests cannot start has an aborted module node with one failure that names what failed, fails the test command, and does not stop another module's tests
    A module's tests cannot start where the module fails to compile, a dependency of the module fails to resolve, or any other task the module's tests depend on fails.
    Where a task the module's tests depend on fails, the module's failure names each task that failed, as Mill writes that task's name, with that task's error.
    Where a dependency of the module fails to resolve, the module's failure holds the resolution's error, which names each dependency that failed to resolve.
    The test command starts no test of a module whose tests cannot start.
    A failing task of a selected test module does not stop the test command from running.

    Scenario: AC-006 broken.test does not compile, flags --keep-going
      Given the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and whose sources do not compile
      When the test command is invoked with the flags `--keep-going` and the module selectors `alpha.test broken.test beta.test`
      Then the files `Alpha1.fast`, `Alpha1.slow`, `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the JSON output's root holds exactly the module nodes `alpha.test`, `beta.test` and `broken.test`, in that order
      And the module node `broken.test` holds no run node, its `outcome` is `aborted`, and its `failures` holds exactly one entry, whose `message` contains `broken.test.compile`
      And the module nodes `alpha.test` and `beta.test` have no `outcome` and no `failures`
      And the JSON output's root has no `outcome` and no `failures`
      And the test command fails

    Scenario Outline: AC-015 broken.test's tests cannot start: <cause>
      Given the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and <cause>
      When the test command is invoked with no flags and the module selectors `alpha.test broken.test beta.test`
      Then the files `Alpha1.fast`, `Alpha1.slow`, `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the JSON output's root holds exactly the module nodes `alpha.test`, `beta.test` and `broken.test`, in that order
      And the module node `broken.test` holds no run node, its `outcome` is `aborted`, and its `failures` holds exactly one entry, whose `message` contains <named>
      And the module nodes `alpha.test` and `beta.test` have no `outcome` and no `failures`
      And the JSON output's root has no `outcome` and no `failures`
      And the test command fails

      Examples:
        | cause                                                                                       | named                          |
        | whose `mvnDeps` holds `com.example::absent:0.0.1`, which no repository the build uses holds | `com.example:absent_3:0.0.1`   |
        | whose `generatedSources` task throws an exception                                           | `broken.test.generatedSources` |

    Scenario: AC-023 broken.test's failure in the human-readable output
      Given the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and whose sources do not compile
      When `mill -j 2 scalatest/test alpha.test broken.test beta.test -oH` is invoked
      Then in the human-readable output on stdout, the line after the line `[broken.test]` is `*** MODULE ABORTED ***`, in red and indented one level more than `[broken.test]`
      And each line between `*** MODULE ABORTED ***` and the next line indented no more than `*** MODULE ABORTED ***` is in red and indented one level more than `*** MODULE ABORTED ***`
      And there is at least one such line, and one of them contains `broken.test.compile`
      And the test command fails

  Rule: Where two of a module's fork groups each run only the same test class, the module runs no test, has an aborted module node, fails the test command, and does not stop another module's tests
    Scenario: AC-024 Two of beta.test's fork groups each hold only samples.Beta1
      Given `beta.test`'s `testForkGrouping` gives two fork groups, each holding only `samples.Beta1`
      When the test command is invoked with no flags and the module selectors `alpha.test beta.test`
      Then the files `Alpha1.fast` and `Alpha1.slow` exist in `<markers>`, and neither `Beta1.fast` nor `Beta1.slow` does
      And the module node `beta.test` holds no run node, its `outcome` is `aborted`, and its `failures` holds exactly one entry, whose `message` contains `samples.Beta1` and does not contain `beta.test`
      And the module node `beta.test` has no `startedAt`, `endedAt` or `duration`
      And Mill started no worker in `beta.test`'s module folder
      And the module node `alpha.test` has no `outcome` and no `failures`
      And the JSON output's root has no `outcome` and no `failures`
      And the test command fails

  Rule: A module's tests start once that module and each module it depends on have compiled, without waiting for another selected test module to compile
    Scenario: AC-007 late.test compiles only after a test of alpha.test has run
      Given the build also has the JVM test module `late.test`, which tests with `com.edc::scalatest` at the release under test and holds the suite `Late1`, whose class is `samples.Late1`
      And `Late1` is an `AnyFunSuite` whose test `fast` creates the file `Late1.fast` in `<markers>`
      And `late.test`'s compile depends on a task that ends once the file `Alpha1.fast` exists in `<markers>`, and fails if that file does not exist within 600 seconds
      When the test command is invoked with no flags and the module selectors `alpha.test late.test`
      Then the files `Alpha1.fast` and `Late1.fast` exist in `<markers>`
      And the test command succeeds

  Rule: Where the test command fails once its tests have run and its JSON output is written, its failure names exactly the selected modules that failed
    A selected module has failed where its module node, or any node under it, fails the test command (`../SPECIFICATION.md` R-137), such as an aborted module node, a run whose outcome is not completed, a failed test, or an aborted suite.

    Scenario: AC-027 A test of alpha.test fails and broken.test does not compile
      Given `Alpha1`'s test `slow` fails
      And the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and whose sources do not compile
      When the test command is invoked with no flags and the module selectors `alpha.test broken.test beta.test`
      Then Mill reports that the test command failed, with the message `The tests of alpha.test and broken.test failed`
```

```gherkin
Feature: Class globs and framework arguments across modules
  The test command's class globs and framework arguments apply alike to every selected test module.
  Where the test command is given class globs, only the test classes of each selected test module whose fully qualified name matches at least one class glob run.
  Where the test command is given no class glob, every test class of each selected test module runs.
  Every run receives the test command's framework arguments.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And `alpha.test` holds the suites `Alpha1` and `Alpha2`, whose classes are `samples.Alpha1` and `samples.Alpha2`, and `beta.test` holds the suite `Beta1`, whose class is `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast` and `slow`, both of which succeed, and only `fast` is tagged `samples.Fast`
    And each of those modules holds the object `samples.Fast`, which extends `org.scalatest.Tag` with the name `samples.Fast` and is not a test class
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 scalatest/test alpha.test beta.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended

  Rule: Class globs and framework arguments reach every selected module
    Scenario Outline: AC-008 Arguments <arguments>
      When the test command is invoked with `<arguments>`
      Then the files in `<markers>` are exactly <files>
      And the test command succeeds

      Examples:
        | arguments                                  | files                                                                  |
        | `-- samples.Alpha1 samples.Beta1`          | `Alpha1.fast`, `Alpha1.slow`, `Beta1.fast` and `Beta1.slow`            |
        | `-- -- -n samples.Fast`                    | `Alpha1.fast`, `Alpha2.fast` and `Beta1.fast`                          |
        | `-- *1 -- -z slow`                         | `Alpha1.slow` and `Beta1.slow`                                         |

  Rule: Where no class glob matches a test class that a selected module's test framework discovers, no test of that module starts, that module's node is aborted with the failure Mill's `testOnly` fails with, the test command fails, and every other module's tests still run
    Scenario: AC-009 Class globs that match only alpha.test's classes
      When the test command is invoked with `-- samples.Alpha*`
      Then the files in `<markers>` are exactly `Alpha1.fast`, `Alpha1.slow`, `Alpha2.fast` and `Alpha2.slow`
      And in the JSON output, the module node `beta.test` holds no run node, its `outcome` is `aborted`, and its `failures` holds exactly one entry
      And that entry's `message` is `Test selector does not match any test: samples.Alpha*` and `Run discoveredTestClasses to see available tests`, joined by a line feed
      And in the JSON output, the module node `alpha.test` has no `outcome` and no `failures`
      And the JSON output's root has no `outcome` and no `failures`
      And the test command fails

  Rule: Where class globs match a selected module's test classes but no test of that module reports a result, that module's node holds its runs and is aborted with the failure Mill's `testOnly` fails with, and the test command fails
    Scenario: AC-022 Framework arguments that exclude every test the class globs keep
      When the test command is invoked with `-- *1 -- -z nothing-matches`
      Then no file exists in `<markers>`
      And in the JSON output, the module nodes `alpha.test` and `beta.test` each hold one or more run nodes, and each has the `outcome` `aborted` and a `failures` that holds exactly one entry
      And each of those entries' `message` is `Test selector does not match any test: *1` and `Run discoveredTestClasses to see available tests`, joined by a line feed
      And the JSON output's root has no `outcome` and no `failures`
      And the test command fails

  Rule: What Mill's test discovery writes for a selected module is that module's output, shown after the module's runs and before the module's failures
    Scenario Outline: AC-025 beta.test's discovery output in the JSON output, with javaHome <java home>
      Given `beta.test` also holds the class `samples.Noisy`, a public Java class that extends `org.scalatest.funsuite.AnyFunSuite` and holds no test
      And `samples.Noisy`'s only public constructor takes no argument
      And `samples.Noisy`'s static initializer writes the line `loading samples.Noisy` to stdout
      And `beta.test`'s `javaHome` is <java home>
      When the test command is invoked with `-- samples.* -- -z nothing-matches`
      Then in the JSON output, the module node `beta.test` has an `output` that holds exactly one item, a `stdio` item whose `stream` is `stdout` and whose `text` is `loading samples.Noisy` followed by a line terminator
      And in the JSON output, the module node `alpha.test` has no `output`
      And the JSON output's root has no `output`

      Examples:
        | java home                                    |
        | left unset                                   |
        | set to the path of a JDK of Java 17 or later |

    Scenario: AC-026 beta.test's runs, output and failure in the human-readable output
      Given `beta.test` also holds the class `samples.Noisy`, a public Java class that extends `org.scalatest.funsuite.AnyFunSuite` and holds no test
      And `samples.Noisy`'s only public constructor takes no argument
      And `samples.Noisy`'s static initializer writes the line `loading samples.Noisy` to stdout
      When the test command is invoked with `-oH -- samples.* -- -z nothing-matches`
      Then in the human-readable output on stdout, the lines that follow `[beta.test]` and are indented more than it end with these lines, in this order:
        | line                                               | indented more than `[beta.test]` by | colour |
        | `[stdout] loading samples.Noisy`                   | one level                           | any    |
        | `*** MODULE ABORTED ***`                           | one level                           | red    |
        | `Test selector does not match any test: samples.*` | two levels                          | red    |
        | `Run discoveredTestClasses to see available tests` | two levels                          | red    |
      And among the lines that follow `[beta.test]` and are indented more than it, a line that begins `Run ` and is indented one level more than `[beta.test]` comes before those four lines
      And the test command fails
```

```gherkin
Feature: Modules the test command cannot run
  The test command cannot run a test module that does not test with the release of this fork the Mill plugin belongs to, a Scala.js test module, or a Native test module.
  A test module tests with the release of this fork the Mill plugin belongs to where the dependencies Mill resolves for that test module hold `com.edc::scalatest-core` at that release, whether the test module names that dependency itself or gets it through another module or library it depends on.
  Where the test command fails before any test of any selected test module starts, nothing exists at the path that `-fJ` names once the test command has ended.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `alpha.test` tests with `com.edc::scalatest` at that release and holds the suite `Alpha1`, an `AnyFunSuite` whose test `fast` succeeds
    And the build also has these test modules, each with one test that succeeds:
      | module          | runtime  | tests with                                                                                                  |
      | `munit.test`    | JVM      | MUnit                                                                                                       |
      | `utest.test`    | JVM      | uTest                                                                                                       |
      | `upstream.test` | JVM      | `org.scalatest::scalatest` 3.2.20                                                                           |
      | `other.test`    | JVM      | `com.edc::scalatest` at a release other than the release under test, which a repository the build uses holds |
      | `js.test`       | Scala.js | `com.edc::scalatest` at the release under test, in `NodeJs`                                                 |
      | `native.test`   | Native   | `com.edc::scalatest` at the release under test                                                              |
    And the build also has the JVM test module `unresolved.test`, which tests with `com.edc::scalatest` at the release under test, holds one test that succeeds, and whose `mvnDeps` also holds `com.example::absent:0.0.1`, which no repository the build uses holds
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates a file in `<markers>`
    And `<report>` is an absolute path at which a file exists that an earlier test command wrote
    And the test command is invoked as `mill -j 2 scalatest/test <selectors> -fJ <report>`, each argument reaching `mill` exactly as written, with no shell expansion

  Rule: A test module the test command cannot run fails the test command before any module's tests start
    Scenario Outline: AC-010 Module selectors alpha.test and <module>
      When the test command is invoked with the module selectors `alpha.test <module>`
      Then Mill reports that the test command failed, with a message naming `<module>`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
      And Mill's console shows no consolidated output

      Examples:
        | module          |
        | `munit.test`    |
        | `utest.test`    |
        | `upstream.test` |
        | `other.test`    |
        | `js.test`       |
        | `native.test`   |

  Rule: The failure names every test module the test command cannot run, and every other selected test module whose tests are known not to start, with that module's failure
    Before any test starts, a module's tests are known not to start where its dependencies fail to resolve, or where its `testArgsDefault` or a task its dependency resolution depends on fails.
    The failure gives such a module's failure as its module node would hold it, as in AC-015.

    Scenario: AC-011 A module selector that resolves to every test module
      When the test command is invoked with the module selector `__.test`
      Then Mill reports that the test command failed, with a message naming each of `munit.test`, `utest.test`, `upstream.test`, `other.test`, `js.test` and `native.test`
      And that message names `unresolved.test` as a module whose tests cannot start, with a failure that contains `com.example:absent_3:0.0.1`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
      And Mill's console shows no consolidated output

  Rule: A test module that gets the release of this fork the Mill plugin belongs to only through a module it depends on is a test module the test command runs
    Scenario: AC-020 A test module that tests with the release under test through a module it depends on
      Given the build also has the JVM module `shared`, which is not a test module and whose `mvnDeps` holds `com.edc::scalatest` at the release under test
      And the build also has the JVM test module `indirect.test`, whose `moduleDeps` holds `shared`, whose own `mvnDeps` holds nothing of this fork, and which holds one test that succeeds
      When the test command is invoked with the module selectors `alpha.test indirect.test`
      Then exactly two files exist in `<markers>`
      And the file at `<report>` holds JSON output whose root holds exactly the module nodes `alpha.test` and `indirect.test`, in that order
      And the test command succeeds

  Rule: A selected test module whose `testArgsDefault` holds an argument ScalaTest does not recognise fails the test command, naming that argument, before any module's tests start
    Scenario: AC-019 A test module whose testArgsDefault holds an unrecognised argument
      Given the build also has the JVM test module `args.test`, which tests with `com.edc::scalatest` at the release under test, holds one test that succeeds, and whose `testArgsDefault` is `--not-a-scalatest-argument`
      When the test command is invoked with the module selectors `alpha.test args.test`
      Then Mill reports that the test command failed, with a message naming `--not-a-scalatest-argument`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
      And Mill's console shows no consolidated output

  Rule: A module selector that resolves to no test module fails the test command, naming that module selector, before any module's tests start
    Scenario: AC-016 Module selectors alpha.test and util
      Given the build also has the JVM module `util`, which is not a test module and holds no module
      When the test command is invoked with the module selectors `alpha.test util`
      Then Mill reports that the test command failed, with a message naming the module selector `util`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
      And Mill's console shows no consolidated output
```

```gherkin
Feature: Another goal fails first
  Where a task that Mill must complete before it starts the test command fails, the test command starts no test, writes no consolidated output, and creates, changes and deletes nothing at the path that `-fJ` names.
  A goal before the test command on the same `mill` command line is a task that Mill must complete before it starts the test command only where Mill's `--keep-going` is not given.
  No task of a selected test module, such as its compile or its dependency resolution, is a task that Mill must complete before it starts the test command.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `alpha.test` tests with `com.edc::scalatest` at that release and holds the suite `Alpha1`, an `AnyFunSuite` whose test `fast` creates a file in `<markers>` when it runs
    And the build's module `broken` does not compile
    And `<markers>` is an absolute path at which an empty folder exists
    And `<report>` is an absolute path at which a file exists that an earlier test command wrote
    And each argument of a `mill` command line reaches `mill` exactly as written, with no shell expansion

  Rule: Where Mill's `--keep-going` is not given, the test command does not run after an earlier goal on the same `mill` command line fails
    Scenario: AC-012 broken.compile before the test command
      When `mill -j 2 broken.compile + scalatest/test alpha.test -fJ <report>` is invoked
      Then Mill reports that `broken.compile` failed
      And no file exists in `<markers>`
      And the file at `<report>` has the content and modification time it had before `mill` was invoked
      And Mill's console shows no consolidated output

  Rule: Where Mill's `--keep-going` is given, the test command runs after an earlier goal on the same `mill` command line fails
    Scenario: AC-021 broken.compile before the test command, with --keep-going
      When `mill -j 2 --keep-going broken.compile + scalatest/test alpha.test -fJ <report>` is invoked
      Then Mill reports that `broken.compile` failed
      And a file exists in `<markers>`
      And the file at `<report>` holds JSON output whose root holds exactly the module node `alpha.test`
```

Each epic requirement this feature enables, with the criteria that test it:

| Epic requirement             | Criteria                                               |
|------------------------------|--------------------------------------------------------|
| `../SPECIFICATION.md` R-028  | AC-002, AC-013, AC-014, AC-017                         |
| `../SPECIFICATION.md` R-046c | AC-006, AC-015, AC-023, AC-024                         |
| `../SPECIFICATION.md` R-053a | AC-008, AC-009, AC-022                                 |
| `../SPECIFICATION.md` R-053b | AC-008                                                 |
| `../SPECIFICATION.md` R-055  | AC-001, AC-003, AC-008, AC-017, AC-018                 |
| `../SPECIFICATION.md` R-056a | AC-005, AC-006, AC-009, AC-015, AC-024                 |
| `../SPECIFICATION.md` R-056b | AC-007                                                 |
| `../SPECIFICATION.md` R-059  | AC-010, AC-011, AC-020                                 |
| `../SPECIFICATION.md` R-061  | AC-006, AC-015                                         |
| `../SPECIFICATION.md` R-062  | AC-012, AC-021                                         |
| `../SPECIFICATION.md` R-072b | AC-006, AC-009, AC-015, AC-022, AC-024                 |
| `../SPECIFICATION.md` R-077  | AC-010, AC-011, AC-016, AC-019                         |
| `../SPECIFICATION.md` R-096a | AC-001, AC-002, AC-003, AC-006, AC-013                 |
| `../SPECIFICATION.md` R-097b | AC-025, AC-026                                         |
| `../SPECIFICATION.md` R-098e | AC-006, AC-009, AC-015, AC-022, AC-024                 |
| `../SPECIFICATION.md` R-105  | AC-001, AC-003, AC-006                                 |
| `../SPECIFICATION.md` R-106d | AC-023, AC-026                                         |
| `../SPECIFICATION.md` R-106e | AC-026                                                 |
| `../SPECIFICATION.md` R-110  | AC-004                                                 |
| `../SPECIFICATION.md` R-137  | AC-005, AC-006, AC-009, AC-015, AC-022, AC-024, AC-027 |
| `../SPECIFICATION.md` R-142  | AC-019                                                 |
