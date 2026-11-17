---
kind: mill-build-tooling
description: How Mill 1.x, its bootstrap scripts, testkit, BSP server and plugin API misbehave, and how to read a meta-build's classpath. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, driving Mill's BSP server, or inspecting a meta-build's classpath.
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

`--ticker true` does not make Mill draw its progress prompt: without a terminal, Mill prints no prompt, and the only status line is the final one (`146/146, 1 FAILED] …`). A test cannot assert that Mill's runner progress is absent; observe it under a pseudo-terminal.
</learning>

<learning>
date: 2026-11-14
---
A Scala 3 file declaring `package <name>` and that package's package object, such as one extending `mill.api.ExternalModule.Alias`, fails to compile with `Cyclic reference involving val <import>` when the file has any import: the package object's members are in scope where the file's imports resolve.

Write no import in that file; name each type from `_root_`, such as `_root_.mill.api.ExternalModule.Alias`.
</learning>

<learning>
date: 2026-11-14
---
`mill.testkit.IntegrationTester.EvalResult`'s `out` and `err` (Mill 1.3.1) strip every ANSI escape sequence and trim the text, so a test of colour that reads them sees none, even under `--color true`.

Read the process's own output, `evalResult.result.out.text()` and `evalResult.result.err.text()`, to test colour.
</learning>

<learning>
date: 2026-11-14
---
A Mill module's `compileMvnDeps` reach neither its runtime classpath nor its `ScalaTests` test module's classpath. A Mill plugin that depends on Mill through `compileMvnDeps` therefore has no Mill or os-lib classes in its unit tests: a test naming `os.*` fails with `Not found: os`.

Keep classes under unit test free of Mill and os-lib types, and do the Mill wiring, such as setting `os.Inherit.out`, in the class that calls Mill. Test that wiring under testkit.
</learning>

<learning>
date: 2026-11-16
---
Mill 1.3.1's BSP server answers every `buildTarget/test` request with the JSON-RPC error `Internal error.`, logging `scala.MatchError: (of class build_.package_$)` from `mill.bsp.worker.MillBspEndpoints.buildTargetTest`. It maps the evaluators' root modules with a partial `case m: BspModuleApi`, and a build's root module (`package build`) is not one. It would also test only targets that are root modules. No test runs.

A test of `buildTarget/test` on Mill 1.3.1 can only compare the error response. Catch the `ExecutionException` whose cause is `org.eclipse.lsp4j.jsonrpc.ResponseErrorException` and record its `getResponseError`.
</learning>

<learning>
date: 2026-11-16
---
To drive Mill 1.3.1's BSP server from a test:

- Spawn `<mill executable> --bsp --jobs 1` in the workspace. This is the argv `--bsp-install` writes to `.bsp/mill-bsp.json`.
- Connect with `ch.epfl.scala:bsp4j:2.2.0-M2`, the version Mill's BSP worker is built with.
- Give lsp4j's `Launcher.Builder` a Scala trait extending `BuildServer`, `ScalaBuildServer` and `JvmBuildServer` as the remote interface.
- Send `build/initialize`, then `build/initialized`, before any request, and `build/shutdown`, then `build/exit`, at the end.

`--bsp` without `--no-daemon` runs through the regular Mill daemon and shares the regular `out/` folder. A test that compares two sessions must stop the daemon between them (`IntegrationTester.removeProcessIdFile()`, with the tester in daemon mode) and remove the module's `out/` folder, or the second session finds the first one's compiled classes and sends no compile notifications.

`buildTarget/scalaTestClasses` is deprecated in bsp4j; calling it under `-Werror` needs `@nowarn("cat=deprecation")`.
</learning>

<learning>
date: 2026-11-17
---
With its ticker on, Mill 1.3.1 re-renders each line a task writes through `Task.log.streams.out` when it adds its prefix (such as `1] `):

- where Mill colours its console output, the prefix itself is coloured, as `ESC[34m1]ESC[39m `;
- an SGR reset `ESC[0m` in the task's line comes out as `ESC[39m`.

The task's own SGR colours reach stdout under `--color false` too. `mill.testkit.IntegrationTester` runs with `--ticker false`, which hides both effects, so pass `--ticker true` to test what a user sees. A test comparing colours on prefixed lines must allow SGR sequences around the prefix and treat `39` as a foreground reset.
</learning>
