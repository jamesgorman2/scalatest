---
kind: scalatest-internals
description: Where upstream ScalaTest 3.2.20's sources, styles and runners behave otherwise than their names suggest. Read before writing a suite, sample or test that depends on which styles ship, on how Framework or Runner filters a suite's tests, on when a suite registers its tests and what a failed registration reports, or on a run that is asked to stop; and before changing a style trait's private registration members.
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

<learning>
date: 2026-11-14
---
`Suite.formatterForSuiteStarting` reads the suite's `testNames`. `Framework`'s `runSuite`, `SuiteRunner.run` and `Suite.runNestedSuites` each call it to build a suite's `SuiteStarting`, so that call, not the suite's `run`, is the first to register a suite that registers on first use: a `RefSpec`, `PathAnyFunSpec` or `PathAnyFreeSpec`.

Where that registration throws, no `SuiteAborted` names the suite itself:

- under `Framework`, a top-level suite's task throws the exception to the build tool, since `runSuite` calls the formatter outside its `try`;
- a nested suite's exception leaves its outer suite's `run`, and the outer suite is reported with `SuiteAborted`;
- under `Runner.run`, the `expectedTestCount` sum before `RunStarting` throws first, and the run ends with `RunAborted`.

Such a suite marks itself registered before it registers, so only the first call throws. A later call finds part of the tests registered and throws nothing.
</learning>

<learning>
date: 2026-11-14
---
`Runner.run` never returns where a stop is requested while a suite it has not started remains. `SuiteRunner.run` does nothing for a suite once `stopper.stopRequested` is true, so that suite's `ScalaTestStatefulStatus` never completes, and `Runner` waits on it: in `status.waitUntilCompleted()` when it runs suites one after another, and on the `CompositeStatus` of all suites under `-P`.

A run of one suite that requests a stop does return, with `RunStopped`. To test what a suite skipped after a stop does, drive `SuiteRunner` or `ConcurrentDistributor` directly with a `Stopper` already asked to stop.
</learning>

<learning>
date: 2026-11-14
---
In ScalaTest 3.2.20's Scala 3 artifacts, a style trait's inline registration methods, such as `AnyWordSpecLike`'s `which`, `that` and `in`, expand in the user's suite to calls of public accessors of the trait's private members, named `<trait's full name with $ for .>$$inline$<member>`: `org$scalatest$wordspec$AnyWordSpecLike$$inline$registerBranch`.

A suite compiled against 3.2.20 therefore links to those private members' 3.2.20 signatures, and passes them arguments that 3.2.20's expansion built, such as a scope's text already joined to `which`.

- Keep the signature of every private member such an accessor forwards to, and keep a quoted reference to it in the trait's inline methods.
- A change to what an inline method passes reaches only suites compiled against the changed trait. A change inside the private member's body reaches both.

List a trait's accessors with `javap -p` on the 3.2.20 jar and on the rebuilt classes, and compare the lines holding `inline$`.
</learning>
