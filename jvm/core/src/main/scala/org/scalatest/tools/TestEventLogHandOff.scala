package org.scalatest.tools

import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteStart
import org.scalatest.events.Event
import org.scalatest.events.RunOutcome

private[scalatest] object TestEventLogHandOff {

  val none: Event => Unit = (_: Event) => ()

  // A closing run event is handed off by DispatchReporter, so that it carries the summary DispatchReporter gives it.
  def isHandedOffByDispatchReporter(event: Event): Boolean = RunOutcome.reportedBy(event).isDefined

  val startWithoutEventLog: (Suite, Filter, Reporter) => SuiteStart = (_: Suite, _: Filter, reporter: Reporter) => SuiteStart.withoutEventLog(reporter)

  // For a suite a distributor is handed. The context's registration of the suite keeps the names read for its `SuiteStarting`.
  val startInTheContextItFinds: (Suite, Filter, Reporter) => SuiteStart = (suite: Suite, _: Filter, reporter: Reporter) => SuiteStart.inTheContextItFinds(suite, reporter)
}
