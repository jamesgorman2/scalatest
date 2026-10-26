# Ubiquitous language

This repo is a fork of ScalaTest that keeps test output readable, by people and by tools, on parallel and multi-module runs by reporting the nested structure of tests. This glossary fixes the terms its planning and code use.

## Runs and modules

**Run**:
One ScalaTest execution, from start to completion: one `Runner` run, or everything one `Framework` runner executes before its `done()`. On Scala.js and Native, one run spans a controller process and the worker processes the build tool starts for it.
_Avoid_: Build, test session, invocation

**Module**:
A build module whose tests produce one or more runs, for example one per platform or per forked JVM. In consolidated output, a module groups its runs.
_Avoid_: Project, subproject, target

## Test structure

**Test structure**:
The tree of nodes a run reports: the run at its root, containing suites, which contain nested suites, scopes and tests.
_Avoid_: Test tree, hierarchy, nesting

**Node**:
A run, suite, nested suite, scope or test in the test structure; the run is the root of its own test structure.
_Avoid_: Element, item, entry, test case

## Output

**Progress output**:
The human-readable account of a run while it is running: what has started and finished, and optionally pass and fail counts.
_Avoid_: Status, ticker, log

**Results output**:
The full human-readable account of a run's, or a consolidation's, nodes and their outcomes, shown at completion.
_Avoid_: Report, summary

**Test output**:
The stdout and stderr written by tests while they run.
_Avoid_: Logs, console output, captured output

## Consolidation

**Consolidation**:
Merging the JSON of many runs into one set of outputs covering all of them.
_Avoid_: Aggregation, merging, report generation

**Consolidator**:
The tool that performs consolidation.
_Avoid_: Aggregator, merger, report generator
