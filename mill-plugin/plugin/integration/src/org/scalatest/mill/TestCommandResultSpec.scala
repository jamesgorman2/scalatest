package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {
  /** `source` is a file in the fixture's `suites` folder, and `className` the name of the class it defines. */
  /** The suite is a source file in the fixture's `suites` folder, and the name of the class it defines. */
  private def runResultFixture(source: String, className: String)(prepare: FixtureBuild => Unit = _ => ()): TestCommandResult =
    withFixtureBuild(FixtureName.result) { build =>
      build.addSuiteFile(build.workspace / "suites" / source, s"$className.scala")
      prepare(build)
      build.runTestCommand("core.test")
    }

  private def resultSuite(source: String): TestCommandResult = runResultFixture(source, "ResultSuite")()

  private def shouldFailTheModulesTests(result: TestCommandResult): Unit = {
    shouldFail(result)
    shouldWriteToTheConsole(result, "The tests of core.test failed")
  }

  Feature("A completed run fails the test command only where it reports a failure") {

    Scenario("A test succeeds") {
      Given("ResultSuite, mixing in BeforeAndAfterAll, whose test \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultSucceeds.scala")
      Then("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("Tests are ignored and pending, and another succeeds") {
      Given("ResultSuite, whose test \"ignored\" is ignored, \"pending\" is pending, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultIgnoredAndPending.scala")
      Then("the test command succeeds")
      shouldSucceed(result)
    }

    Scenario("A test is cancelled, and another succeeds") {
      Given("ResultSuite, whose test \"cancelled\" is cancelled, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultCancelled.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("A test fails, and another succeeds") {
      Given("ResultSuite, whose test \"fails\" fails, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultFails.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("beforeAll throws, so the suite aborts") {
      Given("ResultSuite, whose beforeAll throws, and whose test \"succeeds\" would succeed")
      When("the test command runs core.test")
      val result = resultSuite("ResultBeforeAllThrows.scala")
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }
  }

  Feature("A registered test for which the run sends no event, and which is neither attributed nor marked ignored, fails the test command") {

    Scenario("A suite skips one of its registered tests") {
      Given("SkipSuite, whose test \"runs\" succeeds, and whose runTest returns SucceededStatus for \"skipped\" without running it")
      When("the test command runs core.test")
      val result = runResultFixture("SkipSuite.scala", "SkipSuite")()

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

    Scenario("A suite run through ConfigMapWrapperSuite registers a test with an empty config map that it does not register with the run's config map") {
      Given("ConfigSpec, run through ConfigMapWrapperSuite, whose scope \"A config\" holds \"remote\" where its config map holds db, and \"local\" where it does not")
      And("the module's own ScalaTest arguments are -Ddb=on")
      When("the test command runs core.test")
      val result = runResultFixture("ConfigSpec.scala", "ConfigSpec")(_.setModuleArguments(Seq("-Ddb=on")))

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

    Scenario("A test calls System.exit(0)") {
      Given("ExitSuite, whose test \"exits\" calls System.exit(0)")
      When("the test command runs core.test")
      val result = runResultFixture("ExitSuite.scala", "ExitSuite")()
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }

    Scenario("The worker's JVM exits before any run exists in it") {
      Given("AddSuite, and forkArgs holding -XX:+NoSuchOption")
      When("the test command runs core.test")
      val result = runResultFixture("AddSuite.scala", "AddSuite")(_.addToTestModule("""def forkArgs = Seq("-XX:+NoSuchOption")"""))
      Then("the test command fails")
      shouldFailTheModulesTests(result)
    }
  }

  Feature("The test command's failure lists no test") {

    Scenario("A failed test is not named outside the human-readable output") {
      Given("StackFlatSpec, whose test \"have size 0\" fails")
      When("the test command runs core.test")
      val result = runResultFixture("StackFlatSpec.scala", "StackFlatSpec")()

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
      Scenario(s"The module tests with $framework") {
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
