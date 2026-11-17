# Consolidated human-readable output for one JVM module: testing

Technical design of how the criteria's runs are built and compared, what Mill's console output tests can assert, and how the fork is compared with ScalaTest 3.2.20. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Testing

### Runs the criteria describe

The criteria whose `Given` is an event log that contains events (`../SPECIFICATION.md` § Acceptance criteria) are tested without Mill and without the codec (`../TECHNICAL-DESIGN-DECISIONS.md` "The consolidation library's tests build each run with a test-only builder…"). Those tests build each run with a test-only builder, `org.scalatest.events.TestRunBuilder`. It is in the build's common test project, `commonTestDotty`, which is not published. Core's test project depends on it. The library's project does not: it adds the common test project's classpath to its own unmanaged test classpath, so the library's published POM and `ivy.xml` do not name that project.

The builder takes its input and builds the run as follows:

- A test gives the run as one ordered list: the criterion's events, in the notation of `../SPECIFICATION.md` § Acceptance criteria, with the run's captured lines at the places they are fed.
- The builder derives only each node's ID (its node path), its parent's ID and its declared position, from the `structure` events, and resolves each node an event names to its ID. It emits every event and captured line in the order given, in the form core's decoder yields, so a test can place a `structure` event, a ScalaTest event or a captured line anywhere the criterion puts it.
- It gives each event the execution path that the notation's `execution <n>` and `in <ancestor> execution <m>` rules give it.
- A `structure` block for a suite an earlier block contains becomes a further `structure` event. Each node of it that `../SPECIFICATION.md` § Acceptance criteria makes the node of an earlier block has that node's path, and so its ID.
- A suite a `structure under suite` or `structure under test` block adds gets its parent's ID and no position (`test-event-log.md` § The test event log on the JVM § The format).

The emitter's and the decoder's criteria are those that state events, in the parts of `../specification/test-event-log.md`. Among them, the suites of `../specification/test-event-log-events.md` AC-109 write lone surrogates, and those of `../specification/test-event-log-structure.md` AC-100 and AC-121 write events from concurrent threads. Each side of the codec is tested against the format on its own (`../../decisions/architecture-test-event-log.md` "Each side of core's codec is tested on its own…"), so each such criterion has two tests. Both build the criterion's events with the same builder from its event list, and share expected text in the format, written by hand in the test: a whole line for each event the criterion states, with any value where the criterion fixes none.

- The emitter's test runs the criterion's suites with the runner argument. It compares the lines the run writes with the expected text, only on the keys that carry what the criterion fixes and under the *is* and *holds in order* rules for events (below). It compares node IDs through the structures that contain them. It then decodes the lines with core's codec and compares the decoded events with the built ones.
  - The test of the "assertion with analysis" example of `../specification/test-event-log-events.md` AC-111, and the test of `../specification/test-event-log-structure.md` AC-121, take what the emitter receives from a `-C` recording reporter given to the same run. The reporter receives every event the emitter receives, with the same fields.
  - For AC-121's alerts, the recording also gives the emitter's order:
    - The slowpoke timer runs on one thread. It hands each alert to the emitter and then queues the alert for the reporters.
    - `SuiteSortingReporter` does not withhold an alert assigned to no suite.
    - The recording therefore lists the alerts in the order the emitter received them. Each alert the recording lists before `RunCompleted` reached the emitter before the run ended.
  - Where a criterion has `Framework` run a suite that nests others, the test's task selects each nested suite with a `NestedSuiteSelector` beside its `SuiteSelector`. Given a suite by name alone, `Framework` runs none of the tests of the suites it nests.
  - Where a criterion has `Runner.run` discover its suites, the suites are discoverable samples of package `samples.discovered`, whose class files the test copies to a runpath of its own. The build's own test run discovers and constructs these samples too, so a sample whose constructor must throw does so only on a thread of the thread group in which the test starts `Runner.run`. It goes by the group, not the thread's name, because `Runner.run` renames its thread.
  - The test of `../specification/test-event-log-events.md` AC-112 starts a JVM with the test JVM's classpath, in which `Framework` runs the suite, and reads that JVM's stdout and stderr from files.
- The decoder's test feeds core's decoder the expected text and compares the events it yields with the built ones.

One further decoder test, tied to no criterion, feeds the decoder text whose last line is torn, from which it yields no event (`../../ARCHITECTURE.md` § APIs § The emitter and the Mill plugin).

