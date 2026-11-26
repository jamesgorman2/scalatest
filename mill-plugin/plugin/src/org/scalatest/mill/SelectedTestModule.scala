package org.scalatest.mill

import mill.javalib.JavaModule
import mill.javalib.TestModule
import org.scalatest.consolidation.ModuleName

private[mill] final case class SelectedTestModule(module: TestModule & JavaModule) {

  def name: ModuleName = ModuleName(module.moduleSegments.render)
}
