package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

/** An `info`, `note`, `alert` or `markup` message, with the children of its node that had started in its execution when it arrived. */
private[consolidation] final case class ReceivedMessage(event: DecodedEvent, childrenStarted: Set[NodeId])

/** One execution of a node: its events, with its messages kept apart in the order they arrived. */
private[consolidation] final case class Execution(
  number: Int,
  parentExecution: Option[Int],
  events: Vector[DecodedEvent],
  messages: Vector[ReceivedMessage]
) {

  def added(event: DecodedEvent): Execution = copy(events = events :+ event)

  def added(message: ReceivedMessage): Execution = copy(messages = messages :+ message)

  def holds(isOfKind: DecodedEvent => Boolean): Boolean = events.exists(isOfKind)
}

/** The outcome a node's events give it. A scope that opened and closed, and a suite that did not abort, have none. */
private[consolidation] enum Outcome {
  case Succeeded
  case Failed
  case Ignored
  case Pending
  case Canceled
  case Aborted
  case NotRun
}

/** A message of a suite or scope, placed after the first `childrenBefore` of the node's children in declared order. */
private[consolidation] final case class PlacedMessage(event: DecodedEvent, childrenBefore: Int)

/**
 * A node of a run's test structure, with its outcome and its children in declared order.
 *
 * @param ending the `TestFailed`, `TestCanceled` or `SuiteAborted` whose lines sit under the node's line
 * @param started whether the node, or a node below it, has an execution
 */
private[consolidation] final case class AssembledNode(
  node: StructureNode,
  outcome: Option[Outcome],
  ending: Option[DecodedEvent],
  messages: Vector[PlacedMessage],
  children: Vector[AssembledNode],
  started: Boolean
) {

  def name: String =
    node match {
      case suite: StructureNode.Suite => suite.name
      case scope: StructureNode.Scope => scope.name
      case test: StructureNode.Test => test.name
    }

  /** Whether the node aborted or did not run and no node below it started, so the nodes below it show only as a count. */
  def isCollapsed: Boolean =
    outcome.exists(outcome => outcome == Outcome.Aborted || outcome == Outcome.NotRun) && !children.exists(_.started)

  /** The nodes at any depth below this one. */
  def descendants: Vector[AssembledNode] = children.flatMap(child => child +: child.descendants)
}

/**
 * Builds one run's test structure from its event stream as the stream arrives. It keeps each node's structure by
 * node ID, and the node's events separately, grouped by execution number.
 */
