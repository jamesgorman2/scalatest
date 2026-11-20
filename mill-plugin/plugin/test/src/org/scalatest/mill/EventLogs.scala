package org.scalatest.mill

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private[mill] object EventLogs {

  val runStarted: String = """{"item":"runStarted","pid":4242,"startTime":"2026-11-14T01:02:03.456Z"}"""
  val runEndedCompleted: String = """{"item":"runEnded","outcome":"completed"}"""

  def withEventLogFile[A](test: Path => A): A =
    TemporaryFolder.within("event-log")(folder => test(folder.resolve(JvmTestModuleRun.eventLogFileName)))

  def append(file: Path, text: String): Unit =
    Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

  def appendCompletedRun(file: Path): Unit = append(file, s"$runStarted\n$runEndedCompleted\n")

  def sharingViolation(file: Path): FileNotFoundException =
    new FileNotFoundException(s"$file (The process cannot access the file because it is being used by another process)")
}
