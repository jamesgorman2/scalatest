package org.scalatest.mill

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import mill.api.TaskCtx
import mill.javalib.TestModuleUtil
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.consolidation.ScalaTestArgument
import org.scalatest.consolidation.TestClassName
import org.scalatest.consolidation.TestRunLabel
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[mill] final class JvmTestModuleRun(
    label: TestRunLabel,
    inputs: JvmTestModuleInputs,
    scalaTestArguments: Seq[ScalaTestArgument.Flagged],
    moduleFolder: os.Path,
    ctx: TaskCtx,
    now: () => Instant,
    workerCharset: Charset
) {

  private val scalaTestArgumentStrings: Seq[String] = scalaTestArguments.flatMap(_.arguments)

  private val forkGroups: Seq[Seq[TestClassName]] = inputs.forkGroups.map(_.map(TestClassName(_)))

  def execute(consolidation: Consolidation): Unit = {
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    JvmTestModuleRun.layOutWorkers(consolidation, label)(launcher()).foreach(launcher => runWorkers(consolidation, launcher))
  }

  private def launcher(): Either[ForkGroupsShareProcessFolders, WorkerLauncher] = {
    val filesystemIgnoresCase = () => JvmTestModuleRun.filesystemIgnoresCase(moduleFolder.toNIO)
    // As Mill 1.3.1 does, batch workers run each fork group's classes as `testForkGrouping` lists them, without
    // discovery.
    if (inputs.testBatchFrameworkTasks)
      WorkerLayout.ofBatches(forkGroups, filesystemIgnoresCase).map(new BatchLauncher(_))
    else
      WorkerLayout
        .ofQueue(forkGroups, discoveredTestClasses(), inputs.testParallelism, ctx.jobs, filesystemIgnoresCase)
        .map(new QueueLauncher(_))
  }

  private def runWorkers(consolidation: Consolidation, launcher: WorkerLauncher): Unit = {
    val laidOutForkGroups = JvmTestModuleRun.layOutForkGroups(consolidation, label, launcher.layout.forkGroups)(launcher.layOut)
    val startedAt = now()
    val workers = laidOutForkGroups.flatMap { case (forkGroup, laidOut) => forkGroup.workers.map(plannedWorker => (forkGroup, laidOut, plannedWorker)) }
    val workerRuns = WorkerRun.startEach(workers) { case (forkGroup, laidOut, plannedWorker) =>
      startWorker(consolidation, plannedWorker)((workerCtx, testModuleUtil) => launcher.launch(forkGroup, laidOut, plannedWorker, workerCtx, testModuleUtil))
    }
    WorkerRun.endEach(workerRuns)(workerEnd => ctx.fork.blocking(Await.ready(workerEnd, Duration.Inf)))
    consolidation.timeModule(label, Some(startedAt), Some(now()))
  }

  private def discoveredTestClasses(): Set[TestClassName] =
    inputs.jvmWorker
      .apply(
        ZincOp.GetTestTasks(
          (inputs.runClasspath ++ inputs.testrunnerEntrypointClasspath).map(_.path),
          inputs.testClasspath.map(_.path),
          inputs.testFramework,
          Nil,
          scalaTestArgumentStrings,
          inputs.discoveredClasses
        ),
        javaHome = inputs.javaHome
      )(using ctx)
      .map(TestClassName(_))
      .toSet

  private def resultLog(groupFolder: os.Path): os.Path = groupFolder / "result.log"

  private def inModuleFolder(path: PathUnderModule): os.Path = moduleFolder / path.segments

  private def startWorker(consolidation: Consolidation, plannedWorker: PlannedWorker)(
      runWorker: (WorkerTaskCtx, TestModuleUtil) => Future[WorkerProcess]
  ): WorkerRun = {
    val eventLogFile = inModuleFolder(plannedWorker.processFolder) / JvmTestModuleRun.eventLogFileName
    val capture = new WorkerOutputCapture(workerCharset)
    val reader = new EventLogReader(
      EventLogFile.at(eventLogFile.toNIO),
      readRun =>
        consolidation.addRun(label, Some(plannedWorker.worker)) { feed =>
          capture.addTo(feed.add)
          readRun(feed)
        },
      () => consolidation.addProcessEndedBeforeAnyRun(),
      ProcessExit.hasExited,
      10.millis
    )
    val workerRun = new WorkerRun(consolidation, plannedWorker.describedIn(label), reader, capture)
    workerRun.start { workerStarting =>
      val workerCtx = new WorkerTaskCtx(ctx, capture, workerStarting)
      runWorker(workerCtx, inputs.testModuleUtil(scalaTestArgumentStrings ++ RunnerArgument.forEventLogFile(eventLogFile.toNIO), workerCtx))
    }
    workerRun
  }

  private sealed trait WorkerLauncher {

    def layout: WorkerLayout

    type LaidOut

    def layOut(forkGroup: PlannedForkGroup): LaidOut

    def launch(
        forkGroup: PlannedForkGroup,
        laidOut: LaidOut,
        plannedWorker: PlannedWorker,
        workerCtx: WorkerTaskCtx,
        testModuleUtil: TestModuleUtil
    ): Future[WorkerProcess]
  }

  private final class QueueLauncher(val layout: WorkerLayout) extends WorkerLauncher {

    type LaidOut = os.Path

    def layOut(forkGroup: PlannedForkGroup): os.Path =
      inputs.testModuleUtil(scalaTestArgumentStrings, ctx).prepareTestClassesFolder(forkGroup.testClasses.map(_.value), inModuleFolder(forkGroup.folder))

    def launch(
        forkGroup: PlannedForkGroup,
        queue: os.Path,
        plannedWorker: PlannedWorker,
        workerCtx: WorkerTaskCtx,
        testModuleUtil: TestModuleUtil
    ): Future[WorkerProcess] = {
      val groupFolder = inModuleFolder(forkGroup.folder)
      def joinQueue() =
        testModuleUtil.runTestFuture(
          layout.testClassCount,
          layout.forkGroups.size,
          groupFolder,
          queue,
          groupFolder.last,
          forkGroup.workerIndexDigits,
          forkGroup.label.value,
          plannedWorker.worker.index.value
        )
      // Mill forks a worker through `workerCtx` only when the worker has its own `processFolder`; a worker in the fork
      // group's folder runs on this thread.
      val (_, workerEnd) = if (plannedWorker.processFolder == forkGroup.folder) workerCtx.runOnThisThread(joinQueue()) else joinQueue()
      // The `Result` Mill gives for the process is ignored, even a failure: the consolidation decides the test command's
      // result.
      workerEnd.map { case (_, _, processResult) =>
        if (processResult.isDefined) WorkerProcess.Started else WorkerProcess.NotStarted
      }(using ExecutionContext.parasitic)
    }
  }

  private final class BatchLauncher(val layout: WorkerLayout) extends WorkerLauncher {

    type LaidOut = JvmTestModuleRun.BatchSelector

    def layOut(forkGroup: PlannedForkGroup): JvmTestModuleRun.BatchSelector = {
      val groupFolder = inModuleFolder(forkGroup.folder)
      os.write.over(resultLog(groupFolder), upickle.write((0L, 0L)), createFolders = true)
      // As Mill 1.3.1 does: an empty class list would select every class.
      if (forkGroup.testClasses.isEmpty) {
        val queue = inputs.testModuleUtil(scalaTestArgumentStrings, ctx).prepareTestClassesFolder(Nil, groupFolder)
        val claimFolder = groupFolder / "claim"
        os.makeDir.all(claimFolder)
        JvmTestModuleRun.BatchSelector.EmptyQueue(queue, claimFolder)
      } else {
        JvmTestModuleRun.BatchSelector.ClassList(forkGroup.testClasses)
      }
    }

    def launch(
        forkGroup: PlannedForkGroup,
        selector: JvmTestModuleRun.BatchSelector,
        plannedWorker: PlannedWorker,
        workerCtx: WorkerTaskCtx,
        testModuleUtil: TestModuleUtil
    ): Future[WorkerProcess] = {
      val groupFolder = inModuleFolder(forkGroup.folder)
      def runProcess(): WorkerProcess = {
        // The `Result` Mill gives for the process is ignored, even a failure: the consolidation decides the test
        // command's result.
        testModuleUtil.callTestRunnerSubprocess(groupFolder, resultLog(groupFolder), selector.toMill, () => ())(using workerCtx)
        WorkerProcess.Started
      }
      // As Mill 1.3.1 does, each fork group's worker is forked when there is more than one fork group, whatever the
      // module's `testParallelism`.
      if (layout.forkGroups.size > 1)
        workerCtx.fork.async(groupFolder, forkGroup.label.value, "", priority = -1)(_ => runProcess())(using workerCtx)
      else
        workerCtx.runOnThisThread(Future.successful(runProcess()))
    }
  }
}

