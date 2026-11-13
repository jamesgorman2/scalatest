---
kind: sbt-build-tooling
description: How sbt, its plugins and dependency resolution misbehave or pass silently. Read before changing toolchain versions or MiMa settings, or running sbt from GitBash on Windows.
---

<learning>
date: 2026-11-13
---
Scala Native publishes its Scala 3 library, `org.scala-native:scala3lib_native0.5_3`, once for each pair of Scala version and Scala Native version, as `<scala>+<native>`. A Scala 3 patch released after a Scala Native release exists only for later Scala Native releases: Scala 3.3.8 has `3.3.8+0.5.12` and nothing for 0.5.10 or 0.5.11.

A pair that was never published fails only at `update`, with `Error downloading org.scala-native:scala3lib_native0.5_3:<scala>+<native>`.

Before fixing a Scala 3 version and a Scala Native version together, check the pair in `https://repo1.maven.org/maven2/org/scala-native/scala3lib_native0.5_3/maven-metadata.xml`.
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

Redirecting a file of commands to `sbt.bat`'s stdin starts the interactive shell, which never exits.

To run such a command in batch mode, put it in a temporary `.sbt` file at the build root as `addCommandAlias("name", """set …""")` and pass `name` as the argument, or switch a setting on a `-Dkey=value` system property. A `set` through an alias is applied after every project's settings; a setting for another project written directly in a root `.sbt` file is not, and that project's own `:=` overrides it.
</learning>

<learning>
date: 2026-11-13
---
On Windows, sbt-osgi's `bundle` task rewrites the project's jar on every run and fails with `FileSystemException: … being used by another process` where the same sbt session has already opened that jar, as compiling a dependent project or running MiMa does.

Run a task that bundles and publishes, such as `publishLocal`, in a fresh sbt session or before any task that reads the jars.
</learning>
