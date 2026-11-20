package org.scalatest.tools

import org.scalatest.Reporter
import org.scalatest.events.Event
import org.scalatest.events.TestName

private[scalatest] enum WrapperNode {
  case TopLevelSuite(ofSuite: RegisteredSuite)
  case NestedSuite(ofSuite: RegisteredSuite)
  /** A suite's, put in the `Args` of its run by the first site of the run that registered it. */
  case RegisteredByItsRun(ofSuite: RegisteredSuite)
  case SuiteAheadOfSorter(ofSuite: RegisteredSuite)
  case Test(ofSuite: RegisteredSuite, testName: TestName)

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

/**
 * The reporter a suite or test is given in a run that writes a test event log.
 *
 * Whatever the hand-off throws is kept from the reporter's caller as `TestEventLogEmitter.writeRunEvent` keeps what it
 * throws, so the wrapped reporter misses an event only where the hand-off throws a `VirtualMachineError` other than
 * a `StackOverflowError`, or a `ThreadDeath`.
 */
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
