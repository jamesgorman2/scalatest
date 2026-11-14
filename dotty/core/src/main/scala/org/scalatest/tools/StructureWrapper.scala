package org.scalatest.tools

import org.scalatest.Reporter
import org.scalatest.ReporterOfRegisteredSuite
import org.scalatest.SuiteRegistration
import org.scalatest.events.Event

/** The node a structure wrapper hands its events off for: a suite's, or that of one of the suite's tests. */
private[scalatest] enum WrapperNode {
  case TopLevelSuite(ofSuite: RegisteredSuite)
  case NestedSuite(ofSuite: RegisteredSuite)
  /** A suite's, put in the `Args` of its run by the site that registered it, where no other site had. */
  case SelfRegisteredSuite(ofSuite: RegisteredSuite)
  /** A suite's, put ahead of a reporter that holds the suite's events back. */
  case SuiteAheadOfSorter(ofSuite: RegisteredSuite)
  case Test(ofSuite: RegisteredSuite, testName: String)

  /** The registration of the node's suite. */
  def registration: RegisteredSuite = {
    this match {
      case TopLevelSuite(ofSuite) => ofSuite
      case NestedSuite(ofSuite) => ofSuite
      case SelfRegisteredSuite(ofSuite) => ofSuite
      case SuiteAheadOfSorter(ofSuite) => ofSuite
      case Test(ofSuite, _) => ofSuite
    }
  }

  /** The node within its suite's registration tree. */
  def treeNode: TreeNode = {
    this match {
      case Test(_, testName) => TreeNode.Test(testName)
      case _ => TreeNode.OfSuite
    }
  }
}

/**
 * The reporter a suite or test is given in a run that writes a test event log. It hands off each event it receives,
 * naming its node, then passes the event unchanged to the reporter it wraps.
 *
 * Whatever the hand-off throws is kept from the reporter's caller as `TestEventLogEmitter.runEvent` keeps what it
 * throws, so the wrapped reporter misses an event only where the hand-off throws a `VirtualMachineError` other than
 * a `StackOverflowError`, or a `ThreadDeath`.
 */
private[scalatest] final class StructureWrapper(
  wrapped: Reporter,
  node: WrapperNode,
  handOff: (WrapperNode, Event) => Unit
) extends ReporterOfRegisteredSuite {

  /** Whether the wrapper's suite instance is placed: one that hands events to an emitter, not one holding them until its suite node is known. */
  def isOfPlacedInstance: Boolean = node.registration.isPlaced

  def apply(event: Event): Unit = {
    TestEventLogEmitter.keptFromCaller(handOff(node, event))
    wrapped(event)
  }

  /**
   * The registration the site that runs a suite made as it took this wrapper. None for a wrapper no site took in place
   * of the suite's reporter: a test's wrapper, a suite's wrapper ahead of a sorter, or the wrapper of a suite that
   * registered itself, whose own site removes the registration.
   */
  def registrationOfItsSuite: SuiteRegistration = {
    node match {
      case WrapperNode.Test(_, _) | WrapperNode.SuiteAheadOfSorter(_) | WrapperNode.SelfRegisteredSuite(_) => SuiteRegistration.none
      case ofSuite => ofSuite.registration
    }
  }
}
