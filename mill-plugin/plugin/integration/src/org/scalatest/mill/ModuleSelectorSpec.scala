package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModuleSelectorSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  Feature("The test command fails unless given one module selector that resolves to one JVM test module") {

    val selectorsThatFail = Seq(
      (
        "two module selectors",
        Seq("core.test", "util.test"),
        "The test command takes one module selector, but was given: core.test util.test"
      ),
      (
        "one resolving to three test modules",
        Seq("__.test"),
        "Module selector __.test must resolve to one JVM test module, but resolved to: "
      ),
      (
        "one resolving to a module that is not a test module",
        Seq("core"),
        "Module selector core must resolve to one JVM test module, but resolved to: core"
      ),
      (
        "one resolving to a Scala.js test module",
        Seq("coreJS.test"),
        "Module selector coreJS.test must resolve to one JVM test module, but resolved to: coreJS.test"
      ),
      (
        "one resolving to no module",
        Seq("nosuch.test"),
        "Module selector nosuch.test resolves to no module: "
      ),
      (
        "no module selector",
        Seq("-oH"),
        "The test command takes one module selector, but was given none"
      )
    )

    for ((what, arguments, rejection) <- selectorsThatFail) {
      Scenario(s"Given ${arguments.mkString(" ")}: $what") {
        withFixtureBuild(FixtureName.moduleSelectors) { build =>
          Given("JVM test modules core.test and util.test, and Scala.js test module coreJS.test, each holding AddSuite")
          When(s"the test command is given ${arguments.mkString(" ")}")
          val result = build.runTestCommand(arguments*)

          Then("the test command fails before any test starts")
          shouldFailBeforeAnyTestStarts(build, result)

          And(s"the console holds the test command's rejection: $rejection")
          shouldWriteToTheConsole(result, rejection)

          And("the console holds no line of human-readable output")
          result.treeLines shouldBe empty
        }
      }
    }

    Scenario("Given core.test: one resolving to one JVM test module") {
      withFixtureBuild(FixtureName.moduleSelectors) { build =>
        Given("JVM test modules core.test and util.test, and Scala.js test module coreJS.test, each holding AddSuite")
        When("the test command is given core.test")
        val result = build.runTestCommand("core.test")

        Then("the test command succeeds, having started a test worker")
        withClue(result.debugString) {
          result.succeeded shouldBe true
          build.anyTestWorkerStarted shouldBe true
        }

        And("the human-readable output is core.test's label, with AddSuite under it")
        withClue(result.debugString) {
          result.treeLines.headOption shouldBe Some("[core.test]")
          result.treeLines.map(_.trim) should contain("AddSuite:")
        }
      }
    }
  }
}
