package org.scalatest.events

import org.scalatest.ConfigMap
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class RunOutcomeSpec extends AnyWordSpec with Matchers {

  private val ordinal = new Ordinal(0)

  "The outcome of a closing run event" should {
    "be completed for RunCompleted" in {
      RunOutcome.reportedBy(RunCompleted(ordinal)) shouldBe Some(RunOutcome.Completed)
    }
    "be stopped for RunStopped" in {
      RunOutcome.reportedBy(RunStopped(ordinal)) shouldBe Some(RunOutcome.Stopped)
    }
    "be aborted for RunAborted" in {
      RunOutcome.reportedBy(RunAborted(ordinal, "aborted", None)) shouldBe Some(RunOutcome.Aborted)
    }
    "be absent for an event that does not close the run" in {
      RunOutcome.reportedBy(RunStarting(ordinal, 1, ConfigMap.empty)) shouldBe None
    }
  }
}
