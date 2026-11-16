# Event-stream test reporting for Mill

Scale: epic.

## Summary

Testing a Mill build can start many runs: several modules, parallel runs within a module, and the same suites on several runtimes. Each run prints for itself, so the output interleaves, loses the test structure, and cannot be read as one whole by people or by tools. This epic has each run send what the consolidated output needs in one test event log, and adds a Mill plugin whose test command consolidates the event logs of every run it started into one set of output.

## Plan

[Plan: event-stream test reporting for Mill](https://github.com/jamesgorman2/scalatest/issues/21)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — the decisions behind this document, indexed by topic.
- [ARCHITECTURE.md](ARCHITECTURE.md) — the deployable units, the boundaries between the emitter, the Mill plugin and the consolidation library, and how each run's events reach the consolidated output.
- [ARCHITECTURE-DECISIONS.md](ARCHITECTURE-DECISIONS.md) — the decisions behind the architecture.
- [JSON-LAYOUT.md](JSON-LAYOUT.md) — the layout of the JSON output: every key of a suite, scope, test, message, throwable, location and run record, what it means and when it is absent.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- An emitter in ScalaTest that sends one run's events, test structure and the output it captures in a single test event log.
- A consolidation library that produces the consolidated output from many runs.
- A Mill plugin that hosts the consolidation library, reads the test event log of each run it starts, and adds the test command, which runs the tests of every test module its module selectors name, with the emitter on.
- The test command's output arguments: progress and human-readable output on its stdout or in files, JSON output in files, any number of files, and the *formatting characters* and *filtering characters* that change and hide lines of the human-readable output.
- A module a build defines to preset a test command's arguments.

The emitter, the consolidation library and the Mill plugin cover runs on the JVM, Scala.js and Native, in test modules on Scala 3.3 or later. The fork publishes no Scala 2 artifacts. On Scala.js, the fork meets § Constraints in every JS environment, but the emitter, the consolidated output and the test command cover only runs in the `NodeJs` and `JsDom` JS environments. The fork meets every requirement below on each runtime, and in each JS environment that the requirement covers. No requirement is best effort on Scala.js or Native.

The Mill plugin runs on Mill 1.x releases no older than the one it is built against; older releases and other Mill major versions are out of scope. Every release of the Mill plugin is a release of this fork. Each release of the Mill plugin lists the Mill releases on which its integration tests passed. Each release of the Mill plugin supports exactly the Mill releases it lists, its *supported Mill releases*.

A test event log's format, the consolidation library's API and the switch by which the Mill plugin turns the emitter on carry no compatibility promise across releases of this fork: any release may change them, and no caller outside this fork may rely on them. Within one release of this fork, the consolidation library reads every test event log that the release's emitter writes.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- A supported way to turn the emitter on outside Mill.
- Carrying an event's payload, or any other part of an event or throwable that the consolidated output neither shows nor uses.
- Changing any existing ScalaTest reporter.
- New or changed ScalaTest runner arguments, beyond the switch by which the Mill plugin turns the emitter on. The test command's `-o` and `-f` arguments and their characters belong to the test command, not to ScalaTest's runner.
- A consolidated HTML report; a follow-up epic.
- Consolidated JUnit XML.
- A consolidated CTRF (Common Test Report Format) report; a possible later feature.
- Build tools other than Mill.
- Consolidated output for Scala.js runs in a JS environment other than `NodeJs` and `JsDom`: `ExoegoJsDomNodeJs`, `Phantom`, and the browsers that `Selenium` and `Playwright` drive; browsers are a possible later feature.
- A `--local` option on the test command, running each module as `testLocal` does, in Mill's JVM.
- A `--cached` option on the test command, returning cached test results, each flagged as cached (newer than the last edit) or missing.
- A dry-run option on the test command, listing each module selector's test structure without running tests; a possible later feature.

## Constraints

### Compatibility with ScalaTest 3.2.x

This fork follows upstream ScalaTest's 3.2 line and is based on its release 3.2.20, which every comparison below is made with. *3.2.20* means `org.scalatest:scalatest` 3.2.20 run with the same arguments over the same compiled suites. A reporter *receives what 3.2.20 sends* when it receives the events 3.2.20 sends it, with the same field values apart from timestamps, durations and thread names, in this order:

- in a sequential run, the same sequence;
- in a concurrent run, an order 3.2.20 could deliver them, with each suite's events in the order 3.2.20 delivers that suite's.

A throwable is compared on its class, its message and its chain of causes, and, where it records the file and line of the code that failed, on that file name and line number. A throwable's stack frames, and where among those frames the failed code lies, are not compared.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.20 uses. Each reporter that the run uses receives what 3.2.20 sends. The build tool receives the test-interface events 3.2.20 sends.
- R-002 With the emitter on, the reporter ScalaTest uses when no reporter is configured does not print the run's events.
- R-051 With the emitter on, the build tool still receives the test-interface events 3.2.20 sends.
- R-004 Each artifact of the fork that ScalaTest 3.2.20 also publishes stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.20's.

### Compatibility with IDEs and Mill's test tasks

- R-003 Test runs launched by Metals, Bloop and IntelliJ behave as they do against ScalaTest 3.2.20, including in a build that uses the Mill plugin, on every Mill release the plugin runs on, whether or not it is a supported Mill release. IntelliJ's test runs behave so in each mode in which IntelliJ's Scala plugin runs ScalaTest:
  - *forked*: the IDE calls `Runner.run` with only its own `-C` reporter, compiled against ScalaTest 3.2.20;
  - *sbt shell*: the IDE forces `-oDU` and parses the text ScalaTest prints;
  - *BSP*: the build server runs `Framework` and forwards its test-interface events.
- R-060 In a build that uses the Mill plugin, on every Mill release the plugin runs on, whether or not it is a supported Mill release, Mill's test tasks (`testForked`, `testOnly`, `testCached` and `testLocal`), including when run as `mill __.test`, behave as they do without the plugin.

## What each run reports

- R-063 A run's consolidated output does not depend on the run's reporter configuration.
- R-011 Each value that the consolidated output shows from a run's events, test structure or captured output equals the value the run reported, failure messages included. Within a node, the consolidated output keeps the order in which the run reported that node's events.
- R-074 On every runtime, where a run reports how it ended, completed, aborted or stopped, the JSON output shows that end in the run's run record. Where a run reports that it aborted or stopped, the human-readable output also shows that end.

A run *does not finish* when its process ends before the run has finished recording how it ended, even where its reporters received the closing run event, as when its process dies.

## Test structure

### Nodes

- R-096 A run's test structure has the run at its root, then suites, scopes and tests. A suite sits under the suite that nests or runs it, or under the test that runs it. A suite instance that a suite runs outside its tests, and that has the running suite's own suite ID, is the same node as the running suite. Neither a `DiscoverySuite` nor a `DistributedTestRunnerSuite` is a node, so the suites a `DiscoverySuite` discovers are top-level.
- R-130 A run's test structure contains each suite that the run registered or ran. A run's test structure contains each scope and test that the run ran, and each scope and test in its suite's last registration of scopes and tests, whether or not that scope or test ran. A run's test structure does not contain a scope or test that only an earlier registration of its suite contained and that never ran. A suite registers its scopes and tests more than once where, for example, it runs an instance of itself that has the same suite ID as the running suite and registers other scopes or tests. Each suite, scope or test is one node at its place in its run (`.docs/ubiquitous-language.md` **Node**), and that node has one execution for each time the suite, scope or test ran there.
- R-104 Suites, scopes or tests registered separately are separate nodes even where they share a name, within a run or across runs. A suite run at two places, as by two tests, is two nodes.
- R-097 Each `info`, `alert`, `note` and `markup` message attaches to the node that emitted it and, for a suite, scope or test, to the execution that emitted it. A message whose emitting node cannot be identified attaches to the first identifiable ancestor of that node. No message is lost or reordered, though the human-readable output does not show a message that a filtering character hides.
- R-105 Below a top-level suite, in the human-readable and JSON output, a node's children appear in declared order where it is known, and in descending alphanumeric order by name where it is not.

### Outcomes and durations

- R-098 Every execution of a test has an outcome. An execution of a scope has an outcome only when that execution is pending or aborted. An execution of a suite has an outcome only when that execution is aborted. A run has an outcome only when the run is aborted or stopped. No node or execution is given an outcome rolled up from its children or from other executions. Readers of the output roll up outcomes themselves.
- R-099 A suite that aborts, including one whose constructor throws, is shown as aborted on every runtime. Each execution of a scope or test that is open inside the suite when the suite aborts is aborted.
- R-100 Each nested suite that a run was to execute, that no filter excludes, and that never started is aborted with reason *not run*. For each suite a run constructed, each scope and test in the suite's last registration of scopes and tests that no filter excludes and that never started is aborted with reason *not run*.
- R-112 A suite, scope or test that a filter excludes, as by tag or test name, is shown only as the events ScalaTest 3.2.20 reports for it give.
- R-103 Each execution has a duration, except where the events that time the execution carry no duration. A node that never ran has no duration.

## Consolidated output

Output is *captured against* a suite, scope or test when the means that captured it ties it to that node, and *captured against* the run when it ties it only to the run. Output is *captured from* a process's stdout or stderr when it is read from that stream from outside the process.

### The outputs

- R-020 For one test command, the Mill plugin produces one set of consolidated output covering every run the test command started. That set consists of each progress, human-readable or JSON output that the test command writes to its stdout or to a file. Progress output, human-readable output and JSON output are separate outputs. Any combination of them can be on together, including progress output with JSON output. One output may go to stdout and to files at once.
- R-026 The test command writes progress output while the runs execute, from the events the runs have reported so far, without waiting for any run or for the test command itself to complete.
- R-027 Unless the test command is interrupted, the human-readable and JSON output are written together, once every run of the test command has ended, including each run that does not finish.

### The consolidation tree

- R-078 In the human-readable and JSON output, the root is the consolidation. Under Mill, there is one consolidation for each test command. The consolidation's children are its test run labels. Each test run label's children are the top-level suites of the label's runs and the label's not-run test classes. No run and no module selector is a node.
- R-079 In the human-readable and JSON output, test run labels appear in ascending lexicographic order by name, as do the top-level suites and not-run test classes under each label.
- R-083 A test run label has an outcome only where its module's tests did not run.
- R-021 The human-readable and JSON output each show the following of every run:
  - The human-readable output shows every suite, scope and test in the run's test structure, every failure, and every piece of captured output other than Mill's list of failed tests and ScalaTest's end-of-run summary. The human-readable output does not show a line that a filtering character hides. The human-readable output does not show a node below the root of a collapsed subtree: a subtree whose root is aborted and in which no other node has an execution.
  - The JSON output shows every suite, scope and test in the run's test structure, and every piece of captured output other than Mill's list of failed tests and ScalaTest's end-of-run summary. The JSON output shows the run's failure and the failure of every suite, scope and test that has one execution. For a suite, scope or test with more than one execution, the JSON output records one final state, which shows the failure of at most one of those executions.

### Human-readable output

- R-106 The human-readable output follows the format in which ScalaTest 3.2.20 prints each suite, scope, test, message and failure with no reporter configuration characters, apart from colour, with these differences:
  - its root is the consolidation, with test run labels below it, and each label's top-level suites and not-run test classes below the label;
  - every line is indented under the line it belongs to, in place of 3.2.20's indentation;
  - each message is marked with its kind;
  - every suite shows its name line, including one for which 3.2.20 prints none;
  - each node's outcome, and the reason *not run*, *process failed* or *could not start* that a node is aborted with, is shown with the node, including where 3.2.20 prints no line for it;
  - on stdout, each line carries the prefix Mill puts on every line the test command's own task writes;
  - the formatting and filtering characters of the `-o` or `-f` that writes the output change and hide lines;
  - 3.2.20's format does not fix where the following appear: the lines of a suite, scope or test that ran more than once in one execution of its parent, the blocks that show a run, and captured output.
- R-131 Apart from lines a filtering character hides, the human-readable output shows a scope or test once for each of its executions, each with what that execution alone produced (its outcome, failure, messages, captured output and duration). The human-readable output shows a scope or test that has no execution once, unless the scope or test lies below the root of a collapsed subtree: a subtree whose root is aborted and in which no other node has an execution.
- R-133 In the human-readable output, the executions of a scope or test that ran in one execution of its parent scope, or in its suite where its parent is the suite, appear together, in the order they started. They appear at the node's position among its siblings. That position is the node's place in declared order where declared order is known, and its place in descending alphanumeric order by name where it is not.
- R-134 In the human-readable output, an execution of a suite, scope or test appears under the execution of its parent that it ran in.
- R-132 The human-readable output shows a suite's name line once under each execution of its parent that ran it, however many times the suite ran in that execution. Under that name line, each execution of the suite shows what that execution alone produced (its abort, with the lines under the abort, and its messages), in the order the executions started.
- R-127 Each of the following lowercase characters after the output letters of an `-o` or `-f` formats or filters the human-readable output that argument writes, as its upper-case counterpart does as a ScalaTest 3.2.20 reporter configuration character:
  - the formatting characters `d` durations, `w` no colour and `v` file pathnames;
  - the filtering characters `c` succeeded tests, `x` ignored tests, `e` pending tests, `o` info, `m` markup and `r` pending scopes, each hiding those lines.

  Each `-o` and `-f` formats and filters only its own output, and its characters apply alike to every module of the test command. A `-f` that names JSON output takes no formatting or filtering characters.
- R-128 The formatting characters `s` and `f` after the output letters of an `-o` or `-f` add stack traces to the human-readable output that argument writes. For each throwable that is not a `StackDepthException`, `s` adds the short stack trace that ScalaTest 3.2.20's `S` adds, and `f` adds the full stack trace that 3.2.20's `F` adds. `s` and `f` add no stack trace for a `StackDepthException`, such as a `TestFailedException`.
- R-124 Human-readable output on the test command's stdout is coloured as ScalaTest 3.2.20 colours it where Mill colours its own console output and no `w` is given, and is uncoloured otherwise. Human-readable output in a file an `-f` writes is never coloured.
- R-107 In the human-readable output, a subtree is *collapsed* when its root is aborted and no other node in it has an execution. A collapsed subtree is shown as its root alone, with a count of the nodes it hides where it hides any. No other subtree collapses: a node with an execution never collapses into an ancestor, and siblings are never merged. The JSON output collapses nothing.
- R-082 The human-readable output shows no run as a node. On each test run label, the human-readable output shows one block, which does not name its run, for each run that has any of:
  - output captured against the run;
  - messages attached to the run rather than to a suite;
  - how the run ended, where it aborted, stopped or did not finish.

### JSON output

- R-028 The JSON output is one object for the consolidation. The JSON output is encoded as `JSON-LAYOUT.md` § Encoding states. Each suite, and every node below it, takes the layout `JSON-LAYOUT.md` § Nodes, § Messages, § Throwables and § Locations give, key names included.
- R-108 The JSON output records the final state of each node and run record, taking each value from the event that supplies it, and records nothing per event.
- R-109 In the JSON output, a node's children and messages form one sequence. Each message appears after every child of its node that the run had reached when the message was emitted, whether that child then ran at once, concurrently or later, and before every other child of that node. A node's messages keep the order in which they were emitted.
- R-081 The JSON output's root records:
  - the ScalaTest version, which every run shares;
  - each module selector, with the test modules it resolved to;
  - the build tool's name and version and whether that version is supported: under the test command, Mill, the running Mill version and whether it is a supported Mill release.

  The human-readable output does not show module selectors.
- R-080 In the JSON output, each test run label contains a run record for every run under it, including a run with no suites. A run record takes the layout `JSON-LAYOUT.md` § Run records gives, key names included, and records the run's identifier, unique within its label. Each suite a run reported names that run's record.
- R-084 The JSON output's format is documented for third-party readers and carries this compatibility promise:
  - the format has no version of its own beside the ScalaTest version its root records;
  - once this epic is complete, changes to the format follow ScalaTest's semantic versioning: a patch release never changes it, a minor release only adds to it, and only a major release removes or changes anything;
  - until then, any feature may change it.

  The human-readable and progress output carry no compatibility promise.

### Captured output

- R-012 Each piece of output captured against a suite, scope, test or run is shown on the narrowest suite, scope or test it was captured against, else on its run: in the run's run record in the JSON output, and in the run's block in the human-readable output. Each piece of captured output is shown with its stream, stdout or stderr, where that is known. In the human-readable output, output captured against a suite, scope or test keeps its position among the events of the execution it was captured in. The human-readable output does not show output captured against an execution of a suite, scope or test whose line a filtering character hides.
- R-122 In the JSON output, each node and run record contains the output captured against it, not only test output, whatever its source and whether or not its stream is known.
- R-088 In each of the human-readable and JSON output, each piece of output a process wrote appears at most once, even where it was captured both against a node and from the process's stdout or stderr.
- R-086 Output captured only against the run is shown without claiming that it is test output or worker output.
- R-087 Worker output is never captured against a suite, scope or test.

### Separate runs

- R-022 In the human-readable and JSON output, no suite, scope, test, run record or human-readable block of one run contains an event or captured output of another run.

## The consolidation library

- R-023 The consolidation library is published and depends on no Mill artifact.
- R-113 Given the same runs and inputs, the consolidation library produces the same human-readable and JSON output when called without Mill as when the Mill plugin calls it.

## The test command

- R-054 In a Mill build, only a test command produces consolidated output.

### Arguments

- R-055 The test command's arguments let the user, or a module that presets them, specify:
  - one or more module selectors, each a separate argument; `+` cannot join them, because Mill consumes it before the test command runs;
  - optional class globs and framework arguments, as Mill's `testOnly` takes them, applying alike to every selected test module;
  - whether the test command halts on the first failure (`--halt-on-failure`) or runs every selected test module;
  - whether the test command writes progress output, human-readable output, both or neither to its stdout;
  - one or more files, each receiving JSON, progress or human-readable output;
  - formatting and filtering characters for the human-readable output.
- R-126 A build can define a module that presets a test command's arguments, such as module selectors and tags, so that a `mill` command naming only that module runs that test command.

### Running the tests

A task's *task folder* is the folder Mill gives that task to write its files in.

- R-053 The test command runs each selected test module's tests as Mill's `testOnly` runs them with the test command's class globs and framework arguments, or as `testForked` runs them when no class globs are given, in these respects:
  - the tests that the module's own ScalaTest arguments select, once the test command has withheld the arguments it does not pass to runs, in the processes that Mill's fork grouping, test parallelism and test queue start;
  - the same working directory and environment, except that a path that `testOnly` or `testForked` puts inside its own task folder lies in another folder, and the environment variables Mill's launcher sets from its own command line take other values;
  - the effect of the test command's class globs and framework arguments, though not necessarily as the same ScalaTest arguments: a run may receive them in another form, beside arguments the test command adds.
- R-125 Each run receives each of its test module's own ScalaTest arguments (Mill's `testArgsDefault`) whose flag is `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W`, with that argument's values, ahead of the arguments the test command adds. The test command withholds from each run, with its values, every other argument of the module's own ScalaTest arguments whose flag the JVM `org.scalatest.tools.Runner` of the plugin's release recognises, whatever the module's runtime. The withheld arguments include every reporter argument and the JUnit and TestNG arguments. Where the module's own ScalaTest arguments include an argument whose flag that `Runner` does not recognise, the test command fails before any test starts, naming that argument, and produces no consolidated output.
- R-110 A path that Mill's `testOnly` or `testForked` puts inside its task folder is instead at the same relative path inside a folder the test command owns for that test module alone, outside the test command's own task folder.
- R-111 In each run's environment, each environment variable that Mill's launcher sets from its own command line, such as `MILL_PARAMS` from `mill.bat`, contains the `mill` command line that started the test command.
- R-047 The test command writes each selected test module's JUnit XML report as Mill's `testOnly` writes it, or as `testForked` writes it where no class globs are given, at the same relative path inside the folder the test command owns for that test module.
- R-092 The test command runs a selected test module's tests without the module's definition naming anything the Mill plugin provides, such as a trait to mix in.
- R-056 Unless `--halt-on-failure` is given, the test command runs the tests of every selected test module, whatever happens to other modules' runs and whether or not Mill's `--keep-going` is given. Each selected test module's tests start once that module has compiled and the other tasks they need have finished. A selected test module's tests do not wait for any other selected test module to compile or run, even after another module has failed.
- R-025 The Mill plugin gives each selected test module exactly one test run label, named for its module path. Mill keeps module paths unique.
- R-072 A runner that is not a run, such as Mill's discovery runner, does not appear in the consolidated output and does not affect whether the test command fails.

