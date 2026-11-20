package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputTreeSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val outcomeMarkers: Map[String, String] =
    Map("failed" -> "*** FAILED ***", "canceled" -> "!!! CANCELED !!!", "ignored" -> "!!! IGNORED !!!", "pending" -> "(pending)")

  private val timingKeys: Set[String] = Set("startedAt", "endedAt", "duration", "at", "threadName")

  private val kindsShownInTheTree: Set[NodeKind] = Set(NodeKind.Suite, NodeKind.Scope, NodeKind.Test)

  private def suitesScopesAndTests(json: JsonNode): List[JsonNode] = json.nodes.filter(found => kindsShownInTheTree.contains(found.kind)).toList

  private def isLineOf(node: JsonNode)(line: String): Boolean = {
    val unindented = line.dropWhile(_ == ' ')
    if (node.kind == NodeKind.Suite) unindented == s"${node.name}:" else unindented.startsWith(node.label)
  }

  private def shouldMarkOutcome(node: JsonNode, line: String): Unit = {
    val marker = node.outcome.flatMap(outcomeMarkers.get)
    withClue(s"the line $line of node $node: ") {
      outcomeMarkers.values.filter(line.contains).toSeq shouldBe marker.toSeq
    }
  }

  private def shouldWalkInOrder(treeNodes: List[JsonNode], lines: List[String]): Unit =
    treeNodes match {
      case Nil => ()
      case node :: rest => {
        val index = lines.indexWhere(isLineOf(node))
        withClue(s"no line for node $node among the remaining lines\n${lines.mkString("\n")}\n") {
          index should be >= 0
        }
        shouldMarkOutcome(node, lines(index))
        shouldWalkInOrder(rest, lines.drop(index + 1))
      }
    }

  private def withTimingValuesMasked(value: ujson.Value): ujson.Value =
    value match {
      case ujson.Obj(fields) =>
        ujson.Obj.from(fields.toSeq.map { case (key, field) => key -> (if (timingKeys.contains(key)) ujson.Str("<masked>") else withTimingValuesMasked(field)) })
      case ujson.Arr(items) => ujson.Arr.from(items.map(withTimingValuesMasked))
      case other => other
    }

  Feature("The JSON output and the human-readable output hold one node tree") {

    Scenario("Both outputs hold the same suites, scopes and tests in the same order") {
      Given("suites holding a nested suite, scopes nested in scopes, a pending scope, and tests of every outcome except aborted")
      And("a suite wrapped with ConfigMapWrapperSuite, holding a scope whose tests two instances of the suite register")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputTree { build =>
        val (result, json) = build.resultAndReportOfTestCommand()
        val treeNodes = suitesScopesAndTests(json)
        withClue(s"in ${ujson.write(json.raw, indent = 2)}\n") {
          treeNodes.flatMap(_.outcome).toSet should contain allOf ("succeeded", "failed", "canceled", "ignored", "pending")
          json.node(NodeKind.Suite, "NestingSuite").kindsAndNamesOfContents shouldBe Seq(NodeKind.Suite -> "NestedSuite")
          json.node(NodeKind.Scope, "A calculator").kindsAndNamesOfContents shouldBe Seq(NodeKind.Scope -> "when adding")
          json.node(NodeKind.Scope, "A queue").outcome shouldBe Some("pending")
          json.node(NodeKind.Scope, "A wrapped scope").kindsAndNamesOfContents shouldBe Seq(NodeKind.Test -> "succeeds", NodeKind.Test -> "also succeeds")
        }

        Then("the human-readable output holds a line for each suite, scope and test of the JSON output, in the order of a depth-first walk")
        And("each suite's line is its name followed by a colon, and each scope's and test's starts with its label")
        And("a line marks its node's outcome as failed, canceled, ignored or pending, and only then")
        shouldWalkInOrder(treeNodes, result.treeLines.toList)
      }
    }

    Scenario("The module's reporter arguments do not change the JSON output") {
      Given("the JSON output of the test command invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputTree { build =>
        val withoutReporterArguments = build.jsonOutputOfTestCommand()

        When("the module's testArgsDefault is set to -oDW and the test command is invoked again the same way")
        build.setModuleArguments(Seq("-oDW"))
        val withReporterArguments = build.jsonOutputOfTestCommand()

        Then("the JSON output is the same, key for key and in the same order, apart from the values of its times and thread names")
        ujson.write(withTimingValuesMasked(withReporterArguments.raw), indent = 2) shouldBe ujson.write(withTimingValuesMasked(withoutReporterArguments.raw), indent = 2)
      }
    }
  }
}
