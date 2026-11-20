package org.scalatest.consolidation

import java.time.Instant
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.StructureNode
import org.scalatest.events.SuiteId
import org.scalatest.events.TestName

private[consolidation] final case class AttributionKey(suiteId: SuiteId, testName: TestName)

private[consolidation] final case class Subtree(started: Boolean, testsBelow: Vector[NodeId]) {

  def isAttributed(isAttributedTest: NodeId => Boolean): Boolean = !started && testsBelow.nonEmpty && testsBelow.forall(isAttributedTest)
}

private[consolidation] final class TreeAssembler(run: RecordedRun) {

  private val structure = run.structure
  private val executions = run.executions
  private val runFinished = run.outcome.nonEmpty
  private val runEndedAbnormally = run.outcome.exists(_.isAbnormal)
  private val childrenByParent = structure.values.toVector.groupBy(_.parentId)
  private val orderFirstHeld = structure.keysIterator.zipWithIndex.toMap
  private val keysOfTestsThatRan = executions.keysIterator.flatMap(TreeAssembler.attributionKeyOf).toSet
  private val topLevelNodes = childrenByParent.getOrElse(None, Vector.empty)
  private val subtrees = topLevelNodes.flatMap(withDescendantsChildrenFirst).foldLeft(Map.empty[NodeId, Subtree])(withSubtreeOf)

  def topLevelSuites: Vector[AssembledNode] =
    inNameAndStartOrder(topLevelNodes.filterNot(isAttributed).flatMap(assembledTopLevel))

  private def assembledTopLevel(suite: StructureNode): Vector[AssembledNode] =
    assembledNodes(suite, executionIndexesOf(suite.id), abortedSuiteAround = None)

  private def childrenOf(id: NodeId): Vector[StructureNode] = childrenByParent.getOrElse(Some(id), Vector.empty)

  private def withDescendantsChildrenFirst(node: StructureNode): Vector[StructureNode] =
    childrenOf(node.id).flatMap(withDescendantsChildrenFirst) :+ node

  private def withSubtreeOf(subtreesBelow: Map[NodeId, Subtree], node: StructureNode): Map[NodeId, Subtree] = {
    val children = childrenOf(node.id)
    val started = executions.contains(node.id) || children.exists(child => subtreesBelow(child.id).started)
    val testsBelow = children.flatMap {
      case test: StructureNode.Test => Vector(test.id)
      case suiteOrScope => subtreesBelow(suiteOrScope.id).testsBelow
    }
    subtreesBelow.updated(node.id, Subtree(started, testsBelow))
  }

  private def isAttributedTest(id: NodeId): Boolean =
    !executions.contains(id) && TreeAssembler.attributionKeyOf(id).exists(keysOfTestsThatRan)

  private def isAttributed(node: StructureNode): Boolean =
    node match {
      case test: StructureNode.Test => isAttributedTest(test.id)
      case suiteOrScope => subtrees(suiteOrScope.id).isAttributed(isAttributedTest)
    }

  private def shownChildrenOf(id: NodeId): OrderedChildren = orderedChildren(id, childrenOf(id).filterNot(isAttributed))

  private def orderedChildren(parent: NodeId, children: Vector[StructureNode]): OrderedChildren = {
    val scopesAndTests = children.filter(child => NodeKind.of(child).isScopeOrTest).sortBy(child => (child.declaredPosition, isDropped(child)))
    val suites = children.collect { case suite: StructureNode.Suite => suite }
    val (placedSuites, suitesWithoutPosition) = suites.partition(_.position.nonEmpty)
    val (furtherRunsOfPlacedSuites, otherSuites) = suitesWithoutPosition.partition(suite => placedSuites.exists(_.suiteId == suite.suiteId))
    val firstScopeOrTest = run.firstScopeOrTestPosition.get(parent)
    val (suitesBefore, suitesAfter) =
      placedSuites.sortBy(_.position).partition(suite => firstScopeOrTest.exists(first => suite.position.exists(_ < first)))
    OrderedChildren(furtherRunsOfPlacedSuites.foldLeft(suitesBefore ++ scopesAndTests ++ suitesAfter)(TreeAssembler.withAddedSuite), otherSuites)
  }

  private def isDropped(node: StructureNode): Boolean =
    NodeKind.of(node).isScopeOrTest &&
      !suiteOf(node).exists(suite => run.latestScopesAndTests.getOrElse(suite, Set.empty).contains(node.id))

  private def suiteOf(node: StructureNode): Option[NodeId] =
    node match {
      case suite: StructureNode.Suite => Some(suite.id)
      case scopeOrTest => scopeOrTest.parentId.flatMap(structure.get).flatMap(suiteOf)
    }

  private def executionIndexesOf(id: NodeId): Vector[Option[ExecutionIndex]] = {
    val indexes = executions.of(id).map(_.index)
    if (indexes.isEmpty) Vector(None) else indexes.map(Some(_))
  }

  private def executionOf(id: NodeId, index: Option[ExecutionIndex]): Option[RecordedExecution] =
    index.flatMap(executionIndex => executions.get(id).flatMap(_.get(executionIndex)))

  private def parentIndexOf(id: NodeId, index: Option[ExecutionIndex], parent: NodeId, parentsLatest: Option[ExecutionIndex]): Option[ExecutionIndex] =
    executionOf(id, index)
      .flatMap(_.parent)
      .filter(named => named.node == parent && executions.get(parent).exists(_.contains(named.index)))
      .map(_.index)
      .orElse(parentsLatest)

  private def assembledNodes(
    node: StructureNode,
    indexes: Vector[Option[ExecutionIndex]],
    abortedSuiteAround: Option[DecodedEvent.SuiteAborted]
  ): Vector[AssembledNode] =
    indexes.map(index => AssembledNode(node, assembledExecution(node, index, abortedSuiteAround)))

  private def inNameAndStartOrder(suites: Vector[AssembledNode]): Vector[AssembledNode] =
    suites.sortBy { suite =>
      val id = suite.node.id
      val startOrder = executionOf(id, suite.execution.index).map(_.startOrder)
      (suite.name, startOrder.isEmpty, startOrder, orderFirstHeld(id))
    }

  private def assembledUnder(
    child: StructureNode,
    parent: NodeId,
    parentIndex: Option[ExecutionIndex],
    parentsLatest: Option[ExecutionIndex],
    abortedSuiteAround: Option[DecodedEvent.SuiteAborted]
  ): Vector[AssembledNode] = {
    val id = child.id
    val indexesHere = executionIndexesOf(id).filter(index => parentIndexOf(id, index, parent, parentsLatest) == parentIndex)
    assembledNodes(child, indexesHere, abortedSuiteAround)
  }

  private def assembledExecution(
    node: StructureNode,
    index: Option[ExecutionIndex],
    abortedSuiteAround: Option[DecodedEvent.SuiteAborted]
  ): AssembledExecution = {
    val id = node.id
    val execution = executionOf(id, index)
    // Only a suite's own `SuiteAborted` aborts its subtree: a test's execution can hold the `SuiteAborted` of the
    // `DistributedTestRunnerSuite` that ran it, which aborts only that test.
    val abortedSuiteHere = node match {
      case _: StructureNode.Suite => execution.flatMap(_.suiteAborted).orElse(abortedSuiteAround)
      case _ => abortedSuiteAround
    }
    val latest = executionIndexesOf(id).last
    val shownChildren = shownChildrenOf(id)
    def assembled(children: Vector[StructureNode]): Vector[AssembledNode] =
      children.flatMap(assembledUnder(_, id, index, latest, abortedSuiteHere))
    val children = assembled(shownChildren.inDeclaredOrder) ++ inNameAndStartOrder(assembled(shownChildren.unplacedSuites))
    val executionStarted = execution.nonEmpty || children.exists(_.started)
    val outcome = outcomeOf(node, execution, openScopeOrTestAborted = abortedSuiteHere.nonEmpty || runEndedAbnormally, started = executionStarted)
    val closing = execution.flatMap(_.closing)
    AssembledExecution(
      index,
      outcome,
      execution.flatMap(_.failureEnding),
      execution.flatMap(_.opening),
      closing,
      Option.when(outcome.contains(NodeOutcome.Aborted) && closing.isEmpty)(abortTime(abortedSuiteAround)).flatten,
      execution.fold(Vector.empty[PlacedMessage])(_.messages.map(_.placedAmong(children))),
      children
    )
  }

  private def outcomeOf(
    node: StructureNode,
    execution: Option[RecordedExecution],
    openScopeOrTestAborted: Boolean,
    started: Boolean
  ): Option[NodeOutcome] =
    (node, execution) match {
      case (_: StructureNode.Suite, _) =>
        if (!started) Some(NodeOutcome.NotRun)
        else execution.fold(NodeOutcome.abortedWhere(runEndedAbnormally))(_.suiteOutcome(runFinished = runFinished, runEndedAbnormally = runEndedAbnormally))
      case (test: StructureNode.Test, None) => Some(if (test.ignored) NodeOutcome.Ignored else NodeOutcome.NotRun)
      case (_, None) => Some(NodeOutcome.NotRun)
      case (_: StructureNode.Scope, Some(execution)) =>
        execution.scopeOutcome(abortedWhereOpen = openScopeOrTestAborted || startedInFinishedRun(execution, _.isInstanceOf[DecodedEvent.ScopeOpened]))
      case (_: StructureNode.Test, Some(execution)) =>
        execution.testOutcome(abortedWhereOpen = openScopeOrTestAborted || startedInFinishedRun(execution, _.isInstanceOf[DecodedEvent.TestStarting]))
    }

  private def startedInFinishedRun(execution: RecordedExecution, isStartEvent: DecodedEvent => Boolean): Boolean =
    runFinished && execution.holds(isStartEvent)

  private def abortTime(abortedSuiteAround: Option[DecodedEvent.SuiteAborted]): Option[Instant] =
    abortedSuiteAround.map(_.timeStamp).orElse(run.abort.map(_.timeStamp)).map(Instant.ofEpochMilli)
}

private[consolidation] object TreeAssembler {

  def topLevelSuitesOf(run: RecordedRun): Vector[AssembledNode] = new TreeAssembler(run).topLevelSuites

  private def attributionKeyOf(id: NodeId): Option[AttributionKey] =
    id.places.takeRight(2) match {
      case Vector(NodePlace.Suite(suiteId, _), NodePlace.Test(testName)) => Some(AttributionKey(suiteId, testName))
      case _ => None
    }

  private def withAddedSuite(siblings: Vector[StructureNode], added: StructureNode.Suite): Vector[StructureNode] = {
    val lastSharingSuiteId = siblings.lastIndexWhere {
      case sibling: StructureNode.Suite => sibling.suiteId == added.suiteId
      case _ => false
    }
    if (lastSharingSuiteId < 0) siblings :+ added else siblings.patch(lastSharingSuiteId + 1, Vector(added), 0)
  }
}
