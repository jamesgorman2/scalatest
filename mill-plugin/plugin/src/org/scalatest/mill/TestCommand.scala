package org.scalatest.mill

import mill.api.BuildCtx
import mill.api.Evaluator
import mill.api.TaskCtx
import org.scalatest.consolidation.Consolidation
import org.scalatest.consolidation.TestRunLabel

private[mill] object TestCommand {

  def run(evaluator: Evaluator, arguments: Seq[String], ctx: TaskCtx): Unit = {
    val module = SelectedTestModule.resolve(evaluator, arguments).fold(ctx.fail, identity)
    val inputs = JvmTestModuleInputs.evaluate(evaluator, module.module, ctx.log).fold(ctx.fail, identity)
    val output = ctx.log.streams.out
    val consolidation = Consolidation.start(line => output.println(line), coloured = false)
    val run = new JvmTestModuleRun(TestRunLabel(module.path), inputs, moduleFolder(ctx, module.path), ctx)
    BuildCtx.withFilesystemCheckerDisabled {
      run.run(consolidation)
    }
    consolidation.end()
  }

  private def moduleFolder(ctx: TaskCtx, modulePath: String): os.Path = ctx.dest / os.up / "modules" / modulePath
}
