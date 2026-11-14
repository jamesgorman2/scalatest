package org.scalatest.mill

import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.consolidation.RunEvent
import org.scalatest.consolidation.RunFeed
import org.scalatest.consolidation.RunProcess
import scala.concurrent.duration.FiniteDuration

/**
 * Reads one worker's test event log on a thread of its own, while the worker runs.
 *
 * Once the event log file appears, it adds the run and feeds it each event the file's whole lines decode to. It
 * watches the run's process, named by the `runStarted` event, and once that process has exited reads to the file's
 * end and ends the run's event stream. A line that cannot be decoded ends the stream there. A worker whose event log
 * file has not appeared when the worker ends had no run, and adds none.
 *
 * @param addRun adds the run, once its event log file exists
 */
private[mill] final class EventLogReader(
    eventLogFile: Path,
    addRun: () => RunFeed,
    hasExited: RunProcess => Boolean,
    pollInterval: FiniteDuration
) {

  @volatile private var workerEnded: Boolean = false
  @volatile private var failure: Option[Throwable] = None
  private val thread: Thread = new Thread(() => readCapturingFailure(), s"scalatest event log reader: $eventLogFile")
  thread.setDaemon(true)

  def start(): Unit = thread.start()

  /**
   * Waits for the run's event stream to end. Call it once the worker has ended.
   *
   * @throws Throwable what reading threw
   */
  def awaitStreamEnd(): Unit = {
    workerEnded = true
    thread.join()
    failure.foreach(throwable => throw throwable)
  }

  private def readCapturingFailure(): Unit =
    try {
      if (awaitEventLogFile()) {
        val feed = addRun()
        try {
          readRun(feed)
        } finally {
          feed.endEventStream()
        }
      }
    } catch {
      case throwable: Throwable => failure = Some(throwable)
    }

  private def awaitEventLogFile(): Boolean = {
    var appeared = false
    var noRun = false
    while (!appeared && !noRun) {
      val ended = workerEnded
      appeared = Files.exists(eventLogFile)
      noRun = !appeared && ended
      if (!appeared && !noRun) {
        Thread.sleep(pollInterval.toMillis)
      }
    }
    appeared
  }

  private def readRun(feed: RunFeed): Unit = {
    val input = new FileInputStream(eventLogFile.toFile)
    try {
      new RunReading(feed, input).readUntilStreamEnds()
    } finally {
      input.close()
    }
  }

  private final class RunReading(feed: RunFeed, input: FileInputStream) {

    private val lines = new EventLogLines
    private val buffer = new Array[Byte](64 * 1024)
    private var process: Option[RunProcess] = None
    private var undecodable: Boolean = false

    def readUntilStreamEnds(): Unit = {
      var ended = false
      while (!ended) {
        val exitedBeforeRead = process.exists(hasExited)
        readToEnd()
        ended = undecodable || exitedBeforeRead
        if (!ended) {
          Thread.sleep(pollInterval.toMillis)
        }
      }
    }

    private def readToEnd(): Unit = {
      var read = input.read(buffer)
      while (read >= 0 && !undecodable) {
        lines.append(buffer, read).iterator.takeWhile(_ => !undecodable).foreach(add)
        read = if (undecodable) -1 else input.read(buffer)
      }
    }

    private def add(line: String): Unit =
      RunEvent.decode(line) match {
        case Right(event) =>
          feed.add(event)
          process = process.orElse(event.runProcess)
        case Left(_) =>
          undecodable = true
      }
  }
}
