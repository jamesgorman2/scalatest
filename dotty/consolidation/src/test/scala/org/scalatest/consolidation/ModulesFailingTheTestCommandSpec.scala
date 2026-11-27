package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModulesFailingTheTestCommandSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with ConsolidatedRuns {

  private def givenAnotherModuleCompleted(): Consolidation = {
    Given("a consolidation with a run of another module that ends completed and whose test succeeds")
    startedWithOtherModuleCompleted()
  }

  private def shouldBeFailedByTheModuleAlone(consolidation: Consolidation): Unit = {
    Then("the module, and no other, fails the test command")
    consolidation.modulesFailingTheTestCommand shouldBe Vector(module)
    And("the consolidation state is failed")
    consolidation.state shouldBe ConsolidationState.OpenFailed
  }

  Feature("The modules that fail the test command") {

    Scenario("A module whose run completed and whose test succeeded does not fail the test command") {
      val consolidation = givenAnotherModuleCompleted()

      When("the module is given a run that ends completed and whose test succeeds")
      consolidation.addRunOf(runWithSucceededTest)

      Then("no module fails the test command")
      consolidation.modulesFailingTheTestCommand shouldBe empty
    }

    for (testEnding <- Vector("TestFailed", "TestCanceled")) {
      Scenario(s"A run that completed and reports $testEnding makes its module fail the test command") {
        val consolidation = givenAnotherModuleCompleted()

        When(s"the module is given a run that ends completed and reports the test `adds` with $testEnding")
        consolidation.addRunOf(runOfCalculatorSuite("""test "adds": TestStarting""", s"""test "adds": $testEnding"""))

        shouldBeFailedByTheModuleAlone(consolidation)
      }
    }

    Scenario("A suite that aborts inside a completed run makes its module fail the test command") {
      val consolidation = givenAnotherModuleCompleted()

      When("the module is given a run that ends completed and whose suite aborts")
      consolidation.addRunOf(
        TestRunPart.Events(
          """
          run started
          structure
            suite "CalculatorSuite", class "samples.CalculatorSuite"
          suite "CalculatorSuite": SuiteStarting
          suite "CalculatorSuite": SuiteAborted
          run ended completed
          """
        )
      )

      shouldBeFailedByTheModuleAlone(consolidation)
    }

    Scenario("A test not run inside a completed run makes its module fail the test command once the run's event stream ends") {
      val consolidation = givenAnotherModuleCompleted()

      When("the module is given a run that ends completed and holds the test `adds`, which sends no event")
      consolidation.addRunOf(runOfCalculatorSuiteHolding(addsTest))

      shouldBeFailedByTheModuleAlone(consolidation)
    }

    for (outcome <- Vector("aborted", "stopped")) {
      Scenario(s"A run whose runEnded event records it as $outcome makes its module fail the test command") {
        val consolidation = givenAnotherModuleCompleted()

        When(s"the module is given a run whose runEnded event records it as $outcome")
        consolidation.addRunOf(TestRunPart.Events(s"run started\nrun ended $outcome"))

        shouldBeFailedByTheModuleAlone(consolidation)
      }
    }

    Scenario("A run whose event stream ends without its runEnded event makes its module fail the test command") {
      val consolidation = givenAnotherModuleCompleted()

      When("the module is given a run whose event stream ends after `run started`")
      consolidation.addRunOf(TestRunPart.Events("run started"))

      shouldBeFailedByTheModuleAlone(consolidation)
    }

    Scenario("A process of a module that ended before any run existed in it makes that module fail the test command") {
      val consolidation = givenAnotherModuleCompleted()

      When("a process of the module that ended before any run existed in it is added")
      consolidation.addProcessEndedBeforeAnyRun(module)

      shouldBeFailedByTheModuleAlone(consolidation)
    }

    Scenario("A failure attached to a module makes that module fail the test command") {
      val consolidation = givenAnotherModuleCompleted()

      When("a failure is attached to the module")
      consolidation.addModuleFailure(module, "the module's tests did not start")

      shouldBeFailedByTheModuleAlone(consolidation)
    }

    Scenario("Output attached to a module does not fail the test command") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("a stdout line is attached to the module")
      consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy\n"))

      Then("no module fails the test command")
      consolidation.modulesFailingTheTestCommand shouldBe empty
    }

    Scenario("A failure attached to the root fails the test command without any module failing it") {
      val consolidation = givenAnotherModuleCompleted()

      When("a failure is attached to the root")
      consolidation.addRootFailure("discovery failed")

      Then("no module fails the test command, though the consolidation state is failed")
      consolidation.modulesFailingTheTestCommand shouldBe empty
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }

    Scenario("The modules are ordered by name, by character code, and are still returned after the consolidation ends") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("failures are attached to b.test, B.test and a.test, in that order, and the consolidation ends")
      Vector("b.test", "B.test", "a.test").foreach(name => consolidation.addModuleFailure(ModuleName(name), "the module's tests did not start"))
      consolidation.end()

      Then("the modules that fail the test command are B.test, a.test and b.test, in that order")
      consolidation.modulesFailingTheTestCommand shouldBe Vector(ModuleName("B.test"), ModuleName("a.test"), ModuleName("b.test"))
    }
  }
}
