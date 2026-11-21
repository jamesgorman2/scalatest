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
    val inputs = orFail(JvmTestModuleInputs.evaluate(evaluator, selectedModule.module, ctx.log))
    val scalaTestArguments = orFail(ModuleScalaTestArguments.forRun(inputs.testArgs))
    val output = ctx.log.streams.out
    val humanReadableOutput: String => Unit =
      commandArguments.stdoutContent match {
        case StdoutContent.HumanReadableOutput => line => output.println(line)
        case StdoutContent.NoOutput => _ => ()
      }
    var jsonOutputWritten: Either[TestCommandFailure, Unit] = Right(())
    val module = ModuleName(selectedModule.path)
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
      scalaTestArguments,
      moduleFolder(ctx, selectedModule.path),
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
      ctx.fail(s"The tests of ${selectedModule.path} failed")
    }
  }

  private def moduleFolder(ctx: TaskCtx, modulePath: String): os.Path = ctx.dest / os.up / "modules" / modulePath
}