### When the test command fails

- R-046 Unless the test command is interrupted, it fails if:
  - any run reports a failure or does not complete: it ends aborted or stopped, or does not finish;
  - a process started to execute tests ends before any run exists in it; or
  - the tests of any selected test module cannot start, for example because it or a module it depends on fails to compile.

  A run *reports a failure* when a test in it fails or is cancelled, or a suite in it aborts. A run reports no failure for a test that is ignored or pending, or a scope that is pending.

  The test command's failure does not list the failed tests.

### Each run's own output

- R-040 Nothing that a run a test command starts writes to stdout or stderr reaches the console except through the consolidated output.
- R-041 No reporter that a test module's own ScalaTest arguments configure prints the events of a run a test command starts.
- R-042 Output captured from the stdout and stderr of the processes of a run a test command starts is captured against that run.
- R-129 No line that Mill's test runner writes for a test class it runs, such as `Running Test Class <name>`, is captured from the processes of a run a test command starts.
- R-043 Output captured from the stdout of the processes of a run a test command starts keeps its order, whatever its source. Output captured from the stderr of the processes of a run a test command starts keeps its order, whatever its source. The position of that captured output among the run's events is not guaranteed, and neither is the order of output captured from stdout relative to output captured from stderr.

### Mill's and ScalaTest's own console output

