package org.scalatest.tools

/**
 * The runner argument that makes a run write its test event log to a file: its flag, followed by the file's path as
 * the next argument.
 */
private[scalatest] object TestEventLogArgument {

  val flag: String = "--test-event-log"

  def forFile(eventLogFile: String): List[String] = List(flag, eventLogFile)

  def isFlag(argument: String): Boolean = argument == flag

  /** Takes the file's path from the arguments that follow the flag. */
  def fileAfterFlag(following: Iterator[String]): String =
    if (following.hasNext)
      following.next()
    else
      throw new IllegalArgumentException(flag + " must be followed by the path of the test event log file")
}
