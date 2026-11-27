package org.scalatest.consolidation

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import scala.util.Success
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.time.Seconds
import org.scalatest.time.Span

final class ModuleEndedAbnormallySpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with Eventually with ConsolidatedRuns {

  private def givenAnotherModuleCompleted(): Consolidation = {
    Given("a consolidation with a run of another module that ends completed")
    startedWithOtherModuleCompleted()
  }

  Feature("Whether a module has ended abnormally") {

    Scenario("A module whose run completed has not ended abnormally, though one of its tests failed") {
      Given("a run of the module that ends completed and reports the test `adds` as failed")
      val consolidation = startedWithColour()

      When("the run is added")
      consolidation.addRunOf(runOfCalculatorSuite("""test "adds": TestStarting""", """test "adds": TestFailed"""))

      Then("the module has not ended abnormally, though the consolidation state is failed")
      consolidation.hasEndedAbnormally(module) shouldBe false
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }

    Scenario("A module whose run completed has not ended abnormally, though one of its suites aborted") {
      Given("a run of the module that ends completed and reports the suite `CalculatorSuite` as aborted")
      val consolidation = startedWithColour()

      When("the run is added")
      consolidation.addRunOf(
        TestRunPart.Events(completedRunOf(structureOf("CalculatorSuite", addsTest), """suite "CalculatorSuite": SuiteStarting""", """suite "CalculatorSuite": SuiteAborted"""))
      )

      Then("the module has not ended abnormally, though the consolidation state is failed")
      consolidation.hasEndedAbnormally(module) shouldBe false
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }

    Scenario("A failure attached to a module ends that module abnormally and no other") {
      val consolidation = givenAnotherModuleCompleted()

      When("a failure is attached to the module")
      consolidation.addModuleFailure(module, "Test selector does not match any test: samples.*")

      Then("the module has ended abnormally")
      consolidation.hasEndedAbnormally(module) shouldBe true

      And("the other module has not")
      consolidation.hasEndedAbnormally(otherModule) shouldBe false
    }

    Scenario("Output attached to a module does not end it abnormally") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("a stdout line is attached to the module")
      consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy\n"))

      Then("the module has not ended abnormally")
      consolidation.hasEndedAbnormally(module) shouldBe false
    }

    for (outcome <- Vector("aborted", "stopped")) {
      Scenario(s"A run whose runEnded event records it as $outcome ends its module abnormally before the run's event stream ends") {
        Given("a run of the module being fed")
        val consolidation = startedWithColour()
        consolidation.addRun(module) { feed =>
          feed.addAll(TestRunPart.Events("run started"))

          Then("before the run's runEnded event is fed, the module has not ended abnormally")
          consolidation.hasEndedAbnormally(module) shouldBe false

          When(s"the run's runEnded event, ended $outcome, is fed")
          feed.add(s"run ended $outcome".toRunEvent)

          Then("the module has ended abnormally")
          consolidation.hasEndedAbnormally(module) shouldBe true
        }
      }
    }

    Scenario("A run whose event stream ends without its runEnded event ends its module abnormally and no other") {
      val consolidation = givenAnotherModuleCompleted()

      When("the module is given a run whose event stream ends after `run started`")
      consolidation.addRunOf(TestRunPart.Events("run started"))

      Then("the module has ended abnormally")
      consolidation.hasEndedAbnormally(module) shouldBe true

      And("the other module has not")
      consolidation.hasEndedAbnormally(otherModule) shouldBe false
    }

    Scenario("A process of a module that ended before any run existed in it ends that module abnormally and no other") {
      val consolidation = givenAnotherModuleCompleted()

      When("a process of the module that ended before any run existed in it is added")
      consolidation.addProcessEndedBeforeAnyRun(module)

      Then("the module has ended abnormally")
      consolidation.hasEndedAbnormally(module) shouldBe true

      And("the other module has not")
      consolidation.hasEndedAbnormally(otherModule) shouldBe false
    }

    Scenario("A read waits while another thread holds the consolidation's lock, and answers after the consolidation ends") {
      Given("a failure attached to the module")
      val whileWritingJsonOutput = new AtomicReference[() => Unit](() => ())
      val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(_ => whileWritingJsonOutput.get()()))
      consolidation.addModuleFailure(module, "the module's tests did not start")
      val read = Promise[Boolean]()
      val reader = new Thread(() => { read.success(consolidation.hasEndedAbnormally(module)); () })
      whileWritingJsonOutput.set { () =>
        When("another thread reads whether the module has ended abnormally while the consolidation writes its JSON output")
        reader.start()

        Then("the read waits for the consolidation's lock, which the consolidation holds while it writes that output")
        eventually(timeout(Span(10, Seconds))) {
          reader.getState shouldBe Thread.State.BLOCKED
        }
        read.isCompleted shouldBe false
      }

      consolidation.end()
      reader.join()

      Then("once the consolidation has ended, the read answers that the module has ended abnormally")
      read.future.value shouldBe Some(Success(true))
      consolidation.hasEndedAbnormally(module) shouldBe true
    }
  }
}
