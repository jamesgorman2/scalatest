package org.scalatest.tools

private[scalatest] object TestEventLogArgument {

  val flag: String = "--test-event-log"

  def forFile(eventLogFile: String): List[String] = List(flag, eventLogFile)

  def isFlag(argument: String): Boolean = argument == flag

  def fileAfterFlag(following: Iterator[String]): String =
    if (following.hasNext)
      following.next()
    else
      throw new IllegalArgumentException(flag + " must be followed by the path of the test event log file")
}
