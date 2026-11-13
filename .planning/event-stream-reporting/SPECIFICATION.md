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
- The test command's output arguments: progress and human-readable output on its stdout or in files, JSON output in files, any number of files, and formatting and filtering of human-readable output.
- A module a build defines to preset a test command's arguments (R-126).

The emitter, the consolidation library and the Mill plugin cover runs on the JVM, Scala.js and Native, on these Scala versions: the JVM on Scala 2.11, 2.12, 2.13 and 3.3; Scala.js and Native on 2.12, 2.13 and 3.3. On Scala.js, § Constraints holds in every JS environment, but the emitter, the consolidated output and the test command cover only runs in the `NodeJs` and `JsDom` JS environments. Every requirement below holds on each runtime and in each JS environment it covers; none is best effort on Scala.js or Native.

The Mill plugin runs on Mill 1.x releases no older than the one it is built against; older releases and other Mill major versions are out of scope. Every release of the Mill plugin is a release of this fork. Each lists the Mill releases its integration tests passed on, and supports exactly those (*supported Mill releases*).

A test event log's format, the consolidation library's API and the switch by which the Mill plugin turns the emitter on carry no compatibility promise across releases of this fork: any release may change them, and no caller outside this fork may rely on them.

### Out of scope

- sbt and command-line front ends for the consolidation library.
- A supported way to turn the emitter on outside Mill.
- Carrying an event's payload, or any other part of an event or throwable that the consolidated output neither shows nor uses.
- Changing any existing ScalaTest reporter.
- New or changed ScalaTest runner arguments, beyond the switch by which the Mill plugin turns the emitter on. `-o`, `-f` and their characters (R-048, R-049, R-127) are the test command's own arguments.
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

*3.2.19* means `org.scalatest:scalatest` 3.2.19 run with the same arguments over the same compiled suites. A reporter *receives what 3.2.19 sends* when it receives the events 3.2.19 sends it, with the same field values apart from timestamps, durations and thread names, in this order:

- in a sequential run, the same sequence;
- in a concurrent run, an order 3.2.19 could deliver them, with each suite's events in the order 3.2.19 delivers that suite's.

- R-001 A run with the emitter off uses the reporters ScalaTest 3.2.19 uses. Each receives what 3.2.19 sends, and the build tool receives the test-interface events 3.2.19 sends.
- R-002 With the emitter on, every existing ScalaTest reporter, and any reporter passed with `-C`, still receives what 3.2.19 sends.
- R-051 With the emitter on, the build tool still receives the test-interface events 3.2.19 sends.
- R-004 The fork stays binary compatible with ScalaTest 3.2.x, checked by MiMa against 3.2.19.

### Compatibility with IDEs and Mill's test tasks

- R-003 Test runs launched by Metals, Bloop and IntelliJ behave as they do against ScalaTest 3.2.19, including in a build that uses the Mill plugin. This holds in each mode in which IntelliJ's Scala plugin runs ScalaTest:
  - *forked*: the IDE calls `Runner.run` with only its own `-C` reporter, compiled against ScalaTest 3.2.19;
  - *sbt shell*: the IDE forces `-oDU` and parses the text ScalaTest prints;
  - *BSP*: the build server runs `Framework` and forwards its test-interface events.
- R-060 In a build that uses the Mill plugin, Mill's test tasks (`testForked`, `testOnly`, `testCached` and `testLocal`), including when run as `mill __.test`, behave as they do without the plugin.

## The emitter

- R-010 Each run writes to one test event log every event the run reports, the run's test structure and the output the run captures.
- R-063 What a run sends does not depend on its reporter configuration.
- R-011 Nothing the consolidated output shows or uses is lost or altered between the run and the test event log's reader. This includes failure messages and the order of events within a node.
- R-074 On every runtime, each run's test event log carries how the run ended: completed, aborted or stopped.

A run *does not finish* when its event stream ends without how the run ended, as when its process dies.

## Test structure

These hold for each run's test structure and for how the human-readable and JSON output show it.

### Nodes