Each comparison of events is made by `org.scalatest.events.EventLogComparison`, beside the builder:

- Each event is compared only on what the criterion fixes:
  - A `structure` event is compared on its whole tree.
  - A ScalaTest event is compared on its node, its execution number, each ancestor's execution number the criterion writes, its class, and the fields the criterion names.
  - A throwable is compared on the parts the criterion writes.
- Where the criterion says the event log *is* the events, the decoded events must be exactly those built. Where it says the log *holds* them *in this order*, the built events must be a subsequence of the decoded ones.

A drift between builder and decoder in what a criterion fixes then fails those tests, as well as the criteria run under Mill.

Each test of human-readable output compares the output apart from its summary line, written with colour on, against expected text in which each ANSI SGR sequence is replaced by a marker naming it, such as `{red}` or `{reset}`. One comparison checks text and colour. Only the tests of `../specification/layout-lines.md` AC-171, AC-174 to AC-176, AC-194 to AC-197 and AC-044, and of `../specification/test-command.md` AC-076, read the summary line, so a change to its format (`../../ROADMAP.md` § 9) changes those tests alone.

The tests of `../specification/runs-and-test-structure-runs.md` AC-172 and AC-173 attach the root's captured output and failure through the library's own operations (`test-event-log.md` § The test event log on the JVM), beside a run the builder builds.

### Mill's console output

The plugin's integration tests assert that Mill's list of failed tests and ScalaTest's end-of-run summary do not reach the console. They cannot assert the same of Mill's runner progress: without a terminal Mill prints no progress prompt, even under `testForked` with `--ticker true`, so its absence proves nothing. Suppressing the progress is covered by construction (`../../decisions/capture-and-console.md` "`../SPECIFICATION.md` R-040, R-097a and R-044 hold on every runtime…") and by `../../research/mill-runner-progress-without-trait.md`, which observed it under pseudo-terminals.

### The compatibility fixture's output

This serves `../specification/runs-and-test-structure-nodes.md` AC-073. It runs among the plugin's integration tests under Mill's testkit. The test stores the expected human-readable output for the compatibility fixture as one multi-line string and checks the test command's output against it. Before comparing, it normalises the text that varies from run to run, such as durations and paths.

### Comparison with ScalaTest 3.2.20

This serves `../specification/compatibility-and-library.md` AC-056 and AC-057, `../specification/test-event-log-events.md` AC-108, AC-113, AC-114, AC-116 and AC-117.

- The tests of AC-056 and AC-057 run in a test project of `project/DottyBuild.scala`.
  - The compatibility fixture and the recorders (the `-C` recording reporter, and a test-interface event handler and loggers that record what they receive) are the sources of an sbt configuration of the project's own that extends neither `Compile` nor `Test` and whose one library dependency is `org.scalatest:scalatest` 3.2.20. They compile there once, so both runs load the same compiled classes. `../../SPECIFICATION.md` R-004 lets the fork run them.
    - 3.2.20 depends on the Scala 3.1 library, which code compiled by Scala 3.3 cannot link against, so the project overrides that dependency's version with its own Scala version.
    - ScalaTest leaves the test interface to the build tool. The configuration takes sbt's own test-interface jar as an unmanaged jar, and both runs load it.
  - 3.2.20 is on no classpath that also contains the fork: the two share packages and class names, so on one classpath each class loads from whichever jar comes first. A generated resource gives the test itself, in `Test`, the test-interface jar and three classpaths: that configuration's compiled classes, its resolved classpath (3.2.20 and its dependencies), and the runtime classpath of the fork's `scalatestDotty` and `scalacticDotty` projects.
- The test forks one JVM for each run, as IntelliJ's forked mode and a build tool's test worker each start a JVM of their own. Each JVM's classpath is the fixture's compiled classes, the test-interface jar, and either 3.2.20's resolved classpath or the fork's. No JVM-wide state, such as `System.out` or a system property, then passes from one run to the other.
- In each JVM the recorders write each event to a file as one line, with every field but timestamps, durations and thread names, and of each throwable only what `../../SPECIFICATION.md` § Constraints § Compatibility with ScalaTest 3.2.x compares. The test compares the two runs' files line by line.
- The fork separates the lines of the text it writes with the platform's line separator, and 3.2.20 with `\n`. On Windows the two runs' texts would differ only in each `\r\n`, so the comparison treats `\r\n` as `\n`:
  - every recorder, on every runtime, writes each `\r\n` in a text it quotes, such as a message, as `\n`. One function, `EventLine.withLineFeeds`, does this for them all;
  - the recording loggers' messages, and the summary the runner's `done` returns, are split into lines at `\r?\n`.
