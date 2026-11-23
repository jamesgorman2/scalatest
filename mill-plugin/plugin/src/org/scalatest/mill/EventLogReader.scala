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

private[mill] final class EventLogReader(
    eventLogFile: EventLogFile,
    addRun: (RunFeed => Unit) => Unit,
    addProcessEndedBeforeAnyRun: () => Unit,
    hasExited: RunProcess => Boolean,
    pollInterval: FiniteDuration
) {

  @volatile private var workerEnd: Option[EventLogReader.WorkerEnd] = None
  @volatile private var failure: Option[Throwable] = None
  private val thread: Thread = new Thread(() => readCapturingFailure(), EventLogReader.threadName(eventLogFile))
  thread.setDaemon(true)

  def start(): Unit = thread.start()

  def workerHasEnded(process: WorkerProcess): Unit =
    endWorker(if (process.started) EventLogReader.WorkerEnd.MayHaveStartedAProcess else EventLogReader.WorkerEnd.StartedNoProcess)

  // A worker whose start threw, or whose end is unknown, may have started a process.
  def workerEndIsUnknown(): Unit = endWorker(EventLogReader.WorkerEnd.MayHaveStartedAProcess)

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
          if (workerEnd.contains(EventLogReader.WorkerEnd.MayHaveStartedAProcess)) {
            addProcessEndedBeforeAnyRun()
          }
      }
    } catch {
      case throwable: Throwable => failure = Some(throwable)
    }

  private def endWorker(end: EventLogReader.WorkerEnd): Unit =
    synchronized {
      if (workerEnd.isEmpty) {
        workerEnd = Some(end)
      }
    }

  @tailrec
  private def awaitEventLogFile(): EventLogReader.EventLogFileArrival = {
    val ended = workerEnd.isDefined
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
      new RunReading(feed, input).readUntilProcessExits(process = None)
    } finally {
      input.close()
    }
  }

  @tailrec
  private def openOnceReadable(failedOpens: Int): EventLogInput =
    Try(eventLogFile.open()) match {
      case Success(input) => input
      // On Windows, opening the file just after the emitter moves it into place can fail for a moment with a sharing
      // violation, which Java reports as a `FileNotFoundException`.
      case Failure(_: FileNotFoundException) if failedOpens < EventLogReader.retriedOpens =>
        Thread.sleep(pollInterval.toMillis)
        openOnceReadable(failedOpens + 1)
      case Failure(throwable) => throw throwable
    }

  private final class RunReading(feed: RunFeed, input: EventLogInput) {

    private val lines = new EventLogLines

    @tailrec
    def readUntilProcessExits(process: Option[RunProcess]): Unit = {
      val exitedBeforeRead = process.exists(hasExited)
      val processAfterRead = readToEnd(process)
      if (!exitedBeforeRead) {
        Thread.sleep(pollInterval.toMillis)
        readUntilProcessExits(processAfterRead)
      }
    }

    @tailrec
    private def readToEnd(process: Option[RunProcess]): Option[RunProcess] = {
      val bytes = input.readAvailable()
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

  def threadName(eventLogFile: EventLogFile): String = s"scalatest event log reader: ${eventLogFile.name}"

  private val retriedOpens: Int = 500

  private enum WorkerEnd {
    case MayHaveStartedAProcess
    case StartedNoProcess
  }

  private enum EventLogFileArrival {
    case Appeared
    case WillNotAppear
  }
}
