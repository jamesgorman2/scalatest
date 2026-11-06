# Ubiquitous language

This repo is a fork of ScalaTest that keeps test output readable, by people and by tools, on parallel and multi-module runs by reporting the nested structure of tests. This glossary fixes the terms its planning and code use.

## Runs and modules

**Run**:
One ScalaTest execution, from start to completion: one `Runner` run, or everything one `Framework` runner executes before its `done()`. On Scala.js and Native, one run spans a controller process and the worker processes that serve it.
_Avoid_: Build, test session, invocation

**Runtime**:
The platform a run executes on: the JVM, Scala.js or Native.
_Avoid_: Platform, target, backend

**Execution mode**:
How a run schedules its suites and tests: *sequential*, one after another on one thread, or *concurrent*, on several threads at once; under `Runner`, a concurrent run may also sort its events by suite.
_Avoid_: Parallelism level, run mode

**Module**:
A build tool's unit of build, such as a Mill module or an sbt project, whose tests produce one or more runs, for example one per test-worker JVM. It is not a unit of consolidation; the Mill plugin and the sbt recipe give each module one test run label by default.
_Avoid_: Project, subproject, target

## Test structure

**Test structure**:
The tree of nodes a run reports: the run at its root, containing suites, which contain nested suites, scopes and tests.
_Avoid_: Test tree, hierarchy, nesting

**Node**:
A run, suite, nested suite, scope or test in the test structure; the run is the root of its own test structure. In consolidated output, a test run label is also a node.
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
How a node ended. Succeeded, failed, cancelled and ignored belong to tests alone; pending belongs to tests and scopes; aborted may belong to any node; stopped belongs to the run node alone.
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
The stdout and stderr written on a thread while one of a run's nodes, or the run's own code, is running on it.
_Avoid_: Logs, console output, captured output

**Worker output**:
The stdout and stderr of the processes executing a run that is not test output, such as a JVM warning or a write from native code. It belongs to the run as a whole, never to a node within it.
_Avoid_: Other output, raw output, leftover output

## Consolidation

**Consolidation**:
Bringing things together into a single place.
_Avoid_: Aggregation, merging, report generation

**Test run label**:
A node in consolidated output that wraps the suites of the runs its caller names together, named by the caller and unique within one consolidation. It tells apart runs that are otherwise indistinguishable, such as the same suites run on the JVM, Scala.js and Native.
_Avoid_: Module, module name, group, tag
