package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class JsonOutputArgumentSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with JsonOutputFixture {

  private val leftOver: String = "left over"

  private def other(build: FixtureBuild): os.Path = build.workspace / "other.json"

  private def shouldHoldTheHumanReadableOutput(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.treeLines.map(_.trim) should contain("- adds two numbers")
    }

  private def shouldHoldNoHumanReadableOutput(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.treeLines shouldBe empty
      result.summaryLine shouldBe empty
    }

  Feature("The -fJ argument") {

    Scenario("core.test -o -fJ <report> writes the JSON output, and -o keeps its meaning beside it") {
      Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout and succeeds")
      When("the test command is invoked as mill scalatest/test core.test -o -fJ <report>")
      withJsonOutputFixture("CalculatorSuite") { build =>
        val result = build.runTestCommand("core.test", "-o", "-fJ", build.jsonOutputFile.toString)

        Then("the file at <report> holds a JSON object whose kind is root, holding the test adds two numbers, succeeded")
        val json = build.jsonOutput
        json.kind shouldBe NodeKind.Root
        json.testOutcome("adds two numbers") shouldBe Some("succeeded")

        And("the console holds no line of human-readable output, and no line holding dividing now")
        shouldHoldNoHumanReadableOutput(result)
        withClue(result.debugString) {
          result.consoleLines.filter(_.contains("dividing now")) shouldBe empty
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("No file is at the path while the run executes") {
      withJsonOutputFixture("ProbeSuite") { build =>
        Given("ProbeSuite, whose test finds no report succeeds only where nothing exists at the path its config map gives under report")
        And("the file at <report> holds the text left over, and the module's testArgsDefault is -Dreport=<report>")
        os.write(build.jsonOutputFile, leftOver)
        build.setModuleArguments(Seq(s"-Dreport=${build.jsonOutputFile.toString.replace('\\', '/')}"))
        When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
        val result = build.runTestCommandWritingJsonOutput()

        Then("the JSON output holds the test finds no report, succeeded")
        withClue(result.debugString) {
          build.jsonOutput.testOutcome("finds no report") shouldBe Some("succeeded")
        }
      }
    }

  }

  Feature("Output arguments the test command rejects") {

    Scenario("core.test -fJ <report> -fJ <other> fails the test command, naming -fJ, and leaves every file untouched") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("CalculatorSuite, and the file at <report> holding the text left over")
        os.write(build.jsonOutputFile, leftOver)
        When("the test command is invoked as mill scalatest/test core.test -fJ <report> -fJ <other>")
        val result = build.runTestCommand("core.test", "-fJ", build.jsonOutputFile.toString, "-fJ", other(build).toString)

        Then("the test command fails before any test starts, and its failure names -fJ")
        shouldFailBeforeAnyTestStarts(build, result)
        shouldWriteToTheConsole(result, ": -fJ")

        And("the console holds no line of human-readable output")
        shouldHoldNoHumanReadableOutput(result)

        And("the file at <report> holds the text left over, and nothing exists at <other>")
        os.read(build.jsonOutputFile) shouldBe leftOver
        os.exists(other(build)) shouldBe false
      }
    }
  }

  Feature("Deleting and writing the -fJ file") {

    Scenario("A folder at the path fails the test command before any test starts, naming the path, and is kept") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("CalculatorSuite, and a folder at <report> holding the file keep.txt")
        os.write(build.jsonOutputFile / "keep.txt", "kept", createFolders = true)
        When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
        val result = build.runTestCommandWritingJsonOutput()

        Then("the test command fails before any test starts, and its failure names <report>")
        shouldFailBeforeAnyTestStarts(build, result)
        shouldWriteToTheConsole(result, build.jsonOutputFile.toString)

        And("the console holds no line of human-readable output")
        shouldHoldNoHumanReadableOutput(result)

        And("the folder at <report> still holds the file keep.txt")
        os.read(build.jsonOutputFile / "keep.txt") shouldBe "kept"
      }
    }

    Scenario("A path inside a regular file fails the test command after the runs end, naming the path, and the human-readable output is still written") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("CalculatorSuite, and a regular file at <blocker>")
        val blocker = build.workspace / "blocker"
        os.write(blocker, "a file")
        When("the test command is invoked as mill scalatest/test core.test -fJ <blocker>/report.json")
        val result = build.runTestCommand("core.test", "-fJ", s"$blocker/report.json")

        Then("the console holds the human-readable output, whose lines include - adds two numbers")
        shouldHoldTheHumanReadableOutput(result)

        And("the test command fails, and its failure names <blocker>/report.json")
        shouldFail(result)
        shouldWriteToTheConsole(result, (blocker / "report.json").toString)
      }
    }

    Scenario("The JSON output at a relative path with a folder in it is written below the workspace root") {
      Given("CalculatorSuite")
      When("the test command is invoked as mill scalatest/test core.test -fJ reports/report.json")
      withJsonOutputFixture("CalculatorSuite") { build =>
        val result = build.runTestCommand("core.test", "-fJ", "reports/report.json")

        Then("the file reports/report.json below the workspace root holds a JSON object whose kind is root")
        withClue(result.debugString) {
          os.exists(build.workspace / "reports" / "report.json") shouldBe true
          JsonNode.read(build.workspace / "reports" / "report.json").kind shouldBe NodeKind.Root
        }

        And("no other file named report.json exists below the workspace root")
        os.walk(build.workspace).filter(_.last == "report.json") shouldBe Seq(build.workspace / "reports" / "report.json")

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }
}
