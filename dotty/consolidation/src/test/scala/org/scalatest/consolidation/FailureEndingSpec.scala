package org.scalatest.consolidation

import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

final class FailureEndingSpec extends AnyWordSpec with should.Matchers {

  private val throwable = Some(new DecodedThrowable("java.lang.RuntimeException", Some("boom"), None, false, Vector.empty))

  "A failure ending" when {
    "read from a TestFailed" should {
      "be a test failure with its message, throwable and analysis" in {
        FailureEnding.of(DecodedEvent.TestFailed("failed", Vector("expected 1"), throwable, None, None, "main", 100)) shouldBe
          FailureEnding.TestFailed("failed", throwable, Vector("expected 1"))
      }
    }
    "read from a TestCanceled" should {
      "be a test cancellation with its message and throwable" in {
        FailureEnding.of(DecodedEvent.TestCanceled("canceled", throwable, None, None, "main", 100)) shouldBe
          FailureEnding.TestCanceled("canceled", throwable)
      }
    }
    "read from a SuiteAborted" should {
      "be a suite abort with its message and throwable" in {
        FailureEnding.of(DecodedEvent.SuiteAborted("aborted", throwable, None, None, "main", 100)) shouldBe
          FailureEnding.SuiteAborted("aborted", throwable)
      }
    }
  }
}
