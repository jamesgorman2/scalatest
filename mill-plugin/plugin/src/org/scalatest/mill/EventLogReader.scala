package org.scalatest.mill

import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
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
 * end and ends the run's event stream. A line that cannot be decoded ends the stream there. If the worker ends before
 * the file appears, no run existed in it, and the reader adds the worker as a process that ended before any run.
 *
 * @param addRun adds the run, once its event log file exists
 * @param addProcessEndedBeforeAnyRun adds the worker, once it has ended with no event log file
 * @param open opens the event log file for reading
 */
private[mill] final class EventLogReader(
    eventLogFile: Path,
    addRun: () => RunFeed,
    addProcessEndedBeforeAnyRun: () => Unit,
    hasExited: RunProcess => Boolean,
    pollInterval: FiniteDuration,
    open: Path => InputStream = path => new FileInputStream(path.toFile)
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
      } else {
        addProcessEndedBeforeAnyRun()
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
    val input = openOnceReadable()
    try {
      new RunReading(feed, input).readUntilStreamEnds()
    } finally {
      input.close()
    }
  }

  // On Windows, opening the file just after the emitter moves it into place can fail for a moment with a sharing
  // violation, which Java reports as a `FileNotFoundException`.
  private def openOnceReadable(): InputStream = {
    var input: Option[InputStream] = None
    var failedOpens = 0
    while (input.isEmpty) {
      try {
        input = Some(open(eventLogFile))
      } catch {
        case _: FileNotFoundException if failedOpens < EventLogReader.retriedOpens =>
          failedOpens += 1
          Thread.sleep(pollInterval.toMillis)
      }
    }
    input.get
  }

  private final class RunReading(feed: RunFeed, input: InputStream) {

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

private[mill] object EventLogReader {

  private val retriedOpens: Int = 500
}
