package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

import scala.util.matching.Regex

final class TestCommandArgumentsIntegrationSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

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

  private def withSuites[A](moduleArguments: String*)(suites: String*)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.arguments) { build =>
      build.addSuites(suites*)
      if (moduleArguments.nonEmpty) {
        build.setModuleArguments(moduleArguments)
      }
      test(build)
    }

  private def withMarkingSuites[A](test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.classGlobsAndFrameworkArguments) { build =>
      os.makeDir.all(build.markersFolder)
      test(build)
    }

  private def shouldFailBeforeAnyTestStartsWritingNoOutput(build: FixtureBuild, result: TestCommandResult, message: String): Unit = {
    Then("Mill reports that the test command failed before any test started, with a message naming the argument")
    shouldFailBeforeAnyTestStarts(build, result)
    shouldWriteToTheConsole(result, message)

    And("no file exists in the markers folder")
    withClue(result.debugString) {
      os.list(build.markersFolder) shouldBe empty
    }

    And("nothing exists at the JSON output file's path")
    withClue(result.debugString) {
      os.exists(build.jsonOutputFile) shouldBe false
    }
  }

  Feature("Framework arguments") {

    Scenario("With no -- after the test command's own arguments, every test runs") {
      Given("Alpha1, Alpha2 and Beta1, each with the tests fast and slow")
      And("each test creates a file in the markers folder as it runs")
      When("the test command runs core.test, writing JSON output, with nothing after -fJ and its path")
      withMarkingSuites { build =>
        val result = build.runTestCommandWritingJsonOutputWith(FixtureBuild.jobsArguments(2))()

        Then("the test command succeeds")
        shouldSucceed(result)

        And("the markers folder holds a file for each test of each suite")
        withClue(result.debugString) {
          os.list(build.markersFolder).map(_.last).sorted shouldBe
            Seq("Alpha1.fast", "Alpha1.slow", "Alpha2.fast", "Alpha2.slow", "Beta1.fast", "Beta1.slow")
        }
      }
    }

    val rejectedFrameworkArguments = Seq(
      Seq("-oD") -> "-oD",
      Seq("-s", "samples.Alpha1") -> "-s",
      Seq("--unknown") -> "--unknown",
      Seq("-n") -> "-n",
      Seq("foo") -> "foo"
    )

    for ((frameworkArguments, named) <- rejectedFrameworkArguments) {
      Scenario(s"The framework argument ${frameworkArguments.mkString(" ")} fails the test command, naming $named") {
        Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
        When(s"the test command runs core.test, writing JSON output, with -- -- ${frameworkArguments.mkString(" ")}")
        withMarkingSuites { build =>
          val result = build.runTestCommandWritingJsonOutputWith(FixtureBuild.jobsArguments(2))(("--" +: "--" +: frameworkArguments)*)

          shouldFailBeforeAnyTestStartsWritingNoOutput(build, result, TestCommandFailure.FrameworkArgumentNotPassedOn(named).message)
        }
      }
    }

    for (arguments <- Seq(Seq("-n", "samples.Fast", "--", "samples.Alpha1"), Seq("-n", "samples.Fast"))) {
      Scenario(s"A tag filter among the test command's own arguments, in ${arguments.mkString(" ")}, fails the test command, naming -n") {
        Given("Alpha1, Alpha2 and Beta1, each of whose tests creates a file in the markers folder as it runs")
        When(s"the test command runs core.test, writing JSON output, with ${arguments.mkString(" ")}")
        withMarkingSuites { build =>
          val result = build.runTestCommandWritingJsonOutputWith(FixtureBuild.jobsArguments(2))(arguments*)

          shouldFailBeforeAnyTestStartsWritingNoOutput(build, result, TestCommandFailure.FrameworkArgumentAmongOwnArguments("-n").message)
        }
      }
    }
  }

  Feature("The test command's output arguments") {

    for ((what, outputArguments) <- Seq("no -o" -> Nil, "-oH" -> Seq("-oH"))) {
      Scenario(s"With $what, stdout holds the human-readable output and nothing else the test command writes") {
        Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
        And("Mill colours its console output")
        When(s"the test command runs core.test with $what, with Mill's ticker on")
        val (result, workerStarted) = withSuites()("CalculatorSuite") { build =>
          (build.runTestCommandWithTickerAndColour(("core.test" +: outputArguments)*), build.anyTestWorkerStarted)
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
    }

    Scenario("With a bare -o, the console holds no consolidated output and none of the run's lines") {
      Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout")
      When("the test command runs core.test with -o")
      val result = withSuites()("CalculatorSuite") { build =>
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

    val rejectedArguments = Seq(
      "an output letter other than H" -> (Seq("-oP"), "-oP"),
      "another output letter other than H" -> (Seq("-oJ"), "-oJ"),
      "an output letter other than H, beside H" -> (Seq("-oHP"), "-oHP"),
      "a formatting character" -> (Seq("-oHd"), "-oHd"),
      "a lowercase character that is neither a formatting nor a filtering character" -> (Seq("-oHy"), "-oHy"),
      "an -f other than -fJ" -> (Seq("-fH", "report.txt"), "-fH")
    )

    for ((what, (outputArguments, rejected)) <- rejectedArguments) {
      Scenario(s"${outputArguments.mkString(" ")}, holding $what, fails the test command") {
        Given("CalculatorSuite")
        And("Mill colours its console output")
        When(s"the test command runs core.test with ${outputArguments.mkString(" ")}")
        withSuites()("CalculatorSuite") { build =>
          val result = build.runTestCommandWithColour(("core.test" +: outputArguments)*)

          Then("the test command fails before any test starts")
          shouldFailBeforeAnyTestStarts(build, result)

          And(s"the console names $rejected as the argument the test command rejects")
          shouldWriteToTheConsole(
            result,
            s"The test command takes at most one of -oH or a bare -o, and at most one -fJ followed by a path, but was given: $rejected"
          )

          And("the console holds no line of human-readable output")
          result.treeLines shouldBe empty

          And("no file report.txt is written")
          os.walk(build.workspace).filter(_.last == "report.txt") shouldBe empty
        }
      }
    }
  }

  Feature("The module's own ScalaTest arguments") {

    Scenario("A module's own ScalaTest argument whose flag the Runner does not recognise fails the test command, naming that argument") {
      Given("CalculatorSuite, in a module whose own ScalaTest arguments are -l samples.Slow -x")
      When("the test command runs core.test")
      withSuites("-l", "samples.Slow", "-x")("CalculatorSuite") { build =>
        val result = build.runTestCommand("core.test")

        Then("the test command fails before any test starts, naming -x")
        shouldFailBeforeAnyTestStarts(build, result)
        shouldWriteToTheConsole(result, "hold -x,")

        And("the console holds no line of human-readable output")
        result.treeLines shouldBe empty
      }
    }

    Scenario("A test-name substring that begins with - reaches the run") {
      Given("FlagSuite, with tests parses -y and parses -x, in a module whose own ScalaTest arguments are -z -y")
      When("the test command runs core.test")
      val result = withSuites("-z", "-y")("FlagSuite") { build =>
        build.runTestCommand("core.test")
      }

      Then("the human-readable output shows parses -y alone")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq("[core.test]", "  FlagSuite:", "    - parses -y")
      }
    }

    Scenario("A test whose tag the module's own arguments exclude is not shown") {
      Given("SpeedSuite, whose test slow is tagged samples.Slow and writes slow ran, in a module whose own ScalaTest arguments are -l samples.Slow")
      And("Mill colours its console output")
      When("the test command runs core.test")
      val result = withSuites("-l", "samples.Slow")("SpeedSuite") { build =>
        build.runTestCommandWithColour("core.test")
      }

      Then("the human-readable output shows fast alone")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq("[core.test]", "  SpeedSuite:", "    - fast")
      }
    }

    for (moduleArguments <- Seq(Seq("-oD"), Seq("-eD"), Seq("-s", "samples.OtherSuite"))) {
      Scenario(s"The module's own ${moduleArguments.mkString(" ")} leaves the human-readable output unchanged") {
        Given(s"CalculatorSuite and OtherSuite, in a module whose own ScalaTest arguments are ${moduleArguments.mkString(" ")}")
        And("the module's testParallelism is false")
        When("the test command runs core.test, and runs it again with the module's own arguments removed")
        val (result, withoutArguments) = withSuites(moduleArguments*)("CalculatorSuite", "OtherSuite") { build =>
          val result = build.runTestCommand("core.test")
          build.removeModuleArguments()
          (result, build.runTestCommand("core.test"))
        }

        Then("the human-readable output is the one the test command writes with no module arguments")
        withClue(result.debugString + withoutArguments.debugString) {
          withoutArguments.treeLines.map(_.trim) should contain allOf ("CalculatorSuite:", "OtherSuite:")
          withoutArguments.summaryLine shouldBe defined
          result.treeLines shouldBe withoutArguments.treeLines
          result.summaryLine shouldBe withoutArguments.summaryLine
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
