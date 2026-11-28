# Event-stream test reporting for Mill

Scale: epic.

## Summary

Testing a Mill build can start many runs: several modules, parallel runs within a module, and the same suites on several runtimes. Each run prints for itself, so the output interleaves, loses the test structure, and cannot be read as one whole by people or by tools. This epic has each run send what the consolidated output needs in one test event log, and adds a Mill plugin whose consolidated test command consolidates the event logs of every run it started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this document, indexed by topic.
- [ARCHITECTURE.md](ARCHITECTURE.md) — the deployable units, the boundaries between the emitter, the Mill plugin and the consolidation library, and how each run's events reach the consolidated output.
- [ARCHITECTURE-DECISIONS.md](ARCHITECTURE-DECISIONS.md) — the decisions behind the architecture.
- [../../mill-plugin/consolidated.schema.json](../../mill-plugin/consolidated.schema.json) — the JSON schema of the JSON output, with the decisions behind its layout in [JSON-LAYOUT-DECISIONS.md](JSON-LAYOUT-DECISIONS.md).

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and the output it captures in a single test event log.
- A consolidation library that produces the consolidated output from many runs.
- A Mill plugin that hosts the consolidation library, reads the test event log of each run it starts, and adds the consolidated test command, which runs the tests of every test module its module selectors name, with the emitter on.
- The consolidated test command's output arguments: progress and human-readable output on its stdout or in files, JSON output in files, any number of files, and the *formatting characters* and *filtering characters* that change and hide lines of the human-readable output.
- A module a build defines to preset a consolidated test command's arguments.

The emitter, the consolidation library and the Mill plugin cover runs on the JVM, Scala.js and Native, in test modules on Scala 3.3 or later. The fork publishes no Scala 2 artifacts. On Scala.js, the fork meets § Constraints in every JS environment, but the emitter, the consolidated output and the consolidated test command cover only runs in the `NodeJs` and `JsDom` JS environments. The fork meets every requirement below on each runtime, and in each JS environment that the requirement covers.

The Mill plugin runs on Mill 1.x releases no older than the one it is built against; older releases and other Mill major versions are out of scope. Every release of the Mill plugin is a release of this fork. Each release of the Mill plugin lists the Mill releases on which its integration tests passed. Each release of the Mill plugin supports exactly the Mill releases it lists, its *supported Mill releases*.

A test event log's format, the consolidation library's API, the switch by which the Mill plugin turns the emitter on, and the JSON output's format carry no compatibility promise across releases of this fork: any release may change them. No caller outside this fork may rely on the test event log's format, the consolidation library's API or that switch. The JSON output's format has no version of its own. Within one release of this fork, the consolidation library reads every test event log that the release's emitter writes.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- A supported way to turn the emitter on outside Mill.
- Carrying an event's payload, or any other part of an event or throwable that the consolidated output neither shows nor uses.
- Changing any existing ScalaTest reporter.
- New or changed ScalaTest runner arguments, beyond the switch by which the Mill plugin turns the emitter on. The consolidated test command's `-o` and `-f` arguments and their characters belong to the consolidated test command, not to ScalaTest's runner.
- A consolidated HTML report; a follow-up epic.
- A consolidated CTRF (Common Test Report Format) report; a possible later feature.
- Build tools other than Mill.
- Consolidated output for Scala.js runs in a JS environment other than `NodeJs` and `JsDom`: `ExoegoJsDomNodeJs`, `Phantom`, and the browsers that `Selenium` and `Playwright` drive; browsers are a possible later feature.
- A `--local` option on the consolidated test command, running each module as `testLocal` does, in Mill's JVM.
- A `--cached` option on the consolidated test command, returning cached test results, each flagged as cached (newer than the last edit) or missing.
- A dry-run option on the consolidated test command, listing each module selector's test structure without running tests; a possible later feature.

## Constraints

### Compatibility with ScalaTest 3.2.x

This fork follows upstream ScalaTest's 3.2 line and is based on its release 3.2.20, which every comparison below is made with. *3.2.20* means `org.scalatest:scalatest` 3.2.20 run with the same arguments over the same compiled suites. A reporter *receives what 3.2.20 sends* when it receives the events 3.2.20 sends it, with the same field values apart from timestamps, durations and thread names, in this order:

- in a sequential run, the same sequence;
- in a concurrent run, an order 3.2.20 could deliver them, with each suite's events in the order 3.2.20 delivers that suite's.

