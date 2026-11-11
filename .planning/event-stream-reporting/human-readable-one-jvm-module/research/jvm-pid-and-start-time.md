# A JVM run's PID and process start time when core targets Java 8

Whether a Mill 1.x JVM test worker can run on Java 8, and how a worker on each JDK it can run on gets the PID and start time that item 1 carries and the plugin compares with `ProcessHandle` (`.planning/event-stream-reporting/ARCHITECTURE.md` § APIs § The emitter and the Mill plugin, "The end of a run's event stream"). Answers [How does a JVM run get its PID and process start time on Java 8?](https://github.com/jamesgorman2/scalatest/issues/59), for [Plan: consolidated human-readable output for one JVM module](https://github.com/jamesgorman2/scalatest/issues/54). Facts and their sources only; no option is chosen.

Notes this one builds on and does not repeat:

- `.planning/event-stream-reporting/research/jvm-file-channel.md`, cited as `JFC`: the header's start time from `ProcessHandle.current().info().startInstant()` matched the plugin's `ProcessHandle.of(pid)` in 439 files, with Mill and every worker on Zulu `21.0.10` (§ 1, § 4).
- `.planning/event-stream-reporting/research/node-native-file-channel.md`, cited as `NNFC`: how the JDK computes `startInstant()` on Linux and Windows (§ 2).
- `.planning/event-stream-reporting/human-readable-one-jvm-module/research/mill-plugin-conventions.md`, cited as `MPC`: the newest stable Mill 1.x release is `1.3.1` (§ 1).

`TMU` is `[mill]` `libs/javalib/src/mill/javalib/TestModuleUtil.scala` at tag `1.1.10`.

## Short answer

- **Core targets Java 8.** CI builds and tests every Scala version on JDK 8 and JDK 25 (`.github/workflows/ci.yml` lines 10–177, `java: [ '8', '25' ]`). The sbt build under `project/` sets no `-release`, `-target` or `javacOptions`, so core is compiled against the JDK sbt runs on; on JDK 8 a direct reference to `java.lang.ProcessHandle` does not compile.
- **Mill 1.x cannot run a JVM test worker on Java 8** (§ 1).
  - *Read:* Mill's docs say it "supports user-defined modules down to Java 11". Mill dropped Java 8 in `0.12.0`. The worker's main class, `MillTestRunnerMain`, is built with `jvmId = "11"` and `--release 11`, and nothing in `javaHome` checks the version.
  - *Ran,* Windows 11, Mill `1.1.10`: a module with `jvmVersion = "temurin:8"` failed to compile, because Mill's compile worker on Java 8 threw `java.lang.NoClassDefFoundError: java/lang/ProcessHandle`. Mill's test worker, spawned by hand as Mill spawns it, failed on Temurin `1.8.0_482` with `java.lang.UnsupportedClassVersionError: mill/javalib/testrunner/entrypoint/MillTestRunnerMain has been compiled by a more recent version of the Java Runtime (class file version 55.0) ...`, and ran on Temurin `11.0.13`.
  - At `1.3.1` every class in the worker's Mill jars is class file version 55 (Java 11).
- **So every JVM run under the test command is on Java 11 or later,** and the Java 8 question about a matching start time does not arise there. Taken anyway: `RuntimeMXBean.getStartTime()` never matched `ProcessHandle`'s start time. On Windows it was 37–228 ms later; on Linux under WSL 2 it was 495–692 ms later (§ 3).
- **Reflection on `ProcessHandle` from Java 8 bytecode is enough on every JDK a worker can run on** (§ 2). A class compiled with `--release 8` called `ProcessHandle.current()`, `pid()`, `info()` and `Info.startInstant()` through `Method` objects taken from the public interfaces. Its start time equalled `ProcessHandle.of(pid).info().startInstant()` in a JDK 21 parent in 200 of 200 runs: 20 each on Temurin 11, 17, 21 and 25 and Zulu 21 and 25 on Windows, and on Temurin 11, 17 and 25 and Zulu 21 on Linux. There was no warning on stderr. On Java 8 the same class loaded and ran, and found no `ProcessHandle` class.
  - The same call through `cur.getClass().getMethod("pid")` threw `java.lang.IllegalAccessException` on every JDK from 11 to 25.

## 1. Can a Mill 1.x JVM test worker run on Java 8?

### Read

| Source | What it says |
|---|---|
| `[mill]` `website/docs/modules/ROOT/pages/cli/build-header.adoc` lines 123–124, at `1.1.10` and at the fork's `1.4.0-M0-6-g830ee735b` | "Note that Mill requires a minimum of Java 17 for the build tool itself to run. However, it supports user-defined modules down to Java 11." |
| `[mill]` `changelog.adoc` lines 1983–1984, under `0.12.0` | "Dropped support for Java 8, Scala 2.11.x/3.1.x, and Scala-Native 0.4.x … We now expect a minimum of Java 11" |
| `[mill]` `libs/javalib/testrunner/package.mill` lines 10–16 | The test runner extends `MillJava11ScalaModule`; its `entrypoint` sets `jvmId = "11"` |
| `[mill]` `mill-build/src/millbuild/MillJava11ScalaModule.scala` lines 3–8 | "modules that need compatibility down to Java 11 due to running on the same JVM as user modules"; `jvmId = "11"`, Scala `3.7.4` |
| `[mill]` `mill-build/src/millbuild/MillPublishJavaModule.scala` lines 26–27 | Java modules compile with `--release 11` |
| `TMU` lines 147–163 | The worker is `Jvm.spawnProcess` of `mill.javalib.testrunner.entrypoint.MillTestRunnerMain` (line 149), on `runClasspath ++ testrunnerEntrypointClasspath`, with the module's `javaHome` (line 158) |
| `[mill]` `libs/javalib/testrunner/entrypoint/src/mill/javalib/testrunner/entrypoint/MillTestRunnerMain.java` lines 16–43 | The entry point loads `mill.javalib.testrunner.MillTestRunnerMain0` from the test runner's own class path and invokes it |
| `[mill]` `libs/javalib/src/mill/javalib/JavaHomeModule.scala` lines 29–42 | `javaHome` resolves `jvmVersion` through Coursier and does not check the JDK's version |

The jars Mill puts in the worker, by class file major version (*ran:* a script read every class header):

| Jar | `1.1.10` | `1.3.1` |
|---|---|---|
| `mill-libs-javalib-testrunner-entrypoint` | 55 (2 classes) | 55 (2) |
| `mill-libs-javalib-testrunner_3` | 52 (24) | 55 (24) |
| `mill-libs-util-java11_3` | 52 (27) | 55 (41) |

The entry point, which the JVM loads first, needs Java 11 in both. The `1.1.10` jars were from the local Coursier cache, and the `1.3.1` jars from `repo1.maven.org`.

### Ran

Mill `1.1.10` through `mill.bat --no-daemon`, Windows 11 (`10.0.22631`), x86-64, with Mill on Zulu `21.0.10` as Mill fetched it. Project: `research-59\mill-j8\` in the scratch directory. It had two Scala `2.13.16` modules, each with one ScalaTest `3.2.19` suite that printed `java.version` and `RuntimeMXBean`'s name and start time.

- **`ctl.test`** used Mill's default JVM. It passed on `java.version=21.0.10`.
- **`j8.test`** used `def jvmVersion = "temurin:8"`. Mill fetched Temurin `8u482-b08`. `j8.compile` failed before any test ran:

  ```
  Exception in thread "main" java.lang.NoClassDefFoundError: java/lang/ProcessHandle
  	at mill.client.lock.PidLock.<init>(PidLock.scala:13)
  	...
  	at mill.javalib.worker.MillJvmWorkerMain.main(MillJvmWorkerMain.scala)
  ```

  Mill then reported `java.lang.Exception: server launch failed (timeout was 10000ms)` for `j8.compile`. The test module inherits `javaHome` from its outer module (`[mill]` `libs/javalib/src/mill/javalib/JavaModule.scala` line 123), so its compile uses the same JVM.
- **The worker alone.** `spawn_worker.py` spawned the worker as `TMU` lines 141–163 do: the same main class, class path, test-runner class path argument and a `testargs` file copied from `ctl.test`. The queue and output paths were moved into a fresh folder.
  - Temurin `11.0.13`: the worker ran `probe.ProbeSuite` and wrote `out.json`.
  - Temurin `1.8.0_482`: it exited 1 with `java.lang.UnsupportedClassVersionError: mill/javalib/testrunner/entrypoint/MillTestRunnerMain has been compiled by a more recent version of the Java Runtime (class file version 55.0), this version of the Java Runtime only recognizes class file versions up to 52.0`. No `out.json`.

Under `testLocal` and Mill's in-process discovery, the runner runs in Mill's own JVM, which needs Java 17 (see the Mill documentation above). Neither is a run of the test command (`.planning/event-stream-reporting/SPECIFICATION.md` § Scope, Out of scope: `--local`).

## 2. Reflection on `ProcessHandle` from Java 8 bytecode

### Read

- `ProcessHandle` and `ProcessHandle.Info` are `@since 9`. `Info.startInstant()` "Returns the start time of the process" as an `Optional<Instant>`. "The attributes of a process vary by operating system and are not available in all implementations. Information about processes is limited by the operating system privileges of the process making the request." (Java SE 21 API, `java.base/java/lang/ProcessHandle.Info.html`.)
- How the JDK computes it: on Linux from `/proc/stat`'s `btime` and the process's start ticks over `CLK_TCK`, and on Windows from `GetProcessTimes` (`NNFC` § 2, citing `ProcessHandleImpl_linux.c` and `ProcessHandleImpl_win.c`).

### Ran

`Target.java`, compiled by Zulu 21's `javac --release 8` (class file version 52), printed:

- `RuntimeMXBean.getName()` and `getStartTime()`;
- `viaInterface`: `Class.forName("java.lang.ProcessHandle")` and `"java.lang.ProcessHandle$Info"`, then `getMethod("current")`, `"pid"`, `"info"` and `"startInstant"` on those two classes, then `invoke`; `ClassNotFoundException` gives "absent";
- `viaImplClass`: `ProcessHandle.current()`, then `getClass().getMethod("pid")` on the object it returned.

`Observer.java`, on Zulu `21.0.10` as Mill's JVM is, spawned `Target` 20 times per JDK with `ProcessBuilder`. It checked that the PID in `getName()` was `Process.pid()`, then compared `ProcessHandle.of(pid).flatMap(h -> h.info().startInstant())` with each value while the target was alive. The target's stderr was captured.

| OS | Target JDK | `viaInterface` start time less observer's | `viaImplClass` | stderr |
|---|---|---|---|---|
| Windows 11 | Temurin `1.8.0_482` | absent, 20 of 20 | absent | empty |
| Windows 11 | Temurin `11.0.31`, `17.0.19`, `21.0.11`, `25.0.3`; Zulu `21.0.10`, `25.0.3` | 0 ms, 120 of 120 | `IllegalAccessException`, 120 of 120 | empty |
| Linux | Temurin `1.8.0_512` | absent, 20 of 20 | absent | empty |
| Linux | Temurin `11.0.33`, `17.0.21`, `25.0.5`; Zulu `21.0.10` | 0 ms, 80 of 80 | `IllegalAccessException`, 80 of 80 | empty |

Linux is Ubuntu under WSL 2, kernel `6.18.58.1`, `CLK_TCK` 100. The Linux Temurin JDKs were downloaded from `api.adoptium.net` for this run.

- The PID from `viaInterface` equalled the PID in `getName()` and `Process.pid()` in every run on Java 11 and later.
- `startInstant()` was never empty for a live child on either OS.
- *General:* `ProcessHandleImpl` is not public. A `Method` taken from it cannot be invoked from outside `java.lang`. A `Method` taken from the public interface can.

## 3. Java 8 alternatives: `RuntimeMXBean`

### Read

- `RuntimeMXBean.getName()`: "The returned name string can be any arbitrary string and a Java virtual machine implementation can choose to embed platform-specific useful information in the returned name string." `getStartTime()`: "Returns the start time of the Java virtual machine in milliseconds. This method returns the approximate time when the Java virtual machine started." `getPid()` is `@since 10` (Java SE 21 API, `java.management/java/lang/management/RuntimeMXBean.html`).
- In HotSpot, `getStartTime()` reads `JMM_JVM_INIT_DONE_TIME_MS` (`[jdk21u]` `src/java.management/share/native/libmanagement/VMManagementImpl.c`, `Java_sun_management_VMManagementImpl_getStartupTime`). That is `Management::vm_init_done_time()`, which `record_vm_init_completed` sets to `os::javaTimeMillis()` (`[jdk21u]` `src/hotspot/share/services/management.cpp`). It is the wall-clock time at which VM initialisation completed, not the process's creation time.

### Ran

Same runs as § 2. `getName()` was `<pid>@<host>` on every JDK, 8 to 25, on both OSes. `getStartTime()` less the observer's `startInstant()`:

| OS | JDK 8 | JDK 11 | JDK 17 | JDK 21 (Zulu, Temurin) | JDK 25 (Zulu, Temurin) |
|---|---|---|---|---|---|
| Windows 11 | 41 / 43 / 49 | 47 / 48 / 122 | 37 / 39 / 228 | 38 / 40 / 53; 38 / 39 / 206 | 38 / 40 / 119; 39 / 40 / 210 |
| Linux | 672 / 683 / 692 | 632 / 639 / 646 | 631 / 642 / 660 | 495 / 499 / 504 (Zulu) | 613 / 621 / 651 (Temurin) |

Values are min / median / max in ms, over 20 runs each. They never matched.

- On Linux every `startInstant()` was a multiple of 10 ms. On Windows the last digit was spread across 0–9.
- *Inference:* the Linux gap also contains WSL 2's offset between `btime` and the wall clock that `NNFC` § 5.1 saw for Node. It was not measured here.

## What was not run

- macOS; Linux outside WSL 2; JVMs other than HotSpot (Temurin, Zulu).
- The reflective probe inside a Mill test worker. It ran in a child of a plain JDK 21 process, which is where Mill's spawn puts a worker (`TMU` lines 147–163). The PID and start time do not depend on the parent.
- A worker under a `SecurityManager`, or one whose OS denies process information to its parent.
- PID reuse.
- Mill releases other than `1.1.10`, for running on Java 8. The `1.3.1` jars were only inspected.
- Whether Mill `1.x` builds or runs Scala 2.11 modules. The changelog line in § 1 drops 2.11 along with Java 8, and the epic's § Scope lists the JVM on Scala 2.11. This was not checked.

The scratch artefacts (`mill-j8\`, `probe\`, `scripts\`, `windows.tsv`, `linux.tsv`) are under `research-59\` in the session's scratch directory and are not committed.

## Options left open

1. **Reflection in core through the public interfaces** (§ 2).
   - Core keeps compiling on JDK 8, and core loads and runs on Java 8 without the PID and start time.
   - Costs:
     - a small reflective helper;
     - a rule that `Method`s come from `ProcessHandle` and `ProcessHandle.Info`, not the runtime class;
     - item 1 must say what a run without them looks like, or the emitter must refuse to open the event log. Under the Mill 1.x test command that case does not occur (§ 1).
2. **A multi-release jar,** with a class under `META-INF/versions/9` that calls `ProcessHandle` directly.
   - Costs:
     - a Java 9+ compile step in an sbt build that CI runs on JDK 8 too;
     - handling in the OSGi bundling (`sbt-osgi`) and in each cross-built Scala version.
   - It gives the same results as option 1 at runtime.
3. **`RuntimeMXBean` (`getName()` PID, `getStartTime()`) with a tolerance** in the plugin's comparison (§ 3).
   - It works on Java 8.
   - Costs:
     - it never matched;
     - the gap varied up to 228 ms on Windows and was about 0.5–0.7 s under WSL 2;
     - a tolerance wide enough to cover that weakens the start-time check against PID reuse that `.planning/event-stream-reporting/ARCHITECTURE-DECISIONS.md` "The plugin learns that a run's stream has ended when the run's process exits." Costs relies on;
     - `getName()`'s format is unspecified.
   - Nothing it buys is reachable under the Mill 1.x test command.
