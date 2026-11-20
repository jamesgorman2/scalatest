package org.scalatest.tools

import org.scalatest.Filter
import org.scalatest.Reporter
import org.scalatest.Suite
import org.scalatest.SuiteStart
import org.scalatest.events.Event
import org.scalatest.events.RunOutcome

private[scalatest] object TestEventLogHandOff {

  val none: Event => Unit = (_: Event) => ()

  def isHandedOffByDispatchReporter(event: Event): Boolean = RunOutcome.reportedBy(event).isDefined

  val startWithoutEventLog: (Suite, Filter, Reporter) => SuiteStart = (_: Suite, _: Filter, reporter: Reporter) => SuiteStart.withoutEventLog(reporter)

  val startInTheContextItFinds: (Suite, Filter, Reporter) => SuiteStart = (suite: Suite, _: Filter, reporter: Reporter) => SuiteStart.inTheContextItFinds(suite, reporter)
}
