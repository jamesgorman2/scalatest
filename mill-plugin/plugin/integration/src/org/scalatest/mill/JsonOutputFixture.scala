package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.matchers.should

/** The `json-output` fixture build, whose test module `core.test` holds the suites a scenario names. */
trait JsonOutputFixture extends FixtureBuilds { this: should.Matchers =>

  protected def withJsonOutputFixture[A](suites: String*)(test: IntegrationTester => A): A =
    withTester("json-output") { tester =>
      val samples = tester.workspacePath / "core" / "test" / "src" / "samples"
      os.makeDir.all(samples)
      suites.foreach(suite => os.copy(tester.workspacePath / "suites" / s"$suite.scala", samples / s"$suite.scala"))
      test(tester)
    }

  protected def report(tester: IntegrationTester): os.Path = tester.workspacePath / "report.json"

  protected def jsonOutput(path: os.Path): ujson.Value = ujson.read(os.read(path))

  /** Every node, the root first, then each node's contents in order, depth first. */
  protected def nodes(node: ujson.Value): Seq[ujson.Value] =
    node +: node.obj.get("contents").toSeq.flatMap(_.arr).flatMap(nodes)

  protected def node(json: ujson.Value, kind: String): ujson.Value =
    nodes(json).filter(_("kind").str == kind) match {
      case Seq(found) => found
      case found => throw new NoSuchElementException(s"The JSON output holds ${found.size} nodes of kind $kind: $json")
    }

  protected def testOutcome(json: ujson.Value, name: String): Option[String] =
    nodes(json).find(found => found("kind").str == "test" && found("name").str == name).map(_("outcome").str)
}