- R-050 The test command neither captures nor changes the output of other tasks named on the same `mill` command line, or Mill's own output, apart from:
  - the prefix Mill puts on each line of a task the test command runs;
  - Mill's list of failed tests;
  - the progress Mill's test runner shows for the tests of a run a test command starts.

  On the console, the output of those other tasks and Mill's own output may interleave with the consolidated output.
- R-044 For each run a test command starts, the console does not show the following outside the consolidated output:
  - Mill's list of failed tests;
  - the progress Mill's test runner shows for the tests of the run's module, such as each worker's current test class and the count of classes completed;
  - ScalaTest's end-of-run summary for the run.

  Mill's prompt may still show a line for the test command and for each task it runs, without that progress. A Mill test task named as another goal on the same `mill` command line shows its own progress.
- R-045 The consolidated output does not show Mill's list of failed tests or ScalaTest's end-of-run summary for any run a test command starts.

### Where the consolidated output goes

- R-048 With no `-o` argument, the test command writes progress output and human-readable output to its stdout. An `-o` argument instead names, by its output letters, what the test command writes to its stdout: `H` for human-readable output and `P` for progress output. A bare `-o`, naming neither, leaves stdout without consolidated output.
- R-049 An `-f`, followed by an output letter and a path, writes that output to the file at that path: `J` JSON output, `H` human-readable output or `P` progress output. More than one `-f` may be given. No `-f` changes what the test command writes to its stdout.
- R-077 When the test command starts, before any test starts, the test command deletes or empties each existing file that it will write consolidated output to. However the test command ends, no file that it was to write consolidated output to contains the result of an earlier test command.

