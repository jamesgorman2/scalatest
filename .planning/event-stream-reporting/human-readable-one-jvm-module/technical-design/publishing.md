# Consolidated human-readable output for one JVM module: publishing

Technical design of how every artifact is built and published under `com.edc`, and how MiMa checks them against ScalaTest 3.2.20. Part of [../TECHNICAL-DESIGN.md](../TECHNICAL-DESIGN.md).

## Publishing

The group change of `../../ARCHITECTURE.md` § Modules reaches both builds. Every artifact is published at one version, the fork's release, and the plugin depends on core and the library at its own version.

- The fork's release is the sbt build's `releaseVersion`, `3.2.20`, so the JVM's core is `com.edc:scalatest-core_3:3.2.20`.
- The fork builds and publishes only the Scala 3 projects of `project/DottyBuild.scala` and the Java-only `scalatestCompatible` of `project/scalatest.scala`, which `scalatestCoreDotty` and `scalatestAppDotty` depend on. Each has `organization` `com.edc`, those upstream publishes under `org.scalatest` and `org.scalactic` alike.
- The build's Scala 2 projects stay as upstream wrote them, group included, so that a merge from upstream's `3.2.x-new` branch applies to them unchanged. The fork neither builds nor publishes them, and nothing keeps them compiling. `scalatestCompatible` is the one project of `project/scalatest.scala` the fork changes.
  - The build definition must still compile with the upgraded sbt plugins, so a Scala 2 project's setting changes where it no longer compiles. The change is the smallest that compiles and keeps the setting's behaviour. The one case is `scalatestTestJS` of `project/JsBuild.scala`: Scala.js 1.20.2 rejects `withStrictFloats`, so the call is removed. Strict floats are that version's default.
