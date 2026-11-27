package org.scalatest.mill

import java.nio.charset.Charset
import java.time.Instant
import mill.api.BuildCtx
import mill.api.BuildInfo
import mill.api.Evaluator
import mill.api.Result
import mill.api.Task
import mill.api.TaskCtx
import org.scalatest.consolidation.BuildTool
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ScalaTestArgument
import scala.concurrent.duration.DurationInt

private[mill] final class TestCommand private (
    failFastOffEvaluator: Evaluator,
    commandArguments: TestCommandArguments,
    jsonOutputFile: Option[JsonOutputFile],
    selection: ModuleSelection,
    ctx: TaskCtx,
    now: () => Instant,
    systemProperty: String => Option[String]
) {

  def checkAndRunTests(): Either[TestCommandFailure, Unit] = {
    val (jvmModules, otherModules) = selection.modules.partition(_.isJvm)
    val checkTasks = jvmModules.map(module => TestCommand.ModuleTask(module, ModuleCheckTask.of(module)))
    val checkResults = EvaluatedTasks.of(failFastOffEvaluator.execute(checkTasks.map(_.task), logger = ctx.log))
    // A module whose check task failed is not a module the test command cannot run: its failure alone does not stop
    // the other modules' tests.
    val (failedChecks, checkedModules) = checkTasks.partitionMap(checkTask => TestCommand.CheckedModule.from(checkTask, checkResults))
    val (jvmModuleReasons, runnableModules) = checkedModules.partitionMap(_.toRunnable(commandArguments.frameworkArguments))
    val cannotRunReasons = otherModules.map(module => CannotRunReason.NotJvm(module.name)) ++ jvmModuleReasons.flatten
    if (cannotRunReasons.nonEmpty)
      Left(TestCommandFailure.CannotRunModules(cannotRunReasons.sortBy(_.module.value), failedChecks.sortBy(_.module.value)))
    else
      runTests(runnableModules, failedChecks)
  }

  private def runTests(runnableModules: Seq[TestCommand.RunnableModule], failedChecks: Seq[FailedModuleCheck]): Either[TestCommandFailure, Unit] = {
    val moduleFolders =
      BuildCtx.withFilesystemCheckerDisabled(selection.modules.map(module => module -> clearedModuleFolder(module.name)).toMap)
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
    failedChecks.foreach(failedCheck => consolidation.addModuleFailure(failedCheck.module, failedCheck.message))
    val workerCharset = OutputCapture.workerCharset(systemProperty)
    val runTasks = runnableModules.map { runnable =>
      TestCommand.ModuleTask(runnable.module, moduleRunTask(runnable, moduleFolders(runnable.module), consolidation, workerCharset))
    }
    val runResults = EvaluatedTasks.of(failFastOffEvaluator.execute(runTasks.map(_.task), logger = ctx.log))
    // Where a module run task fails, or Mill skips it because a task upstream of it failed, the task attaches no
    // failure to its module, so the test command attaches it here.
    runTasks.foreach { runTask =>
      runTask.failureIn(runResults).foreach(failure => consolidation.addModuleFailure(runTask.module.name, failure))
    }
    val endedState = consolidation.end(Some(now()))
    jsonOutputWritten.flatMap { _ =>
      if (endedState.failed)
        Left(TestCommandFailure.ModulesFailed(consolidation.modulesFailingTheTestCommand))
      else
        Right(())
    }
  }

  // Each module's tests run in this task's body, downstream of only that module's inputs, so they start once that
  // module's inputs are evaluated.
  private def moduleRunTask(
      runnable: TestCommand.RunnableModule,
      moduleFolder: os.Path,
      consolidation: Consolidation,
      workerCharset: Charset
  ): Task[Unit] = {
    val inputsTask = JvmTestModuleInputs.task(runnable.module.testModule)
    Task.Anon {
      val moduleRun = new JvmTestModuleRun(
        runnable.module.name,
        inputsTask(),
        runnable.runArguments,
        commandArguments.classGlobs,
        moduleFolder,
        Task.ctx(),
        ctx.log,
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

  private def clearedModuleFolder(module: ModuleName): os.Path = {
    val moduleFolder = os.Path(ModuleFolder.inside((ctx.dest / os.up / "modules").toNIO, module))
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    moduleFolder
  }
}

private[mill] object TestCommand {

  def run(
      evaluator: Evaluator,
      arguments: Seq[String],
      ctx: TaskCtx,
      now: () => Instant,
      systemProperty: String => Option[String]
  ): Result[Unit] = {
    val outcome = for {
      commandArguments <- TestCommandArguments.parse(arguments)
      jsonOutputFile = commandArguments.jsonOutputPath.map(path => new JsonOutputFile(os.Path(path, BuildCtx.workspaceRoot).toNIO))
      _ <- jsonOutputFile.map(file => BuildCtx.withFilesystemCheckerDisabled(file.delete())).getOrElse(Right(()))
      selection <- ModuleSelection.from(evaluator, commandArguments.moduleSelectors)
      // Fail-fast off lets every module's tests run, whatever happens to the others' tasks.
      testCommand = new TestCommand(FailFastOffEvaluator.from(evaluator), commandArguments, jsonOutputFile, selection, ctx, now, systemProperty)
      _ <- testCommand.checkAndRunTests()
    } yield ()
    outcome.fold(failure => Result.Failure(failure.message), _ => Result.Success(()))
  }

  private final case class ModuleTask[V](module: SelectedTestModule, task: Task[V]) {

    def resultIn(results: EvaluatedTasks): Either[String, V] = results.valueOrFailure(task)

    def failureIn(results: EvaluatedTasks): Option[String] = resultIn(results).left.toOption
  }

  private final case class CheckedModule(module: SelectedTestModule, check: ModuleCheck) {

    def toRunnable(frameworkArguments: Seq[ScalaTestArgument.Flagged]): Either[Seq[CannotRunReason], RunnableModule] =
      check.runArguments(module.name, frameworkArguments).map(RunnableModule(module, _))
  }

  private object CheckedModule {

    def from(checkTask: ModuleTask[ModuleCheck], results: EvaluatedTasks): Either[FailedModuleCheck, CheckedModule] =
      checkTask
        .resultIn(results)
        .left.map(message => FailedModuleCheck(checkTask.module.name, message))
        .map(check => CheckedModule(checkTask.module, check))
  }

  private final case class RunnableModule(module: SelectedTestModule, runArguments: RunArguments)
}
