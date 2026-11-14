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
    withTester("without-plugin-scala-3.3") { tester =>
      addPlugin(tester)
      runTestCommand(tester, "core.test")
    }

  private lazy val brokenConstructorRun: TestCommandResult =
    withTester("broken-constructor") { tester =>
      runTestCommand(tester, "core.test")
    }

  Feature("The human-readable output is the module's test run label, with its suites under it") {

    Scenario("A module's label heads its suites") {
      Given("core.test, at Mill's default testLogLevel, holds SqrtPropSpec, whose one property succeeds")
      When("the test command runs core.test")
      val result = sqrtPropSpecRun

      Then("the human-readable output is the label, with the suite and its property under it")
      withClue(result.debugString) {
        result.humanReadableOutput shouldBe ("[core.test]" +: sqrtPropSpecLines.map("  " + _))
      }
    }

    Scenario("Every line of the human-readable output on stdout carries Mill's prefix") {
      Given("core.test holds SqrtPropSpec")
      When("the test command runs core.test, with Mill's ticker on as by default")
      val result = withTester("without-plugin-scala-3.3") { tester =>
        addPlugin(tester)
        runTestCommandWithTicker(tester, "core.test")
      }

      Then("each line of the human-readable output on stdout begins with one prefix of Mill's, ahead of its indentation")
      val prefixes = result.humanReadableStdoutLines.map(line => TestCommandResult.millPrefix.findPrefixOf(line))
      withClue(result.debugString) {
        result.humanReadableStdoutLines should have size 3
        prefixes.flatten should have size 3
        prefixes.distinct should have size 1
      }
    }

    Scenario("The scopes and tests of each style show the labels ScalaTest 3.2.20 prints for them") {
      Given("one suite of each Any style, AsyncFeatureSpec and RefSpec, each with one test that succeeds")
      When("the test command runs core.test")
      val result = withTester("styles") { tester =>
        runTestCommand(tester, "core.test")
      }

      Then("each suite's lines are its name line and its scopes' and test's labels")
      withClue(result.debugString) {
        result.linesFor("CalcFunSuite:") shouldBe Seq("CalcFunSuite:", "  - adds")
        result.linesFor("CalcPropSpec:") shouldBe Seq("CalcPropSpec:", "  - adds")
        result.linesFor("CalcFunSpec:") shouldBe Seq("CalcFunSpec:", "  A calculator", "    - adds")
        result.linesFor("CalcFreeSpec:") shouldBe Seq("CalcFreeSpec:", "  A calculator", "    - adds")
        result.linesFor("CalcWordSpec:") shouldBe Seq("CalcWordSpec:", "  A calculator", "    when empty", "      - should add")
        result.linesFor("CalcFlatSpec:") shouldBe Seq("CalcFlatSpec:", "  A calculator", "    - should add")
        result.linesFor("CalcFeatureSpec:") shouldBe Seq("CalcFeatureSpec:", "  Feature: Addition", "    Scenario: adds")
        result.linesFor("CalcAsyncFeatureSpec:") shouldBe Seq("CalcAsyncFeatureSpec:", "  Feature: Addition", "    - Scenario: adds")
        result.linesFor("CalcRefSpec:") shouldBe Seq("CalcRefSpec:", "  A calculator", "    - adds two")
      }
    }
  }

  Feature("A suite whose constructor throws is aborted, and the module's other suites still run") {

    Scenario("A suite's constructor throws") {
      Given("core.test holds BrokenCtorSpec, whose constructor throws IllegalStateException(\"no config\"), and SqrtPropSpec")
      When("the test command runs core.test")
      val result = brokenConstructorRun

      Then("BrokenCtorSpec's lines show it aborted")
      val brokenLines = result.linesFor("BrokenCtorSpec:")
      withClue(result.debugString) {
        brokenLines.take(2) shouldBe Seq("BrokenCtorSpec:", "  samples.BrokenCtorSpec *** ABORTED ***")
      }

      And("a line under the abort is the exception")
      brokenLines.drop(2).map(_.trim) should contain("java.lang.IllegalStateException: no config")

      And("SqrtPropSpec's lines are those of a module holding it alone")
      result.linesFor("SqrtPropSpec:") shouldBe sqrtPropSpecLines
    }
  }

  Feature("Only a run reaches the console, and only through the human-readable output") {

    Scenario("Mill's discovery runner adds nothing to the output or the result") {
      Given("core.test holds SqrtPropSpec")
      When("the test command runs core.test")
      val result = sqrtPropSpecRun

      Then("the test command succeeds")
      withClue(result.debugString) {
        result.succeeded shouldBe true
      }

      And("the human-readable output holds one SqrtPropSpec: line and no Output outside any suite: line")
      result.humanReadableOutput.count(_.trim == "SqrtPropSpec:") shouldBe 1
      result.humanReadableOutput.filter(_.contains("Output outside any suite:")) shouldBe empty
    }

    Scenario("Neither ScalaTest's end-of-run summary nor Mill's list of failed tests reaches the console") {
      Given("core.test holds BrokenCtorSpec, which aborts, and SqrtPropSpec")
      When("the test command runs core.test")
      val result = brokenConstructorRun

      Then("no line on the console holds ScalaTest's summary or Mill's list of failed tests")
      val console = result.eval.out + result.eval.err
      withClue(result.debugString) {
        Seq("Run completed in", "Tests: succeeded", "*** 1 SUITE ABORTED ***", "tests failed:")
          .filter(console.contains) shouldBe empty
      }
    }
  }
}
