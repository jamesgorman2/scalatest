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
import org.scalatest.consolidation.ConsolidationState
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

  def checkAndRunTests(): Either[TestCommandFailure, ConsolidationState] = {
    val checkTasks = selection.modules.map(module => module -> ModuleCheck.task(module))
    val checkResults = EvaluatedTasks.of(failFastOffEvaluator.execute(checkTasks.map(_._2), logger = ctx.log))
    val (checkFailures, moduleChecks) = checkTasks.partitionMap { case (module, task) =>
      checkResults.valueOrFailure(task).left.map(failure => module -> failure).map(check => module -> check)
    }
    TestCommand
      .runArgumentsFrom(moduleChecks, commandArguments.frameworkArguments)
      .flatMap(runArguments => consolidateSelectedModules(runArguments, checkFailures))
  }

  private def consolidateSelectedModules(
      runArguments: Seq[(SelectedTestModule, RunArguments)],
      checkFailures: Seq[(SelectedTestModule, String)]
  ): Either[TestCommandFailure, ConsolidationState] = {
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
    TestCommand.addModuleFailures(consolidation, checkFailures)
    val workerCharset = WorkerOutputCapture.workerCharset(systemProperty)
    val moduleRunTasks = runArguments.map { case (module, moduleRunArguments) =>
      module -> moduleRunTask(module, moduleRunArguments, moduleFolders(module), consolidation, workerCharset)
    }
    val runResults = EvaluatedTasks.of(failFastOffEvaluator.execute(moduleRunTasks.map(_._2), logger = ctx.log))
    // A module run task that fails, or that Mill skips because a task upstream of it failed, does not attach that
    // failure to its module.
    TestCommand.addModuleFailures(
      consolidation,
      moduleRunTasks.flatMap { case (module, task) => runResults.valueOrFailure(task).left.toOption.map(module -> _) }
    )
    val endedState = consolidation.end(Some(now()))
    jsonOutputWritten.map(_ => endedState)
  }

  // Each module's tests run in this task's body, downstream of only that module's inputs, so they start once that
  // module's inputs are evaluated.
  private def moduleRunTask(
      module: SelectedTestModule,
      runArguments: RunArguments,
      moduleFolder: os.Path,
      consolidation: Consolidation,
      workerCharset: Charset
  ): Task[Unit] = {
    val inputsTask = JvmTestModuleInputs.task(module.module)
    Task.Anon {
      val moduleRun = new JvmTestModuleRun(
        module.name,
        inputsTask(),
        runArguments,
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
      endedState <- testCommand.checkAndRunTests()
    } yield selection -> endedState
    outcome match {
      case Left(failure) => Result.Failure(failure.message)
      case Right((selection, endedState)) if endedState.failed =>
        Result.Failure(s"The tests of ${EnglishList.of(selection.modules.map(_.name.value))} failed")
      case Right(_) => Result.Success(())
    }
  }

  private def runArgumentsFrom(
      moduleChecks: Seq[(SelectedTestModule, ModuleCheck)],
      frameworkArguments: Seq[ScalaTestArgument.Flagged]
  ): Either[TestCommandFailure, Seq[(SelectedTestModule, RunArguments)]] =
    FirstFailure.orValues(moduleChecks.map { case (module, check) =>
      check.runArguments(frameworkArguments).map(runArguments => module -> runArguments)
    })

  private def addModuleFailures(consolidation: Consolidation, failures: Seq[(SelectedTestModule, String)]): Unit =
    failures.foreach { case (module, failure) => consolidation.addModuleFailure(module.name, failure) }
}