- R-096 A run's test structure has the run at its root, then suites, scopes and tests. A nested suite sits under the suite that nests it. Neither a `DiscoverySuite` nor a `DistributedTestRunnerSuite` is a node, so the suites a `DiscoverySuite` discovers are top-level.
- R-130 The test structure holds each suite, scope and test a run registered, whether or not it ran and however many times, so it does not depend on how the tests ran. A suite, scope or test is one node in its run however many times it ran in that run, and has one execution for each time.
- R-104 Suites, scopes or tests registered separately are separate nodes even where they share a name, within a run or across runs.
- R-097 Each `info`, `alert`, `note` and `markup` message attaches to the node that emitted it and, for a suite, scope or test, to the execution that emitted it. A message whose emitting node cannot be identified attaches to the first identifiable ancestor of that node. No message is lost or reordered.
- R-105 Below a top-level suite, in the human-readable and JSON output, a node's children appear in declared order where it is known, and in descending alphanumeric order by name where it is not.

### Outcomes and durations

- R-098 Every execution of a test has an outcome. An execution of a scope has one only when it is pending or aborted, an execution of a suite only when it is aborted, and a run only when it is aborted or stopped. No node or execution is given an outcome rolled up from its children or from other executions; readers roll them up.
- R-099 A suite that aborts, including one whose constructor throws, is shown as aborted on every runtime. Each execution of a scope or test open inside it when it aborts is aborted.
- R-100 Each nested suite, scope and test a run was to execute that never started is aborted with reason *not run*.
- R-112 A suite, scope or test that a filter excludes, as by tag or test name, is not aborted as R-100 aborts a node that never started. It is shown only as the events ScalaTest 3.2.19 reports for it give.
- R-103 Each execution has a duration, except where the events that time it carry none. A node that never ran has none.

## Consolidated output

Output is *captured against* a suite, scope or test when the means that captured it ties it to that node, and *captured against* the run when it ties it only to the run. Output is *captured from* a process's stdout or stderr when it is read from that stream from outside the process.

### The outputs

- R-020 For one test command, the Mill plugin produces one set of consolidated output covering every run the test command started: each of progress output, human-readable output and JSON output that the test command's stdout or a file holds (R-048, R-049). Each is a separate output, and any combination can be on together, including progress output with JSON output. One output may go to stdout and to files at once.
- R-026 Progress output is written while the runs execute, from the events received so far. It is not held until a run or the test command completes.
- R-027 The human-readable and JSON output are written together, once every run's event stream has ended.

### The consolidation tree

- R-078 In the human-readable and JSON output, the consolidation is the root, one for each test command under Mill. Its children are its test run labels, and each label's children are the top-level suites of its runs. No run and no module selector is a node.
- R-079 In the human-readable and JSON output, test run labels appear in ascending lexicographic order, as do the top-level suites under each label, by name.
- R-083 A test run label has an outcome only where its module's tests did not run.
- R-021 The human-readable and JSON output show every suite, scope and test, every failure and every piece of captured output of every run.

### Human-readable output

- R-106 The human-readable output shows each suite, scope, test, message and failure in the format ScalaTest 3.2.19 prints it with no reporter configuration characters, as changed by its formatting and filtering characters (R-127), except that:
  - its root is the consolidation, with test run labels below it (R-078), and labels and top-level suites take the order R-079 gives (ascending lexicographic, by name);
  - every line is indented under the line it belongs to, in place of 3.2.19's indentation;
  - each message is marked with its kind (R-097);
  - every suite shows its name line, including one for which 3.2.19 prints none;
  - each outcome R-098 gives a node, and each reason R-100, R-101, R-102, R-058 and R-114 give, is shown with its node, including where 3.2.19 prints no line for it;
  - on stdout, each line carries the prefix Mill puts on every line the test command's own task writes.
- R-131 The human-readable output shows a scope or test once for each of its executions, each with what that execution alone produced (its outcome, failure, messages, captured output and duration), and once where it has none.
- R-133 In the human-readable output, the executions of a scope or test that ran in one execution of its parent scope, or in its suite where its parent is the suite, appear together at the node's declared position (R-105), in the order they started. This overrides R-106 (the format ScalaTest 3.2.19 prints).
- R-134 In the human-readable output, an execution of a scope or test appears under the execution of its parent scope that it ran in.
- R-132 The human-readable output shows a suite's name line once, however many times the suite ran. Under it, each of the suite's executions shows what it alone produced (its abort, with the lines under it, and its messages), in the order the executions started. This overrides R-106 (the format ScalaTest 3.2.19 prints).
- R-127 Lowercase characters after the output letters of an `-o` or `-f` format or filter the human-readable output that argument writes, each as its upper-case counterpart does as a ScalaTest 3.2.19 reporter configuration character:
  - `d` durations, `s` short stack traces, `f` full stack traces, `w` no colour and `v` file pathnames;
  - the filters `c` succeeded tests, `x` ignored tests, `e` pending tests, `o` info, `m` markup and `r` pending scopes, each hiding those lines.

  Each `-o` and `-f` formats only its own output, and its characters apply alike to every module of the test command. A `-f` naming JSON output takes none (R-121). This overrides R-021, R-097 and R-012 in the human-readable output for the lines a filter hides.
