package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

/** A child of a node that had started when a message arrived: a nested suite as a whole, or one execution of a scope or test. */
private[consolidation] final case class StartedChild(node: NodeId, execution: Option[Int])

/** An `info`, `note`, `alert` or `markup` message, with the children of its node that had started in its execution when it arrived. */
private[consolidation] final case class ReceivedMessage(event: DecodedEvent, childrenStarted: Set[StartedChild])

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

/** A message of a suite or scope, placed after the first `childrenBefore` of the children shown under its execution. */
private[consolidation] final case class PlacedMessage(event: DecodedEvent, childrenBefore: Int)

/**
 * What one execution of a node produced, with the nodes shown under it.
 *
 * @param number the execution's number, absent where no event of the node's own names the execution
 * @param ending the `TestFailed`, `TestCanceled` or `SuiteAborted` whose lines sit under the execution's line
 * @param children the nodes shown under this execution in order: each nested suite once, and each scope or test once
 *                 for each of its executions that ran in this one, holding that execution alone
 */
private[consolidation] final case class AssembledExecution(
  number: Option[Int],
  outcome: Option[Outcome],
  ending: Option[DecodedEvent],
  messages: Vector[PlacedMessage],
  children: Vector[AssembledNode]
) {

  def started: Boolean = number.nonEmpty || children.exists(_.started)

  /** Whether the execution aborted or did not run and no node below it started, so the nodes below it show only as a count. */
  def isCollapsed: Boolean =
    outcome.exists(outcome => outcome == Outcome.Aborted || outcome == Outcome.NotRun) && !children.exists(_.started)

  /** The nodes at any depth below this execution. */
  def descendants: Vector[AssembledNode] = children.flatMap(child => child +: child.descendants)
}

/**
 * A node of a run's test structure as the human-readable output shows it: a suite with all its executions, or a scope
 * or test with exactly one, which has no number where the node never started.
 */
private[consolidation] final case class AssembledNode(node: StructureNode, executions: Vector[AssembledExecution]) {

  def name: String =
    node match {
      case suite: StructureNode.Suite => suite.name
      case scope: StructureNode.Scope => scope.name
      case test: StructureNode.Test => test.name
    }

  /** Whether the node, or a node below it, has an execution. */
  def started: Boolean = executions.exists(_.started)

  /** The nodes at any depth below this one. */
  def descendants: Vector[AssembledNode] = executions.flatMap(_.descendants)
}

/**
 * Builds one run's test structure from its event stream as the stream arrives. It keeps each node's structure by
 * node ID, and the node's events separately, grouped by execution number.
 */