- Every Scala 3 project compiles with the Scala, Scala.js and Scala Native versions `../../ARCHITECTURE.md` § Modules § ScalaTest core gives. Upstream's 3.2.20 build sets:
  - Scala 3.1.3 for the JVM and Scala.js (`dottyVersion`'s default);
  - Scala 3.3.3 for Native (`nativeDottySettings`);
  - Scala.js 1.8.0 (`scalaJSVersion`'s default, in both `project/plugins.sbt` and `project/BuildCommons.scala`);
  - Scala Native 0.5.10 (`scalaNativeVersion`'s default, in `project/plugins.sbt`), which has no Scala 3.3.8 library.

  The feature changes those defaults, so the build needs no `SCALAJS_VERSION` or `SCALANATIVE_VERSION` environment variable.
- `publishLocal` of a Scala 3 project must work on Windows, for the dependency check below and for the plugin's Mill build. Upstream's doc settings pass `-doc-source-url`, which Scaladoc 3 does not have: it reads the URL as a source path, and Windows rejects that path. The Scala 3 projects that take those settings drop the option and its URL.
- MiMa compares with upstream's 3.2.20 (`../../SPECIFICATION.md` R-004), and `previousReleaseVersion` is `3.2.20`.
  - `project/plugins.sbt` upgrades `sbt-mima-plugin` from upstream's 0.7.0 to 1.1.4. Under the build's sbt 1.11.0, which is also upstream's, 0.7.0's `mimaReportBinaryIssues` fails on every project, Java-only `scalatestCompatible` included, with `NoSuchMethodError: 'scala.tools.nsc.settings.AbsSettings$AbsSetting scala.tools.nsc.Settings.verbose()'`.
  - The check covers every project the fork publishes whose artifact upstream 3.2.20 also publishes, on all three runtimes: every published Scala 3 project but the consolidation library, and `scalatestCompatible`.
  - Each covered project's `mimaPreviousArtifacts` names upstream's group, `org.scalatest` or `org.scalactic`, the project's `moduleName`, its platform cross-version and `3.2.20`, and its `mimaCurrentClassfiles` is the jar the project packages. `scalatest-compatible` has no Scala suffix.
  - Upstream's build sets both keys on eight Scala 3 projects and on `scalatestCompatible`, and names the right artifact only on `scalatestAppDotty` and `scalatestAppDottyJS`.
    - The other six Scala 3 projects build the artifact's name and the jar's from `name`, which is the project ID where a project sets only `moduleName`. Those on Scala.js and Native use `%%`, which leaves out the platform suffix.
    - `scalatestCompatible` uses `%%`, which adds a Scala suffix.
    - The feature sets both keys on every covered project.
  - MiMa passes where a class loses an inline accessor, a public method whose name holds `$`. Scala 3.3 names the accessor of a private member of a non-final class `<class>$$inline$<member>`, where Scala 3.1 named it `inline$<member>`. Upstream built 3.2.20 for the JVM and Scala.js with Scala 3.1, so code compiled against either calls the accessor by the Scala 3.1 name.
    - 3.2.20's JVM and Scala.js artifacts hold 34 such accessors, all named `inline$applyImpl`, in the 22 nested classes of the `FunSpec`, `FunSuite`, `PropSpec` and `FeatureSpec` style traits whose inline `apply` reaches a private `applyImpl`. On the JVM and Scala.js each of those classes therefore declares each `inline$applyImpl` itself, forwarding to `applyImpl`.
    - Upstream built 3.2.20 for Native with Scala 3.3, so its Native artifacts hold only the Scala 3.3 name. The Native sources leave the forwarders out, between `// SKIP-SCALATESTNATIVE-START` and `// SKIP-SCALATESTNATIVE-END`, so the fork's Native artifacts add no method 3.2.20's lack.
    - § Testing § MiMa gives the check that guards the accessors.
- A dependency of a Scala 3 project on a ScalaTest+ artifact excludes upstream's groups. Its upstream ScalaTest would otherwise sit on the classpath beside the fork's, which no longer evicts it.
- The consolidation library is a JVM-only project of `project/DottyBuild.scala`, depending on its Scala 3 scalatest-core project. `scalatestDotty` aggregates it, so the sbt build publishes it with core, before the plugin's Mill build resolves both.
- A task of the library's project checks `../specification/compatibility-and-library.md` AC-062. It publishes the library and the projects it depends on with `publishLocal`, resolves the published library transitively from the local Ivy repository and Maven Central alone, and fails on any dependency with group `com.lihaoyi` and a name beginning `mill-`.
- The plugin's Mill build is `mill-plugin/build.mill`, pinned to Mill 1.3.1 by `mill-plugin/.mill-version` and run through the `mill` and `mill.bat` bootstrap scripts beside it.
  - Module `plugin` compiles with Mill 1.3.1's Scala, 3.9.0, against `mill-libs` 1.3.1 in `provided` scope. `plugin.publishLocal` publishes `com.edc:scalatest-mill_mill1_3` at the fork's release to the local Ivy repository.
  - Its dependencies on `scalatest-core_3` and `scalatest-consolidation_3` take a single colon before the version, since `::` would append the plugin's `_mill1` platform suffix.
  - `plugin.integration` uses `mill.testkit.IntegrationTester` to run a Mill 1.3.1 launcher, built from `mill-runner-launcher_3`, on each fixture build under `plugin/integration/resources/`.
  - The tests' `COURSIER_REPOSITORIES` lists the plugin's `publishLocalTestRepo` folder, then `ivy2Local|central`. A fixture therefore resolves the plugin as this build last compiled it, and the library and the fork from the local Ivy repository.
  - On Windows the launcher is copied to a `.bat` file, because Windows starts the extensionless script Mill's `launcher` writes only as a batch file. `CreateProcess` also rejects `assembly`'s jar, which has a script prepended.
- These commands, in order, publish what the integration tests resolve and then run them:
  1. At the repository root, `sbt.bat scalatestCompatible/publishLocal scalacticDotty/publishLocal scalatestDotty/publishLocal` (`sbt` off Windows). `scalatestDotty` aggregates core, the style artifacts and the consolidation library but neither `scalactic` nor `scalatest-compatible`, which are published first.
  2. In `mill-plugin/`, `./mill --no-daemon plugin.test.testForked + plugin.integration.testForked` (`mill.bat` on Windows). `plugin.test` holds the plugin's unit tests. The integration tests compile and publish the plugin to their test repository themselves.

  `./mill --no-daemon plugin.publishLocal` then publishes the plugin for builds outside the tests.

## Testing

### MiMa

`../specification/compatibility-and-library.md` AC-058 runs the MiMa check on every project § Publishing covers. The check fails for a covered project that resolves no previous artifact.

The check runs with no problem filter, since a filter would let it pass with a break it would otherwise report:

- The `mimaBinaryIssueFilters` upstream's build sets on covered projects are removed. They excuse upstream's own changes since 3.2.19, and against 3.2.20 they could only hide a break the fork makes.
- Those it sets on Scala 2 projects stay as upstream wrote them (§ Publishing). The fork runs no check on those projects.
- Review rejects any filter this feature adds.

A passing check does not prove that code compiled against 3.2.20 links: MiMa does not report a lost inline accessor (§ Publishing). A test in the comparison's test project (`testing.md` § Testing § Comparison with ScalaTest 3.2.20) covers that gap on the JVM:

- It reads the class files of the `org.scalatest` and `org.scalactic` packages on 3.2.20's resolved classpath and on the fork's runtime classpath. It fails where a class of 3.2.20 declares a method whose name holds `inline$` and the fork's class declares none of that name and erased descriptor.
- It covers every such accessor of every JVM artifact, whichever registrations the compatibility fixture calls.
- Nothing automated covers the Scala.js artifacts. Their forwarders are generated from the same source lines as the JVM's, and the AC-116 comparison links only the accessors its fixture calls.
- The Native artifacts need no such check while the fork builds them with Scala 3.3, as upstream built 3.2.20's.
