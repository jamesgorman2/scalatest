package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandArgumentsIntegrationSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val calculatorSuiteOutput: Seq[String] = Seq(
    "[core.test]",
    "  CalculatorSuite:",
    "    - adds two numbers",
    "  Output outside any suite:",
    "    [stdout] dividing now"
  )

  /**
   * Runs `test` on a copy of the `arguments` fixture whose `core.test` holds the given suites from the fixture's
   * `suites` folder, and whose own ScalaTest arguments are `moduleArguments`.
   */
  private def withSuites[A](moduleArguments: String*)(suites: String*)(test: IntegrationTester => A): A =
    withTester("arguments") { tester =>
      val samples = tester.workspacePath / "core" / "test" / "src" / "samples"
      os.makeDir.all(samples)
      suites.foreach(suite => os.copy(tester.workspacePath / "suites" / s"$suite.scala", samples / s"$suite.scala"))
      if (moduleArguments.nonEmpty) {
        setModuleArguments(tester, moduleArguments)
      }
      test(tester)
    }

  private def setModuleArguments(tester: IntegrationTester, moduleArguments: Seq[String]): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val mvnDeps = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""
    val testArgsDefault = moduleArguments.map(argument => s"\"$argument\"").mkString("    def testArgsDefault = Seq(", ", ", ")")
    os.write.over(buildFile, os.read(buildFile).replace(mvnDeps, s"$mvnDeps\n$testArgsDefault"))
  }

  private def shouldFailBeforeAnyTestStarts(tester: IntegrationTester, result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe false
      anyTestWorkerStarted(tester) shouldBe false
    }

  Feature("The test command's output arguments") {

    for ((what, outputArguments) <- Seq("no -o" -> Nil, "-oH" -> Seq("-oH"))) {
      Scenario(s"With $what, stdout holds the human-readable output and nothing else the test command writes") {
        Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
        When(s"the test command runs core.test with $what, with Mill's ticker on")
        val result = withSuites()("CalculatorSuite") { tester =>
          runTestCommandWithTicker(tester, ("core.test" +: outputArguments)*)
        }

        Then("the human-readable output is the suite, then the run's block")
        withClue(result.debugString) {
          result.humanReadableOutput shouldBe calculatorSuiteOutput
        }

        And("no other stdout line carries the test command's prefix")
        val prefix = TestCommandResult.millPrefix.findPrefixOf(result.humanReadableStdoutLines.head)
        withClue(result.debugString) {
          prefix shouldBe defined
          result.stdoutLines.filter(_.startsWith(prefix.get)) shouldBe result.humanReadableStdoutLines
        }

        And("the test command succeeds")
        result.succeeded shouldBe true
      }
    }

    Scenario("With a bare -o, the console holds no consolidated output and none of the run's lines") {
      Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
      When("the test command runs core.test with -o")
      val result = withSuites()("CalculatorSuite") { tester =>
        runTestCommand(tester, "core.test", "-o")
      }
      val console = result.stdoutLines ++ result.eval.err.linesIterator

      Then("the console holds no line of human-readable output, and no line holding dividing now")
      withClue(result.debugString) {
        result.humanReadableOutput shouldBe empty
        console.filter(_.contains("dividing now")) shouldBe empty
      }

      And("the test command succeeds")
      result.succeeded shouldBe true
    }

    val rejectedArguments = Seq(
      "an output letter other than H" -> (Seq("-oP"), "-oP"),
      "another output letter other than H" -> (Seq("-oJ"), "-oJ"),
      "an output letter other than H, beside H" -> (Seq("-oHP"), "-oHP"),
      "a formatting character" -> (Seq("-oHd"), "-oHd"),
      "a lowercase character that is neither a formatting nor a filtering character" -> (Seq("-oHy"), "-oHy"),
      "an -f" -> (Seq("-fH", "report.txt"), "-fH"),
      "an -f, beside -oH" -> (Seq("-oH", "-fJ", "report.txt"), "-fJ")
    )

    for ((what, (outputArguments, rejected)) <- rejectedArguments) {
      Scenario(s"${outputArguments.mkString(" ")}, holding $what, fails the test command") {
        Given("CalculatorSuite")
        When(s"the test command runs core.test with ${outputArguments.mkString(" ")}")
        withSuites()("CalculatorSuite") { tester =>
          val result = runTestCommand(tester, ("core.test" +: outputArguments)*)

          Then("the test command fails before any test starts")
          shouldFailBeforeAnyTestStarts(tester, result)

          And(s"the console names $rejected as the argument the test command rejects")
          withClue(result.debugString) {
            (result.eval.out + result.eval.err) should include(
              s"The test command takes one output argument, -oH or -o, but was given: $rejected")
          }

          And("the console holds no line of human-readable output")
          result.humanReadableOutput shouldBe empty

          And("no file report.txt is written")
          os.walk(tester.workspacePath).filter(_.last == "report.txt") shouldBe empty
        }
      }
    }
  }

  Feature("The module's own ScalaTest arguments") {

    Scenario("An argument whose flag ScalaTest does not recognise fails the test command, naming it") {
      Given("CalculatorSuite, in a module whose own ScalaTest arguments are -l samples.Slow -x")
      When("the test command runs core.test")
      withSuites("-l", "samples.Slow", "-x")("CalculatorSuite") { tester =>
        val result = runTestCommand(tester, "core.test")

        Then("the test command fails before any test starts, naming -x")
        shouldFailBeforeAnyTestStarts(tester, result)
        withClue(result.debugString) {
          (result.eval.out + result.eval.err) should include("hold -x,")
        }

        And("the console holds no line of human-readable output")
        result.humanReadableOutput shouldBe empty
      }
    }

    Scenario("A test-name substring that begins with - reaches the run") {
      Given("FlagSuite, with tests parses -y and parses -x, in a module whose own ScalaTest arguments are -z -y")
      When("the test command runs core.test")
      val result = withSuites("-z", "-y")("FlagSuite") { tester =>
        runTestCommand(tester, "core.test")
      }

      Then("the human-readable output shows parses -y alone")
      withClue(result.debugString) {
        result.humanReadableOutput shouldBe Seq("[core.test]", "  FlagSuite:", "    - parses -y")
      }
    }

    Scenario("A test whose tag the module's own arguments exclude is not shown") {
      Given("SpeedSuite, whose test slow is tagged samples.Slow and writes slow ran, in a module whose own ScalaTest arguments are -l samples.Slow")
      When("the test command runs core.test")
      val result = withSuites("-l", "samples.Slow")("SpeedSuite") { tester =>
        runTestCommand(tester, "core.test")
      }

      Then("the human-readable output shows fast alone")
      withClue(result.debugString) {
        result.humanReadableOutput shouldBe Seq("[core.test]", "  SpeedSuite:", "    - fast")
      }
    }

    for (moduleArguments <- Seq(Seq("-oD"), Seq("-eD"), Seq("-s", "samples.OtherSuite"))) {
      Scenario(s"${moduleArguments.mkString(" ")} has no effect") {
        Given(s"CalculatorSuite and OtherSuite, in a module whose own ScalaTest arguments are ${moduleArguments.mkString(" ")}")
        When("the test command runs core.test, and runs it again with the module's own arguments removed")
        val (result, withoutArguments) = withSuites(moduleArguments*)("CalculatorSuite", "OtherSuite") { tester =>
          val result = runTestCommand(tester, "core.test")
          val buildFile = tester.workspacePath / "build.mill"
          os.write.over(buildFile, os.read(buildFile).linesIterator.filterNot(_.contains("testArgsDefault")).mkString("\n"))
          (result, runTestCommand(tester, "core.test"))
        }

        Then("the human-readable output is the one the test command writes with no module arguments")
        withClue(result.debugString + withoutArguments.debugString) {
          withoutArguments.humanReadableOutput.map(_.trim) should contain allOf ("CalculatorSuite:", "OtherSuite:")
          result.humanReadableOutput shouldBe withoutArguments.humanReadableOutput
        }

        And("no line on the console outside the human-readable output holds adds two numbers or Run completed in")
        withClue(result.debugString) {
          result.consoleLinesOutsideHumanReadableOutput.filter(line =>
            line.contains("adds two numbers") || line.contains("Run completed in")
          ) shouldBe empty
        }
      }
    }
  }
}
