package org.scalatest.mill

import mill.javalib.TestModule
import org.scalatest.consolidation.ModuleName

private[mill] final case class SelectedTestModule(module: TestModule) {

  def name: ModuleName = ModuleName(module.moduleSegments.render)
}