- R-128 `s` and `f` add no stack trace for a `StackDepthException`, such as a `TestFailedException`. This overrides R-127 (each character does what its upper case does in ScalaTest 3.2.19).
- R-124 Human-readable output on the test command's stdout is coloured where Mill colours its own console output, and not where `w` is given. A file `-f` writes is never coloured. This overrides R-106 (the format ScalaTest 3.2.19 prints) for colour.
- R-107 In the human-readable output, a subtree is *collapsed* when its root is aborted and no other node in it has an execution. A collapsed subtree is shown as its root alone, with a count of the nodes it hides where it hides any. Nothing else is collapsed: a node with an execution never collapses into an ancestor, and siblings are never merged. This overrides R-021 (every suite, scope and test is shown). The JSON output collapses nothing.
- R-082 The human-readable output shows no run. On each test run label, without naming the run, it shows one block for each run that has any of:
  - output captured against the run;
  - messages attached to the run rather than to a suite;
  - how the run ended, where it aborted, stopped or did not finish.

  This overrides R-106 (the human-readable output keeps the format ScalaTest 3.2.19 prints).

### JSON output

- R-028 The JSON output is one object for the consolidation. It is written as `JSON-LAYOUT.md` § Encoding states. Each suite, and every node below it, takes the layout `JSON-LAYOUT.md` § Nodes, § Messages, § Throwables and § Locations give, key names included. This overrides R-021 (every suite, scope, test, failure and piece of captured output is shown) for what that layout leaves out.
- R-121 The JSON output records no reporter's event-filter characters, and none drops a value from it.
- R-108 Each node and run record in the JSON output holds its final state, each value taken from the event that supplies it, and nothing per event. This overrides R-021 (every suite, scope, test, failure and piece of captured output is shown) for what is held only per event.
- R-109 In the JSON output, a node's children and messages form one sequence. Each message appears after every child of its node that the run had reached when the message was emitted, whether that child then ran at once, concurrently or later, and before every other child. Its messages keep the order in which they were emitted.
- R-081 The JSON output's root records the following; the human-readable output does not show module selectors:
  - the ScalaTest version, which every run shares;
  - each module selector, with the test modules it resolved to;
  - the build tool's name and version and whether that version is supported: under the test command, Mill, the running Mill version and whether it is a supported Mill release.
- R-080 In the JSON output, each test run label holds a run record for every run under it, including a run with no suites. A run record takes the layout `JSON-LAYOUT.md` § Run records gives, key names included: the run's own state, without its suites and without the ScalaTest version, which the root records, plus the run's identifier, unique within its label. Each suite a run reported names that run's record.
- R-084 The JSON output's format is documented for third-party readers and carries this compatibility promise:
  - the format has no version of its own beside the ScalaTest version its root records;
  - once this epic is complete, changes to the format follow ScalaTest's semantic versioning: a patch release never changes it, a minor release only adds to it, and only a major release removes or changes anything;
  - until then, any feature may change it.

  The human-readable and progress output carry no compatibility promise.

### Captured output

- R-012 Each piece of captured output is shown on the narrowest suite, scope or test it was captured against, else on its run: in the run's run record in the JSON output, and in the run's block in the human-readable output. Each is shown with its stream, stdout or stderr, where that is known. In the human-readable output, output captured against a suite, scope or test keeps its position among the events of the execution it was captured in. In the human-readable output, this overrides R-106 (the format ScalaTest 3.2.19 prints).
- R-122 In the JSON output, each node and run record holds the output captured against it, not only test output, whatever its source and whether or not its stream is known.
- R-088 In each of the human-readable and JSON output, each piece of output a process wrote appears at most once, even where it was captured both against a node and from the process's stdout or stderr.
- R-086 Output captured only against the run is shown without claiming that it is test output or worker output.
- R-087 Worker output is never captured against a suite, scope or test.

