# ScalaTest's Mill plugin

The plugin `com.edc:scalatest-mill_mill1_3` adds the test command `mill scalatest/test`. It runs one JVM test module's tests and writes their consolidated human-readable output to stdout and, on request, their consolidated JSON output to a file.

Each plugin release is a release of this fork. The plugin, the fork's ScalaTest artifacts and the consolidation library share one version, `3.2.20`.

## What it supports

- Mill 1.3.1 only.
- One JVM test module per command, testing with this fork at the plugin's release.
- The module's tests run in the workers Mill's `testForked` starts for each fork group that holds a test class.
  - A module that does not set `testBatchFrameworkTasks` runs through Mill's test queue.
    - With `testParallelism` on (the default), Mill starts a fork group's first worker. It starts each further worker only where a test class remains in the group's queue, up to the lesser of Mill's `--jobs` and the group's test class count. The group's workers run in parallel.
    - With `testParallelism` off, each fork group gets one worker, and the workers run one after another.
  - A module that sets `testBatchFrameworkTasks` is given no queue of its classes. Each fork group gets one worker, which is given the group's whole class list as `testForkGrouping` gives it, whatever the module's `testParallelism`. Where more than one fork group holds a class, the workers run in parallel.
  - A module that does not set `testForkGrouping` has one fork group. A module with no test class still runs one worker.
- Test modules on Scala 3.3 or later. The fork is built with Scala 3.3.8, so a test module on Scala 2, 3.1 or 3.2 cannot use it.
- Human-readable output on stdout, and JSON output to a file in the format [JSON.md](JSON.md) gives. The only output arguments are `-oH`, a bare `-o` and `-fJ <path>`.

## Publishing

Every artifact is published only to the local Ivy repository, `~/.ivy2/local`. Publish in this order.

1. At the repository root, publish the fork and the consolidation library with sbt:

   ```
   sbt scalatestCompatible/publishLocal scalacticDotty/publishLocal scalatestDotty/publishLocal
   ```

   On Windows, run `sbt.bat`. `scalatestDotty` publishes core, the style artifacts and the consolidation library. The sbt build publishes no Scala 2 artifacts.

2. In `mill-plugin/`, publish the plugin with its Mill build:

   ```
   ./mill --no-daemon plugin.publishLocal
   ```

   On Windows, replace `./mill` with `./mill.bat` in GitBash, `.\mill.bat` in PowerShell, or `mill.bat` in cmd. This applies to every `./mill` command below.

   The bootstrap scripts run Mill 1.3.1, as pinned in `mill-plugin/.mill-version`.

## Testing the plugin

Publish the fork and the library first (§ Publishing, step 1). Then, in `mill-plugin/`:

```
./mill --no-daemon plugin.test.testForked + plugin.integration.testForked
```

`plugin.test` holds the unit tests. `plugin.integration` runs Mill 1.3.1 on each fixture build under `plugin/integration/resources/`. It publishes the plugin to its own test repository, so it needs no `plugin.publishLocal`.

## Adding the plugin to a build

The build runs Mill 1.3.1. Add the plugin to the header of its `build.mill`:

```
//| mvnDeps: ["com.edc::scalatest-mill::3.2.20"]
```

If the header already has `mvnDeps`, add `"com.edc::scalatest-mill::3.2.20"` to it.

A test module run by the test command must depend on this fork's `scalatest` at the plugin's release and be built with Scala 3.3 or later:

```scala
//| mvnDeps: ["com.edc::scalatest-mill::3.2.20"]
package build

import mill.*
import mill.scalalib.*

object core extends ScalaModule {
  def scalaVersion = "3.3.8"

  object test extends ScalaTests, TestModule.ScalaTest {
    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")
  }
}
```

Nothing else in the build names the plugin. Mill's own test tasks, such as `mill core.test`, behave as they do without it.

## Running the test command

```
mill scalatest/test <module selector> [-oH | -o] [-fJ <path>] [-- [<class globs>] [-- <framework arguments>]]
```

For example, `mill scalatest/test core.test`.

