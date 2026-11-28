# Ubiquitous language

This repo is a fork of ScalaTest that keeps test output readable, by people and by tools, on parallel and multi-module runs by reporting the nested structure of tests. This glossary fixes the terms its planning and code use.

## Runs and modules

**Run**:
One ScalaTest execution of tests: one `Runner` run, or the execution by one `Framework` runner constructed to execute tests, whether or not it is given a task. On Scala.js and Native, one run spans a controller process and the worker processes that serve it.
_Avoid_: Build, test session, invocation

**Runtime**:
The platform a run executes on: the JVM, Scala.js or Native.
_Avoid_: Platform, target, backend

**JS environment**:
The JavaScript host a Scala.js run executes in, such as Node, jsdom or a browser. Under Mill, it is the `JsEnvConfig` case that a module's `jsEnvConfig` selects, such as `NodeJs` or `JsDom`.
_Avoid_: JS env, JS runtime

**Execution mode**:
How a run schedules its suites and tests: *sequential*, one after another on one thread, or *concurrent*, on several threads at once; under `Runner`, a concurrent run may also sort its events by suite.
_Avoid_: Parallelism level, run mode

**Module**:
A build tool's unit of build, such as a Mill module or an sbt project, whose tests produce one or more runs, for example one per test-worker JVM.
_Avoid_: Project, subproject, target

**Module name**:
A module's name as its build tool writes it, such as `core.test` for a Mill module.
_Avoid_: Label, test run label

**Worker**:
A JVM process that Mill starts to run some of a module's test classes. A worker hosts at most one run.
_Avoid_: Fork, test JVM, subprocess

**Fork group**:
One of the sets of test classes a Mill module's `testForkGrouping` divides its tests into. Only the workers of a fork group run that group's classes.
_Avoid_: Test group, partition

**Assigned test classes**:
The test classes a process running tests was to run: on the JVM, those its worker claimed from its fork group's queue or, where the module sets `testBatchFrameworkTasks`, the whole class list its worker was given; on Scala.js and Native, every test class of the module. A run *ran* an assigned test class where it reported a suite of that class.
_Avoid_: Claimed classes, scheduled classes

## Test structure

**Test structure**:
The tree of a run's nodes as its tests are written, however many times each ran: the run at its root, containing suites, which contain nested suites, scopes and tests. A test also contains the suites it ran.
_Avoid_: Test tree, hierarchy, nesting

**Execution**:
One running of a suite, scope or test at its place, inside one execution of its parent.
_Avoid_: Attempt, invocation, retry
_Context_: A node of the test structure; unrelated to execution mode

**Node**:
An element in the test structure hierarchy as it is planned and/or executed. A single declaration (eg, suite, scope, test) may be referenced by multiple nodes when it is executed multiple times.
_Avoid_: Element, item, entry, test case

**Node path**:
A node's ID in its run's test event log: its parent's node path followed by the node's place under that parent. Stable for the run, so the node's `structure` and events name it alike.
_Avoid_: Invocation path, minted ID

**Name**:
A node's own text as written in the source file: a suite's suite name, or a scope's or test's text without the text of the scopes around it. ScalaTest's test name, which joins a test's scopes' text with its own, is not a node's name.
_Avoid_: Test name, full name, text
_Context_: A node of the test structure

**Label**:
A scope's or test's name as ScalaTest's formatter renders it, such as `when b` or `- should c`, without its indentation. Suites and the run node have none.
_Avoid_: Formatted name, display name
_Context_: A node of the test structure

**Outcome**:
How a node ended. Succeeded, failed, cancelled and ignored belong to tests alone; pending belongs to tests and scopes; aborted may belong to any node or run record; stopped belongs to a run alone, held on its run node or, in consolidated output, its run record.
_Avoid_: Result, status, verdict

**Declared order**:
The order in which a sequential run reports a node's children, except that the tests of a suite mixing in `RandomTestOrder` keep the order in which the suite registers them. For top-level suites that never start, it is the order in which the run would have started them.
_Avoid_: Source order

## Output

**Reporter**:
Anything that reports on a run's events through ScalaTest's `Reporter`: a reporter a runner argument such as `-o`, `-f` or `-C` configures, a reporter ScalaTest adds itself, or the emitter. A `-C` reporter is one kind, not the meaning.
_Avoid_: Listener

**Emitter**:
The reporter that writes a run's test event log when the run is given the runner argument. It receives each event as core sends it, before any other reporter reorders it.
_Avoid_: Event log reporter, writer

