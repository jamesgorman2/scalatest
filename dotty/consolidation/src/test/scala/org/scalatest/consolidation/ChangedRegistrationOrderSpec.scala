package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.JsonValue
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should.Matchers

final class ChangedRegistrationOrderSpec extends AnyFeatureSpec with GivenWhenThen with Matchers with ConsolidatedRuns with JsonOutputs with EmittedRuns {

  private def kindsAndNamesOf(contents: JsonValue): Vector[(JsonValue, JsonValue)] = contents.items.map(child => (child("kind"), child("name")))

  private def suiteNamed(name: String): (JsonValue, JsonValue) = (text("suite"), text(name))

  private def testNamed(name: String): (JsonValue, JsonValue) = (text("test"), text(name))

  Feature("A suite's scopes and tests keep one order whether or not the suite nests suites") {

    Scenario("A test a later registration drops follows the test registered in its place, in a suite that nests no suite") {
      Given("a suite mixing in OneInstancePerTest that registers tests a and b, and tests c and b in each instance built after a has run")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.ReplacesARanTestSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfEventLog(eventLog)

      Then("the human-readable output shows c, not run, then a, then b")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ReplacesARanTestSuite:{reset}
          |    {red}- c *** NOT RUN ***{reset}
          |    {green}- a{reset}
          |    {green}- b{reset}""".stripExpectedMargin

      And("the JSON output's contents of the suite are c, then a, then b")
      kindsAndNamesOf(json.node("suite", "ReplacesARanTestSuite")("contents")) shouldBe Vector(testNamed("c"), testNamed("a"), testNamed("b"))
    }

    Scenario("A test a later registration drops follows the test registered in its place, in a suite that nests two suites") {
      Given("the same suite nesting suites FirstNestedSuite and SecondNestedSuite")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.ReplacesARanTestNestingSuitesSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfEventLog(eventLog)

      Then("the human-readable output shows the nested suites, then c, not run, then a, then b")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ReplacesARanTestNestingSuitesSuite:{reset}
          |    {green}FirstNestedSuite:{reset}
          |    {green}SecondNestedSuite:{reset}
          |    {red}- c *** NOT RUN ***{reset}
          |    {green}- a{reset}
          |    {green}- b{reset}""".stripExpectedMargin

      And("the JSON output's contents of the suite are the nested suites, then c, then a, then b")
      kindsAndNamesOf(json.node("suite", "ReplacesARanTestNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("c"), testNamed("a"), testNamed("b"))
    }

    Scenario("A suite whose every instance registers the same tests keeps its declared order") {
      Given("a suite mixing in OneInstancePerTest that registers tests a and b in every instance, nesting suites FirstNestedSuite and SecondNestedSuite")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.KeepsItsTestsNestingSuitesSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfEventLog(eventLog)

      Then("the human-readable output shows the nested suites, then a, then b")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}KeepsItsTestsNestingSuitesSuite:{reset}
          |    {green}FirstNestedSuite:{reset}
          |    {green}SecondNestedSuite:{reset}
          |    {green}- a{reset}
          |    {green}- b{reset}""".stripExpectedMargin

      And("the JSON output's contents of the suite are the nested suites, then a, then b")
      kindsAndNamesOf(json.node("suite", "KeepsItsTestsNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("a"), testNamed("b"))
    }
  }

  Feature("A suite's nested suites come before the scopes and tests a later registration adds") {

    def eventLogOfSuiteWhoseFirstRegistrationHeldNoTest(): Vector[String] = {
      Given("a suite nesting suites FirstNestedSuite and SecondNestedSuite, whose one test a the filter drops, and whose runTests also runs a")
      And("the suite runs through `Framework` excluding a's tag, writing the run's test event log")
      eventLogOfFrameworkRun(classOf[samples.RunsADroppedTestNestingSuitesSuite].getName, Seq("-l", "samples.Dropped"))
    }

    Scenario("The human-readable output shows a test a later registration adds after the nested suites, where the first registration held no test") {
      val eventLog = eventLogOfSuiteWhoseFirstRegistrationHeldNoTest()

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)

      Then("the human-readable output shows the nested suites, then a")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}RunsADroppedTestNestingSuitesSuite:{reset}
          |    {green}FirstNestedSuite:{reset}
          |    {green}SecondNestedSuite:{reset}
          |    {green}- a{reset}""".stripExpectedMargin
    }

    Scenario("The JSON output holds a test a later registration adds after the nested suites, where the first registration held no test") {
      val eventLog = eventLogOfSuiteWhoseFirstRegistrationHeldNoTest()

      When("the library consolidates that test event log")
      val json = jsonOutputOfEventLog(eventLog)

      Then("the JSON output's contents of the suite are the nested suites, then a")
      kindsAndNamesOf(json.node("suite", "RunsADroppedTestNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("a"))
    }
  }
}
