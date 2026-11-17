# Learnings index

Each kind maps to a per-kind file in this directory.

| Kind | Description |
|---|---|
| [jvm-platform](jvm-platform.md) | How JDK file, process and I/O APIs behave differently by operating system, especially on Windows. Read before writing JVM code that shares a file or stream between processes, or a test that creates a symbolic link. |
| [mill-build-tooling](mill-build-tooling.md) | How Mill 1.x, its bootstrap scripts, testkit, BSP server and plugin API misbehave, and how to read a meta-build's classpath. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, driving Mill's BSP server, or inspecting a meta-build's classpath. |
| [sbt-build-tooling](sbt-build-tooling.md) | How sbt, its plugins, incremental compilation and dependency resolution misbehave or pass silently. Read before changing toolchain versions, MiMa settings or sbt configurations, relying on a passing MiMa check, running sbt from GitBash on Windows, packaging dependent projects in one sbt session on Windows, or linking Scala Native on Windows. |
| [scalatest-internals](scalatest-internals.md) | Where upstream ScalaTest 3.2.20's sources, styles and runners behave otherwise than their names suggest. Read before writing a suite, sample or test that depends on which styles ship, on which tests a style registers ignored, on how Framework or Runner filters, orders, threads or reports a suite's tests, on when a suite registers its tests, on a run asked to stop, on a failure's source pathname, or on a reporter's output on Scala.js or Native; and before changing a style trait's private registration members. |

## Curating learnings

Edit this register only after reading the `add-learning` skill
(`.claude/skills/add-learning/SKILL.md`), and always follow its rules.
