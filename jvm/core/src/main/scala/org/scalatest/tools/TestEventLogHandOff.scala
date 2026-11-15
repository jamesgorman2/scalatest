package org.scalatest.tools

import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteStart
import org.scalatest.events.Event
import org.scalatest.events.RunAborted
import org.scalatest.events.RunCompleted
import org.scalatest.events.RunStopped

private[scalatest] object TestEventLogHandOff {

  /** The hand-off of a run that writes no test event log. */
  val none: Event => Unit = (_: Event) => ()

  /**
   * Whether `DispatchReporter`, not the send site, hands the event off. It does for a closing run event, so that the
   * event handed off carries the summary `DispatchReporter` gives it.
   */
  def isHandedOffByDispatchReporter(event: Event): Boolean =
    event match {
      case _: RunCompleted | _: RunStopped | _: RunAborted => true
      case _ => false
    }

  /**
   * How a top-level suite of a run that writes no test event log starts: with the reporter it would report to, under
   * no structure wrapper, and in no invocation context.
   */
  val startWithoutEventLog: (Suite, Filter, Reporter) => SuiteStart = (_: Suite, _: Filter, reporter: Reporter) => SuiteStart.withoutEventLog(reporter)

  /**
   * How a suite a distributor is handed starts: with the reporter it is handed, in the context it is handed in. That
   * context's registration of the suite, where it has one, keeps the names read for its `SuiteStarting`.
   */
  val startInTheContextItFinds: (Suite, Filter, Reporter) => SuiteStart = (suite: Suite, _: Filter, reporter: Reporter) => SuiteStart.inTheContextItFinds(suite, reporter)
}
