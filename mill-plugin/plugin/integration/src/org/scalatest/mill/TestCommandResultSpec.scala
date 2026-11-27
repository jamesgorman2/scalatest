package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SharedSandboxes {

  private val resultBuild: SharedSandbox =
    sharedSandbox(FixtureName.result, SandboxReset.fixtureFiles(os.sub / "core" / "test" / "src" / "samples"))(_ => ())

  private def withResultFixture[A](source: String, className: String)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.result) { build =>
      build.addSuiteFile(build.workspace / "suites" / source, s"$className.scala")
      test(build)
    }

  private def runInResultBuild(source: String, className: String): TestCommandResult =
    resultBuild.use { build =>
      build.addSuiteFile(build.workspace / "suites" / source, s"$className.scala")
      build.runTestCommand("core.test")
    }

  private def shouldFailTheModulesTests(result: TestCommandResult): Unit = {
    shouldFail(result)
    shouldWriteToTheConsole(result, "The tests of core.test failed")
  }

  Feature("A registered test for which the run sends no event, and which is neither attributed nor marked ignored, fails the test command") {

    Scenario("A test ConfigMapWrapperSuite registers only under an empty config map is shown as not run, and fails the test command") {
      val result = withResultFixture("ConfigSpec.scala", "ConfigSpec") { build =>
        Given("ConfigSpec, run through ConfigMapWrapperSuite, whose scope \"A config\" holds \"remote\" where its config map holds db, and \"local\" where it does not")
        And("the module's own ScalaTest arguments are -Ddb=on")
        build.setModuleArguments(Seq("-Ddb=on"))
        When("the test command runs core.test")
        build.runTestCommand("core.test")
      }

      Then("the human-readable output shows \"remote\", then \"local\" as not run")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq(
          "[core.test]",
          "  ConfigSpec:",
          "    A config",
          "      - remote",
          "      - local *** NOT RUN ***"
        )
      }

      And("the test command fails")
      shouldFailTheModulesTests(result)
    }
  }

  Feature("A worker that dies fails the test command, whether or not a run existed in it") {

    Scenario("A test calling System.exit(0) fails the test command") {
      Given("ExitSuite, whose test \"exits\" calls System.exit(0)")
      When("the test command runs core.test")
      val result = runInResultBuild("ExitSuite.scala", "ExitSuite")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("A worker JVM that exits before any run exists in it fails the test command") {
      val result = withResultFixture("AddSuite.scala", "AddSuite") { build =>
        Given("AddSuite, and forkArgs holding -XX:+NoSuchOption")
        build.addToTestModule("""def forkArgs = Seq("-XX:+NoSuchOption")""")
        When("the test command runs core.test")
        build.runTestCommand("core.test")
      }
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }
  }

  Feature("The test command's failure lists no test") {

    Scenario("A failed test is not named outside the human-readable output") {
      Given("StackFlatSpec, whose test \"have size 0\" fails")
      When("the test command runs core.test")
      val result = runInResultBuild("StackFlatSpec.scala", "StackFlatSpec")

      Then("the test command fails")
      shouldFailTheModulesTests(result)

      And("the human-readable output names \"have size 0\", and no other console line does")
      withClue(result.debugString) {
        result.treeLines.exists(_.contains("have size 0")) shouldBe true
        result.consoleLinesOutsideHumanReadableOutput.filter(_.contains("have size 0")) shouldBe empty
      }
    }
  }
}
