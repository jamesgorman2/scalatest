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

final class ModuleTestResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with Eventually with ConsolidatedRuns with JsonOutputs {

  private val calculatorSuiteStructure = structureOf("CalculatorSuite", addsTest)

  private def runPartOf(events: String*): TestRunPart = TestRunPart.Events(events.mkString("\n"))

  private val runWithNoSuite: TestRunPart = TestRunPart.Events(completedRunOf())

  Feature("Whether a module has a test result") {

    Scenario("A module that has no run has no test result, though another module has one") {
      Given("a consolidation whose only run belongs to another module and reports a test as succeeded")
      val consolidation = startedWithOtherModuleCompleted()

      Then("the other module has a test result")
      consolidation.hasTestResult(otherModule) shouldBe true

      And("the module, which has no run, has none")
      consolidation.hasTestResult(module) shouldBe false
    }

    for (
      (outcome, testEvents) <- Vector(
        "succeeded" -> Vector("TestStarting", "TestSucceeded"),
        "failed" -> Vector("TestStarting", "TestFailed"),
        "ignored" -> Vector("TestIgnored"),
        "pending" -> Vector("TestStarting", "TestPending"),
        "canceled" -> Vector("TestStarting", "TestCanceled")
      )
    ) {
      Scenario(s"A run that reports a test as $outcome gives its module a test result") {
        Given(s"a run of the module whose event stream reports the test `adds` as $outcome")
        val consolidation = startedWithColour()

        When("the run is added")
        consolidation.addRunOf(runOfCalculatorSuite(testEvents.map(event => s"""test "adds": $event""")*))

        Then("the module has a test result")
        consolidation.hasTestResult(module) shouldBe true
      }
    }

    Scenario("A run that reports a suite as aborted gives its module a test result") {
      Given("a run of the module whose event stream reports the suite `CalculatorSuite` as aborted before its tests run")
      val consolidation = startedWithColour()

      When("the run is added")
      consolidation.addRunOf(
        TestRunPart.Events(
          completedRunOf(calculatorSuiteStructure, """suite "CalculatorSuite": SuiteStarting""", """suite "CalculatorSuite": SuiteAborted""")
        )
      )

      Then("the module has a test result")
      consolidation.hasTestResult(module) shouldBe true
    }

    Scenario("Runs whose event streams report no test's outcome and no suite as aborted give their module no test result") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("the module is given a run that reports no suite, and a run whose suite completes with only a scope in it")
      consolidation.addRunOf(runWithNoSuite)
      consolidation.addRunOf(
        runOfCalculatorSuiteHolding(
          """scope "A calculator", label "A calculator"""",
          """scope "A calculator": ScopeOpened""",
          """scope "A calculator": ScopeClosed"""
        )
      )

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false
    }

    for (
      (order, runs) <- Vector(
        "before" -> Vector(runWithNoSuite, runWithSucceededTest),
        "after" -> Vector(runWithSucceededTest, runWithNoSuite)
      )
    ) {
      Scenario(s"A module has a test result when a run that reports no suite is added $order a run that reports a test as succeeded") {
        Given("a consolidation")
        val consolidation = startedWithColour()

        When(s"the module is given a run that reports no suite $order a run that reports the test `adds` as succeeded")
        runs.foreach(run => consolidation.addRunOf(run))

        Then("the module has a test result")
        consolidation.hasTestResult(module) shouldBe true
      }
    }

    Scenario("A suite that the consolidation reports as not run gives its module no test result") {
      Given("a run of the module whose structure holds the suite `CalculatorSuite` but reports no event for it")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      consolidation.addRunOf(TestRunPart.Events(completedRunOf(calculatorSuiteStructure)))

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false

      And("once the consolidation ends, the JSON output shows the suite as aborted with reason not run")
      consolidation.end()
      val suite = json.node(NodeKind.Suite, "CalculatorSuite")
      (suite("outcome"), suite("reason")) shouldBe ((text("aborted"), text("notRun")))
    }

    Scenario("A test that its suite's structure marks ignored, with no event reporting it as ignored, gives its module no test result") {
      Given("a run of the module whose suite `CalculatorSuite` starts and completes, and whose structure marks the test `adds` ignored")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      consolidation.addRunOf(runOfCalculatorSuiteHolding(s"$addsTest, ignored"))

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false

      And("once the consolidation ends, the JSON output shows the test as ignored")
      consolidation.end()
      json.node(NodeKind.Test, "adds")("outcome") shouldBe text("ignored")
    }

    Scenario("A run that aborts while a test is running gives its module no test result") {
      Given("a run of the module that aborts while the test `adds` is running")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      consolidation.addRunOf(
        runPartOf(
          "run started",
          calculatorSuiteStructure,
          """suite "CalculatorSuite": SuiteStarting""",
          """test "adds": TestStarting""",
          "run: RunAborted",
          "run ended aborted"
        )
      )

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false

      And("once the consolidation ends, the JSON output shows the suite and the test as aborted")
      consolidation.end()
      Vector(json.node(NodeKind.Suite, "CalculatorSuite"), json.node(NodeKind.Test, "adds")).map(_("outcome")) shouldBe Vector(text("aborted"), text("aborted"))
    }

    Scenario("A module has a test result as soon as its run reports a test's outcome, before the run's event stream ends") {
      Given("a run of the module being fed, which has started the test `adds`")
      val untilTestStarts = runPartOf("run started", calculatorSuiteStructure, """suite "CalculatorSuite": SuiteStarting""", """test "adds": TestStarting""")
      val fromTestOutcome = runPartOf("""test "adds": TestSucceeded""", """suite "CalculatorSuite": SuiteCompleted""", "run ended completed")
      val events = runEventsOf(untilTestStarts, fromTestOutcome)
      val testOutcomeIndex = runEventsOf(untilTestStarts).size
      val consolidation = startedWithColour()
      consolidation.addRun(module) { feed =>
        events.take(testOutcomeIndex).foreach(event => feed.add(event))

        Then("before the test's outcome is fed, the module has no test result")
        consolidation.hasTestResult(module) shouldBe false

        When("the run reports the test as succeeded")
        feed.add(events(testOutcomeIndex))

        Then("the module has a test result before the run's event stream ends")
        consolidation.hasTestResult(module) shouldBe true
        events.drop(testOutcomeIndex + 1).foreach(event => feed.add(event))
      }
    }

    Scenario("A read waits while another thread holds the consolidation's lock, and answers after the consolidation ends") {
      Given("a run of the module that reports a test as succeeded")
      val whileWritingJsonOutput = new AtomicReference[() => Unit](() => ())
      val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(_ => whileWritingJsonOutput.get()()))
      consolidation.addRunOf(runWithSucceededTest)
      val read = Promise[Boolean]()
      val reader = new Thread(() => { read.success(consolidation.hasTestResult(module)); () })
      whileWritingJsonOutput.set { () =>
        When("another thread reads whether the module has a test result while the consolidation writes its JSON output")
        reader.start()

        Then("the read waits for the consolidation's lock, which the consolidation holds while it writes that output")
        eventually(timeout(Span(10, Seconds))) {
          reader.getState shouldBe Thread.State.BLOCKED
        }
        read.isCompleted shouldBe false
      }

      consolidation.end()
      reader.join()

      Then("once the consolidation has ended, the read answers that the module has a test result")
      read.future.value shouldBe Some(Success(true))
      consolidation.hasTestResult(module) shouldBe true
    }
  }
}