### Separate runs

- R-022 In the human-readable and JSON output, no suite, scope, test, run record or human-readable block of one run holds an event or captured output of another run.

## The consolidation library

- R-023 The consolidation library is published and depends on no Mill artifact.
- R-113 Given the same runs and inputs, the consolidation library produces the same human-readable and JSON output when called without Mill as when the Mill plugin calls it.

## The test command

- R-054 In a Mill build, only a test command produces consolidated output.

### Arguments

- R-055 The test command's arguments let the user, or a module that runs the test command over other modules (R-126), specify:
  - one or more module selectors, each a separate argument; `+` cannot join them, because Mill consumes it before the test command runs;
  - optional class globs and framework arguments, as Mill's `testOnly` takes them, applying alike to every selected test module;
  - whether the test command halts on the first failure (`--halt-on-failure`) or runs every selected test module (R-056);
  - whether the test command's stdout holds progress output, human-readable output, both or neither (R-048);
  - one or more files, each holding JSON, progress or human-readable output (R-049);
  - formatting and filtering for that output (R-127).
- R-126 A build can define a module that presets a test command's arguments, such as module selectors and tags, so that a `mill` command naming only that module runs that test command.

### Running the tests

A task's *task folder* is the folder Mill gives that task to write its files in.

- R-053 The test command runs each selected test module's tests as Mill's `testOnly` runs them with the test command's class globs and framework arguments, or as `testForked` runs them when no class globs are given:
  - the same tests, in the processes Mill's fork grouping, test parallelism and test queue start;
  - the same working directory and environment;
  - the effect of the test command's class globs and framework arguments, though not necessarily as the same ScalaTest arguments: a run may receive them in another form, beside arguments the test command adds.
- R-125 Each run receives those of its test module's own ScalaTest arguments (Mill's `testArgsDefault`) whose flag is `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W`, each with its values, ahead of the arguments the test command adds. Every other argument whose flag the JVM `org.scalatest.tools.Runner` of the plugin's release recognises is withheld with its values, whatever the module's runtime. Among them are every reporter argument and the JUnit and TestNG arguments. Where the module's arguments hold one whose flag that runner does not recognise, the test command fails before any test starts, naming that argument, and produces no consolidated output. This overrides R-053 (the tests run as that Mill task runs them).
- R-110 A path that Mill's `testOnly` or `testForked` puts inside its task folder is instead at the same relative path inside a folder the test command owns for that test module alone, outside the test command's own task folder. This overrides R-053 (the tests run as that Mill task runs them).
- R-111 Environment variables that Mill's launcher sets from its own command line, such as `MILL_PARAMS` from `mill.bat`, hold the `mill` command line that started the test command. This overrides R-053 (the same environment as that Mill task gives).
- R-047 The test command writes each selected test module's JUnit XML report as the Mill task R-053 names (`testOnly`, or `testForked` when no class globs are given) writes it, at the path R-110 moves it to, in the folder the test command owns for that module.
- R-092 The test command runs a selected test module's tests without the module's definition naming anything the Mill plugin provides, such as a trait to mix in.
- R-056 By default, the test command runs the tests of every selected test module, whatever happens to other modules' runs and whether or not Mill's `--keep-going` is given. Each selected test module's tests start once that module has compiled and the other tasks they need have finished. They do not wait for any other selected test module to compile or run, even after another module has failed.
- R-025 The Mill plugin gives each selected test module exactly one test run label, named for its module path. Mill keeps module paths unique.
- R-072 A runner that is not a run, such as Mill's discovery runner, does not appear in the consolidated output and does not affect whether the test command fails.

### When the test command fails

- R-046 The test command fails if:
  - any run reports a failure or does not complete: it ends aborted or stopped, or does not finish;
  - a process started to execute tests ends before any run exists in it; or
  - the tests of any selected test module cannot start, for example because it or a module it depends on fails to compile.

  Its failure does not list the failed tests.

### Each run's own output

These hold for every run a test command starts.

