package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val sqrtPropSpecLines: Seq[String] = Seq(
    "SqrtPropSpec:",
    "  - sqrt of a square is the root"
  )

  // Criteria that read the same run share one test command.
  private lazy val sqrtPropSpecRun: TestCommandResult =
    withFixtureBuild(FixtureName.withoutPluginOnScala("3.3")) { build =>
      build.addPlugin()
      build.runTestCommand("core.test")
    }

  private lazy val brokenConstructorRun: TestCommandResult =
    withFixtureBuild(FixtureName.brokenConstructor) { build =>
      build.runTestCommandWithColour("core.test")
    }

  Feature("The human-readable output is the module's test run label, with its suites under it") {

    Scenario("A module's label heads its suites") {
      Given("core.test, at Mill's default testLogLevel, holds SqrtPropSpec, whose one property succeeds")
      When("the test command runs core.test")
      val result = sqrtPropSpecRun

      Then("the human-readable output is the label, with the suite and its property under it")
      withClue(result.debugString) {
        result.treeLines shouldBe ("[core.test]" +: sqrtPropSpecLines.map("  " + _))
      }
    }

    Scenario("Every line of the human-readable output on stdout carries Mill's prefix") {
      Given("the module is at Mill's default testLogLevel")
      And("SqrtPropSpec, an AnyPropSpec whose one property, sqrt of a square is the root, succeeds")
      And("the run writes nothing to stdout or stderr, sends no message outside a suite, and completes")
      And("Mill colours its console output")
      When("the test command runs core.test, with Mill's ticker on as by default")
      val result = withFixtureBuild(FixtureName.withoutPluginOnScala("3.3")) { build =>
        build.addPlugin()
        build.runTestCommandWithTicker("core.test")
      }

      Then("each line of the human-readable output on stdout begins with the prefix Mill puts on every line the test command's own task writes, ahead of the line's indentation")
      val prefixes = result.prefixedHumanReadableLines.map(line => TestCommandResult.millPrefix.findPrefixOf(line))
      withClue(result.debugString) {
        result.prefixedHumanReadableLines should have size 4
        prefixes.flatten should have size 4
        prefixes.distinct should have size 1
      }

      And("the last line of the human-readable output on stdout, with every ANSI escape sequence removed, is that prefix followed by the summary line")
      withClue(result.debugString) {
        prefixes.head.map(_ + "Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0") shouldBe
          Some(result.prefixedHumanReadableLines.last)
      }

      And("in that line 0 aborted and failed 0 are red, succeeded 1 is green, canceled 0, ignored 0 and pending 0 are yellow, and every other character after the prefix is cyan")
      withClue(result.debugString) {
        result.colourMarkedSummaryLine.map(TestCommandResult.withoutResetsBeforeAColour) shouldBe Some(
          TestCommandResult.withoutResetsBeforeAColour(
            "{cyan}Suites: 1 of 1 completed, {reset}{red}0 aborted{reset}" +
              "{cyan}. Tests: 1 of 1 run: {reset}{green}succeeded 1{reset}{cyan}, {reset}{red}failed 0{reset}" +
              "{cyan}, {reset}{yellow}canceled 0{reset}{cyan}, {reset}{yellow}ignored 0{reset}{cyan}, {reset}{yellow}pending 0{reset}"
          )
        )
      }
    }

    Scenario("The scopes and tests of each style show the labels ScalaTest 3.2.20 prints for them") {
      Given("one suite of each Any style, AsyncFeatureSpec and RefSpec, each with one test that succeeds")
      When("the test command runs core.test")
      val result = withFixtureBuild(FixtureName.styles) { build =>
        build.runTestCommand("core.test")
      }

      Then("each suite's lines are its name line and its scopes' and test's labels")
      withClue(result.debugString) {
        result.subtreeOf("CalcFunSuite:") shouldBe Seq("CalcFunSuite:", "  - adds")
        result.subtreeOf("CalcPropSpec:") shouldBe Seq("CalcPropSpec:", "  - adds")
        result.subtreeOf("CalcFunSpec:") shouldBe Seq("CalcFunSpec:", "  A calculator", "    - adds")
        result.subtreeOf("CalcFreeSpec:") shouldBe Seq("CalcFreeSpec:", "  A calculator", "    - adds")
        result.subtreeOf("CalcWordSpec:") shouldBe Seq("CalcWordSpec:", "  A calculator", "    when empty", "      - should add")
        result.subtreeOf("CalcFlatSpec:") shouldBe Seq("CalcFlatSpec:", "  A calculator", "    - should add")
        result.subtreeOf("CalcFeatureSpec:") shouldBe Seq("CalcFeatureSpec:", "  Feature: Addition", "    Scenario: adds")
        result.subtreeOf("CalcAsyncFeatureSpec:") shouldBe Seq("CalcAsyncFeatureSpec:", "  Feature: Addition", "    - Scenario: adds")
        result.subtreeOf("CalcRefSpec:") shouldBe Seq("CalcRefSpec:", "  A calculator", "    - adds two")
      }
    }
  }

  Feature("A suite whose constructor throws is aborted, and the module's other suites still run") {

    Scenario("A suite's constructor throws") {
      Given("suite BrokenCtorSpec, class samples.BrokenCtorSpec, an AnyFunSuite whose constructor throws IllegalStateException(\"no config\")")
      And("suite SqrtPropSpec, an AnyPropSpec whose one property, sqrt of a square is the root, succeeds")
      And("Mill colours its console output")
      When("the test command runs core.test")
      val result = brokenConstructorRun

      Then("the lines for BrokenCtorSpec begin with BrokenCtorSpec: and samples.BrokenCtorSpec *** ABORTED ***")
      val brokenLines = result.subtreeOf("BrokenCtorSpec:")
      withClue(result.debugString) {
        brokenLines.take(2) shouldBe Seq("BrokenCtorSpec:", "  samples.BrokenCtorSpec *** ABORTED ***")
      }

      And("a line under the abort is the exception")
      brokenLines.drop(2).map(_.trim) should contain("java.lang.IllegalStateException: no config")

      And("SqrtPropSpec's lines are those of a module holding it alone")
      result.subtreeOf("SqrtPropSpec:") shouldBe sqrtPropSpecLines
    }
  }

  Feature("Only a run reaches the console, and only through the human-readable output") {

    Scenario("Mill's discovery runner adds nothing to the output or the result") {
      Given("core.test holds SqrtPropSpec")
      When("the test command runs core.test")
      val result = sqrtPropSpecRun

      Then("the test command succeeds")
      shouldSucceed(result)

      And("the human-readable output is the module's line with the suite and its property under it, with no root's line and no run's line")
      withClue(result.debugString) {
        result.treeLines shouldBe ("[core.test]" +: sqrtPropSpecLines.map("  " + _))
      }
    }

    Scenario("Neither ScalaTest's end-of-run summary nor Mill's list of failed tests reaches the console") {
      Given("core.test holds BrokenCtorSpec, which aborts, and SqrtPropSpec")
      When("the test command runs core.test")
      val result = brokenConstructorRun

      Then("no line on the console holds ScalaTest's summary or Mill's list of failed tests")
      val console = result.consoleText
      withClue(result.debugString) {
        Seq("Run completed in", "Tests: succeeded", "*** 1 SUITE ABORTED ***", "tests failed:")
          .filter(console.contains) shouldBe empty
      }
    }
  }
}
