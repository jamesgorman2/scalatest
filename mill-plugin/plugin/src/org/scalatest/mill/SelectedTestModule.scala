package org.scalatest.mill

import mill.javalib.JavaModule
import mill.javalib.TestModule
import mill.scalajslib.ScalaJSModule
import mill.scalanativelib.ScalaNativeModule
import org.scalatest.consolidation.ModuleName

private[mill] final case class SelectedTestModule(testModule: TestModule & JavaModule) {

  def name: ModuleName = ModuleName(testModule.moduleSegments.render)

  def isJvm: Boolean = !testModule.isInstanceOf[ScalaJSModule] && !testModule.isInstanceOf[ScalaNativeModule]
}
