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
A build tool's unit of build, such as a Mill module or an sbt project, whose tests produce one or more runs, for example one per test-worker JVM. It is not a unit of consolidation; the Mill plugin and the sbt recipe give each module one test run label.
_Avoid_: Project, subproject, target

## Test structure

**Test structure**:
The tree of a run's nodes as its tests are written, however many times each ran: the run at its root, containing suites, which contain nested suites, scopes and tests.
_Avoid_: Test tree, hierarchy, nesting

**Execution**:
One running of a suite, scope or test within a run. A node has one for each time it ran, and none where it never started.
_Avoid_: Attempt, invocation, retry
_Context_: A node of the test structure; unrelated to execution mode

**Node**:
A run, suite, nested suite, scope or test in the test structure; the run is the root of its own test structure. In consolidated output, the consolidation is the root, a test run label is also a node, and a run is not: its top-level suites sit directly under its test run label.
_Avoid_: Element, item, entry, test case

**Name**:
A node's own text as written in the source file: a suite's suite name, or a scope's or test's text without the text of the scopes around it. ScalaTest's test name, which joins a test's scopes' text with its own, is not a node's name.
_Avoid_: Test name, full name, text
_Context_: A node of the test structure

**Label**:
A scope's or test's name as ScalaTest's formatter renders it, such as `when b` or `- should c`, without its indentation. Suites and the run node have none. It is not a test run label.
_Avoid_: Formatted name, display name
_Context_: A node of the test structure

**Outcome**:
How a node ended. Succeeded, failed, cancelled and ignored belong to tests alone; pending belongs to tests and scopes; aborted may belong to any node or run record; stopped belongs to a run alone, held on its run node or, in consolidated output, its run record.
_Avoid_: Result, status, verdict

**Declared order**:
The order in which a sequential run reports a node's children, except that the tests of a suite mixing in `RandomTestOrder` keep the order in which the suite registers them. For top-level suites that never start, it is the order in which the run would have started them.
_Avoid_: Source order

## Output

**`J` reporter**:
An `-o`, `-e` or `-f` reporter whose configuration characters include `J`, so that it writes JSON in place of text.
_Avoid_: JSON lines reporter

**Event-filter characters**:
The reporter configuration characters `N C X E H L O P Q R M`, each of which drops one kind of event from what the reporter writes.
_Avoid_: Filter flags, suppression characters

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

**Event stream**:
One run's events, test structure and the output it captures, in the order the run produced them, independent of how they are carried.
_Avoid_: Event log, event feed

**Test event log**:
The written record of one run's event stream, as the run wrote it and before it is read back into an event stream. Shortened to "event log" where nothing else could be meant.
_Avoid_: Channel, event stream, event file

**Does not finish**:
Said of a run whose event stream ends without recording how the run ended, completed, aborted or stopped, as when its process dies.

**Test manifest**:
The test classes one test command is to run for one module, each either taken by one or more of the module's test processes or not yet taken by any. It names classes only, not their nested suites, scopes or tests. Shortened to "manifest" where nothing else could be meant.
_Avoid_: Test plan, class list, expected tests

**Take**:
A process's claim on a test class of its module's test manifest. On the JVM, a process takes each class it picks up from those of its fork group still waiting to run; on Scala.js and Native, it takes every test class the test command was to run for its module.
_Context_: A test process and a test class

**Not-run test class**:
A test class the test command was to run that the human-readable and JSON output show as not run, because no run reported it as a suite: a suite node named by its class name, aborted with reason *not run*, with no children, that names no run record.

**Test run label**:
A node in consolidated output that wraps the suites of the runs its caller names together, named by the caller and unique within one consolidation. It tells apart runs that are otherwise indistinguishable, such as the same suites run on the JVM, Scala.js and Native.
_Avoid_: Module, module name, group, tag

**Run record**:
One run's own state in consolidated JSON output, such as its outcome, timings, summary counts and the output captured against the run, held in a list on the run's test run label. Each of the run's suites names its run record.
_Avoid_: Run node, worker record

**Test command**:
One evaluation of the command the Mill plugin adds for consolidated testing, covering the runs of every test module its module selectors resolve to. Under `--watch`, each re-evaluation is a separate test command.
_Avoid_: Mill command, invocation

**Task folder**:
The folder Mill gives a task to write its files in.

**Supported Mill release**:
A Mill release on which the Mill plugin's integration tests passed, as listed by the running release of this fork. Any other Mill release is unsupported, even within the same major version.
_Avoid_: Compatible Mill version, Mill version range

**Module selector**:
An argument of the test command that names test modules, such as `__.test` or `foo.test`.
_Avoid_: Selector, task selector
