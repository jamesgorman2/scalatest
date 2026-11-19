package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputRootSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val eventLogItems: Set[String] = Set("runStarted", "structure", "event", "runEnded")

  Feature("The root, the module and the run in the JSON output") {

    Scenario("One run of one suite") {
      Given("CalculatorSuite, whose one test, `adds two numbers`, registered at line 5, prints dividing now and succeeds")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("CalculatorSuite") { tester =>
        val json = jsonOutputOfTestCommand(tester)

        Then("the JSON output is the root, holding this fork's release, Mill and the module selector, then the module, its run and the run's suite and test, each with its times")
        shouldMatch(
          json,
          s"""
          {
            "kind": "root",
            "scalaTestVersion": "$pluginVersion",
            "buildTool": { "name": "Mill", "version": "${mill.api.BuildInfo.millVersion}" },
            "moduleSelectors": [{ "selector": "core.test", "modules": ["core.test"] }],
            "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
            "contents": [{
              "kind": "module",
              "name": "core.test",
              "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
              "contents": [{
                "kind": "run",
                "number": 1,
                "forkGroup": 0,
                "configMap": {},
                "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
                "summary": {
                  "testsSucceededCount": 1, "testsFailedCount": 0, "testsIgnoredCount": 0, "testsPendingCount": 0,
                  "testsCanceledCount": 0, "suitesCompletedCount": 1, "suitesAbortedCount": 0, "scopesPendingCount": 0
                },
                "contents": [{
                  "kind": "suite",
                  "name": "CalculatorSuite",
                  "suiteId": "samples.CalculatorSuite",
                  "location": <class samples.CalculatorSuite in CalculatorSuite.scala>,
                  "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
                  "threadName": <thread>,
                  "contents": [{
                    "kind": "test",
                    "name": "adds two numbers",
                    "label": "- adds two numbers",
                    "location": <line 5 of CalculatorSuite.scala>,
                    "outcome": "succeeded",
                    "startedAt": <time>, "endedAt": <time>, "duration": <ms>,
                    "threadName": <thread>,
                    "contents": []
                  }]
                }],
                "output": [{ "kind": "stdio", "stream": "stdout", "text": "dividing now\\n" }]
              }]
            }]
          }
          """
        )
      }
    }

    Scenario("A run's config map is written with each value's toString") {
      Given("CalculatorSuite, and the module's testArgsDefault is -Dgreeting=hello")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("CalculatorSuite") { tester =>
        setModuleArguments(tester, Seq("-Dgreeting=hello"))
        val json = jsonOutputOfTestCommand(tester)

        Then("the run node holds the config map greeting = hello")
        shouldHold(node(json, "run"), """{ "configMap": { "greeting": "hello" } }""")
      }
    }

    Scenario("The root's times enclose the module's, the module's enclose the run's, and no test event log records the root's or the module's times") {
      Given("CalculatorSuite, whose test adds two numbers succeeds")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("CalculatorSuite") { tester =>
        val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)
        val json = withClue(result.debugString)(jsonOutput(report(tester)))
        val module = node(json, "module")
        val run = node(json, "run")
        def startedAt(node: ujson.Value): Long = node("startedAt").num.toLong
        def endedAt(node: ujson.Value): Long = node("endedAt").num.toLong

        Then("the root's startedAt is no later than the module's, and the module's is no later than the run's")
        startedAt(json) should be <= startedAt(module)
        startedAt(module) should be <= startedAt(run)

        And("the run's endedAt is no later than the module's, and the module's is no later than the root's")
        endedAt(run) should be <= endedAt(module)
        endedAt(module) should be <= endedAt(json)

        And("the root's duration is its endedAt less its startedAt, and so is the module's")
        json("duration").num.toLong shouldBe endedAt(json) - startedAt(json)
        module("duration").num.toLong shouldBe endedAt(module) - startedAt(module)

        And("the run's test event log holds no event for the root or the module")
        val eventLogs = os.walk(tester.workspacePath / "out").filter(_.last == JvmTestModuleRun.eventLogFileName)
        eventLogs should have size 1
        val items = os.read.lines(eventLogs.head).filter(_.nonEmpty).map(line => ujson.read(line))
        items.map(_("item").str).toSet shouldBe eventLogItems
        items.filter(item => item.obj.contains("moduleSelectors") || item.obj.contains("buildTool")) shouldBe empty
      }
    }

    Scenario("The root records the running Mill as the build tool") {
      Given("CalculatorSuite")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("CalculatorSuite") { tester =>
        val result = runTestCommand(tester, "core.test", "-fJ", report(tester).toString)

        Then("the root's buildTool is Mill, at the version of the Mill release running the test command")
        withClue(result.debugString) {
          jsonOutput(report(tester))("buildTool") shouldBe ujson.Obj("name" -> "Mill", "version" -> mill.api.BuildInfo.millVersion)
        }
      }
    }

    for (selector <- Seq("core.test", "__.test")) {
      Scenario(s"The root records the module selector $selector as given, with the test module it resolved to") {
        Given("CalculatorSuite, in a build with no test module other than core.test")
        When(s"the test command is invoked as mill scalatest/test $selector -fJ <report>")
        withJsonOutputFixture("CalculatorSuite") { tester =>
          val result = runTestCommand(tester, selector, "-fJ", report(tester).toString)

          Then(s"the root of the JSON output records the module selector $selector, resolved to core.test")
          withClue(result.debugString) {
            jsonOutput(report(tester))("moduleSelectors") shouldBe ujson.Arr(ujson.Obj("selector" -> selector, "modules" -> ujson.Arr("core.test")))
          }
        }
      }
    }
  }
}
