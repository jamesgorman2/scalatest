package org.scalatest.events

private[scalatest] enum RunOutcome {
  case Completed
  case Aborted
  case Stopped
}

private[scalatest] object RunOutcome {

  /** The outcome a closing run event reports, or `None` where the event does not close the run. */
  def ofClosing(event: Event): Option[RunOutcome] =
    event match {
      case _: RunCompleted => Some(Completed)
      case _: RunStopped => Some(Stopped)
      case _: RunAborted => Some(Aborted)
      case _ => None
    }
}
