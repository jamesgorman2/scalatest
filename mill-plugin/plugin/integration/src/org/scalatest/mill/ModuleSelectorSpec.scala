package org.scalatest.mill

import org.scalatest.GivenWhenThen
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModuleSelectorSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with SharedSandboxes {

  private val sharedModuleSelectorsBuild: SharedSandbox = sharedSandbox(FixtureName.moduleSelectors, SandboxReset.noModuleFolders)(_ => ())

  Feature("The test command fails before any test starts unless given module selectors that each resolve to JVM test modules") {

    val selectorsThatFail = Seq(
      (
        "one resolving to a module that is not a test module",
        Seq("core"),
        "Module selector core resolves to no test module"
      ),
      (
        "one resolving to no module",
        Seq("nosuch.test"),
        "Module selector nosuch.test resolves to no module: "
      )
    )

    for ((what, arguments, rejection) <- selectorsThatFail) {
      Scenario(s"The test command given ${arguments.mkString(" ")}, $what, fails before any test starts") {
        sharedModuleSelectorsBuild.use { build =>
          Given("the JVM module core, which is not a test module, and its JVM test module core.test, holding AddSuite")
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
  }
}