#### Progress output on stdout

WHERE the test command writes progress output to its stdout:

- R-090 Progress output on stdout is written as whole lines, never as single characters. The lines of progress output on stdout show every failure a run reports and every other cause for which the test command fails, whether or not progress output also shows a summary on Mill's prompt. Those other causes are each run that aborts, stops or does not finish, each process that ends before any run exists in it, and each selected test module whose tests cannot start.
- R-089 Where Mill redraws its prompt in place (the status lines at the foot of the console), progress output also shows a current summary of the test command on the test command's own line of Mill's prompt, rewritten in place.
- R-091 Neither progress output's lines on stdout nor its summary on Mill's prompt erase Mill's own console output, and Mill's own console output erases neither.

## A run given no task

- R-071 A run given no task, including one whose process dies before its first task, has a run record in the JSON output. Such a run has a block in the human-readable output where output was captured against the run, where messages are attached to the run rather than to a suite, or where the run aborted, stopped or did not finish.

## Runs that do not complete

### A stopped run

- R-101 Where a run is stopped, only the run is stopped. Each suite that the stopped run never started, and that no filter excludes, is aborted with reason *not run*.

### A run that does not finish

- R-064 Where a run does not finish in a test command that is not interrupted, the run, and each execution of a suite, scope or test that started in it without an event ending it, are aborted.
- R-075 Where a run does not finish in a test command that is not interrupted, and a test ended the run's process, as a call to `System.exit` does, the consolidated output shows that test and every event the run reported before the test started.
- R-067 Where a run does not finish in a test command that is not interrupted, progress output shows that the run aborted as soon as the run ends.