- The harness that forks a pair of runs takes the arguments only the fork's run is given, so a test with the emitter on passes the runner argument there.
- A test of AC-108 runs the fixture on the fork alone, once through `Runner.run` and once through `Framework`, with the runner argument and a `-C` reporter that records every field the event log keeps of each event.
  - That reporter receives the events the emitter receives, so the test compares each event in the event log with the recording.
  - Within each suite, the reporter receives events in the order core sends them, which is the emitter's order. Under `Framework`, `SuiteSortingReporter` reorders events across suites, so there the test checks the order within each suite only.
- A test of AC-113 forks the same pair of JVMs.
  - In each, a recorder writes one line for each class of `org.scalatest.events`: whether it is an event class, its declared fields with their erased types, and its constructors with their parameter types.
  - Scala 3.1 and 3.3 implement a lazy `val` with different fields. The recorder leaves out the two kinds of field only one of them adds, `OFFSET$<n>` and `<n>bitmap$<n>`. It writes the field that stores the value, `<name>$lzy<n>`, by name alone, without its modifiers and type. A lazy `val` or nested object the fork adds therefore still shows as an added field.
  - The test compares the lines of every class 3.2.20 has and of every event class.
- A test of AC-114 runs in forked-JVM pairs set up as for AC-056, launched through `Runner.run` and through `Framework`, with the emitter off.
  - Each pair runs once for each of several reporter argument lists, which between them turn on every character 3.2.20 accepts for `-o`, `-e` and `-f`: those of `ReporterConfigParam` except `A` and `B`, which `ArgsParser.parseConfigSet` rejects.
  - The test reads:
    - each JVM's stdout and stderr, from the forked process;
    - each `-f` file, from a directory of that JVM's own;
    - under `Framework`, the lines the recording loggers receive and, recorded apart from them, the summary the runner's `done` returns.
  - It checks that each run's reporters wrote to each place the launch sends them:
    - under `Runner.run`, stdout, stderr and the file;
    - under `Framework`, whose `-o` reporter writes to the loggers, the loggers, stderr and the file.
  - Before comparing, it replaces each run of stack-frame lines, and each `stackTraces` and `depth` value in a `J` reporter's JSON, with one placeholder, and blanks durations, timestamps and thread names.
  - The fixture's test writes one line to stdout and one to stderr. Under `Runner.run` the reporters write from the dispatch thread, so where those lines fall among a reporter's is not fixed. The test takes them out of each stream before comparing, and checks that each run wrote each once.
- A test of AC-117 forks the same pair of JVMs, launched through `Runner.run` and through `Framework`, without the runner argument and with a `-f` reporter.
  - It compares the names of the files each run leaves in its working directory, which is a directory of that JVM's own.
  - A `-C` reporter records, at each event it receives, the names of the live threads of thread group `main`. The JVM's own threads are in other groups. It normalises each name in two steps:
    - it removes what `Suite.run` adds to the name of the thread running a suite for the length of that run: `-running-<suite name>`, and the `-ScalaTest` before it. The reporter is called on the dispatcher's thread, so which suite's name it would find depends on scheduling. A thread the fork starts is still recorded, under the name it was started with;
    - it replaces each number with `N`.
  - The fork's run must leave no file and record no thread name that 3.2.20's does not.
- The test of AC-116 runs in a Scala.js and a Native test project of `project/DottyBuild.scala`. The fixture and the recorders compile once against 3.2.20's artefacts for the platform, and the test links them twice, once with 3.2.20's and once with the fork's. There the recorders print each event to stdout as one line, in the form the JVM's recorders write it, and print each line the loggers receive, normalised as for AC-114. The test compares the two runs' lines.
  - For each platform, a fixture project compiles the fixture against 3.2.20 and links it with 3.2.20, and a fork-link project links the same compiled classes with the fork. The test project runs on the JVM: it starts each linked program in a process of its own, `node` running the Scala.js one, and compares their stdout and stderr.
  - Each run is given the fixture's suites and one of the `-o` argument lists of AC-114, or none: `Framework` on Scala.js and Native takes no other reporter.
  - The launchers execute tasks as each platform's test bridge does: on Scala.js, each task once the one before it has called back; on Native, synchronously, printing what a task throws.
