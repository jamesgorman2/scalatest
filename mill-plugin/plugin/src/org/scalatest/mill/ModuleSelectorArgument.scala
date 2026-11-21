package org.scalatest.mill

import org.scalatest.consolidation.ModuleName
import org.scalatest.consolidation.ModuleSelector

private[mill] opaque type ModuleSelectorArgument = String

private[mill] object ModuleSelectorArgument {

  def apply(text: String): ModuleSelectorArgument = text

  extension (selector: ModuleSelectorArgument) {

    def text: String = selector

    def resolvedTo(modules: Vector[ModuleName]): ModuleSelector = ModuleSelector(selector, modules)
  }
}
