# Consolidated human-readable output for one JVM module

Scale: feature.

## Summary

Nothing yet turns a run's events into consolidated output: ScalaTest has no emitter, there is no consolidation library, and Mill has no test command. This feature builds the thinnest end-to-end path through all three, so that one JVM test module's tests, run in one worker, are shown as consolidated human-readable output on the test command's stdout.

## Plan

[Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54)

## Spec documents

- [specification/layout.md](specification/layout.md) — acceptance criteria for the human-readable output's layout: its tree, where each line sits, each line's text and colour, messages, collapsed subtrees, and Mill's prefix.
- [specification/runs-and-test-structure.md](specification/runs-and-test-structure.md) — acceptance criteria for which suites, scopes, tests, failures and captured output the human-readable output shows, in what order, and a run's block.
- [specification/test-command.md](specification/test-command.md) — acceptance criteria for the test command's arguments, how it runs the module, its result, what else reaches the console, and Mill's own test tasks and BSP server.
- [specification/test-event-log.md](specification/test-event-log.md) — acceptance criteria for what a run's test event log holds on the JVM, and for the ScalaTest behaviour that stays unchanged.
- [specification/compatibility-and-library.md](specification/compatibility-and-library.md) — acceptance criteria for compatibility with ScalaTest 3.2.19, the emitter on each of the JVM's Scala versions, and the consolidation library without Mill.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic's specification, which this feature is written against. `R-nnn` IDs in this specification are the epic's.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic's architecture: the modules this feature starts, their parts (§ Modules), and the boundaries between them.
- [../ROADMAP.md](../ROADMAP.md) — the epic's later features, which own what this feature leaves out.
- [../decisions/event-log-contract.md](../decisions/event-log-contract.md) — the epic's decisions on what a test event log carries and who may read it.
- [TECHNICAL-DESIGN.md](TECHNICAL-DESIGN.md) — the feature's non-obvious technical choices.
- [research/jvm-pid-and-start-time.md](research/jvm-pid-and-start-time.md) — which JDKs a Mill 1.x test worker can run on, and which readings of a process's PID and start time match `ProcessHandle`'s.
- [research/mill-plugin-conventions.md](research/mill-plugin-conventions.md) — how Mill 1.x plugins are named, loaded and tested under testkit, and how the Mill internals the plugin drives differ between Mill 1.1.10 and 1.3.1.

Terms are defined in `.docs/ubiquitous-language.md`.

## Scope

- A ScalaTest runner argument on the JVM that turns the emitter on, so the run writes its test event log to a file.
- The consolidation library, producing human-readable output, including each run's block (R-082).
- The Mill plugin's test command. Given one module selector that resolves to one JVM test module, it runs the module's tests in one worker and writes human-readable output to its stdout.
- The test command's output arguments `-oH` and a bare `-o` (R-048).
- The test command fails before any test starts, with no consolidated output, when given any other output argument, such as `-oP` or an `-f`, or module selectors other than one that resolves to one JVM test module. This narrows what R-048, R-049 and R-055 allow.
- The test command supports Mill 1.3.1 only.
- A module that tests with another framework or another release of this fork fails the test command. Unlike R-059, the failure need not come before any test starts or name the module, and consolidated output may still be written.

### Out of scope

Each is left to the named feature of `../ROADMAP.md`.

- JSON output and `-fJ` (§ 2); progress output, `P` and `-fP` (§ 9).
- The formatting and filtering characters (R-127, R-128), and `-f` for human-readable output (R-049) (§ 16).
- A module that presets a test command's arguments (R-126) (§ 17).
- More than one worker for a module, and a run given no task (R-071) (§ 3).
- Class globs and framework arguments on the test command, the module's JUnit XML report, the paths Mill's test tasks put in their task folders (R-110), and the launcher's environment variables (R-111) (§ 4).
- Running more than one module selector, or a module selector that resolves to anything other than one JVM test module (§ 5).
- A module whose tests cannot start, as when it fails to compile (R-058; R-046 third bullet) (§ 5).
- Failing a module that tests with another framework or another release of this fork before any test starts, naming it, with no consolidated output (R-059) (§ 5).
- An unsupported Mill release (R-093, R-118) (§ 6).
- `--halt-on-failure` (§ 7).
- What the consolidated output shows for a run that does not finish, or for a process that ends before any run exists in it (§ 8).
- Test output captured against a suite, scope or test (§ 10).
- The emitter, consolidated output and the test command on Scala.js and Native (§ 11–15).

