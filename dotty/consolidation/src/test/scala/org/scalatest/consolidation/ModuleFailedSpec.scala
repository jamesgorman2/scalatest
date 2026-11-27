package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should
import org.scalatest.time.Seconds
import org.scalatest.time.Span

final class ModuleFailedSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with Eventually with ConsolidatedRuns {

  private val otherModule = ModuleName("api.test")

  private val runWithSucceededTest: TestRunPart = runOfCalculatorSuite("""test "adds": TestStarting""", """test "adds": TestSucceeded""")

  Feature("Whether a module has failed") {

    Scenario("A module whose run completed has not failed, though one of its tests failed") {
      Given("a run of the module that ends completed and reports the test `adds` as failed")
      val consolidation = startedWithColour()

      When("the run is added")
      consolidation.addRunOf(runOfCalculatorSuite("""test "adds": TestStarting""", """test "adds": TestFailed"""))

      Then("the module has not failed, though the consolidation state is failed")
      consolidation.hasFailed(module) shouldBe false
      consolidation.state shouldBe ConsolidationState.OpenFailed
    }

    Scenario("A failure attached to a module fails that module and no other") {
      Given("a consolidation with a run of another module that ends completed")
      val consolidation = startedWithColour()
      consolidation.addRun(otherModule)(_.addAll(runWithSucceededTest))

      When("a failure is attached to the module")
      consolidation.addModuleFailure(module, "Test selector does not match any test: samples.*")

      Then("the module has failed")
      consolidation.hasFailed(module) shouldBe true

      And("the other module has not")
      consolidation.hasFailed(otherModule) shouldBe false
    }

    Scenario("Output attached to a module does not fail it") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("a stdout line is attached to the module")
      consolidation.addModuleOutput(module, CapturedLine.Stdout("loading samples.Noisy\n"))

      Then("the module has not failed")
      consolidation.hasFailed(module) shouldBe false
    }

    for (outcome <- Vector("aborted", "stopped")) {
      Scenario(s"A run whose runEnded event records it as $outcome fails its module before the run's event stream ends") {
        Given("a run of the module being fed")
        val consolidation = startedWithColour()
        consolidation.addRun(module) { feed =>
          feed.addAll(TestRunPart.Events("run started"))

          Then("before the run's runEnded event is fed, the module has not failed")
          consolidation.hasFailed(module) shouldBe false

          When(s"the run's runEnded event, ended $outcome, is fed")
          feed.add(s"run ended $outcome".toRunEvent)

          Then("the module has failed")
          consolidation.hasFailed(module) shouldBe true
        }
      }
    }

    Scenario("A run whose event stream ends without its runEnded event fails its module and no other") {
      Given("a consolidation with a run of another module that ends completed")
      val consolidation = startedWithColour()
      consolidation.addRun(otherModule)(_.addAll(runWithSucceededTest))

      When("the module is given a run whose event stream ends after `run started`")
      consolidation.addRunOf(TestRunPart.Events("run started"))

      Then("the module has failed")
      consolidation.hasFailed(module) shouldBe true

      And("the other module has not")
      consolidation.hasFailed(otherModule) shouldBe false
    }

    Scenario("A process of a module that ended before any run existed in it fails that module and no other") {
      Given("a consolidation with a run of another module that ends completed")
      val consolidation = startedWithColour()
      consolidation.addRun(otherModule)(_.addAll(runWithSucceededTest))

      When("a process of the module that ended before any run existed in it is added")
      consolidation.addProcessEndedBeforeAnyRun(module)

      Then("the module has failed")
      consolidation.hasFailed(module) shouldBe true

      And("the other module has not")
      consolidation.hasFailed(otherModule) shouldBe false
    }

    Scenario("A read waits while another thread holds the consolidation's lock, and answers after the consolidation ends") {
      Given("a failure attached to the module")
      var whileWritingJsonOutput: () => Unit = () => ()
      val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(_ => whileWritingJsonOutput()))
      consolidation.addModuleFailure(module, "the module's tests did not start")
      var read = Option.empty[Boolean]
      val reader = new Thread(() => read = Some(consolidation.hasFailed(module)))
      whileWritingJsonOutput = () => {
        When("another thread reads whether the module has failed while the consolidation writes its JSON output")
        reader.start()

        Then("the read waits for the consolidation's lock, which the consolidation holds while it writes that output")
        eventually(timeout(Span(10, Seconds))) {
          reader.getState shouldBe Thread.State.BLOCKED
        }
        read shouldBe None
      }

      consolidation.end()
      reader.join()

      Then("once the consolidation has ended, the read answers that the module has failed")
      read shouldBe Some(true)
      consolidation.hasFailed(module) shouldBe true
    }
  }
}
