package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

import scala.util.matching.Regex

final class TestCommandOutputArgumentsSpec
    extends AnyFeatureSpec
    with GivenWhenThen
    with should.Matchers
    with SingleModuleFixture {

  private val sharedCalculatorSuiteBuild: SharedSandbox =
    sharedSandbox(FixtureName.arguments, SandboxReset.noModuleFolders)(_.addSuites("CalculatorSuite"))

  private val calculatorSuiteOutput: Seq[String] = Seq(
    "[core.test]",
    "  Run 1:",
    "    CalculatorSuite:",
    "      - adds two numbers",
    "    [stdout] dividing now"
  )

  private val calculatorSuiteSummaryLine: String =
    "Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0"

  private val testCommandTaskName: String = "org.scalatest.mill.TestCommandModule/test"

  private def isMillsTaskNameLine(prefix: String, line: String): Boolean =
    // On stderr, after the task's prefix, Mill writes the task's name when it starts and the name with its duration
    // when it ends.
    line.matches(Regex.quote(prefix + testCommandTaskName) + """( [0-9]+(\.[0-9]+)?(ms|s|m))?""")

  Feature("The test command's output arguments") {

    Scenario("With no -o, stdout holds the human-readable output and nothing else the test command writes") {
      Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
      And("Mill colours its console output")
      When("the test command runs core.test with no -o, with Mill's ticker on")
      val (result, workerStarted) = sharedCalculatorSuiteBuild.use { build =>
        (build.runTestCommandWithTickerAndColour("core.test"), build.anyTestWorkerStarted)
      }

      Then("the human-readable output is Run 1:, holding the suite and then the test's line, then the summary line")
      withClue(result.debugString) {
        result.treeLines shouldBe calculatorSuiteOutput
        result.summaryLine shouldBe Some(calculatorSuiteSummaryLine)
      }

      And("no other line on stdout, and no line on stderr but Mill's own line naming the task, carries the test command's prefix")
      val prefix = result.prefixedHumanReadableLines.headOption.flatMap(TestCommandResult.millPrefix.findPrefixOf)
      withClue(result.debugString) {
        prefix shouldBe defined
        prefix.map(found => result.stdoutLines.filter(_.startsWith(found))) shouldBe Some(result.prefixedHumanReadableLines)
        prefix.map(found => result.stderrLines.filter(_.startsWith(found)).filterNot(isMillsTaskNameLine(found, _))) shouldBe Some(Nil)
      }

      And("the test command succeeds, having started a test worker")
      shouldSucceed(result)
      workerStarted shouldBe true
    }

    Scenario("With a bare -o, the console holds no consolidated output and none of the run's lines") {
      Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
      When("the test command runs core.test with -o")
      val result = sharedCalculatorSuiteBuild.use { build =>
        build.runTestCommand("core.test", "-o")
      }
      val console = result.consoleLines

      Then("the console holds no line of human-readable output, its summary line included, and no line holding dividing now")
      withClue(result.debugString) {
        result.treeLines shouldBe empty
        console.filter(_.contains(calculatorSuiteSummaryLine)) shouldBe empty
        console.filter(_.contains("dividing now")) shouldBe empty
      }

      And("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("-oP, holding an output letter other than H, fails the test command") {
      Given("CalculatorSuite")
      And("Mill colours its console output")
      When("the test command runs core.test with -oP")
      sharedCalculatorSuiteBuild.use { build =>
        val result = build.runTestCommandWithColour("core.test", "-oP")

        Then("the test command fails before any test starts")
        shouldFailBeforeAnyTestStarts(build, result)

        And("the console names -oP as the argument the test command rejects")
        shouldWriteToTheConsole(
          result,
          "The test command takes at most one of -oH or a bare -o, and at most one -fJ followed by a path, but was given: -oP"
        )

        And("the console holds no line of human-readable output")
        result.treeLines shouldBe empty
      }
    }
  }
}
