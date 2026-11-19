package org.scalatest.mill

import java.util.concurrent.ExecutionException
import org.scalatest.consolidation.Consolidation
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/**
 * One worker of a JVM test module, from its start to its end, with the reader of its event log and the capture of its
 * output. The reader polls only from the worker's start until the worker ends, so a worker Mill has not yet scheduled, or
 * has finished, holds no polling reader thread.
 * What starting or running the worker, or reading its event log, threw is attached to the consolidation's root as a
 * failure naming the worker, so the module's other workers still run and the consolidation can still end and write its
 * output.
 *
 * @param workerName the worker and its module, as the failures name them
 */
private[mill] final class WorkerRun(consolidation: Consolidation, workerName: String, reader: EventLogReader, capture: WorkerOutputCapture) {

  private var started: Try[Future[Boolean]] = Failure(new IllegalStateException(s"$workerName was never started"))
  private var readerStarted: Boolean = false
  private var readerClosed: Boolean = false

  /**
   * Starts the worker, which starts reading the worker's event log as it starts. A fatal throwable from starting the
   * worker is rethrown, once the reader is abandoned.
   *
   * @param startWorker starts the worker, giving once it has ended whether it started a process. It is given what to
   *                    call as the worker starts, on whichever thread runs it.
   */
  def start(startWorker: (() => Unit) => Future[Boolean]): Unit = {
    started =
      try {
        Try(startWorker(() => startReading()))
      } catch {
        case fatal: Throwable =>
          abandon()
          throw fatal
      }
    started.foreach(_.onComplete(workerEnd => reader.workerHasEnded(startedAProcess = workerEnd.getOrElse(true)))(using ExecutionContext.parasitic))
  }

  /**
   * Waits for the worker to end, then for the reader to end the worker's run. A worker that started a process without
   * starting its reader, so ran with its output uncaptured, has its event log read then, and a failure naming it is
   * attached to the root. A fatal throwable from waiting is rethrown, once the reader is abandoned.
   *
   * @param awaitWorker returns once the worker has ended
   */
  def end(awaitWorker: Future[Boolean] => Unit): Unit = {
    val ended =
      try {
        started.flatMap(workerEnd => Try(awaitWorker(workerEnd)).flatMap(_ => endOf(workerEnd)))
      } catch {
        case fatal: Throwable =>
          abandon()
          throw fatal
      }
    // Only a worker that ended normally has stopped Mill's threads pumping its output. A failed one, as where Mill
    // cancels the task, can leave its process and those threads running, so its streams are left open until
    // `WorkerRun.endEach` stops capturing them.
    ended.foreach(_ => capture.endStreams())
    // A worker whose end is unknown may have started a process, unless it never started.
    reader.workerHasEnded(startedAProcess = ended.getOrElse(true))
    val startedUncaptured = ended.getOrElse(false) && startReadingLate()
    val reading = if (closeReader()) Try(reader.awaitStreamEnd()) else Success(())
    ended.failed.foreach(throwable => consolidation.addRootFailure(s"Starting $workerName failed: ${WorkerRun.unboxed(throwable)}"))
    if (startedUncaptured) {
      consolidation.addRootFailure(s"Capturing the output of $workerName failed: the worker ran outside the test command's capture, so its output went to Mill's console")
    }
    reading.failed.foreach(throwable => consolidation.addRootFailure(s"Reading the event log of $workerName failed: $throwable"))
  }

  /**
   * Lets the reader end without the worker being awaited, and stops capturing the worker's output. Used where a worker
   * threw a fatal throwable.
   */
  def abandon(): Unit = {
    closeReader()
    reader.workerHasEnded(startedAProcess = true)
    stopCapturing()
  }

  private def startReading(): Unit =
    synchronized {
      if (!readerStarted && !readerClosed) {
        reader.start()
        readerStarted = true
      }
    }

  // Gives whether the reader was started now. The event log file outlives the worker's process, so a reader started
  // after the worker has ended still reads the whole run.
  private def startReadingLate(): Boolean =
    synchronized {
      val late = !readerStarted && !readerClosed
      startReading()
      late
    }

  // Once closed, a worker starting late, as where Mill runs an async body after the task is cancelled, starts no reader.
  // Gives whether the reader was started.
  private def closeReader(): Boolean =
    synchronized {
      readerClosed = true
      readerStarted
    }

  private def stopCapturing(): Unit = capture.stopCapturing()

  private def endOf(workerEnd: Future[Boolean]): Try[Boolean] =
    workerEnd.value.getOrElse(Failure(new IllegalStateException(s"$workerName had not ended once awaited")))
}

private[mill] object WorkerRun {

  /**
   * Starts each worker in turn. A fatal throwable from starting one is rethrown, once the workers already started are
   * abandoned.
   */
  def startEach[Worker](workers: Seq[Worker])(start: Worker => WorkerRun): Seq[WorkerRun] =
    workers.foldLeft(Vector.empty[WorkerRun]) { (started, worker) =>
      try {
        started :+ start(worker)
      } catch {
        case fatal: Throwable =>
          started.foreach(_.abandon())
          throw fatal
      }
    }

  /**
   * Ends each worker in turn, then stops capturing every worker's output, so output a failed worker goes on writing
   * reaches no ended run. A fatal throwable from ending one is rethrown, once the workers not yet ended are abandoned.
   *
   * @param awaitWorker returns once the worker has ended
   */
  def endEach(workerRuns: Seq[WorkerRun])(awaitWorker: Future[Boolean] => Unit): Unit =
    try {
      workerRuns.tails.foreach {
        case workerRun +: notYetEnded =>
          try {
            workerRun.end(awaitWorker)
          } catch {
            case fatal: Throwable =>
              notYetEnded.foreach(_.abandon())
              throw fatal
          }
        case _ => ()
      }
    } finally {
      workerRuns.foreach(_.stopCapturing())
    }

  // A Scala Promise fails with an InterruptedException or an Error boxed in an ExecutionException with this message,
  // which names neither.
  private def unboxed(throwable: Throwable): Throwable =
    throwable match {
      case boxed: ExecutionException if boxed.getMessage == "Boxed Exception" && boxed.getCause != null => boxed.getCause
      case other => other
    }
}