## Epic requirements met

Each holds for one module selector, one JVM test module and one worker. Where a requirement carries a qualifier, only the part it names holds.

- R-001, R-002, R-051, R-003, R-060, R-004.
- R-010, R-063, R-011, R-074.
- R-096, R-097, R-098, R-099, R-100, R-104, R-105, R-106 (with no formatting or filtering character), R-107 and R-112, for human-readable output.
- R-054; R-055 (one module selector; whether stdout holds human-readable output); R-053 (second bullet, except where R-110 and R-111 override it), R-125; R-092, R-072.
- R-020 (human-readable output), R-027 (human-readable output), R-078, R-079, R-082 (first two bullets; aborted and stopped from the third), R-101, R-021, R-012 (on the run), R-086.
- R-023, R-113 (human-readable output), R-025.
- R-040, R-041, R-042, R-129, R-043, R-044, R-045.
- R-046 (first and second bullets; for a run that does not finish and a process in which no run existed, only that the test command fails).
- R-048 (human-readable output on stdout), R-124 (on stdout), R-050.
- `../SPECIFICATION.md` § Scope: the JVM's Scala versions.

## Acceptance criteria

The criteria are in the five `specification/` documents listed in § Spec documents. In them:

- The module is a JVM test module at module path `core.test`, in a build that adds the Mill plugin without changing the module's definition (R-092), and the test command runs it. Some criteria instead feed a run to the consolidation library directly, without Mill; the run's test run label is then `core.test`.
- *The runner argument* is the ScalaTest runner argument that turns the emitter on (§ Scope). A run given it outside Mill writes its test event log to a file it names.
- *An event log holding* items is, in a `Given`, a run fed directly to the consolidation library as core's decoder would yield those items, in that order. In a `Then`, it is what core's codec decodes from the run's test event log. A criterion states an event log as its items, never as bytes (`../decisions/event-log-contract.md`). The items are those of `../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, items 1–4, one per line:
  - `run started`: item 1.
  - `structure`: item 2, with the suite's tree beneath it, one node to a line, each indented 2 spaces under its parent and in declared order: `suite "<name>", class "<class name>"`, `scope "<name>", label "<label>"` or `test "<name>", label "<label>"`. Each node's ID, parent and declared position follow from the tree.
  - `<node>: <event>`: item 3, an `org.scalatest.events` event tagged with the node, named by kind and name as `test "is empty"`, or with `run` where it is the run's. Where nodes of one kind share a name, each adds `#<n>`, counting them in the order the event log's structures hold them: `scope "A Stack" #2`. The event is its class, followed in parentheses by the fields the criterion fixes, a throwable written as its class name and message: `TestFailed(throwable: java.lang.IllegalStateException "outer")`.
  - `run ended <how>`: item 4, where `<how>` is `completed`, `aborted` or `stopped`.

  An event log *is* the items given, and no others. It *holds* items *in this order* when it holds each, in that order, with any others between them.
- *The console* is what the `mill` command writes to its stdout and stderr. A line of it is *outside the human-readable output* when it is not a line of the human-readable output.
- A line is given without Mill's prefix (`specification/layout.md` AC-044). A line *sits under* another when it follows it, indented 2 spaces more, with no line between them indented as little as the other. *The lines for* a node are its own lines and every line under them.
- Text and colours the criteria do not fix are ScalaTest 3.2.19's (R-106). Colour is on (R-124).
- "With every stack frame left out" drops each line under a throwable's first line or `Cause:` line.
- *The compatibility fixture* is a set of suites, at least one of each style `specification/test-event-log.md` AC-103 names that the build for the fixture's runtime and Scala version ships, holding between them:
  - nested suites;
  - scopes, one of them pending;
  - tests that succeed, fail, are ignored, are pending and are cancelled;
  - `info`, `note`, `alert` and `markup` messages from suites, scopes and tests;
  - a suite whose `beforeAll` throws;
  - a test that writes to stdout and to stderr.
