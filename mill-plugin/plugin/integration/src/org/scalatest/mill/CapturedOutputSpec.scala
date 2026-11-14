package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class CapturedOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val calculatorSuiteOutput: Seq[String] = Seq(
    "[core.test]",
    "  CalculatorSuite:",
    "    - adds two numbers",
    "  Output outside any suite:",
    "    [stdout] dividing now"
  )

  private val calculatorSuiteOutputMarkingColours: Seq[String] = Seq(
    "{cyan}[core.test]{reset}",
    "  {green}CalculatorSuite:{reset}",
    "    {green}- adds two numbers{reset}",
    "  {cyan}Output outside any suite:{reset}",
    "    [stdout] dividing now"
  )

  private val millModes: Seq[(String, Boolean)] = Seq("Mill's daemon" -> true, "--no-daemon" -> false)

  /**
   * Runs `test` on a copy of the `captured-output` fixture whose `core.test` holds the given suites from the fixture's
   * `suites` folder.
   */
  private def withSuites[A](suites: String*)(test: IntegrationTester => A): A = withSuitesIn(daemonMode = false)(suites*)(test)

  private def withSuitesIn[A](daemonMode: Boolean)(suites: String*)(test: IntegrationTester => A): A =
    withTester("captured-output", daemonMode) { tester =>
      val samples = tester.workspacePath / "core" / "test" / "src" / "samples"
      os.makeDir.all(samples)
      suites.foreach(suite => os.copy(tester.workspacePath / "suites" / s"$suite.scala", samples / s"$suite.scala"))
      test(tester)
    }

  /** Adds members to the build's `core.test` module. */
  private def addToTestModule(tester: IntegrationTester, members: String*): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val mvnDeps = """    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")"""
    os.write.over(buildFile, os.read(buildFile).replace(mvnDeps, (mvnDeps +: members.map("    " + _)).mkString("\n")))
  }

  private def evalTestCommand(tester: IntegrationTester, millArguments: String*): TestCommandResult =
    TestCommandResult(tester.eval(millArguments ++ Seq("scalatest/test", "core.test")))

  Feature("A run's block holds what its worker wrote to stdout and stderr") {

    millModes.foreach { case (mode, daemonMode) =>
      Scenario(s"A test's stdout line is shown in the run's block, under $mode") {
        Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds, at Mill's default testLogLevel")
        When("the test command runs core.test")
        val result = withSuitesIn(daemonMode)("CalculatorSuite") { tester =>
          runTestCommand(tester, "core.test")
        }

        Then("the human-readable output is the suite, then the run's block holding the line")
        withClue(result.debugString) {
          result.humanReadableOutput shouldBe calculatorSuiteOutput
        }
      }

      Scenario(s"Each stream's lines keep their order, under $mode") {
        Given("ChattySuite, whose one test writes out 1 to out 2000 to stdout and err 1 to err 2000 to stderr, alternating")
        When("the test command runs core.test")
        val result = withSuitesIn(daemonMode)("ChattySuite") { tester =>
          runTestCommand(tester, "core.test")
        }
        val block = result.linesFor("Output outside any suite:").drop(1).map(_.trim)

        Then("the run's block's stdout lines are out 1 to out 2000, in that order")
        withClue(result.debugString.take(10000)) {
          block.filter(_.startsWith("[stdout] ")) shouldBe (1 to 2000).map(i => s"[stdout] out $i")
        }

        And("its stderr lines are err 1 to err 2000, in that order")
        block.filter(_.startsWith("[stderr] ")) shouldBe (1 to 2000).map(i => s"[stderr] err $i")
      }
    }

    Scenario("A test's line and the JVM's own line take one form") {
      Given("CalculatorSuite, in a module whose forkArgs make the worker's JVM write a line ending Using Serial to stdout")
      When("the test command runs core.test")
      val result = withSuites("CalculatorSuite") { tester =>
        addToTestModule(tester, """def forkArgs = Seq("-XX:+UseSerialGC", "-Xlog:gc:stdout")""")
        runTestCommand(tester, "core.test")
      }
      val block = result.linesFor("Output outside any suite:").drop(1).map(_.trim)

      Then("the run's block holds the test's line and the JVM's line, each as [stdout] and the line written")
      withClue(result.debugString) {
        block should contain("[stdout] dividing now")
        block.filter(_.endsWith("Using Serial")) should have size 1
        block.filter(_.endsWith("Using Serial")).head should startWith("[stdout] [")
      }
    }
  }

  Feature("A run's block holds the worker's output whatever the module's testLogLevel") {

    Scenario("A module whose testLogLevel is Debug") {
      Given("CalculatorSuite, in a module whose testLogLevel is Debug, at which Mill prints Running Test Class")
      When("the test command runs core.test")
      val result = withSuites("CalculatorSuite") { tester =>
        addToTestModule(tester, "def testLogLevel = mill.api.daemon.internal.TestReporter.LogLevel.Debug")
        runTestCommand(tester, "core.test")
      }
      val console = result.stdoutLines ++ result.eval.err.linesIterator

      Then("the run's block holds the test's line")
      withClue(result.debugString) {
        result.linesFor("Output outside any suite:").map(_.trim) should contain("[stdout] dividing now")
      }

      And("no line of the human-readable output or of the console holds Running Test Class")
      result.humanReadableOutput.filter(_.contains("Running Test Class")) shouldBe empty
      console.filter(_.contains("Running Test Class")) shouldBe empty
    }
  }

  Feature("Human-readable output on stdout is coloured where Mill colours its own console output") {

    Scenario("The same text with colour on and off") {
      Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds")
      When("the test command runs core.test once with --color true and once with --color false")
      val (coloured, uncoloured) = withSuites("CalculatorSuite") { tester =>
        (evalTestCommand(tester, "--color", "true"), evalTestCommand(tester, "--color", "false"))
      }

      Then("the first human-readable output is the suite and the run's block, in colour")
      withClue(coloured.debugString) {
        coloured.humanReadableOutputMarkingColours shouldBe calculatorSuiteOutputMarkingColours
      }

      And("the second holds no ANSI escape sequence")
      withClue(uncoloured.debugString) {
        uncoloured.humanReadableOutputMarkingColours shouldBe uncoloured.humanReadableOutput
      }

      And("it equals the first with every ANSI escape sequence removed")
      uncoloured.humanReadableOutput shouldBe coloured.humanReadableOutput
    }
  }

  Feature("A test sees the environment and working directory Mill's testForked gives it") {

    Scenario("A forkEnv variable and the working directory with test sandboxing off") {
      Given("WhereSuite, which writes FIXTURE_ENV and its working directory to stdout")
      And("a module whose forkEnv sets FIXTURE_ENV to from-forkEnv, and whose testSandboxWorkingDir is false")
      When("the test command runs core.test, and testForked runs it")
      val (result, testForked) = withSuites("WhereSuite") { tester =>
        addToTestModule(
          tester,
          """def forkEnv = super.forkEnv() ++ Map("FIXTURE_ENV" -> "from-forkEnv")""",
          "def testSandboxWorkingDir = false"
        )
        (runTestCommand(tester, "core.test"), tester.eval(Seq("core.test.testForked")))
      }
      val block = result.linesFor("Output outside any suite:").drop(1).map(_.trim)

      Then("the run's block holds the forkEnv value")
      withClue(result.debugString) {
        block should contain("[stdout] env=from-forkEnv")
      }

      And("its working directory line is the one testForked wrote")
      val testForkedLines = testForked.out.linesIterator.filter(_.contains("cwd=")).map(line => line.substring(line.indexOf("cwd="))).toSeq
      withClue(testForked.debugString) {
        testForkedLines should have size 1
        block.filter(_.startsWith("[stdout] cwd=")) shouldBe testForkedLines.map("[stdout] " + _)
      }
    }
  }

  Feature("A run reaches the console only through the human-readable output") {

    Scenario("A failing test that writes to both streams") {
      Given("QueueFlatSpec, whose test be empty succeeds, and have size 0 writes to stdout and stderr, then fails")
      When("the test command runs core.test")
      val result = withSuites("QueueFlatSpec") { tester =>
        runTestCommand(tester, "core.test")
      }
      val console = result.stdoutLines ++ result.eval.err.linesIterator

      Then("no line on the console holds ScalaTest's end-of-run summary or Mill's list of failed tests")
      withClue(result.debugString) {
        result.humanReadableOutput.map(_.trim) should contain("- must have size 0 *** FAILED ***")
        Seq("Run completed in", "Tests: succeeded", "*** 1 TEST FAILED ***", "tests failed:")
          .filter(text => console.exists(_.contains(text))) shouldBe empty
      }

      And("no line on the console outside the human-readable output is a reporter's, Mill's progress or the run's")
      val outside = result.consoleLinesOutsideHumanReadableOutput
      withClue(result.debugString) {
        Seq("QueueFlatSpec:", "- should be empty", "samples.QueueFlatSpec", "1/1 completed", "dividing now", "warn: careful")
          .filter(text => outside.exists(_.contains(text))) shouldBe empty
      }
    }

    Scenario("Output of a task named before the test command") {
      Given("AddSuite, and the build's task banner, which writes building banner to stdout")
      When("one mill command runs banner and then the test command, with Mill's ticker on")
      val result = withSuites("AddSuite") { tester =>
        evalTestCommand(tester, "--ticker", "true", "banner", "+")
      }

      Then("the console holds building banner outside the human-readable output, with Mill's prefix")
      withClue(result.debugString) {
        result.consoleLinesOutsideHumanReadableOutput.filter(_.endsWith("building banner")) should have size 1
        TestCommandResult.millPrefix.findPrefixOf(result.consoleLinesOutsideHumanReadableOutput.filter(_.endsWith("building banner")).head) shouldBe defined
      }

      And("no line of the human-readable output holds building banner")
      result.humanReadableOutput.filter(_.contains("building banner")) shouldBe empty
    }

    Scenario("A second test command after a test is fixed") {
      Given("CalculatorSuite, whose test adds two numbers has been changed to fail, and the test command has run core.test")
      When("the test is changed back to succeed, and the test command runs core.test again")
      val (first, second) = withSuites("CalculatorSuite") { tester =>
        val source = tester.workspacePath / "core" / "test" / "src" / "samples" / "CalculatorSuite.scala"
        val succeeding = os.read(source)
        os.write.over(source, succeeding.replace("1 + 1 == 2", "1 + 1 == 3"))
        val first = runTestCommand(tester, "core.test")
        os.write.over(source, succeeding)
        (first, runTestCommand(tester, "core.test"))
      }
      withClue(first.debugString) {
        first.succeeded shouldBe false
      }

      Then("the second human-readable output is the suite and the run's block alone")
      withClue(second.debugString) {
        second.humanReadableOutput shouldBe calculatorSuiteOutput
      }

      And("the second test command succeeds")
      second.succeeded shouldBe true
    }
  }
}
