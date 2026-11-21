package org.scalatest.events

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class ConsolidationTestSupportSpec extends AnyWordSpec with should.Matchers {

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
      run.items.drop(1) shouldBe
        Vector(
          BuiltRunItem.Event(
            BuiltEvent(
              "suite \"DivideSuite\": SuiteStarting",
              TestEventLogEvent.ScalaTestEvent(Some(NodeExecution(NodeId.topLevelSuite(SuiteId("samples.DivideSuite"), 0), Vector(1))), DecodedEvent.SuiteStarting(None, "main", 0L)),
              WrittenParts.none
            )
          ),
          BuiltRunItem.CapturedLine(CapturedStream.Stdout, "dividing now")
        )
    }

    "compare a log with built events" in {
      EventLogComparison.mismatchWhenIs(run.events.map(_.event).take(1), run.events) shouldBe
        Some("the log holds 1 events, not the 2 expected")
    }
  }
}
