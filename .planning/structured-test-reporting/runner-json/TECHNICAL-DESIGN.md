# Structured JSON from `Runner`: technical design

Decisions: [TECHNICAL-DESIGN-DECISIONS.md](TECHNICAL-DESIGN-DECISIONS.md).

## Summary

The JSON writer (`../ARCHITECTURE.md` § Modules § ScalaTest core) turns tree assembly's structure of a run into the object of `SPECIFICATION.md` § The JSON object. This document fixes that object's layout, which third-party readers, the consolidator and the documentation of `SPECIFICATION.md` R-010 build against: each node holds its final state, folded from its events, never the events themselves. Two values the layout needs, a node's name as written in the source and a suite's source file, are carried by no 3.2.19 event; `Engine` supplies them through the envelope.

## APIs

### Encoding

- An absent value omits its key; the writer never writes `null`. An empty string is written `""`.
- Keys are written in the order this document lists them.
- `startedAt`, `endedAt` and a message's `at` are the supplying event's `timeStamp`, in epoch milliseconds. `duration` is in milliseconds.
- Strings escape `"`, `\` and control characters. An unpaired UTF-16 surrogate, which a Java string can hold and UTF-8 cannot encode, is written as a `\uXXXX` escape (RFC 8259 § 8.2, https://www.rfc-editor.org/rfc/rfc8259#section-8.2, allows such escapes in a string).
- The writer adds no JSON library: scalatest-core's dependencies are fixed (`../ARCHITECTURE-DECISIONS.md` § Tree assembly and the human renderer are internal to ScalaTest core, which the consolidator depends on), and 3.2.19 already writes its JSON by hand (`org.scalatest.events.Event.toJson`).
- A reader ignores keys and `reason` values it does not know, which a minor release may add (`../SPECIFICATION.md` § JSON output, "JSON compatibility").

### Nodes

A node's keys, in order: `kind`, `name`, `label`, `suiteId`, `location`, `outcome`, `reason`, `message`, `throwable`, `startedAt`, `endedAt`, `duration`, `summary`, `threadName`, `contents`, `output`. The run node writes its own keys (§ The run node) straight after `kind`.

Nothing else an event carries is written: no ordinal, formatter, rerunner, payload, `NameInfo`, or per-event suite name, suite ID, suite class name or test name.

- `kind` — `run`, `suite`, `scope` or `test`.
- `name` — suites, scopes and tests: the node's name (`.docs/ubiquitous-language.md`), never a joined name. In `"A" when { "b" should { "c" in { … } } }`, scope `A` has name `A`, scope `b` has name `b` and test `c` has name `c`; ScalaTest's test name `A when b should c` is not stored. A suite's name is its suite name. A scope's or test's name comes from the envelope (§ Source names and a suite's file); where its suite supplies none, it is the event's text: `ScopeOpened`'s `message`, or the test's `testText`.
- `label` — scopes and tests: the formatted text `Engine` gives the node's `IndentedText`, with leading indentation removed, since the tree gives the nesting. In the example above, `A` has label `A`, `b` has `when b` and `c` has `- should c`; an `AnyFunSpec` test `t1` has `- t1`. It comes from the envelope (§ Source names and a suite's file), so a test that never closed still has one; `TestStarting` carries `MotionToSuppress`, and a test's closing event may never come. Where its suite supplies none, it is the `formattedText` of the node's `ScopeOpened` or closing event, and absent where neither carries one.
- `suiteId` — suites only, always present.
- `location` — § Locations. A scope's comes from its `ScopeOpened`; a test's from its `TestStarting`, else its closing event, skipping `SeeStackDepthException`; a suite's is its class name in `className`, plus the file of § Source names and a suite's file, and never a `lineNumber`.
- `outcome` — in ScalaTest's spelling:
  - test: `succeeded`, `failed`, `canceled`, `ignored`, `pending` or `aborted`;
  - scope: `pending` or `aborted`;
  - suite: `aborted`;
  - run: `aborted` or `stopped`.

  Absent where the node has none (`SPECIFICATION.md` AC-010 to AC-019) or a filter character drops its supplying event.
- `reason` — `notRun` or `processFailed`, only on an aborted node with that reason. This feature writes `notRun` (`SPECIFICATION.md` AC-024 to AC-026); `processFailed` is written by `../ROADMAP.md` § 5.
- `message`, `throwable` — on a failed, canceled or aborted node: the `message` and `throwable` (§ Throwables) of its closing `TestFailed`, `TestCanceled`, `SuiteAborted` or `RunAborted`. A node aborted in the structure only (`SPECIFICATION.md` AC-020, AC-021) has neither. Under `SPECIFICATION.md` AC-052 the test takes them from the `DistributedTestRunnerSuite`'s `SuiteAborted`. The suite whose constructor halted the run (`SPECIFICATION.md` AC-023, AC-026) holds a copy of the run node's, so the same throwable record is written twice.
- `startedAt` — the opening event's: `RunStarting`, `SuiteStarting`, `ScopeOpened` or `TestStarting`.
- `endedAt` — the closing event's; for a node aborted in the structure only, that of the `SuiteAborted` or `RunAborted` that aborted it.
- `duration` — as `SPECIFICATION.md` AC-034 to AC-039 give it. A scope's, and that of a node aborted in the structure only, is computed as `endedAt - startedAt`, and is absent unless both are present.
- `summary` — the run node only (§ The run node).
- `threadName` — suites and tests: the opening event's, else the closing event's.
- `contents` — always present, `[]` when empty: the node's children and messages in the one sequence of `SPECIFICATION.md` R-003. A child or message states its own `kind`.
- `output` — reserved for test output, as `{ "stdout": …, "stderr": … }`. `../ROADMAP.md` § 7 defines its content; this feature never writes it.

A suite that never ran under `SPECIFICATION.md` AC-024 to AC-026 has `outcome` `aborted` and `reason` `notRun`; one named as `DeferredAbortedSuite` names it, for class `p.B`, has `name` `B`, `suiteId` `p.B` and `location.className` `p.B`.

#### The run node

The run node has no `name`, `label`, `suiteId` or `threadName`. After `kind` it writes:

- `scalatestVersion` — what `Runner -v` prints.
- `filterCharacters` — the reporter's event-filter characters as a string, in the order given, `""` when none: `-fWDJHL` gives `"HL"`.
- `testCount` — `RunStarting`'s.
- `configMap` — `RunStarting`'s, as an object of each value's `toString`.
- `discovery` — `{ "startedAt", "duration" }`, from `DiscoveryStarting`'s `timeStamp` and `DiscoveryCompleted`'s `duration`, when the run sent them.

Its `summary` is the closing run event's `Summary`, with ScalaTest's own counts: `testsSucceededCount`, `testsFailedCount`, `testsIgnoredCount`, `testsPendingCount`, `testsCanceledCount`, `suitesCompletedCount`, `suitesAbortedCount`, `scopesPendingCount`.

### Messages

A message in `contents` writes `kind`, `message`, `at`, `location`, `throwable`, `threadName`:

- `kind` — `info`, `alert`, `note` or `markup`.
- `message` — the event's `message`; for `MarkupProvided`, its `text`.
- `throwable` — § Throwables, where the event carries one.
- `threadName` — only where it differs from its node's `threadName`, or the node has none.

Whether a message was recorded in a test's closing event or sent on its own is not kept.

### Throwables

A throwable record holds, in order:

- `id` — an integer, numbered depth-first from 1 within one record, a node's or a message's `throwable`;
- `className`;
- `message` — `getMessage`, absent when `null`;
- for a `StackDepthException`, `TestFailedException` and `TestCanceledException` among them: `location`, from `failedCodeFilePathname` as `filePath`, else `failedCodeFileName` as `fileName`, and `failedCodeLineNumber` as `lineNumber`, each where defined; and no `stackTrace`;
- for any other throwable: `stackTrace`, every frame as `{ "className", "methodName", "fileName", "lineNumber", "isNative" }`, with `fileName` absent when unknown, `lineNumber` absent when negative, and `isNative` present only when `true`. 3.2.19's per-frame `toString` is not written;
- `cause` — recorded the same way, by its own type; absent when there is none;
- `suppressed` — an array, in order, each recorded the same way; absent when empty.

A throwable already recorded within the same record is written `{ "ref": <id> }`, ending any cycle. "Already recorded" is reference identity, never `equals`, which a throwable class may override.

### Locations

A `location` is one object whose keys, each present only when known, are `className`, `methodId`, then `filePath` or `fileName`, then `lineNumber`. `filePath` is written where ScalaTest has the path, `fileName` only where it has not; never both, on every node, message and throwable. `filePath` is written as recorded (scalactic `Position.filePathname`, usually absolute); readers relativise it.

ScalaTest's `Location`s fold into it:

- `LineInFile` — `filePath` or `fileName`, and `lineNumber`;
- `TopOfClass` — `className`;
- `TopOfMethod` — `className` and `methodId`;
- `SeeStackDepthException` — no `location` on the node or message; the throwable's `location` carries it.

### Filtering

`SPECIFICATION.md` R-009 is realised on two kinds of key:

- *Identity keys* — `kind`, `name`, `label`, `suiteId`, `location`, the run node's `scalatestVersion` and `filterCharacters`, and the node's place in `contents`. Tree assembly holds every envelope unfiltered (`../ARCHITECTURE.md` § Modules § ScalaTest core, JSON writer), so these come from all of a node's events whatever the characters.
- *Every other key* — written only where the event that supplies it passes 3.2.19's `FilterReporter` for the reporter's characters, with a test's recorded messages filtered as `FilterReporter` filters them. A key that two events supply, `threadName` from `TestStarting` and the test's closing event, is written where either passes. A computed `duration` needs both of its events.

The supplying events:

- `RunStarting` — the run node's `startedAt`, `testCount`, `configMap`.
- `DiscoveryStarting`, `DiscoveryCompleted` — `discovery`.
- `RunCompleted`, `RunStopped`, `RunAborted` — the run node's `outcome`, `message`, `throwable`, `endedAt`, `duration`, `summary`.
- `SuiteStarting` — a suite's `startedAt`, `threadName`.
- `SuiteCompleted`, `SuiteAborted` — a suite's `endedAt`, `duration`, `threadName`, and, from `SuiteAborted`, its `outcome`, `message`, `throwable`.
- `ScopeOpened` — a scope's `startedAt`.
- `ScopeClosed`, `ScopePending` — a scope's `outcome`, `endedAt`.
- `TestStarting` — a test's `startedAt`, `threadName`.
- `TestSucceeded`, `TestFailed`, `TestCanceled`, `TestIgnored`, `TestPending` — a test's `outcome`, `endedAt`, `duration`, `threadName` and recorded messages, and, from `TestFailed` and `TestCanceled`, its `message`, `throwable`.
- `InfoProvided`, `AlertProvided`, `NoteProvided`, `MarkupProvided` — the message.

So `-fJC` drops a succeeded test's `outcome`, `endedAt`, `duration` and recorded messages, and keeps its `threadName` from `TestStarting`.

## Source names and a suite's file

No 3.2.19 event carries a scope's or test's name, nor, before the test closes, its label: `ScopeOpened` carries `when b` and `TestIgnored` carries `should c`, the text after a style trait's verb is applied. Nor does any carry a suite's file.

- **`Engine`** (the `private[scalatest]` `SuperEngine` and `AsyncSuperEngine` families) keeps, at registration, each scope's and test's name before a parent's child prefix is applied or its test name is joined, its label, and the scalactic `Position` of each registration. Where a style trait hands `Engine` text already combined with a verb, it hands the name separately.
- **A suite's file** is the file of its registrations' `Position`s where they all share one; otherwise it has none. No line is kept.
- **The structure wrapper** puts the name and label in the envelope of the node's events, and the file in the suite's (`../ARCHITECTURE.md` § Modules § ScalaTest core, Structure wrapper). `Engine` gives them to the wrapper it finds as `Args.reporter`; where `Args.reporter` is not a structure wrapper, as when a suite wraps the reporter it is given, or the suite is not built on `Engine` (a hand-written `Suite`, a third-party style, the JUnit and TestNG wrappers), no name, label or file is supplied and `name` and `label` fall back as § Nodes says.
- **Tree assembly** stores them on the node, and the JSON writer reads them from there.

The `Event` itself is unchanged, so no existing reporter sees a difference (`../ARCHITECTURE.md` § The compatibility boundary).
