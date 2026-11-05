# Structured JSON from `Runner`

Scale: feature.

## Summary

ScalaTest 3.2.19's `J` reporters write one JSON line per event, and no event names its node's parent, so a tool reading that output cannot recover the nesting once suites or tests run in parallel. Under `Runner`, this feature makes every `J` reporter write one JSON object per run holding the run's whole test structure, while every other reporter receives exactly what 3.2.19 sends.

## Plan

[Plan: structured JSON from `Runner`](https://github.com/jamesgorman2/scalatest/issues/18)

## Spec documents

- [SPECIFICATION-DECISIONS.md](SPECIFICATION-DECISIONS.md) — why a suite whose constructor throws is aborted in the test structure only, and why a halted run lists no suite that only an unbound `-z` or `-t` would select.
- [../SPECIFICATION.md](../SPECIFICATION.md) — the epic specification this feature is written against; its § Test structure and § JSON output fix what the object holds.
- [../SPECIFICATION-DECISIONS.md](../SPECIFICATION-DECISIONS.md) — the epic's decisions this feature carries: filter characters drop only data, and the format has no version of its own.
- [../ARCHITECTURE.md](../ARCHITECTURE.md) — the epic architecture this feature builds under `Runner`.

Terms are those of `.docs/ubiquitous-language.md`.

## Scope

Under `org.scalatest.tools.Runner` on the JVM, on every Scala version in `../SPECIFICATION.md` § Scope, every `J` reporter writes the object of § The JSON object in place of 3.2.19's one line per event. No other reporter changes.

A run here is one call of `Runner.main` or `Runner.run`, including those IntelliJ's forked mode and `ScalaTestAntTask` make, or one run that the GUI reporter (`-g`) starts with its Run button.

`Runner`'s arguments select the execution mode:

- *sequential* — no `-P` argument; top-level suites run one after another on the calling thread.
- *concurrent* — `-P`, `-P<n>` or `-PC`; top-level suites and nested suites run on a thread pool, as do the tests of a suite mixing in `ParallelTestExecution`.
- *concurrent with suite sorting* — `-PS`, `-PS<n>`, `-PSC` or `-PCS`; as concurrent, with events ordered by suite through `SuiteSortingReporter`.

Within every mode, suites may be asynchronous or mix in `ParallelTestExecution`, `RandomTestOrder`, `SequentialNestedSuiteExecution` or `StepwiseNestedSuiteExecution`, and `-W` may turn on slowpoke alerts.

A run may select suites with:

- `-s`, alone or with `-z`, `-t` or `-i`;
- `-s` with a glob;
- `-A`, `-j`, `-b`, `-m` or `-w`;
- `-z` or `-t` without `-s`;
- none of these, which discovers every suite on the `-R` runpath, narrowed by `-q`.

It may filter by tag with `-n` and `-l`.

A *message* is an `InfoProvided`, `AlertProvided`, `NoteProvided` or `MarkupProvided` event, emitted on its own or recorded in a test's closing event.

### Out of scope

- The human-readable block and progress output (`../ROADMAP.md` § 2 and § 3).
- Test output in the JSON (`../ROADMAP.md` § 7).
- `RunAborted` in place of `RunCompleted` when a run finishes with nodes unfinished, the *process failed* reason, the `-fJ` marker and shutdown hook, and *not run* nodes other than those of AC-024 to AC-026 (`../ROADMAP.md` § 5).
- Which top-level suites appear when `Runner` aborts a run before `RunStarting` for any cause but a suite's constructor throwing, such as an `-s` class that cannot be loaded or is not a suite, a JUnit or TestNG wrapper that fails to load, or an `expectedTestCount` that throws (`../ROADMAP.md` § 5).
- The JVM `Framework`, sbt's forked mode, Scala.js and Scala Native.
- The `org.scalatest.tools.Runner.forever` system property, whose `-P` loop never ends a run.
- An event's payload.

## Constraints

Under `Runner`, this feature holds `../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x and the **Forked** bullet of § Constraints § IntelliJ. § Acceptance criteria, Rule *Existing reporters receive what 3.2.19 sends*, tests them.

In this document, *3.2.19* means `org.scalatest:scalatest` 3.2.19 run with the same arguments over the same compiled suites. A reporter *receives what 3.2.19 sends* when it receives the events 3.2.19 sends it, with the same field values apart from timestamps, durations and thread names, in this order:

- in a sequential run, the same sequence;
- in a concurrent run, an order 3.2.19 could deliver them, with each suite's events in the order 3.2.19 delivers that suite's.

- R-001 The object is JSON text as RFC 8259 defines it (https://www.rfc-editor.org/rfc/rfc8259), and a `-fJ` file is encoded in UTF-8, which RFC 8259 § 8.1 requires of JSON "exchanged between systems that are not part of a closed ecosystem".
- R-012 This feature changes no launch path other than `Runner`: on each, every reporter receives what 3.2.19 sends, and each `J` reporter writes what the same reporter writes in 3.2.19.

## The JSON object

- R-002 The object holds the run's test structure (`../SPECIFICATION.md` § Test structure), rooted at the run node. Every node states its kind — run, suite, scope or test.
- R-003 A node's children and messages form one sequence. Its children appear in declared order. Each message appears after every child of its node that the run had reached when the message was emitted, whether that child then ran at once, concurrently or later, and before every other child. Its messages keep the order in which they were emitted. This holds in every execution mode.
- R-004 For every event attached to a node and passed by the reporter's event filter (R-009), the object holds every field value of that event apart from its payload, with throwables as R-006 records them and each config-map value as its `toString`.
- R-005 Every event the run sends its reporters contributes its data, as far as R-009 lets it, to exactly one node, and no two events or nodes are merged. The one exception is the `RunAborted` that a suite's throwing constructor causes (AC-023, AC-025, AC-026), which also gives its message and throwable to the suite that threw. The `SuiteStarting` and `SuiteCompleted` of a `DiscoverySuite` contribute nothing, and a `DistributedTestRunnerSuite` is not a node: its `SuiteStarting` and `SuiteCompleted` contribute nothing, and a `SuiteAborted` it sends contributes to the one test it ran (AC-052).
- R-011 `RunStarting`, `RunCompleted`, `RunAborted`, `RunStopped`, `DiscoveryStarting` and `DiscoveryCompleted` attach to the run node.
- R-006 A throwable is recorded with:
  - its class name;
  - its `getMessage`, absent when `null`;
  - for a `StackDepthException`, its `failedCodeFileName` and `failedCodeLineNumber`, each where defined;
  - every frame of its stack trace, each with its class name, method name, file name and line number;
  - its cause and its suppressed exceptions, in order, each recorded the same way, except that a throwable already recorded within the same event's throwable is recorded as a reference to that record, so a cycle ends.
- R-007 The root records the ScalaTest version that `Runner -v` prints, and the reporter's event-filter characters.
- R-008 Configuration characters other than the event-filter characters do not change the object.
- R-009 On a `J` reporter, the events whose data the object holds are exactly those that 3.2.19's `FilterReporter` passes for the reporter's event-filter characters, with each test's recorded events filtered as it filters them. A node's kind, its name (a suite's suite name, a scope's text, a test's name) and its place in the test structure are present whatever the characters.
- R-010 The fork's documentation describes the format for third-party readers: every field, what it means, when it is empty or absent, the effect of each event-filter character, and the compatibility promise of `../SPECIFICATION.md` § JSON output.

## Acceptance criteria

```gherkin
Feature: Structured JSON from Runner
  Every J reporter under Runner writes one JSON object per run holding the run's test structure.

  Background:
    Given a reporter class Rec, passed with -C, that records every event it receives
    And suites compiled once and run both by this fork and by 3.2.19

  Rule: Each J reporter writes one object per run, at its end

    Scenario Outline: AC-001 One indented object
      Given a run with arguments "<reporter>" and suites that pass, fail and are ignored
      When the run ends
      Then the reporter has written exactly one JSON object to <destination>, indented to show its nesting
      And it wrote that object after the run's RunCompleted, RunAborted or RunStopped, and before Runner.run returned
      Examples:
        | reporter     | destination                                |
        | -fJ out.json | out.json, which holds nothing else         |
        | -oJ          | stdout                                     |
        | -eJ          | stderr                                     |

    Scenario: AC-002 Several J reporters each write their own object
      Given a run with arguments "-oJ -fJ a.json -fJN b.json"
      When the run ends
      Then stdout, a.json and b.json each hold one object with the same nodes, kinds, names and order
      And the object in b.json records filter character N and the others record none

    Scenario: AC-003 An existing file is replaced whole
      Given out.json exists holding content X
      When a run with "-fJ out.json" ends
      Then out.json holds only the run's object
      And a reader opening out.json at any moment during the run reads either X or a whole object, never part of one
      And no other file the run created remains in the directory of out.json

    Scenario: AC-051 A run the GUI's Run button starts writes its own object
      Given the run that "-g -fJ out.json" starts has ended
      When the user presses the GUI's Run button and that run ends
      Then out.json holds exactly one object, that of the run the Run button started

    Scenario: AC-050 A rerun the GUI starts from a selected event writes nothing
      A rerun is one started from the GUI by selecting an event and pressing Rerun.
      Given a run with "-g -oJ -fJ out.json" has ended, and out.json holds that run's object X
      When the user reruns a selected suite or test from the GUI
      Then neither J reporter the rerun creates writes anything
      And out.json still holds X

  Rule: The root records the writing version and the reporter's filter characters

    Scenario Outline: AC-004 Version and filter characters
      When a run with "<reporter> out.json" ends
      Then the root of out.json records the version that "Runner -v" prints
      And it records the filter characters <recorded>
      Examples:
        | reporter | recorded |
        | -fJ      | none     |
        | -fJNC    | N and C  |
        | -fWDJHL  | H and L  |

  Rule: The object holds the run's whole test structure

    Scenario Outline: AC-005 Suites, nested suites, scopes and tests, in every execution mode
      Given suite A, mixing in <trait>, nests suites B and C, in that order, and registers scope "s" holding tests "t1" and "t2"
      And B registers test "u"
      When a run with "-s A -fJ out.json <mode>" ends
      Then the root of out.json is a run node whose only child is suite A
      And A's children are B, C and scope "s" in declared order
      And "s" holds tests "t1" and "t2" in that order, and B holds test "u"
      And each node states its kind
      Examples:
        | trait                          | mode |
        | no trait                       |      |
        | no trait                       | -P   |
        | no trait                       | -P4  |
        | no trait                       | -PC  |
        | no trait                       | -PS  |
        | no trait                       | -PS4 |
        | no trait                       | -PSC |
        | SequentialNestedSuiteExecution | -P   |
        | StepwiseNestedSuiteExecution   | -P   |

    Scenario Outline: AC-006 Discovered suites are top-level; DiscoverySuite is not a node
      Given the runpath holds suites p.A and p.q.B
      When a run with "-fJ out.json <selection>" ends
      Then the root's children are exactly the suites <children>, each with the run node as its parent
      And no node in out.json is a DiscoverySuite
      Examples:
        | selection | children      |
        |           | p.A and p.q.B |
        | -m p      | p.A           |
        | -w p      | p.A and p.q.B |

    Scenario: AC-007 Nodes that share a name are kept apart
      Given suite A nests two instances of suite B, and registers two sibling scopes both named "s"
      When a run with "-s A -fJ out.json" ends
      Then A has two suite nodes named B and two scope nodes named "s", each with its own children

    Scenario: AC-008 Tests that a filter excludes do not appear
      Given suite A has test "t1" tagged Slow, test "t2", and ignored test "t3"
      When a run with "-s A -l Slow -fJ out.json" ends
      Then A holds tests "t2" and "t3", and no node for "t1"
      And a run with "-s A -z t2 -fJ out.json" gives A only the test "t2"

    Scenario Outline: AC-009 Tests run concurrently or out of order stay under their own scope
      Given suite A <kind> and registers scope "s1" holding tests "t1" and "t2", and scope "s2" holding test "t3"
      When a run with "-s A -P -fJ out.json" ends
      Then "s1" holds "t1" and "t2", in that order, and "s2" holds "t3"
      And no node in out.json is a DistributedTestRunnerSuite
      Examples:
        | kind                           |
        | mixes in ParallelTestExecution |
        | mixes in RandomTestOrder       |
        | is an AsyncFunSpec             |

  Rule: A node's outcome comes from the event that closes it
    No outcome is rolled up from a node's children.
```

| ID     | Event that closes the node                           | Node                    | Outcome     |
|--------|------------------------------------------------------|-------------------------|-------------|
| AC-010 | `TestSucceeded`                                      | test                    | succeeded   |
| AC-011 | `TestFailed`                                         | test                    | failed      |
| AC-012 | `TestCanceled`                                       | test                    | cancelled   |
| AC-013 | `TestIgnored`                                        | test                    | ignored     |
| AC-014 | `TestPending`                                        | test                    | pending     |
| AC-015 | `ScopePending`                                       | scope                   | pending     |
| AC-016 | `SuiteAborted`                                       | suite or nested suite   | aborted     |
| AC-017 | `RunAborted`                                         | run node                | aborted     |
| AC-018 | `RunStopped`                                         | run node                | stopped     |
| AC-019 | `ScopeClosed`, `SuiteCompleted`, `RunCompleted`      | scope, suite, run node  | none        |

An aborted or failed node carries its closing event's message and throwable (R-004).

```gherkin
    Scenario: AC-052 A test's failure outside its body aborts the test, not its suite
      Given suite A mixes in ParallelTestExecution and a beforeEach that throws a RuntimeException for test "t"
      When a run with "-s A -P -C Rec -fJ out.json" ends
      Then "t" is aborted, with the message and throwable of the SuiteAborted named "DistributedTestRunnerSuite" that 3.2.19 sends
      And "t" has no TestStarting data
      And A has no outcome, and no node in out.json is a DistributedTestRunnerSuite
      And Rec receives what 3.2.19 sends, including that SuiteAborted and A's SuiteCompleted
```

```gherkin
  Rule: Nodes left open when their suite or run aborts are aborted in the structure only

    Scenario Outline: AC-020 A suite aborts with a node open
      Given suite A <aborts>
      When a run with "-s A -C Rec -fJ out.json" ends
      Then A is aborted, with the SuiteAborted's message and throwable
      And <open> is aborted, carrying no message or throwable of its own
      And Rec receives what 3.2.19 sends, with no <closing>, ending in RunCompleted
      Examples:
        | aborts                                                                                                                   | open      | closing                |
        | is an AnyFunSpec mixing in BeforeAndAfterEach, whose beforeEach throws a RuntimeException before "t", the only test in scope "s" | scope "s" | ScopeClosed for "s"    |
        | is an AsyncFunSpec whose test "t", in scope "s", returns a Future failed with a StackOverflowError                         | test "t"  | closing event for "t"  |

    Scenario: AC-021 A run aborts with nodes open
      Given test "t" in scope "s" of suite A throws a StackOverflowError
      When a sequential run with "-s A -C Rec -fJ out.json" ends
      Then the run node is aborted, with the RunAborted's message and throwable
      And A, "s" and "t" are aborted, carrying no message or throwable of their own
      And Rec receives what 3.2.19 sends, ending in RunAborted

  Rule: When Runner constructs a suite whose constructor throws, that suite is aborted in the test structure only, and every other top-level suite is aborted, not run
    A suite is named here as DeferredAbortedSuite names one: for class p.B, suite name "B" (the simple
    name, with "$" removed) and suite ID and class name "p.B". A @WrapWith suite is named by the
    annotated class, a -j suite by its JUnit class, and the -b suite as its TestNGWrapperSuite names itself.

    Scenario: AC-022 Existing reporters receive what 3.2.19 sends
      Given suites p.A, p.B and p.C, where p.B's constructor throws E
      When a run with "-s p.A -s p.B -s p.C -C Rec -fJ out.json" ends
      Then Rec receives what 3.2.19 sends: a RunAborted and no SuiteAborted

    Scenario: AC-023 The suite that threw is aborted with the run's throwable
      Given the run of AC-022
      Then the run node is aborted, with the RunAborted's message and throwable
      And the root's children are p.A, p.B and p.C, in that order
      And p.B is a suite named as above, aborted with the RunAborted's message and throwable

    Scenario: AC-024 Every other top-level suite is aborted, not run
      Given the run of AC-022
      Then p.A, which was constructed, is named by its own suite name and suite ID
      And p.C, which was not, is named as above
      And p.A and p.C are aborted with reason "not run"

    Scenario Outline: AC-025 Every suite the arguments name, or select from the runpath's suite names, is listed
      Listed after the suite that threw, in this order: every later -s and -A suite; every -j class; the suites
      that -m, -w and -s globs select from the runpath's suite names; the -b suite. A suite that only a -z
      or -t not bound to an -s would select is not listed.
      Given p.B's constructor throws, the runpath holds suites p.A, p.B, q.D and q.E, JUnit class p.J and TestNG suite file t.xml
      When a run with "<selection> -C Rec -fJ out.json" ends
      Then the root's children are p.B, aborted with the RunAborted's message and throwable, then <listed>, in the order Runner would have started them, each named as above and aborted with reason "not run"
      And Rec receives no DiscoveryStarting or DiscoveryCompleted where 3.2.19 sends none
      Examples:
        | selection                          | listed                                                   |
        | -s p.B -m q                        | q.D and q.E                                              |
        | -s p.B -w q                        | q.D and q.E                                              |
        | -s p.B -s 'q.*'                    | q.D and q.E                                              |
        | -s p.B -s p.A -j p.J -m q -b t.xml | p.A, the -j suite of p.J, q.D and q.E, then the -b suite |
        | -z t -s p.B                        | no suite                                                 |

    Scenario: AC-026 A glob suite throws after discovery has started
      Given the runpath holds suites q.D and q.E, and q.D's constructor throws
      When a run with "-s 'q.*' -fJ out.json" ends
      Then the run node holds the DiscoveryStarting 3.2.19 sends and is aborted with its RunAborted
      And q.D is aborted, named as above, with the RunAborted's message and throwable
      And q.E is aborted with reason "not run"

    Scenario: AC-027 A suite DiscoverySuite discovers aborts as 3.2.19 reports it
      Given the runpath holds suites p.A and p.B, and p.B's constructor throws
      When a run with "-C Rec -fJ out.json" ends
      Then Rec receives the SuiteAborted for p.B that 3.2.19 sends, and p.A runs
      And p.B is a top-level suite aborted with that SuiteAborted's message and throwable

  Rule: Messages attach to the node that emitted them
```

| ID     | Message emitted                                                                         | Attaches to                        |
|--------|-----------------------------------------------------------------------------------------|------------------------------------|
| AC-028 | inside a test, recorded in its closing event or sent on its own                          | the test                           |
| AC-029 | inside a scope, outside any test, as a registration-time `info` in a `describe`          | the scope                          |
| AC-030 | by a suite outside its scopes and tests, as from `beforeAll`                              | the suite                          |
| AC-031 | naming no suite: `-A`'s cannot-rerun alerts and `-W`'s slowpoke alerts                     | the run node                       |
| AC-032 | by a node that cannot be identified, as from the `beforeAll` of a nested suite B that suite A's own `runNestedSuites` override runs by calling `B.run` itself | the first identifiable ancestor of that node, here A, in its emitted order |

```gherkin
    Scenario: AC-033 A node's messages keep their emitted order
      Given test "t" calls info("a"), alert("b"), note("c") and markup("d"), in that order
      When a run with "-fJ out.json" in any execution mode ends
      Then "t" holds the messages "a", "b", "c" and "d" in that order

    Scenario Outline: AC-049 A node's messages sit among its children where they were emitted
      Given suite A <kind>, nests suite B, and calls info("before") in its beforeAll
      And A registers scope "s" holding test "t1", a registration-time info("between"), then test "t2"
      When a run with "-s A -fJ out.json <mode>" ends
      Then A holds, in order, the message "before", suite B, then scope "s"
      And "s" holds, in order, test "t1", the message "between", then test "t2"
      Examples:
        | kind                                             | mode |
        | is an AnyFunSpec                                 |      |
        | is an AnyFunSpec mixing in RandomTestOrder       |      |
        | is an AnyFunSpec                                 | -P   |
        | is an AnyFunSpec mixing in ParallelTestExecution | -P   |
        | is an AnyFunSpec mixing in RandomTestOrder       | -P   |
        | is an AnyFunSpec mixing in SequentialNestedSuiteExecution | -P |
        | is an AnyFunSpec mixing in StepwiseNestedSuiteExecution   | -P |
```

Durations:

| ID     | Node                                                    | Duration                                                                      |
|--------|---------------------------------------------------------|-------------------------------------------------------------------------------|
| AC-034 | run node                                                | its `RunCompleted`, `RunStopped` or `RunAborted`'s duration; none where that event carries none |
| AC-035 | suite or nested suite                                   | its `SuiteCompleted` or `SuiteAborted`'s duration                             |
| AC-036 | test closed by `TestSucceeded`, `TestFailed`, `TestCanceled` or `TestPending` | that event's duration                               |
| AC-037 | scope                                                   | from its `ScopeOpened`'s timestamp to its `ScopeClosed` or `ScopePending`'s timestamp |
| AC-038 | scope or test aborted by AC-020 or AC-021               | from its opening event's timestamp to the timestamp of the `SuiteAborted` or `RunAborted` that aborted it |
| AC-039 | ignored test; suite of AC-023 to AC-026                 | none                                                                          |

```gherkin
  Rule: Event-filter characters drop only events' data, never nodes

    Scenario Outline: AC-040 A filtered event's node remains with its data absent
      Given suite A with an info in its beforeAll, scope "s" holding passing test "t1", ignored test "t2", pending test "t3", and pending scope "p"
      And "t1" calls info("i") and markup("m")
      When a run with "-s A -fJ<chars> out.json" ends
      Then out.json holds every node that a run with "-s A -fJ out.json" holds, with the same kinds, names and order
      And <absent>
      And every other event's data is present
      Examples:
        | chars | absent                                                                          |
        | N     | the data of each test's TestStarting                                            |
        | C     | "t1"'s outcome, duration and recorded messages                                  |
        | X     | "t2"'s outcome                                                                  |
        | E     | "t3"'s outcome, duration and recorded messages                                  |
        | H     | the data of A's SuiteStarting                                                   |
        | L     | the data of A's SuiteCompleted, including its duration                          |
        | O     | A's info and "t1"'s info "i", while "t1"'s markup "m" remains                   |
        | P     | the data of each ScopeOpened, and the durations of "s" and "p"                  |
        | Q     | the data of the ScopeClosed of "s", and its duration                            |
        | R     | the data of the ScopePending of "p", including its outcome and duration         |
        | M     | "t1"'s markup "m", while its info "i" remains                                   |

    Scenario: AC-041 Events that no filter character names keep their data
      Given suites that fail, cancel, abort, and emit alerts and notes
      When a run with "-fJNCXEHLOPQRM out.json" ends
      Then out.json holds the data of every DiscoveryStarting, DiscoveryCompleted, RunStarting, RunCompleted, RunAborted, SuiteAborted, TestFailed, TestCanceled, AlertProvided and NoteProvided the run sent
      And its TestFailed and TestCanceled data holds none of their recorded infos or markups

  Rule: Throwables keep their class, message, location, stack trace, causes and suppressed exceptions

    Scenario: AC-042 A failed test's throwable is recorded whole
      Given test "t" fails with a TestFailedException at Spec.scala line 12, whose cause E1 has suppressed exception E2
      And E2's cause is E1
      When a run with "-fJ out.json" ends
      Then "t"'s throwable records class TestFailedException, its message, file Spec.scala and line 12, and every frame of its stack trace
      And its cause records E1 the same way, with E2 among E1's suppressed exceptions
      And E2's cause is recorded as a reference to E1's record

    Scenario Outline: AC-043 A null message is told apart from an empty one
      Given test "t" fails with a RuntimeException whose message is <message>
      When a run with "-fJ out.json" ends
      Then the throwable's message is <recorded>
      Examples:
        | message | recorded |
        | null    | absent   |
        | ""      | empty    |

  Rule: Existing reporters receive what 3.2.19 sends

    Scenario Outline: AC-044 Every reporter but J's sees 3.2.19's events, with or without a J reporter
      The sequential "-C Rec" run is the call IntelliJ's forked mode makes of Runner.run.
      Given suites that pass, fail, cancel, are pending and ignored, open scopes, emit messages, nest suites and mix in ParallelTestExecution
      When runs with "-C Rec <mode>" and with "-C Rec -fJ out.json -oJ <mode>" end
      Then in each, Rec receives what 3.2.19 sends
      Examples:
        | mode |
        |      |
        | -P   |
        | -PS  |

  Rule: A J reporter's failures leave earlier output intact

    Scenario: AC-046 A -fJ file that cannot be created
      Given out.json's directory does not exist, or the path out.json is a directory
      When a run with "-fJ out.json" is started
      Then Runner prints to stderr the message 3.2.19 prints for "-f out.json", followed by the exception's stack trace
      And it runs no suite, and Runner.run returns false

    Scenario: AC-047 The write at the end of the run fails
      Given out.json exists holding content X, and its file system refuses the end-of-run write
      When the run ends
      Then out.json holds X
      And stderr carries a message naming out.json
      And every other reporter has received what it would have, and Runner.run returns what it would have

    Scenario: AC-048 No reporter can be created
      Given a -C reporter class that cannot be loaded, and out.json holding content X
      When a run with "-C Missing -fJ out.json" is started
      Then Runner prints to stderr and returns what 3.2.19 does, runs no suite, and out.json holds X
```
