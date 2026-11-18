package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputNodesSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private def jsonOutputOfRow(row: String, rowSuite: String, otherSuite: Option[String] = None)(assertions: ujson.Value => Unit): Unit = {
    otherSuite match {
      case None => Given(s"the module holds the one suite RowSuite, $rowSuite")
      case Some(other) => Given(s"the module holds two suites: RowSuite, $rowSuite, and $other")
    }
    When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
    withJsonOutputRow(row) { tester =>
      assertions(jsonOutputOfTestCommand(tester))
    }
  }

  private def startedAt(node: ujson.Value): Long = node("startedAt").num.toLong

  private def endedAt(node: ujson.Value): Long = node("endedAt").num.toLong

  private def names(nodes: Seq[ujson.Value]): Seq[(String, String)] = nodes.map(found => (found("kind").str, found("name").str))

  Feature("Suites, scopes and tests in the JSON output") {

    Scenario("A failed assertion") {
      jsonOutputOfRow("failed-assertion", "an AnyFunSuite whose test adds, at line 7, asserts that 2 equals 3") { json =>
        Then("test adds is failed, with the assertion's message and the failed code's location as its throwable's")
        shouldHold(
          node(json, "test", "adds"),
          """
          {
            "outcome": "failed",
            "message": "2 did not equal 3",
            "throwable": {
              "id": 1,
              "className": "org.scalatest.exceptions.TestFailedException",
              "message": "2 did not equal 3",
              "location": <line 7 of RowSuite.scala>
            }
          }
          """
        )
      }
    }

    Scenario("A canceled test") {
      jsonOutputOfRow("canceled-test", "an AnyFunSuite whose test adds cancels with no database") { json =>
        val test = node(json, "test", "adds")
        Then("test adds is canceled, with the cancel's message")
        shouldHold(test, """{ "outcome": "canceled", "message": "no database" }""")
        And("its throwable is a TestCanceledException")
        shouldHold(test("throwable"), """{ "className": "org.scalatest.exceptions.TestCanceledException" }""")
      }
    }

    Scenario("An ignored test") {
      jsonOutputOfRow("ignored-test", "an AnyFunSuite whose test adds is registered with ignore") { json =>
        val test = node(json, "test", "adds")
        Then("test adds is ignored, with an end time and a thread")
        shouldHold(test, """{ "outcome": "ignored", "endedAt": <time>, "threadName": <thread> }""")
        And("it has no start time and no duration")
        shouldHaveNoKeys(test, "startedAt", "duration")
      }
    }

    Scenario("A pending test") {
      jsonOutputOfRow("pending-test", "an AnyFunSuite whose test adds is pending") { json =>
        Then("test adds is pending")
        shouldHold(node(json, "test", "adds"), """{ "outcome": "pending" }""")
      }
    }

    Scenario("A scope") {
      jsonOutputOfRow("scope", "an AnyFunSpec whose describe A Stack, at line 6, holds the test pops") { json =>
        val suite = node(json, "suite", "RowSuite")
        Then("the suite's contents is one scope, A Stack, with its label, location and times, and no outcome or thread")
        names(contents(suite)) shouldBe Seq("scope" -> "A Stack")
        val scope = contents(suite).head
        shouldHold(
          scope,
          """{ "name": "A Stack", "label": "A Stack", "location": <line 6 of RowSuite.scala>, "startedAt": <time>, "endedAt": <time>, "duration": <ms> }"""
        )
        shouldHaveNoKeys(scope, "outcome", "threadName")
        And("the scope's duration is its end time less its start time")
        scope("duration").num.toLong shouldBe endedAt(scope) - startedAt(scope)
        And("the scope's contents is one test, pops")
        contents(scope) should have size 1
        shouldHold(contents(scope).head, """{ "kind": "test", "name": "pops", "label": "- pops" }""")
      }
    }

    Scenario("A pending scope") {
      jsonOutputOfRow("pending-scope", "an AnyFunSpec whose describe A Queue is pending") { json =>
        Then("scope A Queue is pending")
        shouldHold(node(json, "scope", "A Queue"), """{ "outcome": "pending" }""")
      }
    }

    Scenario("A suite aborted before its tests run") {
      jsonOutputOfRow("aborted-suite", "an AnyFunSuite with BeforeAndAfterAll, whose beforeAll throws no fixture, and whose one test is adds") { json =>
        Then("suite RowSuite is aborted, with the thrown exception as its throwable")
        val suite = node(json, "suite", "RowSuite")
        shouldHold(suite, """{ "outcome": "aborted" }""")
        shouldHold(suite("throwable"), """{ "className": "java.lang.IllegalStateException", "message": "no fixture", "stackTrace": <frames> }""")
        And("test adds is aborted as not run, with no start time, message or throwable")
        val test = node(json, "test", "adds")
        shouldHold(test, """{ "outcome": "aborted", "reason": "notRun" }""")
        shouldHaveNoKeys(test, "startedAt", "message", "throwable")
      }
    }

    Scenario("A nested suite") {
      jsonOutputOfRow("nested-suite", "a Suites nesting InnerSuite", Some("InnerSuite, an AnyFunSuite not discovered, whose one test adds succeeds")) { json =>
        Then("the run's contents is RowSuite, whose contents is InnerSuite, whose contents is test adds")
        val run = node(json, "run")
        names(contents(run)) shouldBe Seq("suite" -> "RowSuite")
        names(contents(contents(run).head)) shouldBe Seq("suite" -> "InnerSuite")
        names(contents(contents(contents(run).head).head)) shouldBe Seq("test" -> "adds")
      }
    }

    Scenario("A test run twice") {
      jsonOutputOfRow("test-run-twice", "an AnyFunSuite whose runTest runs each test twice, and whose one test adds succeeds") { json =>
        Then("the suite's contents is two succeeded tests adds, the first started no later than the second")
        val tests = contents(node(json, "suite", "RowSuite"))
        tests should have size 2
        tests.foreach(test => shouldHold(test, """{ "name": "adds", "outcome": "succeeded" }"""))
        startedAt(tests(0)) should be <= startedAt(tests(1))
      }
    }

    Scenario("Messages") {
      jsonOutputOfRow("messages", "an AnyFunSuite whose test talks sends info one, note two, alert three and markup four") { json =>
        Then("test talks's output is the four messages in order, each with its time and none with a thread")
        val output = node(json, "test", "talks")("output").arr.toSeq
        val messages = Seq("info" -> "one", "note" -> "two", "alert" -> "three", "markup" -> "four")
        output should have size messages.size
        output.zip(messages).foreach { case (item, (kind, message)) =>
          shouldHold(item, s"""{ "kind": "$kind", "message": "$message", "at": <time> }""")
          shouldHaveNoKeys(item, "threadName")
        }
      }
    }

    Scenario("A message from another thread") {
      jsonOutputOfRow("message-from-another-thread", "an AnyFunSuite whose test talks sends info away from another thread and waits for it") { json =>
        Then("test talks's output is the message, with a thread other than the test's")
        val test = node(json, "test", "talks")
        val output = test("output").arr.toSeq
        output should have size 1
        shouldHold(output.head, """{ "kind": "info", "message": "away", "threadName": <thread> }""")
        output.head("threadName") should not be test("threadName")
      }
    }

    Scenario("A throwable with a cause and a suppressed throwable") {
      jsonOutputOfRow("throwable-with-cause-and-suppressed", "an AnyFunSuite whose test throws an exception outer, caused by inner, suppressing closing") { json =>
        Then("test throws is failed, with the exception, its cause and the suppressed exception as its throwable")
        shouldHold(
          node(json, "test", "throws"),
          """
          {
            "outcome": "failed",
            "throwable": {
              "id": 1, "className": "java.lang.RuntimeException", "message": "outer", "stackTrace": <frames>,
              "cause": { "id": 2, "className": "java.io.IOException", "message": "inner", "stackTrace": <frames> },
              "suppressed": [{ "id": 3, "className": "java.lang.IllegalStateException", "message": "closing", "stackTrace": <frames> }]
            }
          }
          """
        )
      }
    }

    Scenario("A cycle of causes") {
      jsonOutputOfRow("throwable-cause-cycle", "an AnyFunSuite whose test loops throws b, caused by a, which is caused by b") { json =>
        Then("test loops's throwable ends the cycle with a reference to the first throwable")
        shouldHold(
          node(json, "test", "loops"),
          """
          {
            "throwable": {
              "id": 1, "className": "java.lang.RuntimeException", "message": "b", "stackTrace": <frames>,
              "cause": { "id": 2, "className": "java.lang.RuntimeException", "message": "a", "stackTrace": <frames>, "cause": { "ref": 1 } }
            }
          }
          """
        )
      }
    }

    Scenario("A throwable with no message") {
      jsonOutputOfRow("throwable-without-message", "an AnyFunSuite whose test throws a RuntimeException with no message") { json =>
        val throwable = node(json, "test", "throws")("throwable")
        Then("the throwable has no message")
        shouldHaveNoKeys(throwable, "message")
        And("each frame of its stack trace holds a class and method name, and none holds isNative false")
        val frames = throwable("stackTrace").arr.toSeq
        frames should not be empty
        frames.foreach(frame => shouldHold(frame, """{ "className": <string>, "methodName": <string> }"""))
        frames.filter(_.obj.get("isNative").contains(ujson.False)) shouldBe empty
      }
    }

    Scenario("Top-level suites ordered by name") {
      jsonOutputOfRow("suites-ordered-by-name", "an AnyFunSuite named ZebraSuite", Some("ZSuite, an AnyFunSuite named AlphaSuite")) { json =>
        Then("the run's contents is AlphaSuite then ZebraSuite")
        names(contents(node(json, "run"))) shouldBe Seq("suite" -> "AlphaSuite", "suite" -> "ZebraSuite")
      }
    }

    Scenario("Tests in declared order") {
      jsonOutputOfRow("tests-in-declared-order", "an AnyFunSuite whose tests subtracts and adds are registered in that order") { json =>
        Then("the suite's contents is test subtracts then test adds")
        names(contents(node(json, "suite", "RowSuite"))) shouldBe Seq("test" -> "subtracts", "test" -> "adds")
      }
    }
  }
}
