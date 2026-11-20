package org.scalatest.consolidation

import java.time.Instant
import scala.collection.mutable
import org.scalatest.events.MalformedTestEventLogException
import org.scalatest.events.TestEventLogEvent
import org.scalatest.events.TestRunPart
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidationSpec extends AnyWordSpec with Matchers with ConsolidatedRuns with JsonOutputs {

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

    "given a run" should {
      "make the run with the factory it was given, passing the run's module and worker" in {
        var made = Vector.empty[(TestRunLabel, Option[Worker])]
        val consolidation = new Consolidation(
          new OutputSink,
          coloured = false,
          jsonOutput = None,
          moduleSelectors = Vector.empty,
          buildTool = None,
          startedAt = None,
          newRun = (label, worker) => {
            made :+= (label -> worker)
            RunAssembly(label, worker)
          }
        )
        consolidation.addRun(moduleLabel, Some(workerAt(1, 2)))(_ => ())
        made shouldBe Vector(moduleLabel -> Some(workerAt(1, 2)))
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
        addRun(
          consolidation,
          suiteWithTest(
            """test "adds": TestStarting
              |test "adds": TestSucceeded""".stripMargin
          )
        )
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
      "give that state as it ends" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.addProcessEndedBeforeAnyRun()
        consolidation.end() shouldBe ConsolidationState.EndedFailed
      }
    }

    "ended twice" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.end()
        an[IllegalStateException] should be thrownBy consolidation.end()
      }
    }

    "ended while a run's event stream is being fed" should {
      "be refused, writing nothing" in {
        val output = new OutputSink
        val consolidation = Consolidation.start(output, coloured = true)
        consolidation.addRun(moduleLabel) { feed =>
          feedParts(feed, Vector(suiteWithTest("")))
          an[IllegalStateException] should be thrownBy consolidation.end()
          consolidation.state shouldBe ConsolidationState.Open
        }
        output.lines shouldBe empty
      }
    }

    "given a run after it has ended" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.end()
        an[IllegalStateException] should be thrownBy consolidation.addRun(moduleLabel)(_ => ())
      }
    }

    "given a run whose feeding throws" should {
      "end the run's event stream, then rethrow" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        val failure = new RuntimeException("reading failed")
        val thrown = intercept[RuntimeException] {
          consolidation.addRun(moduleLabel) { feed =>
            feedParts(feed, Vector(TestRunPart.Events("run started")))
            throw failure
          }
        }
        thrown shouldBe failure
        consolidation.state shouldBe ConsolidationState.OpenFailed
        consolidation.end()
        consolidation.state shouldBe ConsolidationState.EndedFailed
      }
    }

    "given a module's times twice" should {
      "hold only the later times" in {
        val json = new JsonSink
        val consolidation = startedWithoutMill(json)
        consolidation.timeModule(moduleLabel, Some(Instant.ofEpochMilli(1000L)), Some(Instant.ofEpochMilli(2000L)))
        consolidation.timeModule(moduleLabel, Some(Instant.ofEpochMilli(3000L)), None)
        consolidation.end()
        json.module.get("startedAt") shouldBe Some(integer(3000L))
        json.module.get("endedAt") shouldBe None
      }
    }

    "given a module's times after it has ended" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        consolidation.end()
        an[IllegalStateException] should be thrownBy consolidation.timeModule(moduleLabel, None, None)
      }
    }

    "given an event after the run's event stream has ended" should {
      "be refused" in {
        val consolidation = Consolidation.start(new OutputSink, coloured = true)
        val feeds = mutable.ArrayBuffer.empty[RunFeed]
        consolidation.addRun(moduleLabel)(feed => feeds += feed)
        an[IllegalStateException] should be thrownBy feeds.head.add("run ended completed".toRunEvent)
      }
    }

    "given a captured line after the run's event stream has ended" should {
      "show the line under the run" in {
        val output = new OutputSink
        val consolidation = Consolidation.start(output, coloured = false)
        val feeds = mutable.ArrayBuffer.empty[RunFeed]
        consolidation.addRun(moduleLabel)(feed => feeds += feed)
        feeds.head.add(CapturedLine.Stdout("late"))
        consolidation.end()
        output.linesWithoutSummaryLine shouldBe Vector("[core.test]", "  Run 1:", "    [stdout] late")
      }
    }

    "ended with colour off" should {
      "write the same text as with colour on, without ANSI escape sequences" in {
        def outputLines(coloured: Boolean): Vector[String] = {
          val output = new OutputSink
          val consolidation = Consolidation.start(output, coloured)
          addRun(
            consolidation,
            suiteWithTest(
              """test "adds": TestStarting
                |test "adds": TestFailed""".stripMargin
            )
          )
          consolidation.end()
          output.lines
        }

        val uncoloured = outputLines(coloured = false)
        uncoloured.mkString should not include "\u001b"
        uncoloured shouldBe outputLines(coloured = true).map(_.replaceAll("\u001b\\[[0-9;]*m", ""))
        uncoloured.dropRight(1) shouldBe Vector("[core.test]", "  CalculatorSuite:", "    - adds *** FAILED ***")
      }
    }
  }

  "A line of a run's test event log" when {

    "it holds the run's start" should {
      "be decoded to an event naming the run's process" in {
        RunEvent.decode("""{"item":"runStarted","pid":4242,"startTime":"2026-01-02T03:04:05.678Z"}""").runProcess shouldBe
          Some(RunProcess(Pid(4242L), Instant.parse("2026-01-02T03:04:05.678Z")))
      }
    }

    "it holds the run's start with a start time that is not an instant" should {
      "throw, naming the start time and the line as it was given" in {
        val line = """{ "item": "runStarted", "pid": 4242, "startTime": "yesterday" }"""
        val thrown = intercept[MalformedTestEventLogException](RunEvent.decode(line))
        thrown.reason shouldBe "the run's start time is not an instant: yesterday"
        thrown.line shouldBe line
      }
    }

    "it holds another event" should {
      "be decoded to an event naming no process" in {
        RunEvent.decode("""{"item":"runEnded","outcome":"completed"}""").runProcess shouldBe None
      }
    }

    "it is not an event" should {
      "throw, naming the line" in {
        intercept[MalformedTestEventLogException](RunEvent.decode("not an event")).line shouldBe "not an event"
      }
    }
  }

  "A run's start event whose start time is not an instant" should {
    "throw, naming the start time and the event's line, instead of becoming a run event" in {
      val thrown = intercept[MalformedTestEventLogException](RunEvent.of(TestEventLogEvent.RunStarted(4242L, "yesterday")))
      thrown.reason shouldBe "the run's start time is not an instant: yesterday"
      thrown.line shouldBe """{"item":"runStarted","pid":4242,"startTime":"yesterday"}"""
    }
  }
}
