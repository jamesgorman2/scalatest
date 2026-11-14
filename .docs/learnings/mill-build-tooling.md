---
kind: mill-build-tooling
description: How Mill 1.x, its bootstrap scripts and its testkit misbehave on Windows or report their state. Read before running Mill from GitBash on Windows, writing Mill integration tests, or inspecting a meta-build's classpath.
---

<learning>
date: 2026-11-14
---
On Windows, Mill's `sh` bootstrap script `mill`, run from GitBash, stops with `This native mill launcher supports only Linux and macOS.` Run `mill.bat` instead; GitBash starts it directly.
</learning>

<learning>
date: 2026-11-14
---
On Windows, `mill.testkit.IntegrationTester` (Mill 1.3.1) cannot start a Mill executable built by `JavaModule.assembly` (`out.jar`) or `JavaModule.launcher` (`run`). Both are extensionless scripts valid as shell and batch at once, and `os.call` fails with `CreateProcess error=193, %1 is not a valid Win32 application`.

Copy the `launcher` script to a file ending `.bat` in a task of its own, and pass that path as the tester's `millExecutable`. Off Windows, pass the `launcher` script itself.
</learning>

<learning>
date: 2026-11-14
---
`mill --meta-level 1 show compileClasspath` lists the meta-build's classpath, including the jars a `build.mill` header's `//| mvnDeps` adds. Use it to check that a plugin and its dependencies reached the meta-build.

`show` prints each `PathRef` as `ref:v0:<hash>:<path>` or `qref:v1:<hash>:<path>`; on Windows the path holds a drive colon. Read the output with `upickle.default.read[Seq[mill.api.PathRef]]`, never by splitting on `:`.

A jar from a Maven-layout repository, such as a `publishLocalTestRepo` folder, sits under one folder per group part (`com/edc/…`); one from the local Ivy repository, under one folder for the whole group (`com.edc/…`).
</learning>
