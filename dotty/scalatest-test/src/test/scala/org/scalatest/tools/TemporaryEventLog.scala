package org.scalatest.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.scalatest.events.TestEventLogDecoder
import org.scalatest.events.TestEventLogEvent
import scala.jdk.CollectionConverters.*

private[tools] object TemporaryEventLog {

  /** Runs the body with the path of an event log file in a folder of its own, which it removes afterwards. */
  def in[A](body: Path => A): A = {
    val folder = Files.createTempDirectory("test-event-log")
    try {
      body(folder.resolve("events.log"))
    } finally {
      val walk = Files.walk(folder)
      try walk.sorted(Comparator.reverseOrder[Path]()).iterator.asScala.foreach(Files.delete) finally walk.close()
    }
  }

  /** Returns the file's lines, each without its line feed. The last line must end in one. */
  def lines(eventLogFile: Path): Vector[String] = {
    val text = new String(Files.readAllBytes(eventLogFile), StandardCharsets.UTF_8)
    require(text.endsWith("\n"), s"the event log's last line is not ended: $text")
    text.split('\n').toVector
  }

  def decoded(eventLogFile: Path): Vector[Either[String, TestEventLogEvent]] =
    lines(eventLogFile).map(TestEventLogDecoder.decode)

  def namesIn(folder: Path): Set[String] = {
    val entries = Files.list(folder)
    try entries.iterator.asScala.map(_.getFileName.toString).toSet finally entries.close()
  }
}
