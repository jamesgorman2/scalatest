package org.scalatest.events

private[scalatest] enum RunOutcome {
  case Completed
  case Aborted
  case Stopped
}

private[scalatest] object RunOutcome {

  def reportedBy(event: Event): Option[RunOutcome] =
    event match {
      case _: RunCompleted => Some(Completed)
      case _: RunStopped => Some(Stopped)
      case _: RunAborted => Some(Aborted)
      case _ => None
    }
}
