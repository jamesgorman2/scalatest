package org.scalatest.consolidation

import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.events.TestRunBuilder
import org.scalatest.events.TestRunPart
import org.scalatest.featurespec.AnyFeatureSpec
import org.scalatest.matchers.should

final class ModuleTestResultSpec extends AnyFeatureSpec with GivenWhenThen with should.Matchers with Eventually with ConsolidatedRuns with JsonOutputs {

  private val otherModule = ModuleName("api.test")

  private def runOfCalculatorSuite(testEvents: String*): TestRunPart =
    TestRunPart.Events(completedRunOf(eventLogOfSuite("CalculatorSuite", """test "adds", label "- adds"""", testEvents*)))

  private val runWithSucceededTest: TestRunPart = runOfCalculatorSuite("""test "adds": TestStarting""", """test "adds": TestSucceeded""")

  Feature("Whether a module has a test result") {

    Scenario("A module that has no run has no test result, though another module has one") {
      Given("a consolidation with one run under another module, which reports a test as succeeded")
      val consolidation = startedWithColour()
      consolidation.addRun(otherModule)(_.addAll(runWithSucceededTest))

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
          """
          run started
          structure
            suite "CalculatorSuite", class "samples.CalculatorSuite"
              test "adds", label "- adds"
          suite "CalculatorSuite": SuiteStarting
          suite "CalculatorSuite": SuiteAborted
          run ended completed
          """
        )
      )

      Then("the module has a test result")
      consolidation.hasTestResult(module) shouldBe true
    }

    Scenario("Runs whose event streams report no test's or suite's outcome give their module no test result") {
      Given("a consolidation")
      val consolidation = startedWithColour()

      When("the module is given a run that reports no suite, and a run whose suite completes with only a scope in it")
      consolidation.addRunOf(TestRunPart.Events(completedRunOf()))
      consolidation.addRunOf(
        TestRunPart.Events(
          """
          run started
          structure
            suite "CalculatorSuite", class "samples.CalculatorSuite"
              scope "A calculator", label "A calculator"
          suite "CalculatorSuite": SuiteStarting
          scope "A calculator": ScopeOpened
          scope "A calculator": ScopeClosed
          suite "CalculatorSuite": SuiteCompleted
          run ended completed
          """
        )
      )

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false
    }

    Scenario("A suite that the library reports as not run is no test result") {
      Given("a run of the module whose structure holds the suite `CalculatorSuite`, which then has no event")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      consolidation.addRunOf(TestRunPart.Events(completedRunOf(structureOf("CalculatorSuite", """test "adds", label "- adds""""))))

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false

      And("once the consolidation ends, the library reports the suite as aborted with reason not run")
      consolidation.end()
      val suite = json.node(NodeKind.Suite, "CalculatorSuite")
      (suite("outcome"), suite("reason")) shouldBe ((text("aborted"), text("notRun")))
    }

    Scenario("A suite and test that the library aborts, as the run aborts while the test is running, are no test result") {
      Given("a run of the module that aborts while the test `adds` is running")
      val json = new JsonSink
      val consolidation = startedWithoutMill(json)
      consolidation.addRunOf(
        TestRunPart.Events(
          """
          run started
          structure
            suite "CalculatorSuite", class "samples.CalculatorSuite"
              test "adds", label "- adds"
          suite "CalculatorSuite": SuiteStarting
          test "adds": TestStarting
          run: RunAborted
          run ended aborted
          """
        )
      )

      Then("the module has no test result")
      consolidation.hasTestResult(module) shouldBe false

      And("once the consolidation ends, the library reports the suite and the test as aborted")
      consolidation.end()
      Vector(json.node(NodeKind.Suite, "CalculatorSuite"), json.node(NodeKind.Test, "adds")).map(_("outcome")) shouldBe Vector(text("aborted"), text("aborted"))
    }

    Scenario("A module's test result is read while its run is still being fed") {
      Given("a run of the module being fed, which has started the test `adds`")
      val untilTestStarts = TestRunPart.Events(
        Vector(
          "run started",
          structureOf("CalculatorSuite", """test "adds", label "- adds""""),
          """suite "CalculatorSuite": SuiteStarting""",
          """test "adds": TestStarting"""
        ).mkString("\n")
      )
      val fromTestOutcome = TestRunPart.Events(
        """
        test "adds": TestSucceeded
        suite "CalculatorSuite": SuiteCompleted
        run ended completed
        """
      )
      val (eventsUntilTestStarts, eventsFromTestOutcome) =
        TestRunBuilder.build(untilTestStarts, fromTestOutcome).events.map(built => RunEvent.of(built.event)).splitAt(TestRunBuilder.build(untilTestStarts).events.size)
      val consolidation = startedWithColour()
      consolidation.addRun(module) { feed =>
        eventsUntilTestStarts.foreach(event => feed.add(event))

        Then("before the test's outcome is fed, the module has no test result")
        consolidation.hasTestResult(module) shouldBe false

        When("the run reports the test as succeeded")
        feed.add(eventsFromTestOutcome.head)

        Then("the module has a test result before the run's event stream ends")
        consolidation.hasTestResult(module) shouldBe true
        eventsFromTestOutcome.tail.foreach(event => feed.add(event))
      }
    }

    Scenario("A read waits while another thread holds the consolidation's lock, and answers after the consolidation ends") {
      Given("a run of the module that reports a test as succeeded")
      var whileWritingJsonOutput: () => Unit = () => ()
      val consolidation = Consolidation.start(new OutputSink, coloured = false, jsonOutput = Some(_ => whileWritingJsonOutput()))
      consolidation.addRunOf(runWithSucceededTest)
      var read = Option.empty[Boolean]
      val reader = new Thread(() => read = Some(consolidation.hasTestResult(module)))
      whileWritingJsonOutput = () => {
        When("another thread reads whether the module has a test result while the consolidation writes its JSON output")
        reader.start()

        Then("the read waits for the consolidation's lock, which the consolidation holds while it writes that output")
        eventually(reader.getState shouldBe Thread.State.BLOCKED)
        read shouldBe None
      }

      consolidation.end()
      reader.join()

      Then("once the consolidation has ended, the read answers that the module has a test result")
      read shouldBe Some(true)
      consolidation.hasTestResult(module) shouldBe true
    }
  }
}
