package org.scalatest.mill

import org.scalatest.consolidation.Consolidation
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Try

/**
 * One worker of Mill's test queue, from its start to its end, with the reader of its event log and the capture of its
 * output. What starting or running the worker, or reading its event log, threw is attached to the consolidation's
 * root as a failure naming the worker, so the module's other workers still run and the consolidation can still end and
 * write its output.
 *
 * @param workerName the worker and its module, as the failures name them
 */
private[mill] final class WorkerRun(consolidation: Consolidation, workerName: String, reader: EventLogReader, capture: WorkerOutputCapture) {

  private var started: Try[Future[Boolean]] = Failure(new IllegalStateException(s"$workerName was never started"))

  /**
   * Starts reading the worker's event log, then the worker. A fatal throwable from starting the worker is rethrown,
   * once the reader has ended.
   *
   * @param startWorker starts the worker, giving once it has ended whether it started a process
   */
  def start(startWorker: => Future[Boolean]): Unit = {
    reader.start()
    started =
      try {
        Try(startWorker)
      } catch {
        case fatal: Throwable =>
          endReading(startedAProcess = true)
          throw fatal
      }
  }

  /**
   * Waits for the worker to end, then for the reader to end the worker's run.
   *
   * @param awaitWorker returns once the worker has ended
   */
  def end(awaitWorker: Future[Boolean] => Unit): Unit = {
    val workerEnd = started.flatMap(worker => Try(awaitWorker(worker)).flatMap(_ => endOf(worker)))
    // A worker whose end is unknown may have started a process.
    val reading = endReading(startedAProcess = workerEnd.getOrElse(true))
    workerEnd.failed.foreach(throwable => consolidation.addRootFailure(s"Starting $workerName failed: $throwable"))
    reading.failed.foreach(throwable => consolidation.addRootFailure(s"Reading the event log of $workerName failed: $throwable"))
  }

  private def endOf(worker: Future[Boolean]): Try[Boolean] =
    worker.value.getOrElse(Failure(new IllegalStateException(s"$workerName had not ended once awaited")))

  private def endReading(startedAProcess: Boolean): Try[Unit] = {
    capture.endStreams()
    Try(reader.awaitStreamEnd(startedAProcess))
  }
}
