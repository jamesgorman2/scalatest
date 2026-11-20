package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.JsonValue
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ChangedRegistrationOrderSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns with JsonOutputs with EmittedRuns {

  private def kindsAndNamesOf(contents: JsonValue): Vector[(JsonValue, JsonValue)] = contents.items.map(child => (child("kind"), child("name")))

  private def suiteNamed(name: String): (JsonValue, JsonValue) = (text("suite"), text(name))

  private def testNamed(name: String): (JsonValue, JsonValue) = (text("test"), text(name))

  Feature("A suite's scopes and tests keep the same order whether or not it has nested suites") {

    Scenario("A test that later instances no longer register comes after the test that replaces it, in a suite with no nested suites") {
      Given("a suite mixing in OneInstancePerTest that registers tests a and b, and whose instances built after a has run register c in place of a")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.ReplacesARanTestSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfRun(eventLog)

      Then("the human-readable output shows c, not run, then a, then b")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}ReplacesARanTestSuite:{reset}
          |    {red}- c *** NOT RUN ***{reset}
          |    {green}- a{reset}
          |    {green}- b{reset}""".stripExpectedMargin

      And("the JSON output's contents of the suite are c, then a, then b")
      kindsAndNamesOf(json.node(NodeKind.Suite, "ReplacesARanTestSuite")("contents")) shouldBe Vector(testNamed("c"), testNamed("a"), testNamed("b"))
    }

    Scenario("A test that later instances no longer register comes after the test that replaces it, in a suite with two nested suites") {
      Given("the same suite, with nested suites FirstNestedSuite and SecondNestedSuite")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.ReplacesARanTestNestingSuitesSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfRun(eventLog)

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
      kindsAndNamesOf(json.node(NodeKind.Suite, "ReplacesARanTestNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("c"), testNamed("a"), testNamed("b"))
    }

    Scenario("A suite that registers the same tests in every instance keeps their declared order") {
      Given("a suite mixing in OneInstancePerTest that registers tests a and b in every instance, with nested suites FirstNestedSuite and SecondNestedSuite")
      And("the suite runs through `Framework`, writing the run's test event log")
      val eventLog = eventLogOfFrameworkRun(classOf[samples.KeepsItsTestsNestingSuitesSuite].getName)

      When("the library consolidates that test event log")
      val humanReadable = colourMarkedTreeLinesFrom(eventLog)
      val json = jsonOutputOfRun(eventLog)

      Then("the human-readable output shows the nested suites, then a, then b")
      humanReadable.mkString("\n") shouldBe
        """{cyan}[core.test]{reset}
          |  {green}KeepsItsTestsNestingSuitesSuite:{reset}
          |    {green}FirstNestedSuite:{reset}
          |    {green}SecondNestedSuite:{reset}
          |    {green}- a{reset}
          |    {green}- b{reset}""".stripExpectedMargin

      And("the JSON output's contents of the suite are the nested suites, then a, then b")
      kindsAndNamesOf(json.node(NodeKind.Suite, "KeepsItsTestsNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("a"), testNamed("b"))
    }
  }

  Feature("A suite's nested suites come before the scopes and tests that a further structure adds") {

    def eventLogOfSuiteWhoseFirstStructureHasNoTest(): Vector[RunEvent] = {
      Given("a suite with nested suites FirstNestedSuite and SecondNestedSuite, and one test, a, that the suite's runTests runs even when a's tag is excluded")
      And("the suite runs through `Framework` with a's tag excluded, writing the run's test event log")
      eventLogOfFrameworkRun(classOf[samples.RunsADroppedTestNestingSuitesSuite].getName, Seq("-l", "samples.Dropped"))
    }

    Scenario("The human-readable output shows the nested suites before a test that only a further structure holds") {
      val eventLog = eventLogOfSuiteWhoseFirstStructureHasNoTest()

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

    Scenario("The JSON output holds the nested suites before a test that only a further structure holds") {
      val eventLog = eventLogOfSuiteWhoseFirstStructureHasNoTest()

      When("the library consolidates that test event log")
      val json = jsonOutputOfRun(eventLog)

      Then("the JSON output's contents of the suite are the nested suites, then a")
      kindsAndNamesOf(json.node(NodeKind.Suite, "RunsADroppedTestNestingSuitesSuite")("contents")) shouldBe
        Vector(suiteNamed("FirstNestedSuite"), suiteNamed("SecondNestedSuite"), testNamed("a"))
    }
  }
}