private[consolidation] final class RunAssembly(val label: TestRunLabel) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val childIds = mutable.Map.empty[NodeId, mutable.Set[NodeId]]
  private val latestScopesAndTests = mutable.Map.empty[NodeId, Set[NodeId]]
  private val firstScopeOrTestPosition = mutable.Map.empty[NodeId, Option[Int]]
  private val executions = mutable.Map.empty[NodeId, SortedMap[Int, Execution]]
  private val startedSuites = mutable.Set.empty[NodeId]
  private var capturedLines = Vector.empty[CapturedLine]
  private var eventStreamOpen = true

  def isEventStreamOpen: Boolean = eventStreamOpen

  def add(event: TestEventLogEvent): Unit = {
    require(eventStreamOpen, s"the event stream of a run under ${label.value} has ended")
    event match {
      case TestEventLogEvent.Structure(nodes) =>
        addStructure(nodes)
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

    def childrenOf(id: NodeId): Vector[StructureNode] = childrenByParent.getOrElse(Some(id), Vector.empty)

    def started(id: NodeId): Boolean = executions.contains(id) || childrenOf(id).exists(child => started(RunAssembly.idOf(child)))

    def shownChildrenOf(id: NodeId): Vector[StructureNode] =
      orderedChildren(id, childrenOf(id).filter(child => !isDropped(child) || started(RunAssembly.idOf(child))))

    // A node's own execution numbers, with those of its children's executions' parents: a node shows an execution
    // that one of its children ran in, though no event of its own carries that number.
    def executionNumbersOf(id: NodeId): Vector[Option[Int]] = {
      val parentNumbers = childrenOf(id).flatMap(child => executionsOf(RunAssembly.idOf(child)).flatMap(_.parentExecution))
      val numbers = (executionsOf(id).map(_.number) ++ parentNumbers).distinct.sorted
      if (numbers.isEmpty) Vector(None) else numbers.map(Some(_))
    }

    def assembledSuite(suite: StructureNode.Suite, insideAbortedSuite: Boolean): AssembledNode = {
      val numbers = executionNumbersOf(suite.id)
      AssembledNode(suite, numbers.map(number => assembledExecution(suite, number, number == numbers.last, insideAbortedSuite)))
    }

    // Each scope or test is shown under the execution of its parent its execution ran in. One with no execution, or
    // shown only for an execution its children ran in, is shown under its parent's latest execution.
    def assembledUnder(child: StructureNode, parentNumber: Option[Int], parentIsLatest: Boolean, insideAbortedSuite: Boolean): Vector[AssembledNode] =
      child match {
        case suite: StructureNode.Suite =>
          if (parentIsLatest) Vector(assembledSuite(suite, insideAbortedSuite)) else Vector.empty
        case scopeOrTest =>
          val id = RunAssembly.idOf(scopeOrTest)
          val numbers = executionNumbersOf(id)
          numbers
            .filter(number => parentExecutionOf(id, number).fold(parentIsLatest)(parent => parentNumber.contains(parent)))
            .map(number => AssembledNode(scopeOrTest, Vector(assembledExecution(scopeOrTest, number, number == numbers.last, insideAbortedSuite))))
      }

    def assembledExecution(node: StructureNode, number: Option[Int], isLatest: Boolean, insideAbortedSuite: Boolean): AssembledExecution = {
      val id = RunAssembly.idOf(node)
      val execution = number.flatMap(executionNumber => executions.get(id).flatMap(_.get(executionNumber)))
      val abortedSuiteAround = insideAbortedSuite || RunAssembly.isAbortedSuite(node, execution)
      val children = shownChildrenOf(id).flatMap(assembledUnder(_, number, isLatest, abortedSuiteAround))
      val started = execution.nonEmpty || children.exists(_.started)
      AssembledExecution(
        execution.map(_.number),
        RunAssembly.outcomeOf(node, execution, abortedSuiteAround, started),
        execution.flatMap(RunAssembly.endingOf),
        execution.fold(Vector.empty[PlacedMessage])(_.messages.map(RunAssembly.placed(_, children))),
        children
      )
    }

    childrenByParent.getOrElse(None, Vector.empty).collect { case suite: StructureNode.Suite => assembledSuite(suite, false) }
  }

  // A further structure holds its suites' scopes and tests whole. A scope or test it drops keeps the parent, position
  // and label it last had.
  private def addStructure(nodes: IndexedSeq[StructureNode]): Unit = {
    val suites = nodes.collect { case suite: StructureNode.Suite => suite.id }
    val firstSeenSuites = suites.filterNot(structure.contains)
    nodes.foreach(addToStructure)
    val suiteOfNode = nodes.foldLeft(Map.empty[NodeId, NodeId]) { (suites, node) =>
      node match {
        case suite: StructureNode.Suite => suites.updated(suite.id, suite.id)
        case scopeOrTest => RunAssembly.parentOf(scopeOrTest).flatMap(suites.get).fold(suites)(suites.updated(RunAssembly.idOf(scopeOrTest), _))
      }
    }
    val scopesAndTests = nodes.filter(RunAssembly.isScopeOrTest)
    val positionsByParent = scopesAndTests.groupMap(RunAssembly.parentOf)(RunAssembly.declaredPositionOf)
    val scopesAndTestsBySuite = scopesAndTests.groupMap(node => suiteOfNode.get(RunAssembly.idOf(node)))(RunAssembly.idOf)
    firstSeenSuites.foreach { suite =>
      firstScopeOrTestPosition(suite) = positionsByParent.getOrElse(Some(suite), Vector.empty).minOption
    }
    suites.foreach { suite =>
      latestScopesAndTests(suite) = scopesAndTestsBySuite.getOrElse(Some(suite), Vector.empty).toSet
    }
  }

  private def addToStructure(node: StructureNode): Unit = {
    val id = RunAssembly.idOf(node)
    structure.get(id).flatMap(RunAssembly.parentOf).foreach(previousParent => childIds.get(previousParent).foreach(_ -= id))
    structure(id) = node
    RunAssembly.parentOf(node).foreach(parent => childIds.getOrElseUpdate(parent, mutable.Set.empty) += id)
  }

  private def isDropped(node: StructureNode): Boolean =
    RunAssembly.isScopeOrTest(node) &&
      !suiteOf(node).exists(suite => latestScopesAndTests.getOrElse(suite, Set.empty).contains(RunAssembly.idOf(node)))

  private def suiteOf(node: StructureNode): Option[NodeId] =
    node match {
      case suite: StructureNode.Suite => Some(suite.id)
      case scopeOrTest => RunAssembly.parentOf(scopeOrTest).flatMap(structure.get).flatMap(suiteOf)
    }

  // A node's scopes and tests by declared position, each the latest structure drops after any it holds at that
  // position. A suite's nested suites sit before or after them, as its first structure had them; those a later
  // structure added follow, in the order they arrived.
  private def orderedChildren(parent: NodeId, children: Vector[StructureNode]): Vector[StructureNode] = {
    val scopesAndTests = children.filter(RunAssembly.isScopeOrTest).sortBy(child => (RunAssembly.declaredPositionOf(child), isDropped(child)))
    val suites = children.collect { case suite: StructureNode.Suite => suite }
    val (placedSuites, addedSuites) = suites.partition(_.position.nonEmpty)
    val firstScopeOrTest = firstScopeOrTestPosition.get(parent).flatten
    val (suitesBefore, suitesAfter) =
      placedSuites.sortBy(_.position).partition(suite => firstScopeOrTest.exists(first => suite.position.exists(_ < first)))
    suitesBefore ++ scopesAndTests ++ suitesAfter ++ addedSuites
  }

  private def executionsOf(id: NodeId): Vector[Execution] = executions.get(id).fold(Vector.empty[Execution])(_.values.toVector)

  private def parentExecutionOf(id: NodeId, number: Option[Int]): Option[Int] =
    number.flatMap(executionNumber => executions.get(id).flatMap(_.get(executionNumber))).flatMap(_.parentExecution)

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
  private def childrenStarted(parent: NodeId, parentExecution: Int): Set[StartedChild] =
    childIds.get(parent).fold(Set.empty[StartedChild]) { children =>
      children.iterator.flatMap { child =>
        structure(child) match {
          case _: StructureNode.Suite =>
            if (startedSuites.contains(child)) Vector(StartedChild(child, None)) else Vector.empty
          case _ =>
            executionsOf(child).filter(_.parentExecution.contains(parentExecution)).map(execution => StartedChild(child, Some(execution.number)))
        }
      }.toSet
    }

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

  private def isScopeOrTest(node: StructureNode): Boolean = !node.isInstanceOf[StructureNode.Suite]

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

  private def declaredPositionOf(node: StructureNode): Int =
    node match {
      case suite: StructureNode.Suite => suite.position.getOrElse(Int.MaxValue)
      case scope: StructureNode.Scope => scope.position
      case test: StructureNode.Test => test.position
    }

  // A message sits after the last of the children shown under its execution that had started when it arrived.
  private def placed(message: ReceivedMessage, children: Vector[AssembledNode]): PlacedMessage =
    PlacedMessage(message.event, children.lastIndexWhere(hadStarted(message, _)) + 1)

  private def hadStarted(message: ReceivedMessage, child: AssembledNode): Boolean =
    child.node match {
      case suite: StructureNode.Suite => message.childrenStarted.contains(StartedChild(suite.id, None))
      case scopeOrTest => child.executions.exists(execution => message.childrenStarted.contains(StartedChild(idOf(scopeOrTest), execution.number)))
    }

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
