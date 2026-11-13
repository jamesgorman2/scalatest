package org.scalatest.consolidation

import java.nio.file.Path
import org.scalatest.tools.TestEventLogArgument

/** The ScalaTest runner argument that makes a run write its test event log. */
object RunnerArgument {

  /**
   * Returns the arguments to add to a run's ScalaTest arguments so that the run writes its test event log to the
   * given file.
   *
   * @param eventLogFile the absolute path of the file, in a folder that exists
   * @throws IllegalArgumentException where the path is not absolute
   */
  def forEventLogFile(eventLogFile: Path): Seq[String] = {
    require(eventLogFile.isAbsolute, s"the event log file's path must be absolute, but was $eventLogFile")
    TestEventLogArgument.forFile(eventLogFile.toString)
  }
}
