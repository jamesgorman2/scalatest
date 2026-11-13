package org.scalatest.tools

private[tools] object TestEventLogLine {

  def runStarted(pid: Long, startTime: String): String = s"""{"item":"runStarted","pid":$pid,"startTime":"$startTime"}"""

  def runEnded(outcome: String): String = s"""{"item":"runEnded","outcome":"$outcome"}"""
}