- R-040 Nothing the run writes to stdout or stderr reaches the console except through the consolidated output.
- R-041 Neither the reporter ScalaTest uses when no reporter is configured, nor a reporter the test module's own ScalaTest arguments configure (R-125), prints the run's events. This overrides R-002 (with the emitter on, every existing reporter receives what 3.2.19 sends).
- R-042 Output captured from the stdout and stderr of the run's processes is captured against the run.
- R-129 No line that Mill's test runner writes in the run's processes for a test class it runs, such as `Running Test Class <name>`, is captured. This overrides R-042 (output captured from the run's processes is captured against the run).
- R-043 Output captured from the run's processes' stdout keeps its order, as does output captured from their stderr, whatever its source. Its position among the run's events, and the order of stdout relative to stderr, are not guaranteed.

### Mill's and ScalaTest's own console output

- R-050 Output written by other tasks named on the same `mill` command line, and Mill's own output, is neither captured nor changed, except in the prefix Mill puts on each line of a task the test command runs. On the console it may interleave with the consolidated output.
- R-044 For each run a test command starts, the console does not show the following outside the consolidated output. For the first two, this overrides R-050 (Mill's own output is not changed):
  - Mill's list of failed tests;
  - the progress Mill's test runner shows for the tests of the run's module, such as each worker's current test class and the count of classes completed;
  - ScalaTest's end-of-run summary for the run.

  Mill's prompt may still show a line for the test command and for each task it runs, without that progress. A Mill test task named as another goal on the same `mill` command line shows its own progress.
- R-045 The consolidated output does not show Mill's list of failed tests or ScalaTest's end-of-run summary for any run a test command starts. This overrides R-021 (every piece of a run's captured output is shown).

### Where the consolidated output goes

- R-048 With no `-o` argument, the test command's stdout holds progress output and human-readable output. Instead, an `-o` names what stdout holds by its output letters: `H` for human-readable output and `P` for progress output. A bare `-o`, naming neither, leaves stdout without consolidated output.
- R-049 An `-f`, followed by an output letter and a path, writes that output to the file at that path: `J` JSON output, `H` human-readable output or `P` progress output. More than one `-f` may be given, and none changes what stdout holds (R-048).
- R-077 When the test command starts, before any test starts, it deletes or empties each file it will write consolidated output to that already exists. However the test command ends, no such file holds the result of an earlier test command.

#### Progress output on stdout

WHERE the test command's stdout holds progress output (R-048):

- R-090 Progress output on stdout is written as whole lines, never as single characters. Whether or not a summary is also shown (R-089), the lines show every failure a run reports and each other cause of failure R-046 lists for the test command, including each run that aborts or does not finish.
- R-089 Where Mill redraws its prompt in place (the status lines at the foot of the console), progress output also shows a current summary of the test command on the test command's own line of Mill's prompt, rewritten in place.
- R-091 Neither progress output's lines on stdout nor its summary on Mill's prompt erase Mill's own console output, and Mill's own console output erases neither.

## A run given no task

- R-071 A run given no task, including one whose process dies before its first task, has a run record in the JSON output, as R-080 gives every run, and a block in the human-readable output where it has any of what R-082 gives a run a block for (output captured against the run, messages attached to the run rather than to a suite, or how the run ended where it aborted, stopped or did not finish).

## Runs that do not complete

### A stopped run

- R-101 Where a run is stopped, only the run is stopped. Each test it skipped and each suite it never started is aborted with reason *not run*.

### A run that does not finish

These hold for a run that does not finish.

- R-064 The run, and each execution of a suite, scope or test that started in it without an event ending it, are aborted.
- R-065 Every scope and test registered in a suite the run constructed appears, even where the run's process died before the scope or test started. R-100 aborts each that never started, with reason *not run*.
- R-075 Where a test ended the run's process, as a call to `System.exit` does, the consolidated output shows that test and every event the run reported before the test started.
- R-067 Progress output shows that the run aborted as soon as its event stream ends.

### A run whose process failed

- R-102 A run is aborted with reason *process failed* where:
  - it does not finish, when R-064 aborts it;
  - it loses one of its processes; or
  - it reports that it completed while an execution of a suite, scope or test that started in it has no event ending it.
- R-073 When a run's process dies, these hold for everything the run had handed to the operating system: R-010 (the run writes its events, test structure and captured output to its test event log), R-011 (nothing is lost or altered on the way to the reader), R-021 (every suite, scope, test, failure and piece of captured output is shown) and R-042 (output captured from the run's processes is captured against the run). They do not cover what was still buffered inside the process.

## A process that ends before any run exists

A process started to execute tests may end before any run exists in it, as when it dies before its runner is constructed. This holds for such a process.

- R-076 On its module's test run label, the human-readable and JSON output show that the process ended before any run existed, with the output captured from the process's stdout and stderr. That output appears nowhere else, neither in a run record nor in a run's human-readable block. This overrides R-012 (captured output is shown on a suite, scope or test, else on its run).

## Not-run test classes

A *not-run test class* is a test class the requirements of this section show as not run.

A process *takes* a test class as follows:

- on the JVM, it takes each class it picks up from those of its fork group still waiting to run;
- on Scala.js and Native, it takes every test class the test command was to run for its module.

- R-095 In the human-readable and JSON output, a not-run test class is a suite node, named by its class name, aborted with reason *not run*, with no children. It is a child of its module's test run label beside the top-level suites of the label's runs (R-078) and is ordered among them by name (R-079). It names no run record. This overrides R-078 (each label's children are the top-level suites of its runs).
- R-066 Each test class that the process of a run that does not finish took, and that no run reported as a suite, is shown once, as a not-run test class.
- R-094 Each test class that a process that ended before any run existed in it took, and that no run reported as a suite, is shown once, as a not-run test class.
- R-068 Each test class of a fork group that no process had taken when the group's last process ended is shown as a not-run test class.
- R-123 A test class that never started is shown as a not-run test class.
- R-115 A not-run test class is not itself one of the causes of failure R-046 lists for the test command.

## Tests that cannot start

### The test command does not run

- R-061 IF a task Mill must complete before starting the test command fails, THEN the test command does not run and produces no consolidated output. A selected test module's compile and test tasks are not among those tasks.
- R-062 IF another goal on the same `mill` command line fails before the test command starts and Mill's `--keep-going` is not given, THEN the test command does not run and produces no consolidated output.

### A test module the test command cannot run

- R-059 IF a module selector resolves to a test module that does not test with the release of this fork whose test event logs the Mill plugin reads, or to a Scala.js test module whose JS environment is not `NodeJs` or `JsDom`, THEN the test command fails before any test starts, naming each such module, and produces no consolidated output. Examples are a module that tests with MUnit, uTest, upstream ScalaTest 3.2.19 or another release of this fork. Each file it was to write stays deleted or emptied, as R-077 left it when the test command started.

### A test module whose tests cannot start

- R-058 The consolidated output shows the test run label of each selected test module whose tests could not start as aborted, with reason *could not start* and the failure that stopped them.

## `--halt-on-failure`

WHERE `--halt-on-failure` is given:

- R-057 Once anything has failed the test command, by any cause R-046 lists, the test command starts no further selected test module's tests. This overrides R-056 (the tests of every selected test module run).
- R-114 The consolidated output shows the test run label of each selected test module whose tests R-057 skipped as aborted, with reason *not run*.

## An interrupted test command

- R-069 IF the test command is interrupted, as by Ctrl-C, THEN it writes no human-readable output and no JSON, not even a placeholder; its progress output may show runs the interrupt ended. This overrides R-027 (both are written once every run's event stream has ended). Each file of human-readable or JSON output stays deleted or emptied, as R-077 left it when the test command started.
- R-116 When the test command is interrupted, Mill's client and the operating system set its exit status. This overrides R-046 (the causes for which the test command fails).
- R-117 When the test command is interrupted, the requirements on a run that does not finish do not hold: R-064, R-065, R-075 and R-067 (what is aborted, what appears, and when progress output shows it) and R-066 (its not-run test classes).

## An unsupported Mill release

These hold where the running Mill release is not a supported Mill release.

- R-093 Before any test starts, the test command warns on the console, naming the running Mill version, and any human-readable output repeats the warning.
- R-118 The test command runs. Where a Mill internal it needs is missing, it may instead fail before any test starts, naming the Mill version and producing no consolidated output.
- R-119 No other requirement holds for the test command or its output, except that any JSON output records the Mill version and its support status, as R-081 gives for the root.
- R-120 Mill's test tasks behave as they do without the plugin (R-060), and runs launched by Metals, Bloop and IntelliJ behave as they do against ScalaTest 3.2.19 (R-003), on every Mill release the plugin runs on, supported or not.
