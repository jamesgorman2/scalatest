# Ubiquitous language

This repo is a fork of ScalaTest that keeps test output readable, by people and by tools, on parallel and multi-module runs by reporting the nested structure of tests. This glossary fixes the terms its planning and code use.

## Runs and modules

**Run**:
One ScalaTest execution, from start to completion: one `Runner` run, or everything one `Framework` runner executes before its `done()`. On Scala.js and Native, one run spans a controller process and the worker processes that serve it.
_Avoid_: Build, test session, invocation

**Run ID**:
The identifier that distinguishes one run's JSON from every other run's, including runs of the same module, platform or process.
_Avoid_: Run stamp, session ID

**Module**:
A build tool's unit of build, such as a Mill module or an sbt project, whose tests produce one or more runs, for example one per test-worker JVM. It is not a unit of consolidation; the Mill plugin and the sbt recipe give each module one test run label by default.
_Avoid_: Project, subproject, target

## Test structure

**Test structure**:
The tree of nodes a run reports: the run at its root, containing suites, which contain nested suites, scopes and tests.
_Avoid_: Test tree, hierarchy, nesting

**Node**:
A run, suite, nested suite, scope or test in the test structure; the run is the root of its own test structure.
_Avoid_: Element, item, entry, test case

## Output

**Test output**:
The stdout and stderr written on a thread while one of a run's nodes, or the run's own code, is running on it.
_Avoid_: Logs, console output, captured output

## Consolidation

**Consolidation**:
Bringing things together into a single place
_Avoid_: Aggregation, merging, report generation

**Test run label**:
The name a consolidator's caller gives a list of JSON files, unique within one consolidation, under which those files' runs are merged. It tells apart runs whose JSON is otherwise indistinguishable, such as the same suites run on the JVM, Scala.js and Native.
_Avoid_: Module, module name, group, tag
