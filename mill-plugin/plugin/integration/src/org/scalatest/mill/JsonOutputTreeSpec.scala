package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputTreeSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val outcomeMarkers: Map[String, String] =
    Map("failed" -> "*** FAILED ***", "canceled" -> "!!! CANCELED !!!", "ignored" -> "!!! IGNORED !!!", "pending" -> "(pending)")

  private val timingKeys: Set[String] = Set("startedAt", "endedAt", "duration", "at", "threadName")

  private def suitesScopesAndTests(json: ujson.Value): List[ujson.Value] =
    nodes(json).filter(found => Set("suite", "scope", "test").contains(found("kind").str)).toList

  private def isLineOf(node: ujson.Value)(line: String): Boolean = {
    val unindented = line.dropWhile(_ == ' ')
    if (node("kind").str == "suite") unindented == s"${node("name").str}:" else unindented.startsWith(node("label").str)
  }

  private def shouldMarkOutcome(node: ujson.Value, line: String): Unit = {
    val marker = node.obj.get("outcome").flatMap(outcome => outcomeMarkers.get(outcome.str))
    withClue(s"the line $line of node $node: ") {
      outcomeMarkers.values.filter(line.contains).toSeq shouldBe marker.toSeq
    }
  }

  private def shouldWalkInOrder(treeNodes: List[ujson.Value], lines: List[String]): Unit =
    treeNodes match {
      case Nil => ()
      case node :: rest =>
        val index = lines.indexWhere(isLineOf(node))
        withClue(s"no line for node $node among the remaining lines\n${lines.mkString("\n")}\n") {
          index should be >= 0
        }
        shouldMarkOutcome(node, lines(index))
        shouldWalkInOrder(rest, lines.drop(index + 1))
    }

  private def withoutTimingKeys(value: ujson.Value): ujson.Value =
    value match {
      case ujson.Obj(fields) => ujson.Obj.from(fields.toSeq.filterNot { case (key, _) => timingKeys.contains(key) }.map { case (key, field) => key -> withoutTimingKeys(field) })
      case ujson.Arr(items) => ujson.Arr.from(items.map(withoutTimingKeys))
      case other => other
    }

  Feature("The JSON output and the human-readable output hold one node tree") {

    Scenario("Walking both outputs") {
      Given("suites holding a nested suite, scopes nested in scopes, a pending scope, tests of every outcome but aborted, and a suite wrapped by ConfigMapWrapperSuite whose scope's tests two instances register")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputTree { tester =>
        val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)
        val json = withClue(result.debugString)(jsonOutput(report(tester)))
        val treeNodes = suitesScopesAndTests(json)
        withClue(s"in ${ujson.write(json, indent = 2)}\n") {
          treeNodes.flatMap(_.obj.get("outcome")).map(_.str).toSet should contain allOf ("succeeded", "failed", "canceled", "ignored", "pending")
          treeNodes.count(_("kind").str == "scope") should be > 3
        }

        Then("the human-readable output holds a line for each suite, scope and test of the JSON output, in the order of a depth-first walk")
        And("each suite's line is its name followed by a colon, and each scope's and test's starts with its label")
        And("a line marks its node's outcome as failed, canceled, ignored or pending, and only then")
        shouldWalkInOrder(treeNodes, result.treeLines.toList)
      }
    }

    Scenario("The module's reporter arguments do not change the JSON output") {
      Given("the JSON output of the test command invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputTree { tester =>
        val withoutReporterArguments = jsonOutputOfTestCommand(tester)

        When("the module's testArgsDefault is set to -oDW and the test command is invoked again the same way")
        setModuleArguments(tester, Seq("-oDW"))
        val withReporterArguments = jsonOutputOfTestCommand(tester)

        Then("the JSON output is the same, apart from its times and thread names")
        withoutTimingKeys(withReporterArguments) shouldBe withoutTimingKeys(withoutReporterArguments)
      }
    }
  }
}
