package org.scalatest.consolidation

import org.scalatest.events.StructureNode

/** `word` is the kind as the outputs name it. */
private[consolidation] enum NodeKind(val word: String) {
  case Suite extends NodeKind("suite")
  case Scope extends NodeKind("scope")
  case Test extends NodeKind("test")

  def isScopeOrTest: Boolean = this != Suite
}

private[consolidation] object NodeKind {

  def of(node: StructureNode): NodeKind =
    node match {
      case _: StructureNode.Suite => Suite
      case _: StructureNode.Scope => Scope
      case _: StructureNode.Test => Test
    }
}
