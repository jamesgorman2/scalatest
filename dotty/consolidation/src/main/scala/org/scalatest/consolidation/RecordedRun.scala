package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.immutable.VectorMap
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.RunOutcome
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

private[consolidation] final case class RecordedRun(
  // In the order the structures first named the nodes.
  structure: VectorMap[NodeId, StructureNode],
  childIds: Map[NodeId, Set[NodeId]],
  // By suite, as the latest structure holding the suite gives them.
  latestScopesAndTests: Map[NodeId, Set[NodeId]],
  // By suite: the first declared position of any of its scopes or tests.
  firstScopeOrTestPosition: Map[NodeId, Int],
  executions: Map[NodeId, SortedMap[ExecutionIndex, RecordedExecution]],
  nextStartOrder: StartOrder,
  starting: Option[DecodedEvent.RunStarting],
  discoveryStarting: Option[DecodedEvent.DiscoveryStarting],
  discoveryCompleted: Option[DecodedEvent.DiscoveryCompleted],
  closing: Option[RunClosing],
  abort: Option[DecodedEvent.RunAborted],
  outcome: Option[RunOutcome]
) {

  def added(event: TestEventLogEvent): RecordedRun =
    event match {
      case TestEventLogEvent.Structure(nodes) =>
        withStructure(nodes)
      case TestEventLogEvent.ScalaTestEvent(Some(nodeExecution), decoded) =>
        withNodeEvent(nodeExecution, decoded)
      case TestEventLogEvent.ScalaTestEvent(None, decoded) =>
        withRunEvent(decoded)
      case TestEventLogEvent.RunEnded(runOutcome) =>
        copy(outcome = Some(runOutcome))
      case _: TestEventLogEvent.RunStarted =>
        this
    }

  def discovery: Option[RunDiscovery] =
    for (started <- discoveryStarting; completed <- discoveryCompleted) yield RunDiscovery.of(started, completed)

  def suiteClassNames: Set[String] = structure.values.collect { case suite: StructureNode.Suite => suite.className }.toSet

  // A further structure holds its suites' scopes and tests whole. A scope or test it drops keeps the parent, position
  // and label it last had. The emitter numbers a suite's scopes and tests from the same first position in every
  // structure, so the first structure holding any of them gives that position.
  private def withStructure(nodes: IndexedSeq[StructureNode]): RecordedRun = {
    val suites = nodes.collect { case suite: StructureNode.Suite => suite.id }
    val suiteOfNode = nodes.foldLeft(Map.empty[NodeId, NodeId])(RecordedRun.withSuiteOf)
    val scopesAndTests = nodes.filter(node => NodeKind.of(node).isScopeOrTest)
    val positionsByParent = scopesAndTests.groupMap(_.parentId)(_.declaredPosition)
    val scopesAndTestsBySuite = scopesAndTests.groupMap(node => suiteOfNode.get(node.id))(_.id)
    def firstPositionIn(suite: NodeId): Option[(NodeId, Int)] =
      positionsByParent.get(Some(suite)).flatMap(_.flatten.minOption).map(suite -> _)
    def latestOf(suite: NodeId): (NodeId, Set[NodeId]) = suite -> scopesAndTestsBySuite.getOrElse(Some(suite), Vector.empty).toSet
    nodes.foldLeft(this)(_.withNode(_)).copy(
      firstScopeOrTestPosition = firstScopeOrTestPosition ++ suites.filterNot(firstScopeOrTestPosition.contains).flatMap(firstPositionIn),
      latestScopesAndTests = latestScopesAndTests ++ suites.map(latestOf)
    )
  }

  private def withNode(node: StructureNode): RecordedRun = {
    val id = node.id
    val previousParent = structure.get(id).flatMap(_.parentId)
    val withoutPreviousParent = previousParent.fold(childIds)(parent => childIds.updatedWith(parent)(_.map(_ - id)))
    copy(
      structure = structure.updated(id, node),
      childIds = node.parentId.fold(withoutPreviousParent)(parent => withoutPreviousParent.updatedWith(parent)(ids => Some(ids.getOrElse(Set.empty) + id)))
    )
  }

  // An event assigned to the run rather than to a node is handled by kind:
  // - a run or discovery event records the run's own state; only the first of each kind is kept;
  // - a message belongs to the run's output, which the run assembly keeps;
  // - a suite's, scope's or test's event, such as a `DiscoverySuite`'s (which the emitter assigns to the run), is dropped.
  private def withRunEvent(event: DecodedEvent): RecordedRun =
    event match {
      case started: DecodedEvent.RunStarting =>
        copy(starting = starting.orElse(Some(started)))
      case started: DecodedEvent.DiscoveryStarting =>
        copy(discoveryStarting = discoveryStarting.orElse(Some(started)))
      case completed: DecodedEvent.DiscoveryCompleted =>
        copy(discoveryCompleted = discoveryCompleted.orElse(Some(completed)))
      case aborted: DecodedEvent.RunAborted =>
        copy(abort = abort.orElse(Some(aborted))).closedBy(aborted)
      case closed: DecodedEvent.RunClosing =>
        closedBy(closed)
      case _: DecodedEvent.Message | _: DecodedEvent.NodeOpening | _: DecodedEvent.NodeClosing =>
        this
    }

  private def closedBy(event: DecodedEvent.RunClosing): RecordedRun = copy(closing = closing.orElse(Some(RunClosing.of(event))))

  private def withNodeEvent(nodeExecution: NodeExecution, event: DecodedEvent): RecordedRun =
    withExecutionPath(nodeExecution).withEventIn(ExecutionOfNode(nodeExecution.node, ExecutionIndex(nodeExecution.executionIndex)), event)

  private def withEventIn(executionOfNode: ExecutionOfNode, event: DecodedEvent): RecordedRun = {
    val nodeExecutions = executions(executionOfNode.node)
    val execution = nodeExecutions(executionOfNode.index)
    val updated = event match {
      case message: DecodedEvent.Message =>
        execution.added(ReceivedMessage(ProvidedMessage.of(message), childrenStartedIn(executionOfNode)))
      case nodeEvent: (DecodedEvent.NodeOpening | DecodedEvent.NodeClosing) =>
        execution.added(nodeEvent)
      case _: DecodedEvent.RunStarting | _: DecodedEvent.DiscoveryStarting | _: DecodedEvent.DiscoveryCompleted | _: DecodedEvent.RunClosing =>
        execution
    }
    copy(executions = executions.updated(executionOfNode.node, nodeExecutions.updated(executionOfNode.index, updated)))
  }

  // Reads the execution path against the latest structure:
  // - its last index is the node's own; each earlier index belongs to the parent, then the grandparent, and so on,
  //   counting each scope between a test and its suite;
  // - an execution keeps the parent execution it first had;
  // - start order runs top down, so a parent's start precedes its child's.
  private def withExecutionPath(nodeExecution: NodeExecution): RecordedRun = {
    val nodesUp = Iterator.unfold(Option(nodeExecution.node))(_.map(node => (node, structure.get(node).flatMap(_.parentId)))).toVector
    val executionsUp = nodesUp.zip(nodeExecution.executionPath.reverse).map((node, index) => ExecutionOfNode(node, ExecutionIndex(index)))
    val parentsUp = executionsUp.drop(1).map(Some(_)) :+ None
    executionsUp.zip(parentsUp).reverse.foldLeft(this) { case (recorded, (execution, parent)) => recorded.withExecutionStarted(execution, parent) }
  }

  private def withExecutionStarted(execution: ExecutionOfNode, parent: Option[ExecutionOfNode]): RecordedRun = {
    val nodeExecutions = executions.getOrElse(execution.node, SortedMap.empty[ExecutionIndex, RecordedExecution])
    if (nodeExecutions.contains(execution.index)) {
      this
    } else {
      val started = RecordedExecution(execution.index, nextStartOrder, parent, Vector.empty, Vector.empty)
      copy(executions = executions.updated(execution.node, nodeExecutions.updated(execution.index, started)), nextStartOrder = nextStartOrder.next)
    }
  }

  private def childrenStartedIn(parent: ExecutionOfNode): Set[ExecutionOfNode] =
    childIds.getOrElse(parent.node, Set.empty).flatMap(child => executionsIn(parent, child))

  private def executionsIn(parent: ExecutionOfNode, child: NodeId): Vector[ExecutionOfNode] =
    executions.of(child).filter(_.parent.contains(parent)).map(execution => ExecutionOfNode(child, execution.index))
}

private[consolidation] object RecordedRun {

  val empty: RecordedRun =
    RecordedRun(VectorMap.empty, Map.empty, Map.empty, Map.empty, Map.empty, StartOrder.first, None, None, None, None, None, None)

  private def withSuiteOf(suiteOfNode: Map[NodeId, NodeId], node: StructureNode): Map[NodeId, NodeId] =
    node match {
      case suite: StructureNode.Suite => suiteOfNode.updated(suite.id, suite.id)
      case scopeOrTest => scopeOrTest.parentId.flatMap(suiteOfNode.get).fold(suiteOfNode)(suiteOfNode.updated(scopeOrTest.id, _))
    }
}
