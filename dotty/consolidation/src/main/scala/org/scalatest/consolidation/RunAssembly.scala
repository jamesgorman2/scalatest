package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

/** The events of one execution of a node, in the order they arrived. */
private[consolidation] final case class Execution(number: Int, parentExecution: Option[Int], events: Vector[DecodedEvent]) {

  def added(event: DecodedEvent): Execution = copy(events = events :+ event)

  def holds(isOfKind: DecodedEvent => Boolean): Boolean = events.exists(isOfKind)
}

/** What a scope's or test's events make of it. A scope that ran and closed has none. */
private[consolidation] enum Outcome {
  case Succeeded
  case Failed
  case Ignored
  case Pending
  case Canceled
  case Aborted
  case NotRun
}

/** A node of a run's test structure, with its outcome and its children in declared order. */
private[consolidation] final case class AssembledNode(
  node: StructureNode,
  outcome: Option[Outcome],
  children: Vector[AssembledNode]
) {

  def name: String =
    node match {
      case suite: StructureNode.Suite => suite.name
      case scope: StructureNode.Scope => scope.name
      case test: StructureNode.Test => test.name
    }
}

/**
 * Builds one run's test structure from its event stream as it arrives. Each node is kept by its ID with its events
 * grouped by execution number, apart from its suite's structure.
 */
private[consolidation] final class RunAssembly(val label: String) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val executions = mutable.Map.empty[NodeId, SortedMap[Int, Execution]]
  private var capturedLines = Vector.empty[CapturedLine]
  private var eventStreamOpen = true

  def isEventStreamOpen: Boolean = eventStreamOpen

  def add(event: TestEventLogEvent): Unit = {
    require(eventStreamOpen, s"the event stream of a run under $label has ended")
    event match {
      case TestEventLogEvent.Structure(nodes) =>
        nodes.foreach(node => structure(RunAssembly.idOf(node)) = node)
      case TestEventLogEvent.ScalaTestEvent(Some(nodeExecution), decoded) =>
        addToExecution(nodeExecution, decoded)
      case _: TestEventLogEvent.ScalaTestEvent | _: TestEventLogEvent.RunStarted | _: TestEventLogEvent.RunEnded =>
        ()
    }
  }

  def add(line: CapturedLine): Unit = capturedLines :+= line

  def endEventStream(): Unit = eventStreamOpen = false

  /** The run's top-level suites, in the order the event stream first held them. */
  def topLevelSuites: Vector[AssembledNode] = {
    val childrenByParent = structure.values.toVector.groupBy(RunAssembly.parentOf)
    def assembled(node: StructureNode, insideAbortedSuite: Boolean): AssembledNode = {
      val nodeExecutions = executions.get(RunAssembly.idOf(node)).fold(Vector.empty[Execution])(_.values.toVector)
      val abortedSuiteAround = insideAbortedSuite || RunAssembly.isAbortedSuite(node, nodeExecutions)
      val children = childrenByParent.getOrElse(Some(RunAssembly.idOf(node)), Vector.empty).sortBy(RunAssembly.positionOf)
      AssembledNode(
        node,
        RunAssembly.outcomeOf(node, nodeExecutions, abortedSuiteAround),
        children.map(assembled(_, abortedSuiteAround))
      )
    }
    childrenByParent.getOrElse(None, Vector.empty).map(assembled(_, false))
  }

  private def addToExecution(nodeExecution: NodeExecution, event: DecodedEvent): Unit = {
    val nodeExecutions = executions.getOrElse(nodeExecution.node, SortedMap.empty[Int, Execution])
    val execution = nodeExecutions.getOrElse(nodeExecution.execution, Execution(nodeExecution.execution, nodeExecution.parentExecution, Vector.empty))
    executions(nodeExecution.node) = nodeExecutions.updated(nodeExecution.execution, execution.added(event))
  }
}

private[consolidation] object RunAssembly {

  /** Whether the event reports a failure, which fails the test command. */
  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted) => true
      case _ => false
    }

  private def idOf(node: StructureNode): NodeId =
    node match {
      case suite: StructureNode.Suite => suite.id
      case scope: StructureNode.Scope => scope.id
      case test: StructureNode.Test => test.id
    }

  private def parentOf(node: StructureNode): Option[NodeId] =
    node match {
      case suite: StructureNode.Suite => suite.parent
      case scope: StructureNode.Scope => Some(scope.parent)
      case test: StructureNode.Test => Some(test.parent)
    }

  // A suite nested in a later structure has no position, and sorts after its declared siblings.
  private def positionOf(node: StructureNode): Int =
    node match {
      case suite: StructureNode.Suite => suite.position.getOrElse(Int.MaxValue)
      case scope: StructureNode.Scope => scope.position
      case test: StructureNode.Test => test.position
    }

  private def isAbortedSuite(node: StructureNode, executions: Vector[Execution]): Boolean =
    node match {
      case _: StructureNode.Suite => executions.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteAborted]))
      case _ => false
    }

  private def outcomeOf(node: StructureNode, executions: Vector[Execution], insideAbortedSuite: Boolean): Option[Outcome] =
    (node, executions.headOption) match {
      case (_: StructureNode.Suite, _) => None
      case (_, None) => Some(Outcome.NotRun)
      case (_: StructureNode.Scope, Some(execution)) => scopeOutcome(execution, insideAbortedSuite)
      case (_: StructureNode.Test, Some(execution)) => testOutcome(execution, insideAbortedSuite)
    }

  private def scopeOutcome(execution: Execution, insideAbortedSuite: Boolean): Option[Outcome] =
    if (execution.holds(_.isInstanceOf[DecodedEvent.ScopePending]))
      Some(Outcome.Pending)
    else if (execution.holds(_.isInstanceOf[DecodedEvent.ScopeClosed]))
      None
    else
      abortedWhereOpen(insideAbortedSuite)

  private def testOutcome(execution: Execution, insideAbortedSuite: Boolean): Option[Outcome] =
    execution.events.collectFirst {
      case _: DecodedEvent.TestSucceeded => Outcome.Succeeded
      case _: DecodedEvent.TestFailed => Outcome.Failed
      case _: DecodedEvent.TestIgnored => Outcome.Ignored
      case _: DecodedEvent.TestPending => Outcome.Pending
      case _: DecodedEvent.TestCanceled => Outcome.Canceled
    }.orElse(abortedWhereOpen(insideAbortedSuite))

  private def abortedWhereOpen(insideAbortedSuite: Boolean): Option[Outcome] =
    if (insideAbortedSuite) Some(Outcome.Aborted) else None
}
