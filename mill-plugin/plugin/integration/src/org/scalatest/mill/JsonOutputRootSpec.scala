package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.mill.tags.CoreTag
import org.scalatest.mill.tags.Json

@Json
final class JsonOutputRootSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val eventLogItems: Set[String] = Set("runStarted", "structure", "event", "runEnded")

  private lazy val sharedCalculatorSuiteRun: (JsonNode, Seq[Seq[String]]) =
    withJsonOutputFixture("CalculatorSuite") { build =>
      (build.jsonOutputOfTestCommand(), build.eventLogFiles.map(os.read.lines(_)))
    }

  Feature("The root, the module and the run in the JSON output") {

    Scenario("One run of one suite is written as the root, module, run, suite and test, each with its times", CoreTag) {
      Given("CalculatorSuite, whose one test, `adds two numbers`, registered at line 5, prints dividing now and succeeds")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      val (json, _) = sharedCalculatorSuiteRun

      Then("the JSON output is the root, holding this fork's release, Mill and the module selector, then the module, its run and the run's suite and test, each with its times")
      shouldMatch(
        json,
        s"""
        {
          "kind": "root",
          "scalaTestVersion": "${IntegrationEnvironment.pluginVersion}",
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

    Scenario("The root's times enclose the module's, the module's enclose the run's, and no test event log records the root's or the module's times") {
      Given("CalculatorSuite, whose test adds two numbers succeeds")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      val (json, eventLogs) = sharedCalculatorSuiteRun
      val module = json.node(NodeKind.Module)
      val run = json.node(NodeKind.Run)

      Then("the root's startedAt is no later than the module's, and the module's is no later than the run's")
      json.startedAt should be <= module.startedAt
      module.startedAt should be <= run.startedAt

      And("the run's endedAt is no later than the module's, and the module's is no later than the root's")
      run.endedAt should be <= module.endedAt
      module.endedAt should be <= json.endedAt

      And("the root's duration is its endedAt less its startedAt, and so is the module's")
      json.duration shouldBe json.endedAt - json.startedAt
      module.duration shouldBe module.endedAt - module.startedAt

      And("the run's test event log holds no event for the root or the module")
      eventLogs should have size 1
      val items = eventLogs.head.filter(_.nonEmpty).map(line => ujson.read(line))
      items.map(_("item").str).toSet shouldBe eventLogItems
      items.filter(item => item.obj.contains("moduleSelectors") || item.obj.contains("buildTool")) shouldBe empty
    }
  }
}
