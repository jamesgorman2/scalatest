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

  "An outcome's word" should {
    "be the word a runEnded event writes for it" in {
      RunOutcome.values.toVector.map(_.word) shouldBe Vector("completed", "aborted", "stopped")
    }
    "give back the outcome" in {
      RunOutcome.values.toVector.map(outcome => RunOutcome.fromWord(outcome.word)) shouldBe RunOutcome.values.toVector.map(Some(_))
    }
    "give no outcome for a word no outcome has" in {
      RunOutcome.fromWord("failed") shouldBe None
    }
  }
}
