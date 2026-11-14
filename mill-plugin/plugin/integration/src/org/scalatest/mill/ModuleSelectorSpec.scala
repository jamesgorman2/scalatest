package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModuleSelectorSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with FixtureBuilds {

  Feature("The test command fails unless given one module selector that resolves to one JVM test module") {

    val selectorsThatFail = Seq(
      "two module selectors" -> Seq("core.test", "util.test"),
      "one resolving to three test modules" -> Seq("__.test"),
      "one resolving to a module that is not a test module" -> Seq("core"),
      "one resolving to a Scala.js test module" -> Seq("coreJS.test"),
      "one resolving to no module" -> Seq("nosuch.test"),
      "no module selector" -> Seq("-oH")
    )

    for ((what, arguments) <- selectorsThatFail) {
      Scenario(s"Given ${arguments.mkString(" ")}: $what") {
        withTester("module-selectors") { tester =>
          Given("JVM test modules core.test and util.test, and Scala.js test module coreJS.test, each holding AddSuite")
          When(s"the test command is given ${arguments.mkString(" ")}")
          val result = runTestCommand(tester, arguments*)

          Then("the test command fails before any test starts")
          withClue(result.debugString) {
            result.succeeded shouldBe false
            anyTestWorkerStarted(tester) shouldBe false
          }

          And("the console holds no line of human-readable output")
          result.humanReadableOutput shouldBe empty
        }
      }
    }
  }
}