private[consolidation] final class RunAssembly(val label: TestRunLabel) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val childIds = mutable.Map.empty[NodeId, mutable.Set[NodeId]]
  private val executions = mutable.Map.empty[NodeId, SortedMap[Int, Execution]]
  private val startedSuites = mutable.Set.empty[NodeId]
  private var capturedLines = Vector.empty[CapturedLine]
  private var eventStreamOpen = true

  def isEventStreamOpen: Boolean = eventStreamOpen

  def add(event: TestEventLogEvent): Unit = {
    require(eventStreamOpen, s"the event stream of a run under ${label.value} has ended")
    event match {
      case TestEventLogEvent.Structure(nodes) =>
        nodes.foreach(addToStructure)
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
      val id = RunAssembly.idOf(node)
      val firstExecution = executions.get(id).flatMap(_.values.headOption)
      val abortedSuiteAround = insideAbortedSuite || RunAssembly.isAbortedSuite(node, firstExecution)
      val children =
        childrenByParent.getOrElse(Some(id), Vector.empty).sortBy(RunAssembly.positionOf).map(assembled(_, abortedSuiteAround))
      val started = firstExecution.nonEmpty || children.exists(_.started)
      AssembledNode(
        node,
        RunAssembly.outcomeOf(node, firstExecution, abortedSuiteAround, started),
        firstExecution.flatMap(RunAssembly.endingOf),
        firstExecution.fold(Vector.empty[PlacedMessage])(_.messages.map(RunAssembly.placed(_, children))),
        children,
        started
      )
    }
    childrenByParent.getOrElse(None, Vector.empty).map(assembled(_, false))
  }

  private def addToStructure(node: StructureNode): Unit = {
    val id = RunAssembly.idOf(node)
    structure.get(id).flatMap(RunAssembly.parentOf).foreach(previousParent => childIds.get(previousParent).foreach(_ -= id))
    structure(id) = node
    RunAssembly.parentOf(node).foreach(parent => childIds.getOrElseUpdate(parent, mutable.Set.empty) += id)
  }

  private def addToExecution(nodeExecution: NodeExecution, event: DecodedEvent): Unit = {
    val nodeExecutions = executions.getOrElse(nodeExecution.node, SortedMap.empty[Int, Execution])
    val execution = nodeExecutions.getOrElse(
      nodeExecution.execution,
      Execution(nodeExecution.execution, nodeExecution.parentExecution, Vector.empty, Vector.empty)
    )
    val updated =
      if (RunAssembly.isMessage(event)) execution.added(ReceivedMessage(event, childrenStarted(nodeExecution.node, nodeExecution.execution)))
      else execution.added(event)
    executions(nodeExecution.node) = nodeExecutions.updated(nodeExecution.execution, updated)
    markSuitesStarted(nodeExecution.node)
  }

  // A scope or test has started in its parent's execution once it has an execution inside that one. A nested suite
  // has started once it, or a node below it, has an execution.
  private def childrenStarted(parent: NodeId, parentExecution: Int): Set[NodeId] =
    childIds.get(parent).fold(Set.empty[NodeId]) { children =>
      children.iterator.filter { child =>
        structure(child) match {
          case _: StructureNode.Suite => startedSuites.contains(child)
          case _ => hasExecutionIn(child, parentExecution)
        }
      }.toSet
    }

  private def hasExecutionIn(child: NodeId, parentExecution: Int): Boolean =
    executions.get(child).exists(_.values.exists(_.parentExecution.contains(parentExecution)))

  private def markSuitesStarted(node: NodeId): Unit =
    structure.get(node).foreach { structureNode =>
      if (structureNode.isInstanceOf[StructureNode.Suite]) {
        startedSuites += node
      }
      RunAssembly.parentOf(structureNode).foreach(markSuitesStarted)
    }
}

private[consolidation] object RunAssembly {

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted) => true
      case _ => false
    }

  private def isMessage(event: DecodedEvent): Boolean =
    event match {
      case _: DecodedEvent.InfoProvided | _: DecodedEvent.NoteProvided | _: DecodedEvent.AlertProvided | _: DecodedEvent.MarkupProvided => true
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

  // A message sits after the last of its node's children, in declared order, that had started when it arrived.
  private def placed(message: ReceivedMessage, children: Vector[AssembledNode]): PlacedMessage =
    PlacedMessage(message.event, children.lastIndexWhere(child => message.childrenStarted.contains(idOf(child.node))) + 1)

  private def isAbortedSuite(node: StructureNode, execution: Option[Execution]): Boolean =
    node.isInstanceOf[StructureNode.Suite] && execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteAborted]))

  private def endingOf(execution: Execution): Option[DecodedEvent] =
    execution.events.find {
      case _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted => true
      case _ => false
    }

  private def outcomeOf(node: StructureNode, execution: Option[Execution], insideAbortedSuite: Boolean, started: Boolean): Option[Outcome] =
    (node, execution) match {
      case (_: StructureNode.Suite, _) => suiteOutcome(node, execution, started)
      case (_, None) => Some(Outcome.NotRun)
      case (_: StructureNode.Scope, Some(execution)) => scopeOutcome(execution, insideAbortedSuite)
      case (_: StructureNode.Test, Some(execution)) => testOutcome(execution, insideAbortedSuite)
    }

  private def suiteOutcome(node: StructureNode, execution: Option[Execution], started: Boolean): Option[Outcome] =
    if (!started) Some(Outcome.NotRun)
    else if (isAbortedSuite(node, execution)) Some(Outcome.Aborted)
    else None

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