private[mill] object JvmTestModuleRun {

  // Mill writes no file of this name in a worker's `processFolder`.
  val eventLogFileName: String = "scalatest-event-log.jsonl"

  enum BatchSelector {
    case ClassList(testClasses: Seq[TestClassName])
    case EmptyQueue(queue: os.Path, claimFolder: os.Path)

    def toMill: Either[Seq[String], (Option[String], os.Path, os.Path)] =
      this match {
        case ClassList(testClasses) => Left(testClasses.map(_.value))
        case EmptyQueue(queue, claimFolder) => Right((None, queue, claimFolder))
      }
  }

  final class FilesystemCaseCheckFailure(cause: IOException)
      extends Exception(s"checking whether the filesystem ignores case failed: $cause", cause)

  def filesystemIgnoresCase(folder: Path): Boolean =
    try {
      val probe = Files.createFile(folder.resolve("case-probe"))
      try {
        Files.exists(folder.resolve("CASE-PROBE"))
      } finally {
        Files.delete(probe)
      }
    } catch {
      case failure: IOException => throw new FilesystemCaseCheckFailure(failure)
    }

  def layOutWorkers[Launcher](consolidation: Consolidation, label: TestRunLabel)(
      layOut: => Either[ForkGroupsShareProcessFolders, Launcher]
  ): Option[Launcher] = {
    val laidOut =
      try {
        layOut
      } catch {
        case failure: FilesystemCaseCheckFailure => Left(failure)
      }
    laidOut.fold(
      failure => {
        consolidation.addRootFailure(s"Laying out the workers of ${label.value} failed: ${failure.getMessage}")
        consolidation.timeModule(label, None, None)
        None
      },
      launcher => Some(launcher)
    )
  }

  def layOutForkGroups[LaidOut](consolidation: Consolidation, label: TestRunLabel, forkGroups: Seq[PlannedForkGroup])(
      layOut: PlannedForkGroup => LaidOut
  ): Seq[(PlannedForkGroup, LaidOut)] = {
    val attempts = forkGroups.map(forkGroup => forkGroup -> Try(layOut(forkGroup)))
    attempts.foreach {
      case (forkGroup, Failure(throwable)) => consolidation.addRootFailure(s"Laying out ${forkGroup.describedIn(label)} failed: $throwable")
      case (_, Success(_)) => ()
    }
    attempts.collect { case (forkGroup, Success(laidOut)) => forkGroup -> laidOut }
  }
}
