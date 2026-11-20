package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  private def withResultFixture[A](source: String, className: String)(test: FixtureBuild => A): A =
    withFixtureBuild(FixtureName.result) { build =>
      build.addSuiteFile(build.workspace / "suites" / source, s"$className.scala")
      test(build)
    }

  private def runResultFixture(source: String, className: String): TestCommandResult =
    withResultFixture(source, className)(_.runTestCommand("core.test"))

  private def resultSuite(source: String): TestCommandResult = runResultFixture(source, "ResultSuite")

  private def shouldFailTheModulesTests(result: TestCommandResult): Unit = {
    shouldFail(result)
    shouldWriteToTheConsole(result, "The tests of core.test failed")
  }

  Feature("A completed run fails the test command only where it reports a failure") {

    Scenario("A succeeding test succeeds the test command") {
      Given("ResultSuite, mixing in BeforeAndAfterAll, whose test \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultSucceeds.scala")
      Then("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("Ignored and pending tests beside a succeeding one succeed the test command") {
      Given("ResultSuite, whose test \"ignored\" is ignored, \"pending\" is pending, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultIgnoredAndPending.scala")
      Then("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("A cancelled test fails the test command") {
      Given("ResultSuite, whose test \"cancelled\" is cancelled, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultCancelled.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("A failing test fails the test command") {
      Given("ResultSuite, whose test \"fails\" fails, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultFails.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("A suite aborted by its beforeAll fails the test command") {
      Given("ResultSuite, whose beforeAll throws, and whose test \"succeeds\" would succeed")
      When("the test command runs core.test")
      val result = resultSuite("ResultBeforeAllThrows.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }
  }

  Feature("A registered test for which the run sends no event, and which is neither attributed nor marked ignored, fails the test command") {

    Scenario("A registered test its suite skips is shown as not run, and fails the test command") {
      Given("SkipSuite, whose test \"runs\" succeeds, and whose runTest returns SucceededStatus for \"skipped\" without running it")
      When("the test command runs core.test")
      val result = runResultFixture("SkipSuite.scala", "SkipSuite")

      Then("the human-readable output shows \"skipped\" as not run")
      withClue(result.debugString) {
        result.treeLines shouldBe Seq(
          "[core.test]",
          "  SkipSuite:",
          "    - runs",
          "    - skipped *** NOT RUN ***"
        )
      }

      And("the test command fails")
      shouldFailTheModulesTests(result)
    }

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
      val result = runResultFixture("ExitSuite.scala", "ExitSuite")
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
      val result = runResultFixture("StackFlatSpec.scala", "StackFlatSpec")

      Then("the test command fails")
      shouldFailTheModulesTests(result)

      And("the human-readable output names \"have size 0\", and no other console line does")
      withClue(result.debugString) {
        result.treeLines.exists(_.contains("have size 0")) shouldBe true
        result.consoleLinesOutsideHumanReadableOutput.filter(_.contains("have size 0")) shouldBe empty
      }
    }
  }

  Feature("A module that does not test with this fork fails the test command") {

    Seq(
      "MUnit" -> FixtureName.munit,
      "uTest" -> FixtureName.utest,
      "org.scalatest:scalatest 3.2.20" -> FixtureName.upstreamScalaTest
    ).foreach { case (framework, fixture) =>
      Scenario(s"A module testing with $framework fails the test command") {
        Given(s"core.test tests with $framework, and its one suite, FrameworkSuite, has one test, \"runs\", which succeeds")
        When("the test command runs core.test")
        val result = withFixtureBuild(fixture) { build =>
          build.runTestCommand("core.test")
        }
        Then("the test command fails")
        shouldFailTheModulesTests(result)
      }
    }
  }
}