**`J` reporter**:
An `-o`, `-e` or `-f` reporter whose configuration characters include `J`, so that it writes JSON in place of text.
_Avoid_: JSON lines reporter

**Event-filter characters**:
The reporter configuration characters `N C X E H L O P Q R M`, each of which drops one kind of event from what the reporter writes.
_Avoid_: Filter flags, suppression characters

**Output argument**:
An `-o` or `-f` argument of the test command: an `-o` names the test command's stdout, and an `-f` names the file at the path that follows it.
_Avoid_: Output flag, output option, reporter argument

**Output letter**:
One of the upper-case characters `H`, `P` and `J` on an output argument, which select the human-readable, progress and JSON output that the argument writes. They are not ScalaTest reporter configuration characters.
_Avoid_: Output format, format letter

**Formatting characters**:
The characters `w` and `d` on an output argument, each of which changes how that argument's output is written, such as without colour or with durations. They are not ScalaTest reporter configuration characters.
_Avoid_: Format flags, formatting options

**Filtering characters**:
The characters `c`, `x`, `e`, `o`, `m` and `r` on an output argument, each of which omits one kind of node or message from that argument's output. They are distinct from ScalaTest's event-filter characters, which drop events from what a reporter writes.
_Avoid_: Event-filter characters, filter characters, filter flags

**Test output**:
Output written from within a suite, scope or test, such as logging. It excludes the test framework's own reporting and I/O that is the behaviour under test or that the test itself captures. It does not depend on the thread, stream or code that writes it, or on whether it can be captured.
_Avoid_: Logs, console output

**Worker output**:
Output from the test harness executing a run, such as a JVM warning or what the build tool's test runner writes. It excludes the test framework's own reporting and comes from no suite, scope or test. It does not depend on whether it can be captured or attributed.
_Avoid_: Other output, raw output, leftover output

**Captured against**:
How captured output relates to a node: output is captured against a suite, scope or test when the means that captured it ties it to that node, and against the run when that means ties it only to the run.

**Captured from**:
How captured output relates to a process's stdout or stderr: output is captured from that stream when it is read from the stream from outside the process.

## Consolidation

**Consolidation**:
Bringing things together into a single place.
_Avoid_: Aggregation, merging, report generation

**Consolidation state**:
Whether a consolidation has ended and whether anything has failed its test command: *open* or *open, failed* until the consolidation ends, then *ended* or *ended, failed*. Once failed, it stays failed.
_Avoid_: Result, status, running, complete

**Event stream**:
One run's events, test structure and the output it captures, in the order the run produced them, independent of how they are carried.
_Avoid_: Event log, event feed

**Test event log**:
The written record of one run's event stream, as the run wrote it and before it is read back into an event stream. Shortened to "event log" where nothing else could be meant.
_Avoid_: Channel, event stream, event file

**Does not finish**:
Said of a run whose event stream ends without recording how the run ended, completed, aborted or stopped, as when its process dies.

**Test manifest**:
The test classes one test command is to run for one module, each either claimed (picked up to run) by one or more of the module's test processes or not yet claimed by any. It names classes only, not their nested suites, scopes or tests. Shortened to "manifest" where nothing else could be meant.
_Avoid_: Test plan, class list, expected tests

**Test command**:
One evaluation of the command the Mill plugin adds for consolidated testing, covering the runs of every test module its module selectors resolve to. Under `--watch`, each re-evaluation is a separate test command.
_Avoid_: Mill command, invocation

**Task folder**:
The folder Mill gives a task to write its files in.

**Module folder**:
The folder a test command owns for one test module, in Mill's output folder.
_Avoid_: Module's output folder, module dest

**Supported Mill release**:
A Mill release on which the Mill plugin's integration tests passed, as listed by the running release of this fork. Any other Mill release is unsupported, even within the same major version.
_Avoid_: Compatible Mill version, Mill version range

**Module selector**:
An argument of the test command that names test modules, such as `__.test` or `foo.test`.
_Avoid_: Selector, task selector

**Class glob**:
An argument of the test command after its first `--` and before any second `--`, as Mill's `testOnly` reads its arguments. In each test module the test command selects, the class glob keeps each test class whose fully qualified name it matches. Each `*` in it matches any run of characters, including none.
_Avoid_: Class selector, test selector, class filter

**Framework argument**:
An argument of the test command after its second `--`, as Mill's `testOnly` reads its arguments. Every run of each test module the test command selects receives the test command's framework arguments.
_Avoid_: Test argument