### A run whose process failed

- R-102 A run is aborted with reason *process failed* where:
  - it does not finish;
  - it loses one of its processes; or
  - it reports that it completed while an execution of a suite, scope or test that started in it has no event ending it.
- R-073 When a run's process dies, the consolidated output shows everything the run had handed to the operating system before the process died, as the run handed it over: the run's events, its test structure, the output captured against its suites, scopes and tests, and the output captured from its processes, which is captured against the run. What was still buffered inside the process may be missing.

## A process that ends before any run exists

- R-076 Where a process started to execute tests ends before any run exists in it, as when the process dies before its runner is constructed, the human-readable and JSON output show on its module's test run label that the process ended before any run existed, with the output captured from the process's stdout and stderr. The output captured from the stdout and stderr of a process that ended before any run existed in it appears nowhere else in either output: in no run record and in no run's human-readable block.

## Not-run test classes

A *not-run test class* is a test class that the test command was to run, that no run reported as a suite, and that the human-readable and JSON output show as not run.

- R-095 In the human-readable and JSON output, a not-run test class is a suite node, named by its class name, aborted with reason *not run*, with no children. A not-run test class is a child of its module's test run label and names no run record.
- R-123 Unless the test command is interrupted, the human-readable and JSON output show once, as a not-run test class, each test class that the test command was to run for a selected test module whose tests started, and that no run reported as a suite.
- R-115 A not-run test class does not itself fail the test command.

