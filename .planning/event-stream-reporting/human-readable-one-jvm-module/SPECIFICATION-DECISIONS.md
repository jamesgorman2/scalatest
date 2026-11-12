# Decisions: consolidated human-readable output for one JVM module

Decisions behind [SPECIFICATION.md](SPECIFICATION.md), newest first.

## The test command is invoked as `mill scalatest/test`, a command of an `ExternalModule` the plugin ships.

**Alternatives**

- A reverse-DNS external module, such as `mill org.scalatest.mill.ScalaTest/test`, is typed in full on every invocation.
- A command on a trait or module the build defines needs a build change beyond adding the plugin (`../SPECIFICATION.md` R-092), and sits under the build's root module, where `mill __.test` reaches a command named `test` (R-060).
- A `def` alias the build author adds in `build.mill` costs every build one more line, and whether `mill __.test` reaches a `test` command under it is not determined.

**Reasoning** Mill reaches an external module only through an explicit `<prefix>/` selector, never through a wildcard resolved from the build's root module, so the command can be named `test` while R-060 holds. A single-segment prefix keeps what is typed beyond the module selector to `scalatest/test`, with nothing in the build referring to it.

**Costs** The plugin claims the top-level name `scalatest` on the meta-build's classpath. That Mill resolves `scalatest/` through a package-object alias was read from Mill 1.3.1's source and has not been run (`TECHNICAL-DESIGN.md` § Critical code paths § The test command's entry point). A build wanting a shorter name of its own waits for R-126's preset module (`../ROADMAP.md` § 17).

**Settled by** [What is the plugin's user-facing surface?](https://github.com/jamesgorman2/scalatest/issues/61).

**See** `research/mill-plugin-conventions.md` § 3, § 8.3.

---
