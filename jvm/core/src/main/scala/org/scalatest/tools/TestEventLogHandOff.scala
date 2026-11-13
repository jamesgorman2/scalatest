package org.scalatest.tools

import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.events.Event

private[scalatest] object TestEventLogHandOff {

  /** The hand-off of a run that writes no test event log. */
  val none: Event => Unit = (_: Event) => ()

  /**
   * What gives a top-level suite its reporter in a run that writes no test event log: the reporter the suite would
   * report to, under no structure wrapper.
   */
  val noStructureWrapper: (Suite, Filter, Reporter) => Reporter = (_: Suite, _: Filter, reporter: Reporter) => reporter
}