A throwable is compared on its class, its message and its chain of causes, and, where it records the file and line of the code that failed, on that file name and line number. A throwable's stack frames, and where among those frames the failed code lies, are not compared.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.20 uses. Each reporter that the run uses receives what 3.2.20 sends. The build tool receives the test-interface events 3.2.20 sends.
- R-002 With the emitter on, ScalaTest only prints exceptional output.
- R-051 With the emitter on, the build tool still receives the test-interface events 3.2.20 sends.
- R-004 Each artefact of the fork that ScalaTest 3.2.20 also publishes stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.20's.

### Compatibility with IDEs and Mill's test tasks

- R-003 Test runs launched by Metals, Bloop and IntelliJ behave as they do against ScalaTest 3.2.20, including in a build that uses the Mill plugin, on every Mill release the plugin runs on, whether or not it is a supported Mill release. IntelliJ's test runs behave so in each mode in which IntelliJ's Scala plugin runs ScalaTest:
  - *forked*: the IDE calls `Runner.run` with only its own `-C` reporter, compiled against ScalaTest 3.2.20;
  - *sbt shell*: the IDE forces `-oDU` and parses the text ScalaTest prints;
  - *BSP*: the build server runs `Framework` and forwards its test-interface events.
- R-060 In a build that uses the Mill plugin, on every Mill release the plugin runs on, whether or not it is a supported Mill release, Mill's test tasks (`testForked`, `testOnly`, `testCached` and `testLocal`), including when run as `mill __.test`, behave as they do without the plugin.

## What each run reports

- R-063 Neither the emitter nor the consolidation plugin change their output based on any ScalaTest reporter configuration.
- R-011 The test event log and consolidation plugin write captured values verbatim
- R-074a The consolidated JSON output shows each run's completion state, whether successful or not.
- R-074b The consolidated human-readable output shows only unsuccessful completion states for each run.

## Test structure

### Nodes

- R-096a A node is one of:
  - root - the consolidated test command itself
  - module
  - run (JVM forked only)
  - suite
  - scope
  - test
- R-135 a non-forked run is reported as part of its module
- R-130a Where the structure is knowable up front, a structure event containing all registered tests is emitted. 
- R-130b Multiple structure events may be emitted in a single run. The registered entities is the union of these trees.
- R-130c The node tree contains each node that was registered and/or executed.
- R-130d The node tree is organised by the registration and execution hierarchy.
- R-130e An declared suite, scope or test may be executed more than once. If so they will appear as many times as they were executed, in the structure of their execution.
- R-130f Within a single event log file, where an execution hierarchy does not match the registered hierarchy:
  - all registered tests that can be attributed to any executed tests are not considered *not run*
  - any registered subtree where all its registered tests are not considered *not run* is also not considered *not run*
- R-104 All nodes are identified by their position in the node tree
- R-105 The node tree is ordered by declaration order, when known, otherwise alphanumeric by label, then execution order where two siblings have identical labels
- R-097a All output is captured and attached to a node. Output is stdio, and `info`, `alert`, `note` and `markup` messages.
- R-097b All output is attached to the most specific identifiable node.
- R-043 All captured output keeps its order within a node, whatever its source.
- R-076 Output captured from a forked execution, before any Runner is constructed, is associated with the most specific node that can be identified.
- R-072a A runner that is not a run, such as Mill's discovery runner, creates no node.
- R-072b An error that no run, suite, scope or test records is attached to the node of the one selected test module it can be attributed to, such as the module whose discovery runner failed. Such an error that cannot be attributed to one selected test module is attached to the root.
- R-136 The node tree is constructed from the consolidated test command's execution, each process's assigned test classes, and the events emitted by all Runners

### Outcomes and durations

- R-098a Every test has a recorded outcome. 
- R-098b A scope has an outcome only when that execution is pending or aborted.
- R-098c An execution of a suite has an outcome only when that execution is aborted. 
- R-098d A run has an outcome only when the run is aborted or stopped. 
- R-098e A module has an outcome only when an error is attached to it.
- R-098f The root has an outcome only when an error is attached to it.
- R-095a Any suite scheduled to be run, or any suite, scope or test that is recorded in a structure event, and has no subsequent test events is reported as aborted with reason *not run*.
- R-095b Any node recorded with a start event but no end event is reported as aborted.
- R-103 A node's duration is measured from that node's own start and end, and a node whose start or end was not measured has no duration.

