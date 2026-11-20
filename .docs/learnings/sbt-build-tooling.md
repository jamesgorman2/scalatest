---
kind: sbt-build-tooling
description: How sbt, its plugins, incremental compilation, generated sources and dependency resolution misbehave or pass silently. Read before changing toolchain versions, MiMa settings or sbt configurations, relying on a passing MiMa check or on `-Werror` covering Scaladoc, running sbt from GitBash on Windows, packaging dependent projects in one sbt session on Windows, linking Scala Native on Windows, adding JVM-only code to Scala 3 core sources, or relying on a build-generated source being current after a version bump.
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

<learning>
date: 2026-11-14
---
sbt-mima-plugin 1.1.4 does not report a change to a package-qualified private class or member, such as `private[scalatest]`, of a Scala 3 artifact, though each is public in bytecode. A `private[scalatest]` class whose only constructor gained a parameter passed `mimaReportBinaryIssues` with no filter.

A passing check therefore does not show that a jar of another artifact in the same package, compiled against the earlier release, still links: upstream splits ScalaTest into artifacts that share package `org.scalatest` and call each other through such members.

Where a caller outside the artifact may exist, keep the earlier signature beside the new one, as an overload or an auxiliary constructor.

It does report a change to an unqualified `private` class nested in a public class, which no other artifact can call: a `private class` inside a public class whose constructor gained a parameter failed with `DirectMissingMethodProblem` on `<Outer>#<Inner>.this`. Where no problem filter may be added, keep that class's earlier constructor as an auxiliary constructor too.
</learning>

<learning>
date: 2026-11-15
---
In an sbt build of a Scala 3 project, an incremental compile can fail after a type changes kind, from an opaque type to a class or the reverse. An opaque type lives in the enclosing file's `<File>$package` class, so the stale analysis still references it there. On Windows this failed with `Required: <package>.<File>$package.<Type> … could not resolve reference`.

Run `sbt.bat <project>/clean` and recompile before suspecting the code. When a type changes kind in a Scala 3 project, clean it first.
</learning>

<learning>
date: 2026-11-15
---
Scala Native needs clang only to link: `compile` produces NIR without it, but `nativeLink`, and so `test` and `run` of a Native project, fail before linking with `'clang' not found in PATH or via 'LLVM_BIN' environment variable.` A Windows machine may have neither LLVM on `PATH` nor the `clang.exe` of Visual Studio's LLVM component; Visual Studio's `VC/Tools/Llvm` can hold only `clang-format` and `clang-tidy`.

On Windows, set `LLVM_BIN` to the `bin` directory of an LLVM release whose target is `x86_64-pc-windows-msvc` before starting sbt; with the MSVC build tools installed it links. Report a Native run that could not link as not run, never as passed.
</learning>

<learning>
date: 2026-11-18
---
The Scala.js and Scala Native core builds have no source directories of their own for shared code: `GenScalaTestDotty.genScalaJS` and `genScalaNative` copy the Scala 3 core's top-level `org/scalatest`, `events`, `enablers` and `expectations` directories, and the JVM core's `tools` directory less a skip list, into `src_managed`. The Scala 3 core's `tools` directory is not copied, so it is JVM-only.

A file in a copied directory that uses a JVM-only API, such as `java.time`, breaks both builds. Keep the JVM-only part in a directory that is not copied, or in a JVM-only project such as `scalatestConsolidation`, rather than adding the file to a `copyDir` skip list: a skipped file is missing from Scala.js and Native for any code there that needs it. Compile `scalatestCoreDottyJS` and `scalatestCoreDottyNative` to confirm.

`copyDir` never deletes a file dropped from the copy. The stale file stays in `src_managed` but is not compiled, since only the generators' output is. Scala Native's incremental compile also leaves the dropped file's `.nir` files in `classes`; clean the project before trusting its `classes` directory.
</learning>

<learning>
date: 2026-11-18
---
A build that generates a source file holding the build's `version` can regenerate it only when the generator's own source changes, not when `version` changes. An incremental build after a version bump then compiles in the old version string, while a clean build is correct. Anything that reports the constant, such as a JSON output field recording the ScalaTest version, shows the stale value.

Before trusting a version string read from a generated constant, run a clean build, or touch the generator's source, after any version bump.
</learning>

<learning>
date: 2026-11-21
---
Scaladoc 3 does not apply `-Werror` or `-old-syntax`: `<project>/doc` logs `Skipping unused scalacOptions: -Werror, … -old-syntax, …` and continues. A Scaladoc link it cannot resolve, written as `[[…]]` or as a `@throws` target, is only a warning, `Couldn't resolve a member for the given link query: <target>`, so `doc` and `publishLocal` succeed and the broken link ships in the published API documentation.

`-Werror` on a project therefore does not keep its API documentation warning-free. Run `<project>/doc` and read its warnings. Name a class outside the file's imports by its full name, such as `@throws java.lang.IllegalStateException`, and a class member from a companion object as `[[Owner#member]]`.
</learning>