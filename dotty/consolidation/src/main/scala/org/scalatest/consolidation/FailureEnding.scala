package org.scalatest.consolidation

import org.scalatest.events.DecodedEvent
import org.scalatest.events.DecodedThrowable

/** The `TestFailed`, `TestCanceled` or `SuiteAborted` that ended an execution, whose lines sit under the execution's line. */
private[consolidation] enum FailureEnding {
  case TestFailed(message: String, throwable: Option[DecodedThrowable], analysis: IndexedSeq[String])
  case TestCanceled(message: String, throwable: Option[DecodedThrowable])
  case SuiteAborted(message: String, throwable: Option[DecodedThrowable])

  def message: String

  def throwable: Option[DecodedThrowable]
}

private[consolidation] object FailureEnding {

  def of(event: DecodedEvent): Option[FailureEnding] =
    event match {
      case failed: DecodedEvent.TestFailed => Some(TestFailed(failed.message, failed.throwable, failed.analysis))
      case canceled: DecodedEvent.TestCanceled => Some(TestCanceled(canceled.message, canceled.throwable))
      case aborted: DecodedEvent.SuiteAborted => Some(SuiteAborted(aborted.message, aborted.throwable))
      case _ => None
    }
}
