package org.scalatest.mill

import java.time.Instant
import mill.api.TaskCtx
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.TestRunLabel
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * Runs the module's tests through Mill's test queue, in the workers Mill 1.3.1's `testForked` starts for each fork group
 * holding a test class: in parallel where the module's `testParallelism` is on and other than exactly one class is
 * queued, else one after another.
 */
private[mill] final class JvmTestModuleRun(
    label: TestRunLabel,
    inputs: JvmTestModuleInputs,
    scalaTestArguments: Seq[String],
    moduleFolder: os.Path,
    ctx: TaskCtx
) {

  /**
   * Times the module from just before its first worker starts until its last worker has ended and its event log is read.
   * Where two fork groups would share a folder, or checking whether the filesystem ignores case fails, no worker starts.
   * A failure naming the module is attached to the root, and the module stays in the output with no times and no run.
   */
  def run(consolidation: Consolidation): Unit = {
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    QueueLayout
      .of(inputs.forkGroups, discoveredTestClasses(), inputs.testParallelism, ctx.jobs, () => filesystemIgnoresCase())
      .fold(
        reason => {
          consolidation.addRootFailure(s"Laying out the workers of ${label.value} failed: $reason")
          consolidation.timeModule(label, None, None)
        },
        layout => runWorkers(consolidation, layout)
      )
  }

  private def runWorkers(consolidation: Consolidation, layout: QueueLayout): Unit = {
    val queues = JvmTestModuleRun.layOutQueues(consolidation, label, layout.forkGroups)(layOutQueue)
    val startedAt = Instant.now()
    val workers = queues.flatMap { case (forkGroup, queue) => forkGroup.workers.map(worker => (forkGroup, queue, worker)) }
    val workerRuns = WorkerRun.startEach(workers) { case (forkGroup, queue, worker) => startWorker(consolidation, layout, forkGroup, queue, worker) }
    WorkerRun.endEach(workerRuns)(workerEnd => ctx.fork.blocking(Await.ready(workerEnd, Duration.Inf)))
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

  private def layOutQueue(forkGroup: QueueForkGroup): os.Path =
    inputs.testModuleUtil(scalaTestArguments, ctx).prepareTestClassesFolder(forkGroup.testClasses, moduleFolder / forkGroup.folder)

  private def startWorker(consolidation: Consolidation, layout: QueueLayout, forkGroup: QueueForkGroup, queue: os.Path, worker: QueueWorker): WorkerRun = {
    val eventLogFile = moduleFolder / worker.processFolder / JvmTestModuleRun.eventLogFileName
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
    val workerRun = new WorkerRun(consolidation, worker.describedIn(label), reader, capture)
    workerRun.start(runOnQueue(layout, forkGroup, queue, worker, eventLogFile, capture))
    workerRun
  }

  // Mill's own test queue starts the worker: on this thread, ending before it returns, where it runs the fork group's
  // workers one after another, else on a future forked through the given `TaskCtx`. The worker's result is ignored,
  // even a failure: the consolidation decides the test command's result.
  private def runOnQueue(
      layout: QueueLayout,
      forkGroup: QueueForkGroup,
      queue: os.Path,
      worker: QueueWorker,
      eventLogFile: os.Path,
      capture: WorkerOutputCapture
  ): Future[Boolean] = {
    val workerCtx = new WorkerOutputTaskCtx(ctx, capture)
    val util = inputs.testModuleUtil(scalaTestArguments ++ RunnerArgument.forEventLogFile(eventLogFile.toNIO), workerCtx)
    val groupFolder = moduleFolder / forkGroup.folder
    val (_, workerEnd) = workerCtx.capturing {
      util.runTestFuture(
        layout.testClassCount,
        layout.forkGroups.size,
        groupFolder,
        queue,
        groupFolder.last,
        forkGroup.workerIndexLength,
        forkGroup.label,
        worker.forkGroupAndIndex.index.value
      )
    }
    workerEnd.map { case (_, _, processResult) => processResult.isDefined }(using ExecutionContext.parasitic)
  }
}

private[mill] object JvmTestModuleRun {

  /** Mill writes no file of this name in a worker's `processFolder`. */
  val eventLogFileName: String = "scalatest-event-log.jsonl"

  /**
   * Lays out each fork group's queue, in order, before any worker starts. What laying out a group's queue throws is
   * attached to the consolidation's root as a failure naming the fork group, and that group's workers are left out, so
   * the module's other groups still run.
   *
   * @return each fork group whose queue was laid out, with its queue
   */
  def layOutQueues[Queue](consolidation: Consolidation, module: TestRunLabel, forkGroups: Seq[QueueForkGroup])(
      layOutQueue: QueueForkGroup => Queue
  ): Seq[(QueueForkGroup, Queue)] =
    forkGroups.flatMap { forkGroup =>
      Try(layOutQueue(forkGroup)).fold(
        throwable => {
          consolidation.addRootFailure(s"Laying out the queue of ${forkGroup.describedIn(module)} failed: $throwable")
          None
        },
        queue => Some(forkGroup -> queue)
      )
    }
}
