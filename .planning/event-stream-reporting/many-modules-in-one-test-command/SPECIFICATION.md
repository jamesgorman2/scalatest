# Many modules in one test command

Scale: feature.

## Summary

The test command takes exactly one module selector, which must name one JVM test module, so a build with several test modules needs one test command per module and gets a separate consolidated output from each. This feature has the test command take several module selectors and run the tests of every test module they select in one test command, with one consolidated output. Each selected module's tests start once that module compiles, and run whatever happens to the other selected modules.

## Plan

[Plan: many modules in one test command](https://github.com/jamesgorman2/scalatest/issues/240)

## Spec documents

- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — how each selected module's tests run inside the test command's nested evaluation.
- [../../../mill-plugin/consolidated.schema.json](../../../mill-plugin/consolidated.schema.json) — the JSON schema of the JSON output, including the root's `moduleSelectors` and `contents` (`$defs/root`, `$defs/moduleSelector` and `$defs/module`).
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.

Bare `AC-nnn` IDs are this document's. The epic's requirements are cited as `../SPECIFICATION.md` R-nnn. Terms are defined in `.docs/ubiquitous-language.md`, among them *module selector*, *class glob*, *framework argument* and *module folder*.

## Scope

- Several module selectors on one test command, each resolved to the test modules it names, and each test module the module selectors name selected once.
- The module nodes of the consolidated output and the JSON root's `moduleSelectors` when several test modules are selected.
- Running every selected JVM test module's tests in one test command: each module's tests start once that module compiles, and run whatever happens to the other selected modules, whether or not Mill's `--keep-going` is given.
- A selected test module whose tests cannot start, as where it fails to compile, its dependencies fail to resolve, or another task its tests depend on fails.
- The class globs and framework arguments applying alike to every selected test module.
- Failing the test command before any test starts where a module selector resolves to no test module, or names a test module that does not test with the release of this fork the Mill plugin belongs to, or a Scala.js or Native test module.
- The test command not running where a task Mill must complete before it, such as another goal on the same `mill` command line, fails first.

### Out of scope

An item that names a section of `../ROADMAP.md` is left to the feature that section describes.

- `--halt-on-failure` (§ 7).
- Running the tests of Scala.js and Native test modules (§ 11, § 12, § 13).
- A module that presets module selectors (§ 17).
- The warning on an unsupported Mill release (§ 6).
- The consolidated output of a worker that ends before its tests finish (§ 8).
- Progress output (§ 9).
- What the test command does where another Mill launcher, sharing the daemon, changes a task that a selected test module's tests depend on while the test command runs is undefined.

## Acceptance criteria

```gherkin
Feature: Several module selectors
  The test command takes one or more module selectors and selects each test module that at least one of them resolves to.
  A test module that more than one module selector resolves to is selected once.
  The consolidated output holds one module node for each selected test module.
  Each module node's name is the module's name as Mill writes it in a task name, such as `core.test`.
  The JSON output's root records, in `moduleSelectors`, each module selector the test command was given, with the test modules it resolved to.
  Module names are ordered by comparing them with Java's `String.compareTo`.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And each of those modules has `testParallelism` off and `testSandboxWorkingDir` on, and leaves `testForkGrouping`, `testBatchFrameworkTasks`, `testArgsDefault` and `testReportXml` unset
    And `alpha.test` holds the suites `Alpha1` and `Alpha2`, the classes `samples.Alpha1` and `samples.Alpha2`, and `beta.test` holds the suite `Beta1`, the class `samples.Beta1`
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

  Rule: Each test module that at least one module selector resolves to runs its tests once and has one module node
    Scenario Outline: AC-001 Module selectors <selectors>
      When the test command is invoked with the module selectors <selectors>
      Then the JSON output's root holds exactly the module nodes `alpha.test` and `beta.test`, in that order
      And each of the files `Alpha1.fast`, `Alpha1.slow`, `Alpha2.fast`, `Alpha2.slow`, `Beta1.fast` and `Beta1.slow` in `<markers>` holds exactly one line
      And the test command succeeds

      Examples:
        | selectors                       |
        | `alpha.test beta.test`          |
        | `beta.test alpha.test`          |
        | `alpha.test __.test`            |
        | `__.test alpha.test beta.test`  |

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
    A module's module folder is the module's name, as Mill writes it in a task name, in the folder `org.scalatest.mill.TestCommandModule/modules` of Mill's output folder.

    Scenario: AC-004 The working directory of each module's tests
      Given the tests `Alpha1.fast` and `Beta1.fast` each send an `info` message holding the system property `user.dir`
      When the test command is invoked with the module selectors `alpha.test beta.test`
      Then the `info` message of `Alpha1.fast` is the path of the folder `sandbox` in `out/org.scalatest.mill.TestCommandModule/modules/alpha.test` in the build's workspace
      And the `info` message of `Beta1.fast` is the path of the folder `sandbox` in `out/org.scalatest.mill.TestCommandModule/modules/beta.test` in the build's workspace
```

```gherkin
Feature: Every selected module runs, whatever happens to the others
  The test command runs the tests of every selected test module, whether another selected test module's tests cannot start, another selected test module has a test that fails, or another selected test module fails the test command in any other way.
  The test command does so whether or not Mill's `--keep-going` is given.
  A selected test module's tests start once that module, and each module it depends on, has compiled.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And `alpha.test` holds the suite `Alpha1`, the class `samples.Alpha1`, and `beta.test` holds the suite `Beta1`, the class `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast` and `slow`, both of which succeed unless the scenario says otherwise
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 <flags> scalatest/test <selectors> -fJ <report>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists
    And the JSON output is the JSON value parsed from the file at `<report>` once the test command has ended

  Rule: A failing test in one module fails the test command and does not stop another module's tests
    Scenario Outline: AC-005 A test of alpha.test fails, flags <flags>
      Given `Alpha1`'s test `slow` fails
      When the test command is invoked with the flags <flags> and the module selectors `alpha.test beta.test`
      Then the files `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the test command fails

      Examples:
        | flags          |
        | none           |
        | `--keep-going` |

  Rule: A module whose tests cannot start has its module node and fails the test command, and does not stop another module's tests
    A module's tests cannot start where the module fails to compile, a dependency of the module fails to resolve, or any other task the module's tests depend on fails.
    The test command starts no test of that module.
    A failing task of a selected test module does not stop the test command from running.

    Scenario Outline: AC-006 broken.test does not compile, flags <flags>
      Given the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and whose sources do not compile
      When the test command is invoked with the flags <flags> and the module selectors `alpha.test broken.test beta.test`
      Then the files `Alpha1.fast`, `Alpha1.slow`, `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the JSON output's root holds exactly the module nodes `alpha.test`, `beta.test` and `broken.test`, in that order
      And the test command fails

      Examples:
        | flags          |
        | none           |
        | `--keep-going` |

    Scenario Outline: AC-015 broken.test's tests cannot start: <cause>
      Given the build also has the JVM test module `broken.test`, which tests with `com.edc::scalatest` at the release under test and <cause>
      When the test command is invoked with no flags and the module selectors `alpha.test broken.test beta.test`
      Then the files `Alpha1.fast`, `Alpha1.slow`, `Beta1.fast` and `Beta1.slow` exist in `<markers>`
      And the JSON output's root holds exactly the module nodes `alpha.test`, `beta.test` and `broken.test`, in that order
      And the test command fails

      Examples:
        | cause                                                                                       |
        | whose `mvnDeps` holds `com.example::absent:0.0.1`, which no repository the build uses holds |
        | whose `generatedSources` task throws an exception                                           |

  Rule: A module's tests start once that module and each module it depends on have compiled, without waiting for another selected test module to compile
    Scenario: AC-007 late.test compiles only after a test of alpha.test has run
      Given the build also has the JVM test module `late.test`, which tests with `com.edc::scalatest` at the release under test and holds the suite `Late1`, the class `samples.Late1`, an `AnyFunSuite` whose test `fast` creates the file `Late1.fast` in `<markers>`
      And `late.test`'s compile depends on a task that ends once the file `Alpha1.fast` exists in `<markers>`, and fails if that file does not exist within 120 seconds
      When the test command is invoked with no flags and the module selectors `alpha.test late.test`
      Then the files `Alpha1.fast` and `Late1.fast` exist in `<markers>`
      And the test command succeeds
```

```gherkin
Feature: Class globs and framework arguments across modules
  The test command's class globs and framework arguments apply alike to every selected test module.
  In each selected test module, only the test classes whose fully qualified name matches at least one class glob run, and every run receives the framework arguments.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test modules `alpha.test` and `beta.test` each test with `com.edc::scalatest` at that release, and their definitions name nothing of the plugin
    And `alpha.test` holds the suites `Alpha1` and `Alpha2`, the classes `samples.Alpha1` and `samples.Alpha2`, and `beta.test` holds the suite `Beta1`, the class `samples.Beta1`
    And each of those suites is an `AnyFunSuite` with two tests, `fast`, tagged `samples.Fast`, and `slow`, both of which succeed
    And each of those modules holds `samples.Fast`, an object extending `org.scalatest.Tag` with the name `samples.Fast`, which is not a test class
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates the file `<suite>.<test>` in `<markers>`
    And the test command is invoked as `mill -j 2 scalatest/test alpha.test beta.test -fJ <report> <arguments>`, each argument reaching `mill` exactly as written, with no shell expansion
    And `<report>` is an absolute path at which nothing exists

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

  Rule: Where no class glob matches a test class that a selected module's test framework discovers, no test of that module starts and the test command fails, and every other module's tests still run
    Scenario: AC-009 Class globs that match only alpha.test's classes
      When the test command is invoked with `-- samples.Alpha*`
      Then the files in `<markers>` are exactly `Alpha1.fast`, `Alpha1.slow`, `Alpha2.fast` and `Alpha2.slow`
      And the test command fails
```

```gherkin
Feature: Modules the test command cannot run
  Where a module selector resolves to a test module that does not test with the release of this fork the Mill plugin belongs to, the test command fails before any test of any selected test module starts.
  Such a test module tests with MUnit, uTest, upstream ScalaTest 3.2.20 or another release of this fork.
  Where a module selector resolves to a Scala.js or Native test module, the test command fails before any test of any selected test module starts.
  Where a module selector resolves to no test module, the test command fails before any test of any selected test module starts.
  In each of these cases, once the test command has ended, no file exists at the path `-fJ` names.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `alpha.test` tests with `com.edc::scalatest` at that release and holds the suite `Alpha1`, an `AnyFunSuite` whose test `fast` succeeds
    And the build also has these test modules, each with one test that succeeds:
      | module          | runtime  | tests with                                                  |
      | `munit.test`    | JVM      | MUnit                                                       |
      | `upstream.test` | JVM      | `org.scalatest::scalatest` 3.2.20                           |
      | `js.test`       | Scala.js | `com.edc::scalatest` at the release under test, in `NodeJs` |
      | `native.test`   | Native   | `com.edc::scalatest` at the release under test              |
    And `<markers>` is an absolute path at which an empty folder exists, and each test, when it runs, creates a file in `<markers>`
    And `<report>` is an absolute path at which a file exists that an earlier test command wrote
    And the test command is invoked as `mill -j 2 scalatest/test <selectors> -fJ <report>`, each argument reaching `mill` exactly as written, with no shell expansion

  Rule: A test module the test command cannot run fails the test command before any module's tests start
    Scenario Outline: AC-010 Module selectors alpha.test and <module>
      When the test command is invoked with the module selectors `alpha.test <module>`
      Then Mill reports that the test command failed, with a message naming `<module>`
      And no file exists in `<markers>`
      And nothing exists at `<report>`

      Examples:
        | module          |
        | `munit.test`    |
        | `upstream.test` |
        | `js.test`       |
        | `native.test`   |

  Rule: The failure names every test module the test command cannot run
    Scenario: AC-011 A module selector that resolves to every test module
      When the test command is invoked with the module selector `__.test`
      Then Mill reports that the test command failed, with a message naming each of `munit.test`, `upstream.test`, `js.test` and `native.test`
      And no file exists in `<markers>`
      And nothing exists at `<report>`

  Rule: A module selector that resolves to no test module fails the test command, naming that module selector, before any module's tests start
    Scenario: AC-016 Module selectors alpha.test and util
      Given the build also has the JVM module `util`, which is not a test module and holds no module
      When the test command is invoked with the module selectors `alpha.test util`
      Then Mill reports that the test command failed, with a message naming the module selector `util`
      And no file exists in `<markers>`
      And nothing exists at `<report>`
```

```gherkin
Feature: Another goal fails first
  Where a task that Mill must complete before it starts the test command fails, the test command starts no test and writes no consolidated output.
  The test command then creates, changes and deletes nothing at the path `-fJ` names.
  Such a task is a goal before the test command on the same `mill` command line where Mill's `--keep-going` is not given.
  No task of a selected test module, such as its compile or its dependency resolution, is a task that Mill must complete before it starts the test command.

  Background:
    Given a Mill 1.3.1 build that adds the Mill plugin, at the release of this fork under test, as the entry `com.edc::scalatest-mill` of its `build.mill` header's `mvnDeps`
    And the build's JVM test module `alpha.test` tests with `com.edc::scalatest` at that release and holds the suite `Alpha1`, an `AnyFunSuite` whose test `fast` creates a file in `<markers>` when it runs
    And the build's module `broken` does not compile
    And `<markers>` is an absolute path at which an empty folder exists
    And `<report>` is an absolute path at which a file exists that an earlier test command wrote

  Rule: The test command does not run after an earlier goal fails
    Scenario: AC-012 broken.compile before the test command
      When `mill -j 2 broken.compile + scalatest/test alpha.test -fJ <report>` is invoked, each argument reaching `mill` exactly as written, with no shell expansion
      Then Mill reports that `broken.compile` failed
      And no file exists in `<markers>`
      And the file at `<report>` has the content and modification time it had before `mill` was invoked
```

Each epic requirement this feature enables, with the criteria that test it:

| Epic requirement             | Criteria                               |
|------------------------------|----------------------------------------|
| `../SPECIFICATION.md` R-028  | AC-002, AC-013, AC-014, AC-017         |
| `../SPECIFICATION.md` R-046c | AC-006, AC-015                         |
| `../SPECIFICATION.md` R-053a | AC-008, AC-009                         |
| `../SPECIFICATION.md` R-053b | AC-008                                 |
| `../SPECIFICATION.md` R-055  | AC-001, AC-003, AC-008, AC-017         |
| `../SPECIFICATION.md` R-056a | AC-005, AC-006, AC-009, AC-015         |
| `../SPECIFICATION.md` R-056b | AC-007                                 |
| `../SPECIFICATION.md` R-059  | AC-010, AC-011                         |
| `../SPECIFICATION.md` R-061  | AC-006, AC-012, AC-015                 |
| `../SPECIFICATION.md` R-062  | AC-012                                 |
| `../SPECIFICATION.md` R-077  | AC-010, AC-011, AC-016                 |
| `../SPECIFICATION.md` R-096a | AC-001, AC-002, AC-003, AC-006, AC-013 |
| `../SPECIFICATION.md` R-098e | AC-006, AC-015                         |
| `../SPECIFICATION.md` R-105  | AC-001, AC-003, AC-006                 |
| `../SPECIFICATION.md` R-110  | AC-004                                 |
| `../SPECIFICATION.md` R-137  | AC-005, AC-006, AC-009, AC-015         |
