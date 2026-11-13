package org.scalatest.tools

import org.scalatest.events.Event

private[scalatest] object TestEventLogHandOff {

  /** The hand-off of a run that writes no test event log. */
  val none: Event => Unit = (_: Event) => ()
}