## The consolidation library

- R-023 The consolidation library is published and depends on no Mill artefact.
- R-113 Given the same runs and inputs, the consolidation library produces the same human-readable and JSON output when called without Mill as when the Mill plugin calls it.

## The consolidated test command

- R-054 In a Mill build, only a consolidated test command produces consolidated output.

### Consolidation plugin arguments

- R-055 the consolidated test command's arguments let the user, or a module that presets them, specify:
  - one or more module selectors, each a separate argument; `+` cannot join them, because Mill consumes it before the consolidated test command runs;
  - optional class globs and framework arguments, as Mill's `testOnly` takes them, applying alike to every selected test module;
  - whether the consolidated test command halts on the first failure (`--halt-on-failure`) or runs every selected test module;
  - whether the consolidated test command writes progress output, human-readable output, both or neither to its stdout;
  - one or more files, each receiving JSON, progress or human-readable output;
  - formatting and filtering characters for the human-readable output.
- R-126 A build can define a module that presets a consolidated test command's arguments, such as module selectors and tags, so that a `mill` command naming only that module runs that consolidated test command.
- R-125a The consolidated test command accepts and passes on the following ScalaTest arguments with their values: `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W`.
- R-125b The consolidated test command fails on any unknown arguments
- R-056a Unless `--halt-on-failure` is given, the consolidated test command runs the tests of every selected test module, whatever happens to other modules' runs as if Mill's `--keep-going` was given.

### ScalaTest Arguments

- R-142 any test module `testArgsDefault` arguments not in  `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W` are suppressed and not passed to ScalaTest. An argument ScalaTest does not recognise fails the consolidated test command before any test starts, naming it.

### Running the tests

A task's *task folder* is the folder Mill gives that task to write its files in.

- R-053a When class globs are provided, the consolidated test command runs each selected test module's tests as Mill's `testOnly` with the provided globs
- R-053b When no class globs are provided, the consolidated test command runs each selected test module's tests as Mill's `testForked`
- R-056b A selected test module may compile and run without waiting for any other test module whose compilation it does not depend on
- R-110 The consolidated test command gives each selected test module a module folder. Every file Mill's `testOnly` or `testForked` would write in its task folder, other than its JUnit XML report, is written at the same relative path in the module folder instead.
- R-092 The consolidated test command does not require any additional annotation of existing mill test modules. 

### Failure

- R-046a Tests do not fail when ignored or pending
- R-046b Scopes do not fail when pending
- R-046c Other nodes fail when there is an error, including compilation, or when they or do not complete
- R-137 the library and Mill return a failure if any node has failed 

### The consolidation plugin outputs

- R-020 Any combination of outputs may be configured. All configured outputs use the same node tree
- R-026 Progress output is written with best effort as the consolidation plugin reads the test event logs.
- R-027 Consolidated outputs are written after the last run has ended.
- R-106h Consolidated console output is written through mill with mill's line prefix
- R-047 The consolidated test command writes no JUnit XML report.
- R-040 Nothing that a run a consolidated test command starts writes to stdout or stderr reaches the console except through the consolidated output.
- R-129 No line that Mill's test runner writes for a test class it runs, such as `Running Test Class <name>`, is captured from the processes of a run a consolidated test command starts.
- R-050a the consolidated test command neither captures nor changes the output of other tasks named on the same `mill` command line, or Mill's own output
- R-050b when the consolidated test command is run with tasks, or run multiple times within a single invocation of `mill`, it may interleave output
- R-044 For each run a consolidated test command starts, the console does not show the following outside the consolidated output:
  - Mill's list of failed tests;
  - the progress Mill's test runner shows for the tests of the run's module, such as each worker's current test class and the count of classes completed;
  - ScalaTest's end-of-run summary for the run.
- R-048 Console output is set with the `-o[HP]` argument:
  - no `-o` argument and `-oHP` are equivalent, and produce progress and human-readable output
  - `-oH` human-readable output only
  - `-oP` progress output only
  - `-o` no console output. Mill output not part of the consolidated tests will still be output
- R-049a file output is set with the `-f[JHP] <file>` argument, at least one format must be specified as below:
  - `-fJ` JSON output
  - `-fH` human-readable output only
  - `-fP` progress output only
  - `-fHP` progress and human-readable output
