package org.scalatest.events

/** `word` is the outcome as a test event log's `runEnded` event writes it. */
private[scalatest] enum RunOutcome(val word: String) {
  case Completed extends RunOutcome("completed")
  case Aborted extends RunOutcome("aborted")
  case Stopped extends RunOutcome("stopped")

  /** Whether the run aborted or stopped. */
  def isAbnormal: Boolean = this != Completed
}

private[scalatest] object RunOutcome {

  def fromWord(word: String): Option[RunOutcome] = values.find(_.word == word)

  def reportedBy(event: Event): Option[RunOutcome] =
    event match {
      case _: RunCompleted => Some(Completed)
      case _: RunStopped => Some(Stopped)
      case _: RunAborted => Some(Aborted)
      case _ => None
    }
}
