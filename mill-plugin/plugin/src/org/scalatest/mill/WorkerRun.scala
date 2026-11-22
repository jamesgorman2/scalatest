package org.scalatest.mill

import java.util.concurrent.ExecutionException
import org.scalatest.consolidation.Consolidation
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[mill] final class WorkerRun(consolidation: Consolidation, workerName: String, reader: EventLogReader, capture: WorkerOutputCapture) {

  private var started: Try[Future[WorkerProcess]] = Failure(new IllegalStateException(s"$workerName was never started"))
  private var readerState: WorkerRun.ReaderState = WorkerRun.ReaderState.NotStarted

  def start(startWorker: (() => Unit) => Future[WorkerProcess]): Unit = {
    // The reader starts when the worker starts, so a worker Mill has not yet scheduled holds no reader thread.
    started = WorkerRun.abandoningOnThrow(abandon())(Try(startWorker(() => startReading())))
    started match {
      case Success(workerEnd) => workerEnd.onComplete(ended => reader.workerHasEnded(ended.getOrElse(WorkerProcess.Started)))(using ExecutionContext.parasitic)
      // A worker whose start threw may have started a process.
      case Failure(_) => reader.workerHasEnded(WorkerProcess.Started)
    }
  }

  def end(awaitWorker: Future[WorkerProcess] => Unit): WorkerRun.Ending = {
    val ended = WorkerRun.abandoningOnThrow(abandon()) {
      started.flatMap(workerEnd => Try(awaitWorker(workerEnd)).flatMap(_ => endOf(workerEnd)))
    }
    // Only a worker that ended normally has stopped Mill's threads pumping its output. A failed one, as when Mill
    // cancels the task, can leave its process and those threads running, so the capture's streams stay open.
    ended.foreach(_ => capture.endStreams())
    // A worker whose end is unknown may have started a process, unless it never started.
    reader.workerHasEnded(ended.getOrElse(WorkerProcess.Started))
    val startedUncaptured = ended.toOption.contains(WorkerProcess.Started) && startReadingLate()
    val reading = if (closeReader()) Try(reader.awaitStreamEnd()) else Success(())
    ended.failed.foreach(throwable => consolidation.addRootFailure(s"Starting $workerName failed: ${WorkerRun.unboxed(throwable)}"))
    if (startedUncaptured) {
      consolidation.addRootFailure(s"Capturing the output of $workerName failed: the worker ran outside the test command's capture, so its output went to Mill's console")
    }
    reading.failed.foreach(throwable => consolidation.addRootFailure(s"Reading the event log of $workerName failed: $throwable"))
    if (ended.isFailure) WorkerRun.Ending.FailedToStart else WorkerRun.Ending.Ended
  }

  def abandon(): Unit = {
    closeReader()
    reader.workerHasEnded(WorkerProcess.Started)
    stopCapturing()
  }

  private def startReading(): Unit =
    synchronized {
      if (readerState == WorkerRun.ReaderState.NotStarted) {
        reader.start()
        readerState = WorkerRun.ReaderState.Started
      }
    }

  private def startReadingLate(): Boolean =
    synchronized {
      val late = readerState == WorkerRun.ReaderState.NotStarted
      // The event log file outlives the worker's process, so a reader started after the worker has ended still reads
      // the whole run.
      startReading()
      late
    }

  private def closeReader(): Boolean =
    synchronized {
      // Once the reader is closed, a worker starting late, as when Mill runs an async body after the task is cancelled,
      // starts no reader.
      readerState = readerState.afterClosing
      readerState == WorkerRun.ReaderState.ClosedAfterStarting
    }

  private def stopCapturing(): Unit = capture.stopCapturing()

  private def endOf(workerEnd: Future[WorkerProcess]): Try[WorkerProcess] =
    workerEnd.value.getOrElse(Failure(new IllegalStateException(s"$workerName had not ended once awaited")))
}

private[mill] object WorkerRun {

  enum Ending {
    case Ended
    case FailedToStart
  }

  private enum ReaderState {
    case NotStarted
    case Started
    case ClosedBeforeStarting
    case ClosedAfterStarting

    def afterClosing: ReaderState =
      this match {
        case NotStarted | ClosedBeforeStarting => ClosedBeforeStarting
        case Started | ClosedAfterStarting => ClosedAfterStarting
      }
  }

  def startEach[A](workers: Seq[A])(start: A => WorkerRun): Seq[WorkerRun] =
    workers.foldLeft(Vector.empty[WorkerRun]) { (started, worker) =>
      started :+ abandoningOnThrow(started.foreach(_.abandon()))(start(worker))
    }

  def endEach(workerRuns: Seq[WorkerRun])(awaitWorker: Future[WorkerProcess] => Unit): Seq[Ending] =
    try {
      workerRuns.tails.toSeq.collect { case workerRun +: notYetEnded =>
        abandoningOnThrow(notYetEnded.foreach(_.abandon()))(workerRun.end(awaitWorker))
      }
    } finally {
      // A failed worker can go on writing output, which must not reach a run that has ended.
      workerRuns.foreach(_.stopCapturing())
    }

  private def unboxed(throwable: Throwable): Throwable =
    throwable match {
      // A Scala `Promise` boxes an `InterruptedException` or an `Error` in an `ExecutionException` whose message,
      // "Boxed Exception", hides the cause.
      case boxed: ExecutionException if boxed.getMessage == "Boxed Exception" => Option(boxed.getCause).getOrElse(boxed)
      case other => other
    }

  private def abandoningOnThrow[A](abandon: => Unit)(body: => A): A =
    try {
      body
    } catch {
      case throwable: Throwable =>
        abandon
        throw throwable
    }
}
