# Learnings index

Each kind maps to a per-kind file in this directory.

| Kind | Description |
|---|---|
| [agent-tooling](agent-tooling.md) | How the agent's own shell tools, such as the Bash and PowerShell tools, behave differently from what was requested. Read before running a long command or relying on a tool's report of how long a command took. |
| [jvm-platform](jvm-platform.md) | How JDK file, process and I/O APIs behave differently by operating system, especially on Windows. Read before writing JVM code that shares a file or stream between processes, or a test that creates a symbolic link. |
| [jvm-libraries](jvm-libraries.md) | Where third-party JVM libraries, such as JSON parsers and schema validators, impose limits or requirements their APIs do not show. Read before adding a JVM library dependency or relying on one to check large or deeply nested input. |
| [mill-build-tooling](mill-build-tooling.md) | How Mill 1.x, its bootstrap scripts, testkit, test runners, BSP server and plugin API misbehave or constrain a build. Read before running Mill from GitBash on Windows, writing a Mill plugin or its integration tests, tuning a parallel test run, driving Mill's BSP server, or inspecting a meta-build's classpath. |
| [sbt-build-tooling](sbt-build-tooling.md) | How sbt, its plugins, cross-builds and incremental compilation misbehave or pass silently. Read before changing the build or toolchain versions, relying on a passing check, running sbt on Windows, or adding JVM-only code to shared core sources. |
| [scalatest-internals](scalatest-internals.md) | Where upstream ScalaTest 3.2.20's styles, runners and reporters behave otherwise than their names suggest. Read before relying on how ScalaTest registers, runs, orders or reports tests, changing its style traits, or refactoring code a stack-trace test covers. |

## Curating learnings

Edit this register only after reading the `add-learning` skill
(`.claude/skills/add-learning/SKILL.md`), and always follow its rules.
