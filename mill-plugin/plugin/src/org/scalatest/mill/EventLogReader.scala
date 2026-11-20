package org.scalatest.mill

import java.io.FileNotFoundException
import org.scalatest.consolidation.RunEvent
import org.scalatest.consolidation.RunFeed
import org.scalatest.consolidation.RunProcess
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

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
    eventLogFile: EventLogFile,
    addRun: (RunFeed => Unit) => Unit,
    addProcessEndedBeforeAnyRun: () => Unit,
    hasExited: RunProcess => Boolean,
    pollInterval: FiniteDuration
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
      awaitEventLogFile() match {
        case EventLogReader.EventLogFileArrival.Appeared =>
          addRun(readRun)
        case EventLogReader.EventLogFileArrival.WillNotAppear =>
          if (endedWorkerProcess.contains(WorkerProcess.Started)) {
            addProcessEndedBeforeAnyRun()
          }
      }
    } catch {
      case throwable: Throwable => failure = Some(throwable)
    }

  @tailrec
  private def awaitEventLogFile(): EventLogReader.EventLogFileArrival = {
    val ended = endedWorkerProcess.isDefined
    if (eventLogFile.exists) {
      EventLogReader.EventLogFileArrival.Appeared
    } else if (ended) {
      EventLogReader.EventLogFileArrival.WillNotAppear
    } else {
      Thread.sleep(pollInterval.toMillis)
      awaitEventLogFile()
    }
  }

  private def readRun(feed: RunFeed): Unit = {
    val input = openOnceReadable(failedOpens = 0)
    try {
      new RunReading(feed, input).readUntilStreamEnds(process = None)
    } finally {
      input.close()
    }
  }

  // On Windows, opening the file just after the emitter moves it into place can fail for a moment with a sharing
  // violation, which Java reports as a `FileNotFoundException`.
  @tailrec
  private def openOnceReadable(failedOpens: Int): EventLogInput =
    Try(eventLogFile.open()) match {
      case Success(input) => input
      case Failure(_: FileNotFoundException) if failedOpens < EventLogReader.retriedOpens =>
        Thread.sleep(pollInterval.toMillis)
        openOnceReadable(failedOpens + 1)
      case Failure(throwable) => throw throwable
    }

  private final class RunReading(feed: RunFeed, input: EventLogInput) {

    private val lines = new EventLogLines

    @tailrec
    def readUntilStreamEnds(process: Option[RunProcess]): Unit = {
      val exitedBeforeRead = process.exists(hasExited)
      val processAfterRead = readToEnd(process)
      if (!exitedBeforeRead) {
        Thread.sleep(pollInterval.toMillis)
        readUntilStreamEnds(processAfterRead)
      }
    }

    @tailrec
    private def readToEnd(process: Option[RunProcess]): Option[RunProcess] = {
      val bytes = input.read()
      if (bytes.isEmpty)
        process
      else
        readToEnd(lines.append(bytes, bytes.length).foldLeft(process)(add))
    }

    private def add(process: Option[RunProcess], line: String): Option[RunProcess] = {
      val event = RunEvent.decode(line)
      feed.add(event)
      process.orElse(event.runProcess)
    }
  }
}

private[mill] object EventLogReader {

  def threadName(eventLogFile: EventLogFile): String = s"scalatest event log reader: $eventLogFile"

  private val retriedOpens: Int = 500

  private enum EventLogFileArrival {
    case Appeared
    case WillNotAppear
  }
}
