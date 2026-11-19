package org.scalatest.mill

import java.time.Instant
import mill.api.TaskCtx
import mill.javalib.TestModuleUtil
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
 * Runs the module's tests in the workers Mill 1.3.1's `testForked` starts for each fork group holding a test class.
 *
 * Where the module does not set `testBatchFrameworkTasks`, the workers run through Mill's test queue: in parallel where
 * the module's `testParallelism` is on, unless exactly one class is queued; else one after another. Where it does, each
 * fork group has one worker, given the group's whole class list. The workers run in parallel where more than one fork
 * group holds a class, whatever the module's `testParallelism`.
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
    layout().fold(
      reason => {
        consolidation.addRootFailure(s"Laying out the workers of ${label.value} failed: $reason")
        consolidation.timeModule(label, None, None)
      },
      layout =>
        if (inputs.testBatchFrameworkTasks)
          runWorkers(consolidation, layout)(layOutBatch)((forkGroup, selector, _) => runBatch(layout)(forkGroup, selector))
        else
          runWorkers(consolidation, layout)(layOutQueue)(runOnQueue(layout))
    )
  }

  // Like Mill 1.3.1, the test command runs no discovery for a module that sets `testBatchFrameworkTasks`: each fork
  // group's worker is given the group's classes as `testForkGrouping` gives them.
  private def layout(): Either[String, WorkerLayout] =
    if (inputs.testBatchFrameworkTasks)
      WorkerLayout.ofBatches(inputs.forkGroups, () => filesystemIgnoresCase())
    else
      WorkerLayout.ofQueue(inputs.forkGroups, discoveredTestClasses(), inputs.testParallelism, ctx.jobs, () => filesystemIgnoresCase())

  private def runWorkers[LaidOut](consolidation: Consolidation, layout: WorkerLayout)(layOut: PlannedForkGroup => LaidOut)(
      runWorker: (PlannedForkGroup, LaidOut, PlannedWorker) => (WorkerTaskCtx, TestModuleUtil) => Future[Boolean]
  ): Unit = {
    val laidOutForkGroups = JvmTestModuleRun.layOutForkGroups(consolidation, label, layout.forkGroups)(layOut)
    val startedAt = Instant.now()
    val workers = laidOutForkGroups.flatMap { case (forkGroup, laidOut) => forkGroup.workers.map(worker => (forkGroup, laidOut, worker)) }
    val workerRuns = WorkerRun.startEach(workers) { case (forkGroup, laidOut, worker) =>
      startWorker(consolidation, worker)(runWorker(forkGroup, laidOut, worker))
    }
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

  private def layOutQueue(forkGroup: PlannedForkGroup): os.Path =
    inputs.testModuleUtil(scalaTestArguments, ctx).prepareTestClassesFolder(forkGroup.testClasses, moduleFolder / forkGroup.folder)

  // As Mill 1.3.1 does, a fork group with no class is given an empty queue, not an empty class list, so the framework's
  // setup and teardown still run without every class being selected.
  private def layOutBatch(forkGroup: PlannedForkGroup): JvmTestModuleRun.BatchSelector = {
    val groupFolder = moduleFolder / forkGroup.folder
    os.write.over(resultLog(groupFolder), upickle.write((0L, 0L)), createFolders = true)
    if (forkGroup.testClasses.isEmpty) {
      val queue = inputs.testModuleUtil(scalaTestArguments, ctx).prepareTestClassesFolder(Nil, groupFolder)
      val claimFolder = groupFolder / "claim"
      os.makeDir.all(claimFolder)
      Right((None, queue, claimFolder))
    } else {
      Left(forkGroup.testClasses)
    }
  }

  private def resultLog(groupFolder: os.Path): os.Path = groupFolder / "result.log"

  private def startWorker(consolidation: Consolidation, worker: PlannedWorker)(
      runWorker: (WorkerTaskCtx, TestModuleUtil) => Future[Boolean]
  ): WorkerRun = {
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
    workerRun.start { workerStarting =>
      val workerCtx = new WorkerTaskCtx(ctx, capture, workerStarting)
      runWorker(workerCtx, inputs.testModuleUtil(scalaTestArguments ++ RunnerArgument.forEventLogFile(eventLogFile.toNIO), workerCtx))
    }
    workerRun
  }

  // Mill's own test queue starts the worker. Where it runs the fork group's workers one after another, the worker runs
  // on this thread and ends before this returns; otherwise it runs on a future forked through the given `TaskCtx`. Mill
  // forks exactly the workers it gives a folder of their own. Only whether the worker started a process is kept. The
  // `Result` Mill gives for that process is ignored, even a failure, because the consolidation decides the test
  // command's result.
  private def runOnQueue(layout: WorkerLayout)(forkGroup: PlannedForkGroup, queue: os.Path, worker: PlannedWorker)(
      workerCtx: WorkerTaskCtx,
      util: TestModuleUtil
  ): Future[Boolean] = {
    val groupFolder = moduleFolder / forkGroup.folder
    def joinQueue() =
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
    val (_, workerEnd) = if (worker.processFolder == forkGroup.folder) workerCtx.runOnThisThread(joinQueue()) else joinQueue()
    workerEnd.map { case (_, _, processResult) => processResult.isDefined }(using ExecutionContext.parasitic)
  }

  // As Mill 1.3.1 does, the fork group's one worker is forked through the given `TaskCtx` where the module has more than
  // one fork group holding a class, and otherwise runs on this thread. A batch worker always starts a process. The
  // `Result` Mill gives for that process is ignored, as in [[runOnQueue]].
  private def runBatch(layout: WorkerLayout)(forkGroup: PlannedForkGroup, selector: JvmTestModuleRun.BatchSelector)(
      workerCtx: WorkerTaskCtx,
      util: TestModuleUtil
  ): Future[Boolean] = {
    val groupFolder = moduleFolder / forkGroup.folder
    def runProcess(): Boolean = {
      util.callTestRunnerSubprocess(groupFolder, resultLog(groupFolder), selector, () => ())(using workerCtx)
      true
    }
    if (layout.forkGroups.size > 1)
      workerCtx.fork.async(groupFolder, forkGroup.label, "", priority = -1)(_ => runProcess())(using workerCtx)
    else
      workerCtx.runOnThisThread(Future.successful(runProcess()))
  }
}

