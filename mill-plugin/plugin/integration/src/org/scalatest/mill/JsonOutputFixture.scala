package org.scalatest.mill

import org.scalatest.matchers.should

trait JsonOutputFixture extends FixtureBuilds { this: should.Matchers =>

  protected def withJsonOutputFixture[A](suites: String*)(test: FixtureBuild => A): A =
    withSuiteFiles(build => build.addSuites(suites*))(test)

  protected def withJsonOutputRow[A](row: String)(test: FixtureBuild => A): A =
    withSuiteFiles(build => build.addSuiteFiles(os.list(build.workspace / "rows" / row)))(test)

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

  protected def shouldHold(actual: ujson.Value, pattern: String): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      JsonPattern.parse(pattern).mismatchesHeldBy(actual) shouldBe empty
    }

  protected def shouldHold(actual: JsonWrapper, pattern: String): Unit = shouldHold(actual.raw, pattern)

  protected def shouldHaveNoKeys(actual: ujson.Value, keys: String*): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      keys.filter(actual.obj.contains) shouldBe empty
    }

  protected def shouldHaveNoKeys(actual: JsonWrapper, keys: String*): Unit = shouldHaveNoKeys(actual.raw, keys*)
}
