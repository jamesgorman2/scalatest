package org.scalatest.mill

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import mill.api.Result
import mill.api.TaskCtx
import mill.javalib.TestModuleUtil
import mill.javalib.api.internal.ZincOp
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.RunProcess
import org.scalatest.consolidation.RunnerArgument
import org.scalatest.events.TestClassName
import org.scalatest.mill.WorkerLayoutFailure.FilesystemCaseNotChecked
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[mill] final class JvmTestModuleRun(
    module: ModuleName,
    inputs: JvmTestModuleInputs,
    runArguments: RunArguments,
    classGlobs: Seq[ClassGlob],
    moduleFolder: os.Path,
    commandCtx: TaskCtx,
    now: () => Instant,
    workerCharset: Charset,
    eventLogFileAt: Path => EventLogFile,
    processHasExited: RunProcess => Boolean,
    eventLogPollInterval: FiniteDuration,
    filesystemIgnoresCase: Path => Either[FilesystemCaseNotChecked, Boolean]
) {

  // The test command places every worker path in the module folder itself. `dest` points there too, so anything Mill
  // derives from `Task.dest` also lands there. In Mill 1.3.1 only test discovery reads `dest`, and it writes nothing
  // there, so no test can show whether `dest` is the module folder or the command's own.
  private val ctx: TaskCtx = new ModuleFolderTaskCtx(commandCtx, moduleFolder)

  private val forkGroups: Seq[Seq[TestClassName]] = inputs.forkGroups.map(_.map(TestClassName(_)))

  def execute(consolidation: Consolidation): Unit =
    JvmTestModuleRun.launcherOrReportFailure(consolidation, module, layOutWorkers()).foreach(launcher => runWorkers(consolidation, launcher))

  private def layOutWorkers(): Either[WorkerLayoutFailure, WorkerLauncher] = {
    val moduleFolderIgnoresCase = () => filesystemIgnoresCase(moduleFolder.toNIO)
    // As Mill 1.3.1 does, batch workers run, without discovery, the classes in each fork group's `testForkGrouping` list
    // that the class globs match.
    if (inputs.testBatchFrameworkTasks)
      WorkerLayout.ofBatches(forkGroups, classGlobs, moduleFolderIgnoresCase).map(new BatchLauncher(_))
    else
      WorkerLayout
        .ofQueue(forkGroups, discoveredTestClasses(), classGlobs, inputs.testParallelism, ctx.jobs, moduleFolderIgnoresCase)
        .map(new QueueLauncher(_))
  }

  private def runWorkers(consolidation: Consolidation, launcher: WorkerLauncher): Unit = {
    val laidOutForkGroups = JvmTestModuleRun.layOutForkGroups(consolidation, module, launcher.layout.forkGroups)(launcher.layOut)
    val startedAt = now()
    val workers = laidOutForkGroups.flatMap { case (forkGroup, laidOut) => forkGroup.workers.map(plannedWorker => (forkGroup, laidOut, plannedWorker)) }
    val workerRuns = WorkerRun.startEach(workers) { case (forkGroup, laidOut, plannedWorker) =>
      startWorker(consolidation, plannedWorker)((workerCtx, testModuleUtil) => launcher.launch(forkGroup, laidOut, plannedWorker, workerCtx, testModuleUtil))
    }
    val workerEndings = WorkerRun.endEach(workerRuns)(workerEnd => ctx.fork.blocking(Await.ready(workerEnd, Duration.Inf)))
    consolidation.timeModule(module, Some(startedAt), Some(now()))
    JvmTestModuleRun.reportNoTestResultForClassGlobs(
      consolidation,
      module,
      classGlobs,
      everyForkGroupLaidOut = laidOutForkGroups.size == launcher.layout.forkGroups.size,
      workerEndings
    )
  }

  private def discoveredTestClasses(): Set[TestClassName] =
    inputs.jvmWorker
      .apply(
        ZincOp.GetTestTasks(
          (inputs.runClasspath ++ inputs.testrunnerEntrypointClasspath).map(_.path),
          inputs.testClasspath.map(_.path),
          inputs.testFramework,
          classGlobs.map(_.text),
          runArguments.commandLine,
          inputs.discoveredClasses
        ),
        javaHome = inputs.javaHome
      )(using ctx)
      .map(TestClassName(_))
      .toSet

  private def resultLog(groupFolder: os.Path): os.Path = groupFolder / "result.log"

  // Mill 1.3.1 gives a failure for a process only where it exited without writing its `out.json`. Beyond that, the
  // `Result` is ignored: the consolidation decides the test command's result.
  private def processEndedWith(processResult: Result[?]): WorkerProcess =
    processResult match {
      case _: Result.Failure => WorkerProcess.EndedWithoutResult
      case _: Result.Success[?] => WorkerProcess.EndedWithResult
    }

  private def inModuleFolder(path: PathUnderModule): os.Path = moduleFolder / path.segments

  private def startWorker(consolidation: Consolidation, plannedWorker: PlannedWorker)(
      runWorker: (WorkerTaskCtx, TestModuleUtil) => Future[WorkerProcess]
  ): WorkerRun = {
    val eventLogFile = inModuleFolder(plannedWorker.processFolder) / JvmTestModuleRun.eventLogFileName
    val capture = new WorkerOutputCapture(workerCharset)
    val reader = new EventLogReader(
      eventLogFileAt(eventLogFile.toNIO),
      readRun =>
        consolidation.addRun(module, Some(plannedWorker.id)) { feed =>
          capture.addTo(feed.add)
          readRun(feed)
        },
      () => consolidation.addProcessEndedBeforeAnyRun(),
      processHasExited,
      eventLogPollInterval
    )
    val workerRun = new WorkerRun(consolidation, plannedWorker.describedIn(module), reader, capture)
    workerRun.start { workerStarting =>
      val workerCtx = new WorkerTaskCtx(ctx, capture, workerStarting)
      runWorker(workerCtx, inputs.testModuleUtil(runArguments.commandLine ++ RunnerArgument.forEventLogFile(eventLogFile.toNIO), workerCtx))
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
      inputs.testModuleUtil(runArguments.commandLine, ctx).prepareTestClassesFolder(forkGroup.testClasses.map(_.value), inModuleFolder(forkGroup.folder))

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
          plannedWorker.id.index.value
        )
      // Mill forks a worker through `workerCtx` only when the worker has its own `processFolder`; a worker in the fork
      // group's folder runs on this thread.
      val (_, workerEnd) = if (plannedWorker.processFolder == forkGroup.folder) workerCtx.runOnThisThread(joinQueue()) else joinQueue()
      workerEnd.map { case (_, _, processResult) => processResult.map(processEndedWith).getOrElse(WorkerProcess.NotStarted) }(using ExecutionContext.parasitic)
    }
  }

  private final class BatchLauncher(val layout: WorkerLayout) extends WorkerLauncher {

    type LaidOut = JvmTestModuleRun.BatchSelector

    def layOut(forkGroup: PlannedForkGroup): JvmTestModuleRun.BatchSelector = {
      val groupFolder = inModuleFolder(forkGroup.folder)
      // As Mill 1.3.1 does, each fork group's `result.log` starts as `(0L, 0L)`.
      os.write.over(resultLog(groupFolder), upickle.write((0L, 0L)), createFolders = true)
      // An empty class list would select every class, so, as Mill 1.3.1 does, an empty group gets an empty queue
      // instead.
      if (forkGroup.testClasses.isEmpty) {
        val queue = inputs.testModuleUtil(runArguments.commandLine, ctx).prepareTestClassesFolder(Nil, groupFolder)
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
      def runProcess(): WorkerProcess =
        processEndedWith(testModuleUtil.callTestRunnerSubprocess(groupFolder, resultLog(groupFolder), selector.toMill, () => ())(using workerCtx))
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

  def filesystemIgnoresCase(folder: Path): Either[FilesystemCaseNotChecked, Boolean] =
    try {
      val probe = Files.createFile(folder.resolve("case-probe"))
      try {
        Right(Files.exists(folder.resolve("CASE-PROBE")))
      } finally {
        Files.delete(probe)
      }
    } catch {
      case failure: IOException => Left(new FilesystemCaseNotChecked(failure))
    }

  def launcherOrReportFailure[Launcher](
      consolidation: Consolidation,
      module: ModuleName,
      outcomeOfLayingOut: Either[WorkerLayoutFailure, Launcher]
  ): Option[Launcher] =
    outcomeOfLayingOut.fold(
      failure => {
        consolidation.addRootFailure(failure.rootMessage(module))
        consolidation.timeModule(module, None, None)
        None
      },
      launcher => Some(launcher)
    )

  def reportNoTestResultForClassGlobs(
      consolidation: Consolidation,
      module: ModuleName,
      classGlobs: Seq[ClassGlob],
      everyForkGroupLaidOut: Boolean,
      workerEndings: Seq[WorkerRun.Ending]
  ): Unit = {
    // Fails the test command as Mill 1.3.1's `testOnly` fails the module where its class globs gave no test result once
    // the workers ended. The consolidation, not the result Mill gives for each worker, tells whether a test result
    // reached it. A fork group that failed to lay out, or a worker that failed to start or run, already explains the
    // missing result, and Mill's `testOnly` skips this check after either. So does a worker whose JVM ended without
    // writing its result, but only where the test command already fails, as where the JVM died before its run ended: a
    // JVM that dies after its run ended leaves nothing else to fail the test command.
    val missingTestResultExplained =
      !everyForkGroupLaidOut ||
        workerEndings.contains(WorkerRun.Ending.Failed) ||
        (workerEndings.contains(WorkerRun.Ending.EndedWithoutResult) && consolidation.state.failed)
    if (classGlobs.nonEmpty && !missingTestResultExplained && !consolidation.hasTestResult(module)) {
      consolidation.addRootFailure(ClassGlob.noTestMatchedMessage(classGlobs))
    }
  }

  def layOutForkGroups[LaidOut](consolidation: Consolidation, module: ModuleName, forkGroups: Seq[PlannedForkGroup])(
      layOut: PlannedForkGroup => LaidOut
  ): Seq[(PlannedForkGroup, LaidOut)] = {
    val attempts = forkGroups.map(forkGroup => forkGroup -> Try(layOut(forkGroup)))
    attempts.foreach {
      case (forkGroup, Failure(throwable)) => consolidation.addRootFailure(s"Laying out ${forkGroup.describedIn(module)} failed: $throwable")
      case (_, Success(_)) => ()
    }
    attempts.collect { case (forkGroup, Success(laidOut)) => forkGroup -> laidOut }
  }
}
