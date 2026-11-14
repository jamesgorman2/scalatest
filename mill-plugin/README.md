# ScalaTest's Mill plugin

The plugin `com.edc:scalatest-mill_mill1_3` adds the test command `mill scalatest/test`. It runs one JVM test module's tests and writes their consolidated human-readable output to stdout.

Every release of the plugin is a release of this fork. The plugin, the fork's ScalaTest artifacts and the consolidation library share one version, the fork's release: `3.2.20`.

## What it supports

- Mill 1.3.1 only.
- One JVM test module per command, testing with this fork at the plugin's release.
- Test modules on Scala 3.3 or later. The fork is built with Scala 3.3.8, so a test module on Scala 2, 3.1 or 3.2 cannot use it.
- Human-readable output on stdout. The only output arguments are `-oH` and a bare `-o`.

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

   On Windows, run `mill.bat`, from GitBash too. The bootstrap scripts run Mill 1.3.1, which `mill-plugin/.mill-version` pins.

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

A test module the test command runs depends on this fork's `scalatest` at the plugin's release, and is built with Scala 3.3 or later:

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
mill scalatest/test <module selector> [-oH | -o]
```

For example, `mill scalatest/test core.test`.

- **Module selector**: exactly one, resolving to one JVM test module.
- **`-oH`, or no output argument**: stdout holds the human-readable output.
- **`-o`**: stdout holds no consolidated output. The tests still run and decide the result.

The module's own ScalaTest arguments, its `testArgsDefault`, reach the run only for these flags, with their values: `-D`, `-n`, `-l`, `-P`, `-m`, `-w`, `-F`, `-T`, `-t`, `-z`, `-S` and `-W`. Every other flag, including every reporter's, is withheld.

The test command fails before any test starts when:

- it is given any other output argument, such as `-oP`, `-oHd` or an `-f`;
- it is given no module selector, or more than one;
- the module selector resolves to anything other than one JVM test module, such as a Scala.js test module or `__.test`;
- the module has more than one fork group;
- the module's own ScalaTest arguments hold a flag ScalaTest's runner does not recognise.

It also fails when:

- a test fails or is cancelled, a suite aborts, or a run aborts or is stopped;
- the test worker dies;
- the module does not test with this fork, such as one testing with MUnit, uTest or upstream `org.scalatest:scalatest`.

Under the test command, ScalaTest prints no end-of-run summary. Mill's own test tasks still print it.
