# Learnings index

Each kind maps to a per-kind file in this directory.

| Kind | Description |
|---|---|
| [sbt-build-tooling](sbt-build-tooling.md) | How sbt, its plugins and dependency resolution misbehave or pass silently. Read before changing toolchain versions, MiMa settings or sbt configurations, relying on a passing MiMa check, running sbt from GitBash on Windows, or packaging dependent projects in one sbt session on Windows. |
| [scalatest-internals](scalatest-internals.md) | Where upstream ScalaTest 3.2.20's sources, styles and runners behave otherwise than their names suggest. Read before writing a suite, sample or test that depends on which styles ship, on how Framework or Runner filters, orders, threads or reports a suite's tests, on when a suite registers its tests, or on a run asked to stop; and before changing a style trait's private registration members. |

## Curating learnings

Edit this register only after reading the `add-learning` skill
(`.claude/skills/add-learning/SKILL.md`), and always follow its rules.
