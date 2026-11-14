package org.scalatest.mill

import mill.api.BuildCtx
import mill.api.Evaluator
import mill.api.TaskCtx
import mill.scalatestplugin.MillColour
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.ConsolidationState
import org.scalatest.consolidation.TestRunLabel

private[mill] object TestCommand {

  def run(evaluator: Evaluator, arguments: Seq[String], ctx: TaskCtx): Unit = {
    val commandArguments = TestCommandArguments.parse(arguments).fold(ctx.fail, identity)
    val module = SelectedTestModule.resolve(evaluator, commandArguments.moduleSelectors).fold(ctx.fail, identity)
    val inputs = JvmTestModuleInputs.evaluate(evaluator, module.module, ctx.log).fold(ctx.fail, identity)
    val scalaTestArguments = ModuleScalaTestArguments.forRun(inputs.testArgs).fold(ctx.fail, identity)
    val output = ctx.log.streams.out
    val humanReadableOutput: String => Unit =
      if (commandArguments.humanReadableOnStdout) line => output.println(line) else _ => ()
    val consolidation = Consolidation.start(humanReadableOutput, coloured = MillColour.colours(ctx.log))
    val run = new JvmTestModuleRun(TestRunLabel(module.path), inputs, scalaTestArguments, moduleFolder(ctx, module.path), ctx)
    BuildCtx.withFilesystemCheckerDisabled {
      run.run(consolidation)
    }
    consolidation.end()
    if (consolidation.state == ConsolidationState.EndedFailed) {
      ctx.fail(s"The tests of ${module.path} failed")
    }
  }

  private def moduleFolder(ctx: TaskCtx, modulePath: String): os.Path = ctx.dest / os.up / "modules" / modulePath
}
