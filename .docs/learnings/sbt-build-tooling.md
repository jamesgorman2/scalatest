---
kind: sbt-build-tooling
description: How sbt, its plugins and dependency resolution misbehave or pass silently. Read before changing toolchain versions, MiMa settings or sbt configurations, running sbt from GitBash on Windows, or packaging dependent projects in one sbt session on Windows.
---

<learning>
date: 2026-11-13
---
Scala Native publishes its Scala 3 library, `org.scala-native:scala3lib_native0.5_3`, once for each pair of Scala version and Scala Native version, as `<scala>+<native>`. A Scala 3 patch released after a Scala Native release exists only for later Scala Native releases: Scala 3.3.8 has `3.3.8+0.5.12` and nothing for 0.5.10 or 0.5.11.

A pair that was never published fails only at `update`, with `Error downloading org.scala-native:scala3lib_native0.5_3:<scala>+<native>`.

Before pinning a Scala 3 version and a Scala Native version together, check the pair in `https://repo1.maven.org/maven2/org/scala-native/scala3lib_native0.5_3/maven-metadata.xml`.
</learning>

<learning>
date: 2026-11-13
---
In sbt-mima-plugin 1.1.4, `mimaFailOnNoPrevious` fails the check only where `mimaPreviousArtifacts` is unset. Other cases:

- An explicitly empty set passes, logging `mimaPreviousArtifacts is empty, not analyzing binary compatibility`.
- An artifact that does not resolve fails in `mimaPreviousClassfiles` with a `ResolveException`.
- A wrong artifact that does resolve is compared without complaint.

A passing `mimaReportBinaryIssues` therefore does not show that the intended artifact was compared. Read `mimaPreviousClassfiles`, which maps each previous module to the jar it resolved, and check the jar's path and group.
</learning>

<learning>
date: 2026-11-13
---
From GitBash on Windows, `sbt.bat` passes each argument through `cmd`, which breaks an sbt command holding double quotes or parentheses:

- `'set p / libraryDependencies += "g" % "a" % "v"'` reaches sbt with its strings dropped and fails with `Error parsing expression`.
- An argument holding `(x / y).value` stops the launcher with `.value was unexpected at this time`.
- An sbt command wrapped as `'"…"'` stops the launcher with `The syntax of the command is incorrect.`, exit code 255, before any build starts.

Pass an sbt command holding spaces as one plain single-quoted argument: `sbt.bat 'project/testOnly package.Prefix*'`.

Redirecting a file of commands to `sbt.bat`'s stdin starts the interactive shell, which never exits.

To run such a command in batch mode, either:

- put it in a temporary `.sbt` file at the build root as `addCommandAlias("name", """set …""")` and pass `name` as the argument; or
- make the setting depend on a `-Dkey=value` system property.

A `set` through an alias is applied after every project's settings; a setting for another project written directly in a root `.sbt` file is not, and that project's own `:=` overrides it.
</learning>

<learning>
date: 2026-11-13
---
On Windows, sbt-osgi's `bundle` task rewrites the project's jar on every run and fails with `FileSystemException: … being used by another process` where the same sbt session has already bundled a dependent project. sbt-osgi 0.9.6 reads `dependencyClasspathAsJars`, so the dependent's bundle opens the jar of each project it depends on and keeps it open.

- Packaging or publishing a dependent project bundles it. So does running `mimaReportBinaryIssues` on it where `mimaCurrentClassfiles` is the packaged jar.
- Compiling a dependent project does not open the jar: its classpath holds the dependency's `classes` directory.
- Running `mimaReportBinaryIssues` on the project itself does not hold its jar open.

In one sbt session, bundle each project once, dependencies first. Bundle a project again only in a fresh session.
</learning>

<learning>
date: 2026-11-13
---
Scala 3.3 names the inline accessor of a private member of a non-final class `<owner>$$inline$<member>`. Scala 3.1 named it `inline$<member>`. Code compiled against a library built with Scala 3.1 calls the accessor by that name, so the same library sources rebuilt with Scala 3.3 fail it at run time with `NoSuchMethodError: '… <Class>.inline$<member>(…)'`.

sbt-mima-plugin 1.1.4's `mimaReportBinaryIssues` passes with the accessor missing, so a passing MiMa check does not show that code compiled against the earlier build still links.

A library's platforms can differ in the name the earlier release holds. Upstream built ScalaTest 3.2.20 with Scala 3.1 for the JVM and Scala.js, and with Scala 3.3 for Native: its `_3` and `_sjs1_3` jars hold `inline$<member>`, and its `_native0.5_3` jars only `<owner>$$inline$<member>`. Read each platform's jar before restoring an accessor there. The jars of all three hold `.class` files that `javap` reads.

- To find lost accessors, list each class's methods with `javap -p` in the earlier jar and in the rebuilt classes, and compare those whose names hold `inline$`.
- To restore one, declare a public `def inline$<member>(…)` in the class with the private member's parameters, forwarding to it.
- Running code compiled against the earlier build on the rebuilt one proves the fix only for the accessors that code calls. Comparing the class files covers them all.
</learning>

<learning>
date: 2026-11-13
---
sbt forces the Scala library to `scalaVersion` only in its standard configurations. A configuration of a project's own, extending neither `Compile` nor `Test`, resolves the `scala3-library_3` its dependencies ask for: 3.1.3 for `org.scalatest:scalatest_3:3.2.20`.

Compiling there with Scala 3.3 then fails with `value getOffsetStatic is not a member of object scala.runtime.LazyVals` and `Bad symbolic reference. A signature in … scala3-library_3-3.1.3.jar(scala/runtime/LazyVals.class)`.

Pin the library for such a configuration with `dependencyOverrides += "org.scala-lang" %% "scala3-library" % scalaVersion.value`.

sbt's slash syntax addresses a configuration by its capitalised name, not by the `val` that holds it: `config("fixture")` is `<project>/Fixture/compile`.
</learning>
