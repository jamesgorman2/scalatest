package org.scalatest.mill

import java.time.Instant
import mill.api.BuildCtx
import mill.api.BuildInfo
import mill.api.Evaluator
import mill.api.TaskCtx
import org.scalatest.consolidation.BuildTool
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ModuleSelector
import org.scalatest.consolidation.TestRunLabel

private[mill] object TestCommand {

  def run(evaluator: Evaluator, arguments: Seq[String], ctx: TaskCtx): Unit = {
    val commandArguments = TestCommandArguments.parse(arguments).fold(ctx.fail, identity)
    val jsonOutputFile = commandArguments.jsonOutputPath.map(path => new JsonOutputFile(os.Path(path, BuildCtx.workspaceRoot).toNIO))
    jsonOutputFile.foreach(file => BuildCtx.withFilesystemCheckerDisabled(file.delete()).fold(ctx.fail, identity))
    val module = SelectedTestModule.resolve(evaluator, commandArguments.moduleSelectors).fold(ctx.fail, identity)
    val inputs = JvmTestModuleInputs.evaluate(evaluator, module.module, ctx.log).fold(ctx.fail, identity)
    val scalaTestArguments = ModuleScalaTestArguments.forRun(inputs.testArgs).fold(ctx.fail, identity)
    val output = ctx.log.streams.out
    val humanReadableOutput: String => Unit =
      if (commandArguments.humanReadableOnStdout) line => output.println(line) else _ => ()
    var jsonOutputWritten: Either[String, Unit] = Right(())
    val label = TestRunLabel(module.path)
    val consolidation = Consolidation.start(
      humanReadableOutput,
      coloured = true,
      jsonOutput = jsonOutputFile.map(file => json => jsonOutputWritten = BuildCtx.withFilesystemCheckerDisabled(file.write(json.writeTo))),
      moduleSelectors = commandArguments.moduleSelectors.map(selector => ModuleSelector(selector, Vector(label))),
      buildTool = Some(BuildTool("Mill", BuildInfo.millVersion)),
      startedAt = Some(Instant.now())
    )
    val run = new JvmTestModuleRun(label, inputs, scalaTestArguments, moduleFolder(ctx, module.path), ctx)
    BuildCtx.withFilesystemCheckerDisabled {
      run.run(consolidation)
    }
    consolidation.end(Some(Instant.now()))
    jsonOutputWritten.fold(ctx.fail, identity)
    if (consolidation.state.failed) {
      ctx.fail(s"The tests of ${module.path} failed")
    }
  }

  private def moduleFolder(ctx: TaskCtx, modulePath: String): os.Path = ctx.dest / os.up / "modules" / modulePath
}
