package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.Json

@Json
final class JsonOutputNodesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private def jsonOutputOfRow(row: String, rowSuite: String, otherSuite: Option[String] = None)(assertions: JsonNode => Unit): Unit = {
    withJsonOutputRow(row) { build =>
      otherSuite match {
        case None => Given(s"the module holds the one suite RowSuite, $rowSuite")
        case Some(other) => Given(s"the module holds two suites: RowSuite, $rowSuite, and $other")
      }
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      assertions(build.jsonOutputOfTestCommand())
    }
  }

  Feature("Suites and tests in the JSON output") {

    Scenario("A canceled test is canceled, with the cancel's message") {
      jsonOutputOfRow("canceled-test", "an AnyFunSuite whose test adds cancels with the message no database") { json =>
        val test = json.node(NodeKind.Test, "adds")
        Then("test adds is canceled, with the cancel's message")
        shouldHold(test, """{ "outcome": "canceled", "message": "no database" }""")
        And("its throwable is a TestCanceledException")
        shouldHold(test("throwable"), """{ "className": "org.scalatest.exceptions.TestCanceledException" }""")
      }
    }

    Scenario("A message sent from another thread records that thread") {
      jsonOutputOfRow("message-from-another-thread", "an AnyFunSuite whose test talks calls info(\"away\") on another thread and waits for it") { json =>
        Then("test talks's output is the message, with a thread other than the test's")
        val test = json.node(NodeKind.Test, "talks")
        val output = test.output
        output should have size 1
        shouldHold(output.head, """{ "kind": "info", "message": "away", "threadName": <thread> }""")
        output.head("threadName") should not be test("threadName")
      }
    }

    Scenario("Top-level suites are ordered by name") {
      jsonOutputOfRow("suites-ordered-by-name", "an AnyFunSuite named ZebraSuite", Some("ZSuite, an AnyFunSuite named AlphaSuite")) { json =>
        Then("the run's contents is AlphaSuite then ZebraSuite")
        json.node(NodeKind.Run).kindsAndNamesOfContents shouldBe Seq(NodeKind.Suite -> "AlphaSuite", NodeKind.Suite -> "ZebraSuite")
      }
    }
  }
}
