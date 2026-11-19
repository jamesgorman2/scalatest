package org.scalatest.mill

import java.time.Instant
import mill.api.TaskCtx
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import scala.concurrent.duration.DurationInt
import scala.util.Success
import scala.util.Try

/** Runs the module's tests through Mill's test queue, in one worker for each fork group, one worker after another. */
private[mill] final class JvmTestModuleRun(
    label: TestRunLabel,
    inputs: JvmTestModuleInputs,
    scalaTestArguments: Seq[String],
    moduleFolder: os.Path,
    ctx: TaskCtx
) {

  /**
   * Times the module from just before its first worker starts until its last worker has ended and its event log is read.
   * Where two workers would share a `processFolder`, or checking whether the filesystem ignores case fails, none starts.
   * A failure naming the module is attached to the root, and the module stays in the output with no times and no run.
   */
  def run(consolidation: Consolidation): Unit = {
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    QueueWorker
      .forEachForkGroup(inputs.forkGroups, discoveredTestClasses(), () => filesystemIgnoresCase())
      .fold(
        reason => {
          consolidation.addRootFailure(s"Laying out the workers of ${label.value} failed: $reason")
          consolidation.timeModule(label, None, None)
        },
        workers => runWorkers(consolidation, workers)
      )
  }

  private def runWorkers(consolidation: Consolidation, workers: Seq[QueueWorker]): Unit = {
    val queues = JvmTestModuleRun.layOutQueues(consolidation, label, workers)(layOutQueue)
    val startedAt = Instant.now()
    queues.foreach { case (worker, queue) => runWorker(consolidation, worker, queue) }
    consolidation.timeModule(label, Some(startedAt), Some(Instant.now()))
  }

  private def discoveredTestClasses(): Set[String] =
    inputs.jvmWorker
      .apply(
        ZincOp.GetTestTasks(
          (inputs.runClasspath ++ inputs.testrunnerEntrypointClasspath).map(_.path),
          inputs.testClasspath.map(_.path),
          inputs.testFramework,
          Nil,
          scalaTestArguments,
          inputs.discoveredClasses
        ),
        javaHome = inputs.javaHome
      )(using ctx)
      .toSet

  private def filesystemIgnoresCase(): Either[String, Boolean] =
    Try {
      val probe = moduleFolder / "case-probe"
      os.write(probe, "")
      try {
        os.exists(moduleFolder / "CASE-PROBE")
      } finally {
        os.remove(probe)
      }
    }.toEither.left.map(throwable => s"checking whether the filesystem ignores case failed: $throwable")

  private def processFolder(worker: QueueWorker): os.Path = moduleFolder / worker.processFolder

  private def layOutQueue(worker: QueueWorker): os.Path =
    inputs.testModuleUtil(scalaTestArguments, ctx).prepareTestClassesFolder(worker.testClasses, processFolder(worker))

  private def runWorker(consolidation: Consolidation, worker: QueueWorker, queue: os.Path): Unit = {
    val eventLogFile = processFolder(worker) / JvmTestModuleRun.eventLogFileName
    val capture = new WorkerOutputCapture(WorkerOutputCapture.workerCharset)
    val reader = new EventLogReader(
      eventLogFile.toNIO,
      () => {
        val feed = consolidation.addRun(label, Some(worker.forkGroupAndIndex))
        capture.addTo(feed.add)
        feed
      },
      () => consolidation.addProcessEndedBeforeAnyRun(),
      ProcessExit.hasExited,
      10.millis
    )
    JvmTestModuleRun.runWorker(consolidation, worker.describedIn(label), reader, capture)(startWorker(worker, queue, eventLogFile, capture))
  }

  // The worker's result is ignored, even a failure: the consolidation decides the test command's result.
  // Mill's call spawns the worker on this thread, reading `os.Inherit` here, and returns once both streams are read.
  private def startWorker(worker: QueueWorker, queue: os.Path, eventLogFile: os.Path, capture: WorkerOutputCapture): Unit = {
    val folder = processFolder(worker)
    val util = inputs.testModuleUtil(scalaTestArguments ++ RunnerArgument.forEventLogFile(eventLogFile.toNIO), ctx)
    val claimFolder = folder / "claim"
    os.makeDir.all(claimFolder)
    os.Inherit.out.withValue(os.ProcessOutput.ReadBytes(capture.stdout)) {
      os.Inherit.err.withValue(os.ProcessOutput.ReadBytes(capture.stderr)) {
        util.callTestRunnerSubprocess(folder, folder / "result.log", Right((None, queue, claimFolder)), () => ())(using ctx)
      }
    }
  }
}

private[mill] object JvmTestModuleRun {

  /** Mill writes no file of this name in a worker's `processFolder`. */
  val eventLogFileName: String = "scalatest-event-log.jsonl"

  /**
   * Lays out each worker's queue, in order, before any worker starts. What laying out a worker's queue throws is attached
   * to the consolidation's root as a failure naming the worker, and that worker is left out, so the module's other workers
   * still run.
   *
   * @return each worker whose queue was laid out, with its queue
   */
  def layOutQueues[Queue](consolidation: Consolidation, module: TestRunLabel, workers: Seq[QueueWorker])(
      layOutQueue: QueueWorker => Queue
  ): Seq[(QueueWorker, Queue)] =
    workers.flatMap { worker =>
      Try(layOutQueue(worker)).fold(
        throwable => {
          consolidation.addRootFailure(s"Laying out the queue of ${worker.describedIn(module)} failed: $throwable")
          None
        },
        queue => Some(worker -> queue)
      )
    }

  /**
   * What starting the worker or reading its event log threw is attached to the consolidation's root as a failure naming
   * the worker, so the module's other workers still run and the consolidation can still end and write its output. A
   * fatal throwable from starting the worker is rethrown instead, once the reader has ended.
   *
   * @param workerName the worker and its module, as the failure names them
   */
  def runWorker(consolidation: Consolidation, workerName: String, reader: EventLogReader, capture: WorkerOutputCapture)(
      startWorker: => Unit
  ): Unit = {
    reader.start()
    var reading: Try[Unit] = Success(())
    val workerStart =
      try {
        Try {
          try {
            startWorker
          } finally {
            capture.endStreams()
          }
        }
      } finally {
        reading = Try(reader.awaitStreamEnd())
      }
    workerStart.failed.foreach(throwable => consolidation.addRootFailure(s"Starting $workerName failed: $throwable"))
    reading.failed.foreach(throwable => consolidation.addRootFailure(s"Reading the event log of $workerName failed: $throwable"))
  }
}
