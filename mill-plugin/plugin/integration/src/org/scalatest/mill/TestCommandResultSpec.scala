package org.scalatest.mill

import mill.testkit.IntegrationTester
import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class TestCommandResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  /**
   * Runs the test command on `core.test` of the `result` fixture, holding the given suites from the fixture's `suites`
   * folder. Each source file is copied as `<its class>.scala`.
   */
  private def runResultFixture(suites: (String, String)*)(prepare: IntegrationTester => Unit = _ => ()): TestCommandResult =
    withTester("result") { tester =>
      val samples = tester.workspacePath / "core" / "test" / "src" / "samples"
      os.makeDir.all(samples)
      suites.foreach { case (source, className) =>
        os.copy(tester.workspacePath / "suites" / source, samples / s"$className.scala")
      }
      prepare(tester)
      runTestCommand(tester, "core.test")
    }

  private def resultSuite(source: String): TestCommandResult = runResultFixture(source -> "ResultSuite")()

  private def shouldSucceed(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe true
    }

  private def shouldFail(result: TestCommandResult): Unit =
    withClue(result.debugString) {
      result.succeeded shouldBe false
      (result.eval.out + result.eval.err) should include("The tests of core.test failed")
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
      shouldFail(result)
    }

    Scenario("A test fails, and another succeeds") {
      Given("ResultSuite, whose test \"fails\" fails, and \"succeeds\" succeeds")
      When("the test command runs core.test")
      val result = resultSuite("ResultFails.scala")
      Then("the test command fails")
      shouldFail(result)
    }

    Scenario("beforeAll throws, so the suite aborts") {
      Given("ResultSuite, whose beforeAll throws, and whose test \"succeeds\" would succeed")
      When("the test command runs core.test")
      val result = resultSuite("ResultBeforeAllThrows.scala")
      Then("the test command fails")
      shouldFail(result)
    }
  }

  Feature("A worker that dies fails the test command, whether or not a run existed in it") {

    Scenario("A test calls System.exit(0)") {
      Given("ExitSuite, whose test \"exits\" calls System.exit(0)")
      When("the test command runs core.test")
      val result = runResultFixture("ExitSuite.scala" -> "ExitSuite")()
      Then("the test command fails")
      shouldFail(result)
    }

    Scenario("The worker's JVM exits before any run exists in it") {
      Given("AddSuite, and forkArgs holding -XX:+NoSuchOption")
      When("the test command runs core.test")
      val result = runResultFixture("AddSuite.scala" -> "AddSuite")(addForkArgument(_, "-XX:+NoSuchOption"))
      Then("the test command fails")
      shouldFail(result)
    }
  }

  Feature("The test command's failure lists no test") {

    Scenario("A failed test is not named outside the human-readable output") {
      Given("StackFlatSpec, whose test \"have size 0\" fails")
      When("the test command runs core.test")
      val result = runResultFixture("StackFlatSpec.scala" -> "StackFlatSpec")()

      Then("the test command fails")
      shouldFail(result)

      And("the human-readable output names \"have size 0\", and no other console line does")
      withClue(result.debugString) {
        result.humanReadableOutput.exists(_.contains("have size 0")) shouldBe true
        result.consoleLinesOutsideHumanReadableOutput.filter(_.contains("have size 0")) shouldBe empty
      }
    }
  }

  Feature("A module that does not test with this fork fails the test command") {

    Seq(
      "MUnit" -> "munit",
      "uTest" -> "utest",
      "org.scalatest:scalatest 3.2.20" -> "upstream-scalatest"
    ).foreach { case (framework, fixture) =>
      Scenario(s"The module tests with $framework") {
        Given(s"core.test tests with $framework, and its one suite, FrameworkSuite, has one test, \"runs\", which succeeds")
        When("the test command runs core.test")
        val result = withTester(fixture) { tester =>
          runTestCommand(tester, "core.test")
        }
        Then("the test command fails")
        shouldFail(result)
      }
    }
  }

  private def addForkArgument(tester: IntegrationTester, argument: String): Unit = {
    val buildFile = tester.workspacePath / "build.mill"
    val testModule = "object test extends ScalaTests, TestModule.ScalaTest {"
    val build = os.read(buildFile)
    require(build.contains(testModule), s"$buildFile declares no $testModule")
    os.write.over(buildFile, build.replace(testModule, s"""$testModule\n    def forkArgs = Seq("$argument")"""))
  }
}
