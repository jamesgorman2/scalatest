package org.scalatest.mill

import org.scalatest.consolidation.ModuleSelector
import org.scalatest.consolidation.TestRunLabel

/** A module selector as the test command was given it, before it is resolved. */
private[mill] opaque type ModuleSelectorArgument = String

private[mill] object ModuleSelectorArgument {

  def apply(text: String): ModuleSelectorArgument = text

  extension (selector: ModuleSelectorArgument) {

    def text: String = selector

    def resolvedTo(modules: Vector[TestRunLabel]): ModuleSelector = ModuleSelector(selector, modules)
  }
}
