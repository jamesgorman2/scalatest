package org.scalatest.mill

import org.scalatest.matchers.should

/**
 * The `json-output` fixture build, whose test module `core.test` holds the suites a scenario names: the files of
 * `suites` it names, or every file of one folder of `rows`, or of `tree`.
 */
trait JsonOutputFixture extends FixtureBuilds { this: should.Matchers =>

  protected def withJsonOutputFixture[A](suites: String*)(test: FixtureBuild => A): A =
    withSuiteFiles(build => build.addSuites(suites*))(test)

  /** The module holds the suites of the folder `rows/<row>`, `RowSuite` among them. */
  protected def withJsonOutputRow[A](row: String)(test: FixtureBuild => A): A =
    withSuiteFiles(build => build.addSuiteFiles(os.list(build.workspace / "rows" / row)))(test)

  /** The module holds the suites of the folder `tree`, which together hold every kind of node and every outcome except aborted. */
  protected def withJsonOutputTree[A](test: FixtureBuild => A): A =
    withSuiteFiles(build => build.addSuiteFiles(os.list(build.workspace / "tree")))(test)

  private def withSuiteFiles[A](addSuites: FixtureBuild => Unit)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.jsonOutput) { build =>
      addSuites(build)
      test(build)
    }

  protected def shouldMatch(actual: JsonNode, pattern: String): Unit =
    withClue(s"in ${ujson.write(actual.raw, indent = 2)}\n") {
      JsonPattern.parse(pattern).mismatches(actual.raw) shouldBe empty
    }

  /** `actual` holds each key of the object `pattern`, in the pattern's order, with a value matching the pattern's in full; it may hold other keys. */
  protected def shouldHold(actual: ujson.Value, pattern: String): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      JsonPattern.parse(pattern).mismatchesHeldBy(actual) shouldBe empty
    }

  protected def shouldHold(actual: JsonNode, pattern: String): Unit = shouldHold(actual.raw, pattern)

  protected def shouldHaveNoKeys(actual: ujson.Value, keys: String*): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      keys.filter(actual.obj.contains) shouldBe empty
    }

  protected def shouldHaveNoKeys(actual: JsonNode, keys: String*): Unit = shouldHaveNoKeys(actual.raw, keys*)
}
