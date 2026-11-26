package org.scalatest.mill

import java.nio.charset.Charset
import java.time.Instant
import mill.api.BuildCtx
import mill.api.BuildInfo
import mill.api.Evaluator
import mill.api.Logger
import mill.api.Result
import mill.api.Task
import mill.api.TaskCtx
import org.scalatest.consolidation.BuildTool
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import scala.concurrent.duration.DurationInt

private[mill] object TestCommand {

  def run(
      evaluator: Evaluator,
      arguments: Seq[String],
      ctx: TaskCtx,
      now: () => Instant,
      systemProperty: String => Option[String]
  ): Result[Unit] = {
    def orFail[A](result: Either[TestCommandFailure, A]): A = result.fold(failure => ctx.fail(failure.message), identity)
    val commandArguments = orFail(TestCommandArguments.parse(arguments))
    val jsonOutputFile = commandArguments.jsonOutputPath.map(path => new JsonOutputFile(os.Path(path, BuildCtx.workspaceRoot).toNIO))
    jsonOutputFile.foreach(file => orFail(BuildCtx.withFilesystemCheckerDisabled(file.delete())))
    val selection = orFail(ModuleSelection.from(evaluator, commandArguments.moduleSelectors))
    // Fail-fast off lets every module's tests run, whatever happens to the others' tasks.
    val failFastOffEvaluator = FailFastOffEvaluator.from(evaluator)
    val checkTasks = selection.modules.map(module => module -> moduleCheckTask(module))
    val checkEvaluation = failFastOffEvaluator.execute(checkTasks.map(_._2), logger = ctx.log)
    val checkedModuleTestArgs = successfulValues(checkEvaluation, checkTasks)
    val runArguments = checkedModuleTestArgs.map { case (module, testArgs) =>
      module -> RunArguments.from(orFail(ModuleScalaTestArguments.kept(testArgs)), commandArguments.frameworkArguments)
    }
    val moduleFolders = BuildCtx.withFilesystemCheckerDisabled(selection.modules.map(module => module -> clearedModuleFolder(ctx, module.name)).toMap)
    val output = ctx.log.streams.out
    val humanReadableOutput: String => Unit =
      commandArguments.stdoutContent match {
        case StdoutContent.HumanReadableOutput => line => output.println(line)
        case StdoutContent.NoOutput => _ => ()
      }
    var jsonOutputWritten: Either[TestCommandFailure, Unit] = Right(())
    val consolidation = Consolidation.start(
      humanReadableOutput,
      coloured = true,
      jsonOutput = jsonOutputFile.map(file => json => jsonOutputWritten = BuildCtx.withFilesystemCheckerDisabled(file.write(json.writeTo))),
      moduleSelectors = selection.moduleSelectors,
      buildTool = Some(BuildTool("Mill", BuildInfo.millVersion)),
      startedAt = Some(now())
    )
    val workerCharset = WorkerOutputCapture.workerCharset(systemProperty)
    val moduleRunTasks = runArguments.map { case (module, moduleRunArguments) =>
      moduleRunTask(module, moduleRunArguments, commandArguments.classGlobs, moduleFolders(module), consolidation, ctx.log, now, workerCharset)
    }
    val runEvaluation = failFastOffEvaluator.execute(moduleRunTasks, logger = ctx.log)
    val endedState = consolidation.end(Some(now()))
    orFail(jsonOutputWritten)
    val failures = Seq(checkEvaluation.values, runEvaluation.values).collect { case failure: Result.Failure => failure } ++
      Option.when(endedState.failed)(Result.Failure(s"The tests of ${EnglishList.of(selection.modules.map(_.name.value))} failed"))
    if (failures.nonEmpty) Result.Failure.join(failures) else Result.Success(())
  }

  // The check evaluates no compile, so that no module's tests wait on another module's compile.
  private def moduleCheckTask(module: SelectedTestModule): Task[Seq[String]] =
    Task.Anon {
      module.module.testArgsDefault()
    }

  // Each module's tests run in this task's body, downstream of only that module's inputs, so they start once that
  // module's inputs are evaluated.
  private def moduleRunTask(
      module: SelectedTestModule,
      runArguments: RunArguments,
      classGlobs: Seq[ClassGlob],
      moduleFolder: os.Path,
      consolidation: Consolidation,
      workerLog: Logger,
      now: () => Instant,
      workerCharset: Charset
  ): Task[Unit] = {
    val inputsTask = JvmTestModuleInputs.task(module.module)
    Task.Anon {
      val moduleRun = new JvmTestModuleRun(
        module.name,
        inputsTask(),
        runArguments,
        classGlobs,
        moduleFolder,
        Task.ctx(),
        workerLog,
        now,
        workerCharset,
        EventLogFile.at,
        ProcessExit.hasExited,
        10.millis,
        JvmTestModuleRun.filesystemIgnoresCase
      )
      // Mill's filesystem checker refuses this task's writes to the module folder, as an anonymous task has no `dest`.
      // Workers forked inside the block run with the checker off too.
      BuildCtx.withFilesystemCheckerDisabled {
        moduleRun.execute(consolidation)
      }
    }
  }

  private def successfulValues[K, V](evaluation: Evaluator.Result[V], tasks: Seq[(K, Task[V])]): Seq[(K, V)] =
    tasks.flatMap { case (key, task) =>
      evaluation.executionResults.transitiveResults.get(task).flatMap(_.asSuccess).map(success => key -> success.value.value.asInstanceOf[V])
    }

  private def clearedModuleFolder(ctx: TaskCtx, module: ModuleName): os.Path = {
    val moduleFolder = os.Path(ModuleFolder.inside((ctx.dest / os.up / "modules").toNIO, module))
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    moduleFolder
  }
}
