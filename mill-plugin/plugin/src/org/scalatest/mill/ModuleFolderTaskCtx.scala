package org.scalatest.mill

import mill.api.TaskCtx

private[mill] final class ModuleFolderTaskCtx(ctx: TaskCtx, moduleFolder: os.Path) extends DelegatingTaskCtx(ctx) {

  override def dest: os.Path = moduleFolder
}
