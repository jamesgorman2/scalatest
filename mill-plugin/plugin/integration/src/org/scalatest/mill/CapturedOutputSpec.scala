package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class CapturedOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private val calculatorSuiteOutput: Seq[String] = Seq(
    "[core.test]",
    "  Run 1:",
    "    CalculatorSuite:",
    "      - adds two numbers",
    "    [stdout] dividing now"
  )

  private val calculatorSuiteOutputMarkingColours: Seq[String] = Seq(
    "{cyan}[core.test]{reset}",
    "  {cyan}Run 1:{reset}",
    "    {green}CalculatorSuite:{reset}",
    "      {green}- adds two numbers{reset}",
    "    [stdout] dividing now"
  )

  /** A line the JVM's unified logging writes with its default decorators: uptime, level and tags. */
  private val jvmUnifiedLoggingLine: String = """\[stdout\] \[[0-9]+[.,][0-9]+s\]\[info\]\[gc\] Using Serial"""

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

  Feature("A run's line holds what its worker wrote to stdout and stderr") {

    millModes.foreach { case (mode, daemonMode) =>
      Scenario(s"A test's stdout line is shown under Run 1:, under $mode") {
        Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds, at Mill's default testLogLevel")
        And("Mill colours its console output")
        When("the test command runs core.test")
        val result = withSuitesIn(daemonMode)("CalculatorSuite") { tester =>
          evalTestCommand(tester, "--color", "true")
        }

        Then("the human-readable output is the module's line, then Run 1: holding the suite and then the line")
        withClue(result.debugString) {
          result.humanReadableOutputMarkingColours shouldBe calculatorSuiteOutputMarkingColours
        }
      }

      Scenario(s"Each stream's lines keep their order, under $mode") {
        Given("ChattySuite, whose one test writes out 1 to out 2000 to stdout and err 1 to err 2000 to stderr, alternating")
        And("Mill colours its console output")
        When("the test command runs core.test")
        val result = withSuitesIn(daemonMode)("ChattySuite") { tester =>
          evalTestCommand(tester, "--color", "true")
        }
        val underRunLines = result.linesFor("Run 1:").drop(1).map(_.trim)

        Then("the [stdout] lines under Run 1: are out 1 to out 2000, in that order")
        withClue(result.debugString.take(10000)) {
          underRunLines.filter(_.startsWith("[stdout] ")) shouldBe (1 to 2000).map(i => s"[stdout] out $i")
        }

        And("the [stderr] lines under Run 1: are err 1 to err 2000, in that order")
        underRunLines.filter(_.startsWith("[stderr] ")) shouldBe (1 to 2000).map(i => s"[stderr] err $i")
      }
    }

    Scenario("A test's line and the JVM's own line take one form") {
      Given("CalculatorSuite, in a module whose forkArgs make the worker's JVM write a line ending Using Serial to stdout")
      And("Mill colours its console output")
      When("the test command runs core.test")
      val result = withSuites("CalculatorSuite") { tester =>
        addToTestModule(tester, """def forkArgs = Seq("-XX:+UseSerialGC", "-Xlog:gc:stdout")""")
        evalTestCommand(tester, "--color", "true")
      }
      val underRunLines = result.linesFor("Run 1:").drop(1).map(_.trim)
      val written = result.humanReadableOutputMarkingColours.map(_.trim)

      Then("the lines under Run 1: hold [stdout] dividing now and a line [stdout] <text> whose text ends Using Serial")
      withClue(result.debugString) {
        underRunLines should contain("[stdout] dividing now")
        underRunLines.filter(_.endsWith("Using Serial")) should have size 1
      }

      And("each of those two lines is [stdout] followed by nothing but the line written")
      withClue(result.debugString) {
        written should contain("[stdout] dividing now")
        written.filter(_.endsWith("Using Serial")) should have size 1
        written.filter(_.endsWith("Using Serial")).head should fullyMatch regex jvmUnifiedLoggingLine
      }
    }
  }

  Feature("A run's line holds the worker's output whatever the module's testLogLevel") {

    Scenario("A module whose testLogLevel is Debug") {
      Given("CalculatorSuite, in a module whose testLogLevel is Debug, at which Mill prints Running Test Class")
      When("the test command runs core.test")
      val result = withSuites("CalculatorSuite") { tester =>
        addToTestModule(tester, "def testLogLevel = mill.api.daemon.internal.TestReporter.LogLevel.Debug")
        runTestCommand(tester, "core.test")
      }
      val console = result.stdoutLines ++ result.stderrLines

      Then("the lines under Run 1: hold the test's line")
      withClue(result.debugString) {
        result.linesFor("Run 1:").map(_.trim) should contain("[stdout] dividing now")
      }

      And("no line of the human-readable output or of the console holds Running Test Class")
      result.humanReadableOutput.filter(_.contains("Running Test Class")) shouldBe empty
      console.filter(_.contains("Running Test Class")) shouldBe empty
    }
  }

  Feature("Human-readable output on stdout is coloured whether or not Mill colours its own console output") {

    Scenario("The same output with Mill's colour on and off") {
      Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds")
      When("the test command runs core.test once with Mill's --color true and once with Mill's --color false")
      val outputs = withSuites("CalculatorSuite") { tester =>
        Seq("true", "false").map(colour => colour -> evalTestCommand(tester, "--ticker", "true", "--color", colour))
      }

      outputs.foreach { case (colour, result) =>
        Then(s"under --color $colour, the human-readable output, with Mill's prefix and every ANSI escape sequence removed from each line, is the module's line, then Run 1: holding the suite and the test's line")
        withClue(result.debugString) {
          result.humanReadableOutput shouldBe calculatorSuiteOutput
        }

        And(s"under --color $colour, [core.test] and Run 1: are cyan, - adds two numbers is green and [stdout] dividing now is uncoloured")
        withClue(result.debugString) {
          result.humanReadableOutputMarkingColours shouldBe calculatorSuiteOutputMarkingColours
        }
      }
    }
  }

  Feature("A test sees the environment and working directory Mill's testForked gives it") {

    Scenario("A forkEnv variable and the working directory with test sandboxing off") {
      Given("the build adds the Mill plugin at that release as the entry com.edc::scalatest-mill of its build.mill header's mvnDeps, without changing the module's definition")
      And("the module's forkEnv sets FIXTURE_ENV to from-forkEnv, and its testSandboxWorkingDir is false")
      And("WhereSuite, which writes FIXTURE_ENV and its working directory to stdout")
      When("the test command runs core.test, and testForked runs it")
      val (result, testForked) = withSuites("WhereSuite") { tester =>
        addToTestModule(
          tester,
          """def forkEnv = super.forkEnv() ++ Map("FIXTURE_ENV" -> "from-forkEnv")""",
          "def testSandboxWorkingDir = false"
        )
        (runTestCommand(tester, "core.test"), tester.eval(Seq("core.test.testForked")))
      }
      val underRunLines = result.linesFor("Run 1:").drop(1).map(_.trim)

      Then("a line under Run 1: holds the forkEnv value")
      withClue(result.debugString) {
        underRunLines should contain("[stdout] env=from-forkEnv")
      }

      And("its working directory line is the one testForked wrote")
      val testForkedLines = testForked.out.linesIterator.filter(_.contains("cwd=")).map(line => line.substring(line.indexOf("cwd="))).toSeq
      withClue(testForked.debugString) {
        testForkedLines should have size 1
        underRunLines.filter(_.startsWith("[stdout] cwd=")) shouldBe testForkedLines.map("[stdout] " + _)
      }
    }
  }

  Feature("A run reaches the console only through the human-readable output") {

    Scenario("A failing test that writes to both streams") {
      Given("QueueFlatSpec, whose test be empty succeeds, and have size 0 writes to stdout and stderr, then fails")
      And("Mill's ticker is on and Mill colours its console output")
      When("the test command runs core.test, and Mill's own test task runs core.test in the same build without the plugin")
      val (result, withoutPlugin) = withSuites("QueueFlatSpec") { tester =>
        val result = evalTestCommand(tester, "--ticker", "true", "--color", "true")
        val buildFile = tester.workspacePath / "build.mill"
        os.write.over(buildFile, os.read(buildFile).linesIterator.filterNot(_.startsWith("//| mvnDeps:")).mkString("\n"))
        (result, MillConsole(tester.eval(Seq("--ticker", "true", "--color", "true", "core.test"))))
      }
      val console = result.stdoutLines ++ result.stderrLines

      Then("no line on the console holds ScalaTest's end-of-run summary or Mill's list of failed tests")
      withClue(result.debugString) {
        result.humanReadableOutput.map(_.trim) should contain("- must have size 0 *** FAILED ***")
        Seq("Run completed in", "Tests: succeeded", "*** 1 TEST FAILED ***", "tests failed:")
          .filter(text => console.exists(_.contains(text))) shouldBe empty
      }

      And("no line on the console outside the human-readable output is a reporter's, Mill's progress or the run's")
      val outside = result.consoleLinesOutsideHumanReadableOutput
      withClue(result.debugString) {
        Seq("QueueFlatSpec:", "- should be empty", "samples.QueueFlatSpec", "dividing now", "warn: careful")
          .filter(text => outside.exists(_.contains(text))) shouldBe empty
      }

      And("without the plugin, Mill's own test task prints a reporter's lines, Mill's progress line naming the test class, and the run's lines")
      withClue(withoutPlugin.debugString) {
        Seq("QueueFlatSpec:", "- should be empty", "Running Test Class samples.QueueFlatSpec", "dividing now", "warn: careful")
          .filterNot(text => withoutPlugin.lines.exists(_.contains(text))) shouldBe empty
      }
    }

    Scenario("Output of a task named before the test command") {
      Given("AddSuite, and the build's task banner, which writes building banner to stdout")
      When("one mill command runs banner and then the test command, with Mill's ticker on")
      val result = withSuites("AddSuite") { tester =>
        evalTestCommand(tester, "--ticker", "true", "banner", "+")
      }

      Then("the console holds building banner outside the human-readable output, with Mill's prefix")
      val bannerLines = result.consoleLinesOutsideHumanReadableOutput.filter(_.endsWith("building banner"))
      withClue(result.debugString) {
        bannerLines should have size 1
        TestCommandResult.millPrefix.findPrefixOf(bannerLines.head) shouldBe defined
      }

      And("the human-readable output is the module's line with AddSuite and its test under it, then the summary line")
      withClue(result.debugString) {
        result.humanReadableOutput shouldBe Seq("[core.test]", "  AddSuite:", "    - adds")
        result.summaryLine shouldBe Some(
          "Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0"
        )
      }
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

      Then("the second human-readable output is Run 1:, holding the suite and the test's line, alone")
      withClue(second.debugString) {
        second.humanReadableOutput shouldBe calculatorSuiteOutput
      }

      And("the second test command succeeds")
      second.succeeded shouldBe true
    }
  }
}
