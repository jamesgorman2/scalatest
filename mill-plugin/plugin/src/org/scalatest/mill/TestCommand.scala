package org.scalatest.mill

import java.time.Instant
import mill.api.BuildCtx
import mill.api.BuildInfo
import mill.api.Evaluator
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
  ): Unit = {
    def orFail[A](result: Either[TestCommandFailure, A]): A = result.fold(failure => ctx.fail(failure.message), identity)
    val commandArguments = orFail(TestCommandArguments.parse(arguments))
    val jsonOutputFile = commandArguments.jsonOutputPath.map(path => new JsonOutputFile(os.Path(path, BuildCtx.workspaceRoot).toNIO))
    jsonOutputFile.foreach(file => orFail(BuildCtx.withFilesystemCheckerDisabled(file.delete())))
    val selectedModule = orFail(SelectedTestModule.resolve(evaluator, commandArguments.moduleSelectors))
    val module = selectedModule.name
    val moduleFolder = BuildCtx.withFilesystemCheckerDisabled(clearedModuleFolder(ctx, module))
    val inputs = orFail(JvmTestModuleInputs.evaluate(evaluator, selectedModule.module, ctx.log))
    val runArguments = RunArguments.from(orFail(ModuleScalaTestArguments.kept(inputs.testArgs)), commandArguments.frameworkArguments)
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
      moduleSelectors = commandArguments.moduleSelectors.map(_.resolvedTo(Vector(module))),
      buildTool = Some(BuildTool("Mill", BuildInfo.millVersion)),
      startedAt = Some(now())
    )
    val moduleRun = new JvmTestModuleRun(
      module,
      inputs,
      runArguments,
      commandArguments.classGlobs,
      moduleFolder,
      ctx,
      now,
      WorkerOutputCapture.workerCharset(systemProperty),
      EventLogFile.at,
      ProcessExit.hasExited,
      10.millis,
      JvmTestModuleRun.filesystemIgnoresCase
    )
    BuildCtx.withFilesystemCheckerDisabled {
      moduleRun.execute(consolidation)
    }
    val endedState = consolidation.end(Some(now()))
    orFail(jsonOutputWritten)
    if (endedState.failed) {
      ctx.fail(s"The tests of ${module.value} failed")
    }
  }

  private def clearedModuleFolder(ctx: TaskCtx, module: ModuleName): os.Path = {
    val moduleFolder = os.Path(ModuleFolder.inside((ctx.dest / os.up / "modules").toNIO, module))
    os.remove.all(moduleFolder)
    os.makeDir.all(moduleFolder)
    moduleFolder
  }
}
