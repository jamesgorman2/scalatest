package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.events.TestRunPart
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidationSpec extends AnyWordSpec with Matchers with ConsolidatedRuns {

  private def suiteWithTest(testEvents: String): TestRunPart =
    TestRunPart.Events(
      Vector(
        "run started",
        "structure",
        "  suite \"CalculatorSuite\", class \"samples.CalculatorSuite\"",
        "    test \"adds\", label \"- adds\"",
        "suite \"CalculatorSuite\": SuiteStarting",
        testEvents,
        "suite \"CalculatorSuite\": SuiteCompleted",
        "run ended completed"
      ).mkString("\n")
    )

  private def stateAfterRunOf(testEvents: String): ConsolidationState = {
    val consolidation = Consolidation.start(new OutputSink, coloured = true)
    addRun(consolidation, suiteWithTest(testEvents))
    consolidation.state
  }

  "A consolidation" when {

    "started" should {
      "be open" in {
        Consolidation.start(new OutputSink, coloured = true).state shouldBe ConsolidationState.Open
      }
    }

    "given a run whose events report no failure" should {
      "stay open" in {
        stateAfterRunOf(
          """test "adds": TestStarting
            |test "adds": TestSucceeded""".stripMargin
        ) shouldBe ConsolidationState.Open
      }

      "stay open where a test is ignored or pending" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        addRun(
          consolidation,
          TestRunPart.Events(
            """
            run started
            structure
              suite "CalculatorSuite", class "samples.CalculatorSuite"
                scope "A calculator", label "A calculator"
                test "adds", label "- adds"
                test "divides", label "- divides"
            suite "CalculatorSuite": SuiteStarting
            scope "A calculator": ScopePending
            test "adds": TestIgnored
            test "divides": TestStarting
            test "divides": TestPending
            suite "CalculatorSuite": SuiteCompleted
            run ended completed
            """
          )
        )
        consolidation.state shouldBe ConsolidationState.Open
      }
    }

    "given a run with a test that failed" should {
      "be open and failed" in {
        stateAfterRunOf(
          """test "adds": TestStarting
            |test "adds": TestFailed""".stripMargin
        ) shouldBe ConsolidationState.OpenFailed
      }
    }

    "given a run with a test that was cancelled" should {
      "be open and failed" in {
        stateAfterRunOf(
          """test "adds": TestStarting
            |test "adds": TestCanceled""".stripMargin
        ) shouldBe ConsolidationState.OpenFailed
      }
    }

    "given a run with a suite that aborted" should {
      "be open and failed" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        addRun(
          consolidation,
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
        consolidation.state shouldBe ConsolidationState.OpenFailed
      }
    }

    "given a process that ended before any run existed in it" should {
      "be open and failed" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.addProcessEndedBeforeAnyRun()
        consolidation.state shouldBe ConsolidationState.OpenFailed
      }
    }

    "ended with nothing failed" should {
      "be ended" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        addRun(consolidation, suiteWithTest("")).endEventStream()
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.Ended
      }
    }

    "ended after a failure" should {
      "be ended and failed" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.addProcessEndedBeforeAnyRun()
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }
    }

    "ended while a run's event stream is open" should {
      "be refused, writing nothing" in {
        val output = new OutputSink
        val consolidation = Consolidation.start(output, coloured = true)
        addRun(consolidation, suiteWithTest(""))
        an[IllegalArgumentException] should be thrownBy consolidation.end()
        output.lines shouldBe empty
        consolidation.state shouldBe ConsolidationState.Open
      }
    }

    "given a run after it has ended" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.end()
        an[IllegalArgumentException] should be thrownBy consolidation.addRun(moduleLabel)
      }
    }

    "given an event after the run's event stream has ended" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        val feed = consolidation.addRun(moduleLabel)
        feed.endEventStream()
        an[IllegalArgumentException] should be thrownBy feed.add(runEvent("run ended completed"))
      }
    }

    "ended with colour off" should {
      "write the text it writes with colour on, with no ANSI escape sequence" in {
        def outputLines(coloured: Boolean): Vector[String] = {
          val output = new OutputSink
          val consolidation = Consolidation.start(output, coloured)
          addRun(
            consolidation,
            suiteWithTest(
              """test "adds": TestStarting
                |test "adds": TestFailed""".stripMargin
            )
          ).endEventStream()
          consolidation.end()
          output.lines
        }

        val uncoloured = outputLines(coloured = false)
        uncoloured.mkString should not include "\u001b"
        uncoloured shouldBe outputLines(coloured = true).map(_.replaceAll("\u001b\\[[0-9;]*m", ""))
        uncoloured shouldBe Vector("[core.test]", "  CalculatorSuite:", "    - adds *** FAILED ***")
      }
    }
  }

  "A line of a run's test event log" when {

    "it holds the run's start" should {
      "be decoded to an event naming the run's process" in {
        RunEvent.decode("""{"item":"runStarted","pid":4242,"startTime":"2026-01-02T03:04:05.678Z"}""").map(_.runProcess) shouldBe
          Right(Some(RunProcess(Pid(4242L), Instant.parse("2026-01-02T03:04:05.678Z"))))
      }
    }

    "it holds the run's start with a start time that is not an instant" should {
      "not be decoded" in {
        RunEvent.decode("""{"item":"runStarted","pid":4242,"startTime":"yesterday"}""") shouldBe
          Left("the run's start time is not an instant: yesterday")
      }
    }

    "it holds another event" should {
      "be decoded to an event naming no process" in {
        RunEvent.decode("""{"item":"runEnded","outcome":"completed"}""").map(_.runProcess) shouldBe Right(None)
      }
    }

    "it is not an event" should {
      "not be decoded" in {
        RunEvent.decode("not an event").isLeft shouldBe true
      }
    }
  }
}
