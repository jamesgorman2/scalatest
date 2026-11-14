---
kind: mill-build-tooling
description: How Mill 1.x, its bootstrap scripts, testkit and plugin API misbehave, and how to read a meta-build's classpath. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, or inspecting a meta-build's classpath.
---

<learning>
date: 2026-11-14
---
On Windows, Mill's `sh` bootstrap script `mill`, run from GitBash, stops with `This native mill launcher supports only Linux and macOS.` Run `mill.bat` instead; GitBash starts it directly.
</learning>

<learning>
date: 2026-11-14
---
On Windows, `mill.testkit.IntegrationTester` (Mill 1.3.1) cannot start a Mill executable built by `JavaModule.assembly` (`out.jar`) or `JavaModule.launcher` (`run`). `launcher` writes an extensionless script, valid as both shell and batch; `assembly` writes a jar with such a script prepended. `CreateProcess` rejects both, and `os.call` fails with `CreateProcess error=193, %1 is not a valid Win32 application`.

In a separate task, copy the `launcher` script to a `.bat` file, and pass that path as the tester's `millExecutable`. Off Windows, pass the `launcher` script itself.
</learning>

<learning>
date: 2026-11-14
---
`mill --meta-level 1 show compileClasspath` lists the meta-build's classpath, including the jars a `build.mill` header's `//| mvnDeps` adds. Use it to check that a plugin and its dependencies reached the meta-build.

`show` prints each `PathRef` as `ref:v0:<hash>:<path>` or `qref:v1:<hash>:<path>`; on Windows the path holds a drive colon. Read the output with `upickle.default.read[Seq[mill.api.PathRef]]`, never by splitting on `:`.

A Maven-layout repository, such as a `publishLocalTestRepo` folder, stores a jar under one folder per group part (`com/edc/…`); the local Ivy repository, under one folder for the whole group (`com.edc/…`).
</learning>

<learning>
date: 2026-11-14
---
In Mill 1.3.1, a `Task.Command` taking a `mill.api.Evaluator` parameter, declared outside Mill's own sources, fails `Discover[this.type]` with `No mainargs.TokensReader[mill.api.Evaluator] found for parameter ...`. Mill's own modules get the reader through `import mill.{Task, given}` or `mill.util.TokenReaders.*`.

Import `mill.util.TokenReaders.millEvaluatorTokenReader` in the file declaring the command.
</learning>

<learning>
date: 2026-11-14
---
`mill.testkit.IntegrationTester` (Mill 1.3.1) runs Mill with `--ticker false`. Without the ticker, Mill puts no prefix such as `1] ` on the lines a task writes, so a test of what a user sees at a console misses the prefix.

To test the prefix, pass `--ticker true` ahead of the task in the arguments to `eval`; Mill takes the later flag.
</learning>

<learning>
date: 2026-11-14
---
A Scala 3 file declaring `package <name>` and that package's package object, such as one extending `mill.api.ExternalModule.Alias`, fails to compile with `Cyclic reference involving val <import>` when the file has any import: the package object's members are in scope where the file's imports resolve.

Write no import in that file; name each type from `_root_`, such as `_root_.mill.api.ExternalModule.Alias`.
</learning>
