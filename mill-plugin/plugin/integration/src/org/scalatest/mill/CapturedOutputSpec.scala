package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class CapturedOutputSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SharedSandboxes {

  private val calculatorSuiteOutput: Seq[String] = Seq(
    "[core.test]",
    "  Run 1:",
    "    CalculatorSuite:",
    "      - adds two numbers",
    "    [stdout] dividing now"
  )

  private val colourMarkedCalculatorSuiteOutput: Seq[String] = Seq(
    "{cyan}[core.test]{reset}",
    "  {cyan}Run 1:{reset}",
    "    {green}CalculatorSuite:{reset}",
    "      {green}- adds two numbers{reset}",
    "    [stdout] dividing now"
  )

  // `-Xlog`'s default decorators: uptime, level and tags.
  private val jvmUnifiedLoggingLine: String = """\[stdout\] \[[0-9]+[.,][0-9]+s\]\[info\]\[gc\] Using Serial"""

  private def withSuites[A](suites: String*)(test: FixtureBuild => A): A = withSuitesIn(daemonMode = false)(suites*)(test)

  private def withSuitesUnderMillsDaemon[A](suites: String*)(test: FixtureBuild => A): A = withSuitesIn(daemonMode = true)(suites*)(test)

  private def withSuitesIn[A](daemonMode: Boolean)(suites: String*)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.capturedOutput, daemonMode) { build =>
      build.addSuites(suites*)
      test(build)
    }

  private val sharedCapturedOutputBuild: SharedSandbox =
    sharedSandbox(FixtureName.capturedOutput, SandboxReset.fixtureFiles(os.sub / "core" / "test" / "src" / "samples"))(_ => ())

  private def inSharedSandboxWithSuites[A](suites: String*)(test: FixtureBuild => A): A =
    sharedCapturedOutputBuild.use { build =>
      build.addSuites(suites*)
      test(build)
    }

  Feature("A run's line holds what its worker wrote to stdout and stderr") {

    Scenario("A test's stdout line is shown under Run 1:, with Mill's daemon") {
      val result = withSuitesUnderMillsDaemon("CalculatorSuite") { build =>
        Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds, at Mill's default testLogLevel")
        And("Mill colours its console output")
        When("the test command runs core.test")
        build.runTestCommandWithColour("core.test")
      }

      Then("the human-readable output is the module's line, then Run 1: holding the suite and then the line")
      withClue(result.debugString) {
        result.colourMarkedTreeLines shouldBe colourMarkedCalculatorSuiteOutput
      }
    }

    Scenario("Each stream's lines keep their order, with Mill's daemon") {
      val result = withSuitesUnderMillsDaemon("ChattySuite") { build =>
        Given("ChattySuite, whose one test writes out 1 to out 2000 to stdout and err 1 to err 2000 to stderr, alternating")
        And("Mill colours its console output")
        When("the test command runs core.test")
        build.runTestCommandWithColour("core.test")
      }
      val underRunLines = result.subtreeOf("Run 1:").drop(1).map(_.trim)

      Then("the [stdout] lines under Run 1: are out 1 to out 2000, in that order")
      withClue(result.debugString.take(10000)) {
        underRunLines.filter(_.startsWith("[stdout] ")) shouldBe (1 to 2000).map(i => s"[stdout] out $i")
      }

      And("the [stderr] lines under Run 1: are err 1 to err 2000, in that order")
      underRunLines.filter(_.startsWith("[stderr] ")) shouldBe (1 to 2000).map(i => s"[stderr] err $i")
    }

    Scenario("A test's line and the JVM's own line take one form") {
      val result = withSuites("CalculatorSuite") { build =>
        Given("CalculatorSuite, in a module whose forkArgs make the worker's JVM write a line ending Using Serial to stdout")
        build.addToTestModule("""def forkArgs = Seq("-XX:+UseSerialGC", "-Xlog:gc:stdout")""")
        And("Mill colours its console output")
        When("the test command runs core.test")
        build.runTestCommandWithColour("core.test")
      }
      val underRunLines = result.subtreeOf("Run 1:").drop(1).map(_.trim)
      val written = result.colourMarkedTreeLines.map(_.trim)

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

    Scenario("At testLogLevel Debug, the run's line still holds the test's output, and Mill's Running Test Class line is not shown") {
      val result = withSuites("CalculatorSuite") { build =>
        Given("CalculatorSuite, in a module whose testLogLevel is Debug, at which Mill prints Running Test Class")
        build.addToTestModule("def testLogLevel = mill.api.daemon.internal.TestReporter.LogLevel.Debug")
        When("the test command runs core.test")
        build.runTestCommand("core.test")
      }
      val console = result.consoleLines

      Then("the lines under Run 1: hold the test's line")
      withClue(result.debugString) {
        result.subtreeOf("Run 1:").map(_.trim) should contain("[stdout] dividing now")
      }

      And("no line of the human-readable output or of the console holds Running Test Class")
      result.treeLines.filter(_.contains("Running Test Class")) shouldBe empty
      console.filter(_.contains("Running Test Class")) shouldBe empty
    }
  }

  Feature("Human-readable output on stdout is coloured whether or not Mill colours its own console output") {

    Scenario("The human-readable output is the same, colours included, with Mill's colour on and off") {
      val outputs = inSharedSandboxWithSuites("CalculatorSuite") { build =>
        Given("CalculatorSuite, whose one test writes dividing now to stdout and succeeds")
        When("the test command runs core.test once with Mill's --color true and once with Mill's --color false")
        Seq("true", "false").map(colour => colour -> build.runTestCommandWithMillArguments(Seq("--ticker", "true", "--color", colour))("core.test"))
      }

      outputs.foreach { case (colour, result) =>
        Then(s"under --color $colour, the human-readable output, with Mill's prefix and every ANSI escape sequence removed from each line, is the module's line, then Run 1: holding the suite and the test's line")
        withClue(result.debugString) {
          result.treeLines shouldBe calculatorSuiteOutput
        }

        And(s"under --color $colour, [core.test] and Run 1: are cyan, - adds two numbers is green and [stdout] dividing now is uncoloured")
        withClue(result.debugString) {
          result.colourMarkedTreeLines shouldBe colourMarkedCalculatorSuiteOutput
        }
      }
    }
  }

  Feature("A test sees the environment and working directory Mill's testForked gives it") {

    Scenario("With test sandboxing off, a test sees the module's forkEnv variable and the working directory testForked gives it") {
      val (result, testForked) = withSuites("WhereSuite") { build =>
        Given("the build adds the Mill plugin at that release as the entry com.edc::scalatest-mill of its build.mill header's mvnDeps, without changing the module's definition")
        And("WhereSuite, which writes FIXTURE_ENV and its working directory to stdout")
        And("the module's forkEnv sets FIXTURE_ENV to from-forkEnv, and its testSandboxWorkingDir is false")
        build.addToTestModule(
          """def forkEnv = super.forkEnv() ++ Map("FIXTURE_ENV" -> "from-forkEnv")""",
          "def testSandboxWorkingDir = false"
        )
        When("the test command runs core.test, and testForked runs it")
        (build.runTestCommand("core.test"), build.eval(Seq("core.test.testForked")))
      }
      val underRunLines = result.subtreeOf("Run 1:").drop(1).map(_.trim)

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

    Scenario("A failing test that writes to both streams reaches the console only through the human-readable output") {
      val (result, withoutPlugin) = inSharedSandboxWithSuites("QueueFlatSpec") { build =>
        Given("QueueFlatSpec, whose test be empty succeeds, and have size 0 writes to stdout and stderr, then fails")
        And("Mill's ticker is on and Mill colours its console output")
        When("the test command runs core.test, and Mill's own test task runs core.test in the same build without the plugin")
        val result = build.runTestCommandWithTickerAndColour("core.test")
        build.removePlugin()
        (result, MillConsole(build.eval(Seq("--ticker", "true", "--color", "true", "core.test"))))
      }
      val console = result.consoleLines

      Then("no line on the console holds ScalaTest's end-of-run summary or Mill's list of failed tests")
      withClue(result.debugString) {
        result.treeLines.map(_.trim) should contain("- must have size 0 *** FAILED ***")
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

    Scenario("A task named before the test command keeps its output on the console, outside the human-readable output") {
      val result = inSharedSandboxWithSuites("AddSuite") { build =>
        Given("AddSuite, and the build's task banner, which writes building banner to stdout")
        When("one mill command runs banner and then the test command, with Mill's ticker on")
        build.runTestCommandWithMillArguments(Seq("--ticker", "true", "banner", "+"))("core.test")
      }

      Then("the console holds building banner outside the human-readable output, with Mill's prefix")
      val bannerLines = result.consoleLinesOutsideHumanReadableOutput.filter(_.endsWith("building banner"))
      withClue(result.debugString) {
        bannerLines should have size 1
        TestCommandResult.millPrefix.findPrefixOf(bannerLines.head) shouldBe defined
      }

      And("the human-readable output is the module's line with AddSuite and its test under it, then the summary line")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq("[core.test]", "  AddSuite:", "    - adds")
        result.summaryLine shouldBe Some(
          "Suites: 1 of 1 completed, 0 aborted. Tests: 1 of 1 run: succeeded 1, failed 0, canceled 0, ignored 0, pending 0"
        )
      }
    }

    Scenario("After a failing test is fixed, a second test command shows the fixed run alone and succeeds") {
      val second = inSharedSandboxWithSuites("CalculatorSuite") { build =>
        Given("CalculatorSuite, whose test adds two numbers has been changed to fail, and the test command has run core.test")
        val source = build.samples / "CalculatorSuite.scala"
        val succeeding = os.read(source)
        os.write.over(source, succeeding.replace("1 + 1 == 2", "1 + 1 == 3"))
        shouldFail(build.runTestCommand("core.test"))
        When("the test is changed back to succeed, and the test command runs core.test again")
        os.write.over(source, succeeding)
        build.runTestCommand("core.test")
      }

      Then("the second human-readable output is Run 1:, holding the suite and the test's line, alone")
      withClue(second.debugString) {
        second.treeLines shouldBe calculatorSuiteOutput
      }

      And("the second test command succeeds")
      shouldSucceed(second)
    }
  }
}