- R-049b more than one `-f` argument may be provided
- R-138 each `-o` and `-f` may be provided with filtering and formatting characters
- R-139 filtering and formatting characters are local to the argument to which they are attached
- R-077 When the consolidated test command starts, before any test starts, the consolidated test command deletes or empties each existing file that it will write consolidated output to.
- R-087 Worker output is captured against a run, module or root.

### Human-readable output

- R-106a Human-readable output prints each node's label as either the ScalaTest formatted name, or the name of the node.
- R-106b Human-readable output prints the node tree as with each child indented from its parent
- R-106c Human-readable output prints every module, suite, scope and test of the node tree, except:
  - where a filtering character removes nodes
  - where a subtree contains identical aborted messages, only the root is printed
- R-106d Human-readable output prints each nodes outcome and outcome message
- R-106e Human-readable output prints each nodes output
- R-106f Human-readable output prints in colour, unless the `w` formatting character is given
- R-106g Human-readable output prints durations only when the `d` formatting character is given
- R-127a For each filtering character given, human-readable output omits what that character names: `c` succeeded tests, `x` ignored tests, `e` pending tests, `o` info, `m` markup and `r` pending scopes.
- R-127b Human-readable output omits a scope when all its children have been omitted by filtering characters. This is recursive.
- R-143 Human-readable output shows the root only where the root failed or has output attached.
- R-144 Human-readable output shows a run only where the run has output attached, the run aborted or stopped, or the run was assigned test classes and ran none of them.
- R-140 Human-readable output starts after the last progress output, if they are enabled together
- R-141 Human-readable output ends with a summary line that is identical to the last progress output, regardless of whether progress output was enabled.

### JSON output

- R-028 JSON output encodes the node tree, per the JSON schema `../../mill-plugin/consolidated.schema.json`.
- R-084 The JSON output records the release of this fork that wrote it.
- R-127c For each filtering character given, JSON output omits what that character names: `c` succeeded tests, `x` ignored tests, `e` pending tests, `o` info, `m` markup and `r` pending scopes.
- R-127d JSON output omits a scope when all its children have been omitted by filtering characters. This is recursive.

### Progress output

- R-090a Progress output reports a summary of number of suites and tests expected to be run, and their current completion state. This includes success and failure
- R-090b Progress output always reports the most recent values, including the number of expected tests
- R-090c Progress output reports as new data is available to it
- R-090d Progress output *may* debounce output to reduce writes when numbers change rapidly
- R-090e Progress output uses colour, unless the `w` formatting character is given
- R-089 Progress output attempts to redraw in-place
- R-091 Progress output's lines on stdout do not erase Mill's own console output

## Runs that do not complete

### A stopped run

- R-101 Where a run is stopped, only the run is stopped. Each suite that the stopped run never started, and that no filter excludes, is aborted with reason *not run*.

### A run whose process failed

- R-102 A run is aborted with reason *process failed* where:
  - it does not finish;
  - it loses one of its processes; or
  - it reports that it completed while an execution of a suite, scope or test that started in it has no event ending it.
- R-073 The emitter writes all events to disk as soon as it is able to. On failure, what was still buffered inside the process may not be written.

### The consolidated test command does not run

- R-061 If a non compile task Mill must complete before starting the consolidated test command fails, then the consolidated test command does not run and produces no consolidated output.
- R-062 If another goal on the same `mill` command line fails before the consolidated test command starts and Mill's `--keep-going` is not given, then the consolidated test command does not run and produces no consolidated output.

### A test module the consolidated test command cannot run

- R-059 IF a module selector resolves to a test module that does not test with the release of this fork that the Mill plugin belongs to, or to a Scala.js test module whose JS environment is not `NodeJs` or `JsDom`, THEN the consolidated test command fails before any test starts, naming each such module, and produces no consolidated output. Examples of such a test module are a module that tests with MUnit, uTest, upstream ScalaTest 3.2.20 or another release of this fork. Each file the consolidated test command was to write consolidated output to is left deleted or emptied.

## An interrupted test command

- R-069 The consolidated test command has no special behaviour when interrupted, as by Ctrl-C.

## An unsupported Mill release

- R-093 When the running mill is not supported, the consolidated test command warns on the console and any file outputs, naming the running Mill version, before any test starts. Beyond this warning, running a non-supported Mill is undefined behaviour  
