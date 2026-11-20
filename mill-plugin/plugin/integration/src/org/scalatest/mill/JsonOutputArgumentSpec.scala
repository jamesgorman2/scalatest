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

    val consoles = Seq(
      Seq("-fJ", "<report>") -> true,
      Seq("-oH", "-fJ", "<report>") -> true,
      Seq("-fJ", "<report>", "-oH") -> true,
      Seq("-o", "-fJ", "<report>") -> false
    )

    for ((arguments, humanReadable) <- consoles) {
      Scenario(s"core.test ${arguments.mkString(" ")} writes the JSON output, and -o keeps its meaning beside it") {
        Given("CalculatorSuite, whose test adds two numbers writes dividing now to stdout and succeeds")
        When(s"the test command is invoked as mill scalatest/test core.test ${arguments.mkString(" ")}")
        withJsonOutputFixture("CalculatorSuite") { build =>
          val result = build.runTestCommand(("core.test" +: arguments.map(_.replace("<report>", build.report.toString)))*)

          Then("the file at <report> holds a JSON object whose kind is root, holding the test adds two numbers, succeeded")
          val json = build.reportJson
          json.kind shouldBe NodeKind.Root
          json.testOutcome("adds two numbers") shouldBe Some("succeeded")

          if (humanReadable) {
            And("the console holds the human-readable output, whose lines include - adds two numbers")
            shouldHoldTheHumanReadableOutput(result)
          } else {
            And("the console holds no line of human-readable output, and no line holding dividing now")
            shouldHoldNoHumanReadableOutput(result)
            withClue(result.debugString) {
              result.consoleLines.filter(_.contains("dividing now")) shouldBe empty
            }
          }

          And("the test command succeeds")
          shouldSucceed(result)
        }
      }
    }

    Scenario("The module selector may follow the path, and the root still records it") {
      Given("CalculatorSuite")
      When("the test command is invoked as mill scalatest/test -fJ <report> core.test")
      withJsonOutputFixture("CalculatorSuite") { build =>
        val result = build.runTestCommand("-fJ", build.report.toString, "core.test")

        Then("the root of the JSON output records the module selector core.test, resolved to core.test")
        build.reportJson("moduleSelectors") shouldBe ujson.Arr(ujson.Obj("selector" -> "core.test", "modules" -> ujson.Arr("core.test")))

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }

    Scenario("The JSON output replaces a file from an earlier test command") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("CalculatorSuite, and the file at <report> holding the text left over")
        os.write(build.report, leftOver)
        When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
        build.runTestCommandWritingReport()

        Then("the file at <report> holds one JSON object, whose kind is root, and nothing else")
        build.reportJson.kind shouldBe NodeKind.Root
      }
    }

    Scenario("No file is at the path while the run executes") {
      withJsonOutputFixture("ProbeSuite") { build =>
        Given("ProbeSuite, whose test finds no report succeeds only where nothing exists at the path its config map gives under report")
        And("the file at <report> holds the text left over, and the module's testArgsDefault is -Dreport=<report>")
        os.write(build.report, leftOver)
        build.setModuleArguments(Seq(s"-Dreport=${build.report.toString.replace('\\', '/')}"))
        When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
        val result = build.runTestCommandWritingReport()

        Then("the JSON output holds the test finds no report, succeeded")
        withClue(result.debugString) {
          build.reportJson.testOutcome("finds no report") shouldBe Some("succeeded")
        }
      }
    }

    Scenario("The JSON output is written when a test fails, and the test command still fails") {
      Given("FailSuite, whose test subtracts fails")
      When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
      withJsonOutputFixture("FailSuite") { build =>
        val result = build.runTestCommandWritingReport()

        Then("the JSON output holds the test subtracts, failed")
        build.reportJson.testOutcome("subtracts") shouldBe Some("failed")

        And("the test command fails")
        shouldFail(result)
      }
    }
  }

  Feature("Output arguments the test command rejects") {

    val rejectedArguments = Seq(
      Seq("-fH", "<report>") -> "-fH",
      Seq("-fP", "<report>") -> "-fP",
      Seq("-fHP", "<report>") -> "-fHP",
      Seq("-fJH", "<report>") -> "-fJH",
      Seq("-fJc", "<report>") -> "-fJc",
      Seq("-f", "<report>") -> "-f",
      Seq("-fJ", "<report>", "-fJ", "<other>") -> "-fJ",
      Seq("-oJ", "-fJ", "<other>") -> "-oJ",
      Seq("-oP", "-fJ", "<other>") -> "-oP",
      Seq("-fJ") -> "-fJ"
    )

    for ((arguments, rejected) <- rejectedArguments) {
      Scenario(s"core.test ${arguments.mkString(" ")} fails the test command, naming $rejected, and leaves every file untouched") {
        withJsonOutputFixture("CalculatorSuite") { build =>
          Given("CalculatorSuite, and the file at <report> holding the text left over")
          os.write(build.report, leftOver)
          When(s"the test command is invoked as mill scalatest/test core.test ${arguments.mkString(" ")}")
          val invoked = arguments.map(_.replace("<report>", build.report.toString).replace("<other>", other(build).toString))
          val result = build.runTestCommand(("core.test" +: invoked)*)

          Then(s"the test command fails before any test starts, and its failure names $rejected")
          shouldFailBeforeAnyTestStarts(build, result)
          shouldWriteToTheConsole(result, s": $rejected")

          And("the console holds no line of human-readable output")
          shouldHoldNoHumanReadableOutput(result)

          And("the file at <report> holds the text left over, and nothing exists at <other>")
          os.read(build.report) shouldBe leftOver
          os.exists(other(build)) shouldBe false
        }
      }
    }
  }

  Feature("Deleting and writing the -fJ file") {

    val selectorsFailingAfterTheDelete = Seq(
      ("nothing.test", "no other module, so that nothing.test names no module", None),
      (
        "__.test",
        "JVM test module util.test, which tests with this fork, so that __.test names two test modules",
        Some(
          """object util extends ScalaModule {
            |  def scalaVersion = "3.3.8"
            |
            |  object test extends ScalaTests, TestModule.ScalaTest {
            |    def mvnDeps = Seq(mvn"com.edc::scalatest::3.2.20")
            |  }
            |}""".stripMargin
        )
      )
    )

    for ((selector, otherModule, module) <- selectorsFailingAfterTheDelete) {
      Scenario(s"$selector, failing before any test starts, leaves no file at the path") {
        withJsonOutputFixture("CalculatorSuite") { build =>
          Given(s"CalculatorSuite, and the build also has $otherModule")
          And("the file at <report> holds the text left over")
          module.foreach(build.appendToBuild)
          os.write(build.report, leftOver)
          When(s"the test command is invoked as mill scalatest/test $selector -fJ <report>")
          val result = build.runTestCommand(selector, "-fJ", build.report.toString)

          Then("the test command fails before any test starts")
          shouldFailBeforeAnyTestStarts(build, result)

          And("the console holds no line of human-readable output")
          shouldHoldNoHumanReadableOutput(result)

          And("nothing exists at <report>")
          os.exists(build.report) shouldBe false
        }
      }
    }

    Scenario("A folder at the path fails the test command before any test starts, naming the path, and is kept") {
      withJsonOutputFixture("CalculatorSuite") { build =>
        Given("CalculatorSuite, and a folder at <report> holding the file keep.txt")
        os.write(build.report / "keep.txt", "kept", createFolders = true)
        When("the test command is invoked as mill scalatest/test core.test -fJ <report>")
        val result = build.runTestCommandWritingReport()

        Then("the test command fails before any test starts, and its failure names <report>")
        shouldFailBeforeAnyTestStarts(build, result)
        shouldWriteToTheConsole(result, build.report.toString)

        And("the console holds no line of human-readable output")
        shouldHoldNoHumanReadableOutput(result)

        And("the folder at <report> still holds the file keep.txt")
        os.read(build.report / "keep.txt") shouldBe "kept"
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

    Scenario("A relative path with a folder in it is written below the workspace root") {
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

    Scenario("The test command creates the file's missing parent folders") {
      Given("CalculatorSuite, and nothing at <missing>")
      When("the test command is invoked as mill scalatest/test core.test -fJ <missing>/nested/report.json")
      withJsonOutputFixture("CalculatorSuite") { build =>
        val nested = build.workspace / "missing" / "nested" / "report.json"
        val result = build.runTestCommand("core.test", "-fJ", nested.toString)

        Then("the file at <missing>/nested/report.json holds a JSON object whose kind is root")
        withClue(result.debugString) {
          JsonNode.read(nested).kind shouldBe NodeKind.Root
        }

        And("the test command succeeds")
        shouldSucceed(result)
      }
    }
  }
}
