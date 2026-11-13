package org.scalatest.events

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidationTestSupportSpec extends AnyWordSpec with Matchers {

  private val run = TestRunBuilder.build(
    TestRunPart.Events(
      """
      structure
        suite "DivideSuite", class "samples.DivideSuite"
      suite "DivideSuite": SuiteStarting
      """
    ),
    TestRunPart.CapturedLine(CapturedStream.Stdout, "dividing now")
  )

  "The consolidation library's tests" should {
    "build a run with the test run builder" in {
      run.map(_.items.drop(1)) shouldBe Right(
        Vector(
          BuiltRunItem.Event(
            BuiltEvent(
              "suite \"DivideSuite\": SuiteStarting",
              TestEventLogEvent.ScalaTestEvent(Some(NodeExecution(NodeId(1), 1, None)), DecodedEvent.SuiteStarting(None, "main", 0L)),
              FixedParts.none
            )
          ),
          BuiltRunItem.CapturedLine(CapturedStream.Stdout, "dividing now")
        )
      )
    }

    "compare a log with built events" in {
      run.map(built => EventLogComparison.mismatchWhenIs(built.events.map(_.event).take(1), built.events)) shouldBe
        Right(Some("the log holds 1 events, not the 2 expected"))
    }
  }
}
