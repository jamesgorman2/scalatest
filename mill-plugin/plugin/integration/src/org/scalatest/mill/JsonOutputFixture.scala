package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.matchers.should

/**
 * The `json-output` fixture build, whose test module `core.test` holds the suites a scenario names: the files of
 * `suites` it names, or every file of one folder of `rows`, or of `tree`.
 */
trait JsonOutputFixture extends FixtureBuilds { this: should.Matchers =>

  protected def withJsonOutputFixture[A](suites: String*)(test: IntegrationTester => A): A =
    withSuiteFiles(tester => suites.map(suite => tester.workspacePath / "suites" / s"$suite.scala"))(test)

  /** The module holds the suites of the folder `rows/<row>`, `RowSuite` among them. */
  protected def withJsonOutputRow[A](row: String)(test: IntegrationTester => A): A =
    withSuiteFiles(tester => os.list(tester.workspacePath / "rows" / row))(test)

  /** The module holds the suites of the folder `tree`, which together hold every kind of node and every outcome except aborted. */
  protected def withJsonOutputTree[A](test: IntegrationTester => A): A =
    withSuiteFiles(tester => os.list(tester.workspacePath / "tree"))(test)

  private def withSuiteFiles[A](files: IntegrationTester => Seq[os.Path])(test: IntegrationTester => A): A =
    withTester("json-output") { tester =>
      val samples = tester.workspacePath / "core" / "test" / "src" / "samples"
      os.makeDir.all(samples)
      files(tester).foreach(file => os.copy(file, samples / file.last))
      test(tester)
    }

  protected def report(tester: IntegrationTester): os.Path = tester.workspacePath / "report.json"

  protected def jsonOutput(path: os.Path): ujson.Value = ujson.read(os.read(path))

  /** Deletes any earlier report, invokes the test command as `mill scalatest/test core.test -fJ <report>`, and parses the JSON output it writes. */
  protected def jsonOutputOfTestCommand(tester: IntegrationTester): ujson.Value = {
    os.remove(report(tester))
    val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)
    withClue(result.debugString) {
      os.exists(report(tester)) shouldBe true
    }
    jsonOutput(report(tester))
  }

  /** Every node, the root first, then each node's contents in order, depth first. */
  protected def nodes(node: ujson.Value): Seq[ujson.Value] =
    node +: node.obj.get("contents").toSeq.flatMap(_.arr).flatMap(nodes)

  protected def node(json: ujson.Value, kind: String): ujson.Value =
    nodes(json).filter(_("kind").str == kind) match {
      case Seq(found) => found
      case found => throw new NoSuchElementException(s"The JSON output holds ${found.size} nodes of kind $kind: $json")
    }

  protected def node(json: ujson.Value, kind: String, name: String): ujson.Value =
    nodes(json).filter(found => found("kind").str == kind && found.obj.get("name").contains(ujson.Str(name))) match {
      case Seq(found) => found
      case found => throw new NoSuchElementException(s"The JSON output holds ${found.size} nodes of kind $kind named $name: $json")
    }

  protected def contents(node: ujson.Value): Seq[ujson.Value] = node("contents").arr.toSeq

  protected def testOutcome(json: ujson.Value, name: String): Option[String] =
    nodes(json).find(found => found("kind").str == "test" && found("name").str == name).map(_("outcome").str)

  protected def shouldMatch(actual: ujson.Value, pattern: String): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      JsonPattern.parse(pattern).mismatches(actual) shouldBe empty
    }

  /** `actual` holds each key of the object `pattern`, in the pattern's order, with a value matching the pattern's in full; it may hold other keys. */
  protected def shouldHold(actual: ujson.Value, pattern: String): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      JsonPattern.parse(pattern).mismatchesHeldBy(actual) shouldBe empty
    }

  protected def shouldHaveNoKeys(actual: ujson.Value, keys: String*): Unit =
    withClue(s"in ${ujson.write(actual, indent = 2)}\n") {
      keys.filter(actual.obj.contains) shouldBe empty
    }
}
