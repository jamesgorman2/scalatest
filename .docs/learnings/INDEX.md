# Learnings index

Each kind maps to a per-kind file in this directory.

| Kind | Description |
|---|---|
| [jvm-platform](jvm-platform.md) | How JDK file, process and I/O APIs behave differently by operating system, especially on Windows. Read before writing JVM code that shares a file or stream between processes, or a test that creates a symbolic link. |
| [mill-build-tooling](mill-build-tooling.md) | How Mill 1.x, its bootstrap scripts, testkit, BSP server and plugin API misbehave, and how to read a meta-build's classpath. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, driving Mill's BSP server, or inspecting a meta-build's classpath. |
| [sbt-build-tooling](sbt-build-tooling.md) | How sbt, its plugins, cross-builds and incremental compilation misbehave or pass silently. Read before changing the sbt build or toolchain versions, relying on a passing check, or running sbt on Windows. |
| [scalatest-internals](scalatest-internals.md) | Where upstream ScalaTest 3.2.20's styles, runners and reporters behave otherwise than their names suggest. Read before relying on how ScalaTest registers, runs, orders or reports tests, or changing its style traits. |

## Curating learnings

Edit this register only after reading the `add-learning` skill
(`.claude/skills/add-learning/SKILL.md`), and always follow its rules.
