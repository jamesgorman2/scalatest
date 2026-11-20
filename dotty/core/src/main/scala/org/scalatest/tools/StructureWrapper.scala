package org.scalatest.tools

import org.scalatest.Reporter
import org.scalatest.events.Event
import org.scalatest.events.TestName

private[scalatest] enum WrapperNode {
  case TopLevelSuite(ofSuite: RegisteredSuite)
  case NestedSuite(ofSuite: RegisteredSuite)
  case RegisteredByItsRun(ofSuite: RegisteredSuite)
  case SuiteAheadOfSorter(ofSuite: RegisteredSuite)
  case Test(ofSuite: RegisteredSuite, scalaTestName: TestName)

  def registration: RegisteredSuite = {
    this match {
      case TopLevelSuite(ofSuite) => ofSuite
      case NestedSuite(ofSuite) => ofSuite
      case RegisteredByItsRun(ofSuite) => ofSuite
      case SuiteAheadOfSorter(ofSuite) => ofSuite
      case Test(ofSuite, _) => ofSuite
    }
  }

  def treeNode: TreeNode = {
    this match {
      case Test(_, testName) => TreeNode.Test(testName)
      case _ => TreeNode.OfSuite
    }
  }
}

private[scalatest] final class StructureWrapper(
  wrapped: Reporter,
  node: WrapperNode,
  handOff: (WrapperNode, Event) => Unit
) extends Reporter {

  def apply(event: Event): Unit = {
    TestEventLogEmitter.keptFromCaller(handOff(node, event))
    wrapped(event)
  }
}