## Tests that cannot start

### The test command does not run

- R-061 IF a task Mill must complete before starting the test command fails, THEN the test command does not run and produces no consolidated output. A selected test module's compile and test tasks are not among those tasks.
- R-062 IF another goal on the same `mill` command line fails before the test command starts and Mill's `--keep-going` is not given, THEN the test command does not run and produces no consolidated output.

### A test module the test command cannot run

- R-059 IF a module selector resolves to a test module that does not test with the release of this fork that the Mill plugin belongs to, or to a Scala.js test module whose JS environment is not `NodeJs` or `JsDom`, THEN the test command fails before any test starts, naming each such module, and produces no consolidated output. Examples of such a test module are a module that tests with MUnit, uTest, upstream ScalaTest 3.2.20 or another release of this fork. Each file the test command was to write consolidated output to is left deleted or emptied.

### A test module whose tests cannot start

- R-058 The consolidated output shows the test run label of each selected test module whose tests could not start as aborted, with reason *could not start* and the failure that stopped them.

## `--halt-on-failure`

WHERE `--halt-on-failure` is given:

- R-057 Each selected test module's tests start once that module has compiled and the other tasks they need have finished, without waiting for any other selected test module to compile or run, until one of these happens:
  - a run reports a failure or does not complete;
  - a process ends before any run exists in it;
  - a selected test module's tests cannot start.

  Once one of these has happened, the test command starts no further selected test module's tests.
- R-114 The consolidated output shows the test run label of each selected test module whose tests `--halt-on-failure` kept from starting as aborted, with reason *not run*.

## An interrupted test command

- R-069 IF the test command is interrupted, as by Ctrl-C, THEN it writes no human-readable output and no JSON, not even a placeholder, and each file of human-readable or JSON output is left deleted or emptied. The test command's progress output may show runs that the interrupt ended.
- R-116 When the test command is interrupted, Mill's client and the operating system set its exit status.

## An unsupported Mill release

WHERE the running Mill release is not a supported Mill release:

- R-093 Before any test starts, the test command warns on the console, naming the running Mill version, and any human-readable output repeats the warning.
- R-118 The test command runs. Where a Mill internal that the test command needs is missing, the test command may instead fail before any test starts, naming the Mill version and producing no consolidated output.
- R-119 Any JSON output records the running Mill version and that it is not a supported Mill release.