private[mill] object JvmTestModuleRun {

  /** Mill writes no file of this name in a worker's `processFolder`. */
  val eventLogFileName: String = "scalatest-event-log.jsonl"

  /**
   * What a batch worker is given in place of Mill's test queue, in the form Mill's `callTestRunnerSubprocess` takes: its
   * fork group's class list or, where the group holds no class, an empty queue and a claim folder.
   */
  type BatchSelector = Either[Seq[String], (Option[String], os.Path, os.Path)]

  /**
   * Lays out each fork group's files, in order, before any worker starts: its queue, or its batch worker's files. What
   * laying out a group throws is attached to the consolidation's root as a failure naming the fork group, and that
   * group's workers are left out, so the module's other groups still run.
   *
   * @return each fork group that was laid out, with what laying it out gave
   */
  def layOutForkGroups[LaidOut](consolidation: Consolidation, module: TestRunLabel, forkGroups: Seq[PlannedForkGroup])(
      layOut: PlannedForkGroup => LaidOut
  ): Seq[(PlannedForkGroup, LaidOut)] =
    forkGroups.flatMap { forkGroup =>
      Try(layOut(forkGroup)).fold(
        throwable => {
          consolidation.addRootFailure(s"Laying out ${forkGroup.describedIn(module)} failed: $throwable")
          None
        },
        laidOut => Some(forkGroup -> laidOut)
      )
    }
}
