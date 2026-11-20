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
 * Reads one worker's test event log on a thread of its own, while the worker runs. It may also start after the worker
 * has ended, and then reads the finished event log.
 *
 * Once the event log file appears, it adds the run and feeds it each event the file's whole lines decode to. It
 * watches the run's process, named by the `runStarted` event, and once that process has exited reads to the file's
 * end and ends the run's event stream. A line that cannot be decoded ends the stream there, and `awaitStreamEnd` throws
 * an exception naming the line and why. If
 * the worker ends before the file appears, no run existed in it: where the worker started a process, the reader adds
 * that process as one that ended before any run.
 */
private[mill] final class EventLogReader(
    eventLogFile: Path,
    addRun: () => RunFeed,
    addProcessEndedBeforeAnyRun: () => Unit,
    hasExited: RunProcess => Boolean,
    pollInterval: FiniteDuration,
    open: Path => InputStream = path => new FileInputStream(path.toFile)
) {

  @volatile private var endedWorkerProcess: Option[WorkerProcess] = None
  @volatile private var failure: Option[Throwable] = None
  private val thread: Thread = new Thread(() => readCapturingFailure(), EventLogReader.threadName(eventLogFile))
  thread.setDaemon(true)

  def start(): Unit = thread.start()

  /**
   * Tells the reader the worker has ended, so a missing event log file will not appear. The reader then ends at once if
   * the file has not appeared, else once the run's process has exited. Only the first call counts.
   *
   * @param process whether the worker started a process
   */
  def workerHasEnded(process: WorkerProcess): Unit =
    synchronized {
      if (endedWorkerProcess.isEmpty) {
        endedWorkerProcess = Some(process)
      }
    }

  /**
   * Call it once the reader has been told the worker has ended: until then, the reader may wait on the event log file.
   *
   * @throws Throwable what reading threw
   */
  def awaitStreamEnd(): Unit = {
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
      } else if (endedWorkerProcess.contains(WorkerProcess.Started)) {
        addProcessEndedBeforeAnyRun()
      }
    } catch {
      case throwable: Throwable => failure = Some(throwable)
    }

  private def awaitEventLogFile(): Boolean = {
    var appeared = false
    var noRun = false
    while (!appeared && !noRun) {
      val ended = endedWorkerProcess.isDefined
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

    def readUntilStreamEnds(): Unit = {
      var exitedBeforeRead = false
      while (!exitedBeforeRead) {
        exitedBeforeRead = process.exists(hasExited)
        readToEnd()
        if (!exitedBeforeRead) {
          Thread.sleep(pollInterval.toMillis)
        }
      }
    }

    private def readToEnd(): Unit = {
      var read = input.read(buffer)
      while (read >= 0) {
        lines.append(buffer, read).foreach(add)
        read = input.read(buffer)
      }
    }

    private def add(line: String): Unit = {
      val event = RunEvent.decode(line)
      feed.add(event)
      process = process.orElse(event.runProcess)
    }
  }
}

private[mill] object EventLogReader {

  def threadName(eventLogFile: Path): String = s"scalatest event log reader: $eventLogFile"

  private val retriedOpens: Int = 500
}