- **Module selector**: exactly one, resolving to one JVM test module.
- **`-oH`, or no output argument**: stdout holds the human-readable output.
- **`-o`**: stdout holds no consolidated output. The tests still run and decide the result.
- **`-fJ <path>`**: once every run has ended, the file at `<path>` holds the JSON output ([JSON.md](JSON.md)), whatever the tests' outcomes. A relative path resolves against the workspace root, the folder holding `build.mill`. Missing parent folders are created. Once the arguments are accepted, any file already at `<path>` is deleted, so a file there is always the JSON output of the latest test command that wrote one.
- **`--`**: the arguments after it are read as `mill <module>.testOnly` reads its arguments: class globs, then, after a second `--`, framework arguments, as in `mill scalatest/test core.test -- -- -n samples.Fast`. A framework argument is one of ScalaTest's `-D` with a config map entry joined to it (`-Dcolour=blue`), `-P` alone or with a thread count joined to it (`-P4`), or `-n`, `-l`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` or `-W` followed by the values ScalaTest's runner takes with it. The test command checks the framework arguments, but neither they nor the class globs change which tests run.

The module's own ScalaTest arguments (`testArgsDefault`) reach the run only if they are one of these flags, with their values: `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`.
Every other flag, including every reporter flag, is withheld.

The test command fails before any test starts when:

- it is given any other output argument, such as `-oP`, `-oHd`, `-fH` or a second `-fJ`;
- it is given `-fJ` with no path;
- it is given, after a second `--`, anything other than a framework argument, such as `-oD`, `-s`, `-n` with no value after it, `-W` followed by anything but two integers, `-D` with no config map entry, `foo` or a third `--`;
- it is given one of `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`, alone or with its value joined to it, before its first `--` or, where it is given no `--`, anywhere;
- a folder is at the `-fJ` path, or the file there cannot be deleted;
- it is given no module selector, or more than one;
- the module selector resolves to anything other than one JVM test module, such as a Scala.js test module or `__.test`;
- the module's own ScalaTest arguments hold a flag ScalaTest's runner does not recognise.

It also fails when:

- a test fails or is cancelled, a suite aborts, or a run aborts or is stopped;
- a test worker dies;
- laying out a fork group's files fails, or starting or running a test worker fails;
- the test command fails to capture a test worker's output, which goes to Mill's console instead;
- a test worker's test event log cannot be read, or holds a line that cannot be decoded;
- two of the module's fork groups would share a `processFolder`, as where each holds only the same test class or, on a case-insensitive filesystem, only classes whose names differ only in case. No test runs;
- two or more of the module's fork groups hold a test class, and the test command cannot tell whether the filesystem is case-insensitive. No test runs;
- the module does not test with this fork, such as one testing with MUnit, uTest or upstream `org.scalatest:scalatest`;
- the JSON output cannot be written to the `-fJ` path. The human-readable output is still written.

Under the test command, ScalaTest prints no end-of-run summary of its own. Instead, the human-readable output ends with one summary line that counts the suites and tests of every run. Mill's own test tasks still print ScalaTest's summary.

## Runs in the human-readable output

A run's number is its `number` in [JSON.md](JSON.md) § Run node. The human-readable output shows a run's line, `Run <n>:`, only where the run:

- aborted or stopped;
- has output of its own: its worker's stdout or stderr, or a message sent outside any suite.

The output is laid out as:

- The suites of every run whose line is not shown form one list directly under the module's line, ordered by name. A suite that ran in several runs appears once for each, in order of the runs' numbers.
- Each run whose line is shown follows that list, in order of number, so `Run 10:` follows `Run 2:`. It holds its own suites, then its output, then `*** RUN ABORTED ***` or `*** RUN STOPPED ***` where the run aborted or stopped.

For example, fork group 0 runs `Zeta`, whose test writes `hello` to stdout, and fork group 1 runs `Alpha`. Run 1 has output of its own, so its line is shown. Run 2's line is not. The output, without its summary line, is:

```
[core.test]
  Alpha:
    - t
  Run 1:
    Zeta:
      - t
    [stdout] hello
```
