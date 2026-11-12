# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The test command is invoked under the plugin's own `scalatest/` prefix, outside every module the build defines.

**Alternatives**

- A command on a trait or module the build defines needs a build change beyond adding the plugin (`../SPECIFICATION.md` R-092), and puts a `test` command where `mill __.test` reaches it (R-060).
- A `def` alias in `build.mill` adds a line to every build beyond the plugin's, and may put a `test` command where `mill __.test` reaches it (R-060).

**Reasoning** The plugin ships the command on an `ExternalModule`, which nothing in the build names. Mill reaches an external module only through an explicit `<prefix>/` selector, never through a wildcard resolved from the build's root module, so the command can be named `test` while R-060 holds and the build need not mention it.

**Costs** The plugin claims the top-level name `scalatest` on the meta-build's classpath, and a build wanting a shorter name of its own waits for R-126's preset module (`../ROADMAP.md` § 17).

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

**See** `research/mill-plugin-conventions.md` § 3, § 8.3.

---
