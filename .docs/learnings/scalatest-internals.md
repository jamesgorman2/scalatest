---
kind: scalatest-internals
description: Where upstream ScalaTest 3.2.20's sources, styles and runners behave otherwise than their names suggest. Read before writing a suite, sample or test that depends on which styles ship or on how Framework or Runner filters a suite's tests.
---

<learning>
date: 2026-11-14
---
ScalaTest 3.2.20 ships no `AsyncPropSpec` or `FixtureAsyncPropSpec`. Their sources, and those of their `Like` traits, are in `jvm/propspec/src/main/scala/org/scalatest/propspec/`, but each file is one block comment, opening at `/*package org.scalatest.propspec`.

A search for `trait AsyncPropSpecLike` or `class AsyncPropSpec` therefore finds text that is never compiled, and code added inside it has no effect.

The PropSpec styles that ship are `AnyPropSpec` and `FixtureAnyPropSpec`. Before relying on a style, check that its source file is not commented out, or that its class is in the published jar.
</learning>

<learning>
date: 2026-11-14
---
`org.scalatest.tools.Framework` given a `TaskDef` that is explicitly specified, with a `SuiteSelector`, runs none of the tests of the suites that the task's suite nests. Its filter then requires `Suite.SELECTED_TAG` and gives that tag to the task's own suite ID alone. Each nested suite still starts and completes, with no test.

A `TaskDef` that is not explicitly specified keeps the nested suites' tests, but `Framework` returns a task for it only where the class is discoverable, so not for a class annotated `@DoNotDiscover`.

To run a suite by name with its nested suites' tests, use `Runner.run` with `-s <class>`, whose filter requires no selected tag.
</learning>
