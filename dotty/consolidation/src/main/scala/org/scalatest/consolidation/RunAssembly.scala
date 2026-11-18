package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.NodePlace
import org.scalatest.events.RunOutcome
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

/** What attributes a test with no execution to a test that ran: the suite ID of the nearest suite above it, and its test name. */
private[consolidation] final case class AttributionKey(suiteId: String, testName: String)

private[consolidation] final case class ExecutionOfNode(node: NodeId, index: Int)

/** An `info`, `note`, `alert` or `markup` message, with the executions of its node's children that had started in its execution when it arrived. */
private[consolidation] final case class ReceivedMessage(event: DecodedEvent, childrenStarted: Set[ExecutionOfNode])

/**
 * @param parent the execution of the node's parent that this one ran in, as the first execution path naming it gives
 */
private[consolidation] final case class RecordedExecution(
  index: Int,
  startOrder: Int,
  parent: Option[ExecutionOfNode],
  events: Vector[DecodedEvent],
  messages: Vector[ReceivedMessage]
) {

  def added(event: DecodedEvent): RecordedExecution = copy(events = events :+ event)

  def added(message: ReceivedMessage): RecordedExecution = copy(messages = messages :+ message)

  def holds(isOfKind: DecodedEvent => Boolean): Boolean = events.exists(isOfKind)
}

/** The outcome a node's events give it. A scope that opened and closed, and a suite that did not abort, have none. */
private[consolidation] enum NodeOutcome {
  case Succeeded
  case Failed
  case Ignored
  case Pending
  case Canceled
  case Aborted
  case NotRun

  def isAbortedOrNotRun: Boolean = this == Aborted || this == NotRun
}

/**
 * A node's children in two groups.
 *
 * @param inDeclaredOrder its scopes, tests and suites with a declared position, each further run of such a suite
 *                        directly after it
 * @param unplacedSuites suites without a declared position that share no suite ID with a placed sibling; assembly
 *                       orders their executions by name, then start order
 */
private[consolidation] final case class OrderedChildren(inDeclaredOrder: Vector[StructureNode], unplacedSuites: Vector[StructureNode])

/** A message of a suite, scope or test, placed after the first `childrenBefore` of the children shown under its execution. */
private[consolidation] final case class PlacedMessage(event: DecodedEvent, childrenBefore: Int)

/**
 * @param index absent where the node has no execution
 * @param ending the `TestFailed`, `TestCanceled` or `SuiteAborted` whose lines sit under the execution's line
 * @param children the nodes shown under this execution, in order: a suite, scope or test once for each of its
 *                 executions that ran in this one
 */
private[consolidation] final case class AssembledExecution(
  index: Option[Int],
  outcome: Option[NodeOutcome],
  ending: Option[DecodedEvent],
  messages: Vector[PlacedMessage],
  children: Vector[AssembledNode]
) {

  def started: Boolean = index.nonEmpty || children.exists(_.started)

  // A collapsed execution shows the nodes below it only as a count.
  def isCollapsed: Boolean =
    outcome.exists(_.isAbortedOrNotRun) && !children.exists(_.started)

  def descendants: Vector[AssembledNode] = children.flatMap(child => child +: child.descendants)
}

/** A node of a run's test structure as the human-readable output shows it, once for each of its executions. */
private[consolidation] final case class AssembledNode(node: StructureNode, execution: AssembledExecution) {

  def name: String = node.name

  def started: Boolean = execution.started

  def descendants: Vector[AssembledNode] = execution.descendants
}

/** A message attached to a run rather than to a node, or a line captured from the run's processes. */
private[consolidation] enum RunOutput {
  case Message(event: DecodedEvent)
  case Captured(line: CapturedLine)
}

/**
 * Builds one run's test structure from its event stream as the stream arrives. It keeps each node's structure by
 * node path, and the node's executions separately, by execution index.
 *
 * @param number the run's place, from 1, among its module's runs in the order they were added
 */
private[consolidation] final class RunAssembly(val label: TestRunLabel, val number: Int) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val childIds = mutable.Map.empty[NodeId, mutable.Set[NodeId]]
  private val latestScopesAndTests = mutable.Map.empty[NodeId, Set[NodeId]]
  private val firstScopeOrTestPosition = mutable.Map.empty[NodeId, Option[Int]]
  private val executions = mutable.Map.empty[NodeId, SortedMap[Int, RecordedExecution]]
  private var executionsStarted = 0
  private var received = Vector.empty[RunOutput]
  private var abort = Option.empty[DecodedEvent.RunAborted]
  private var ending = Option.empty[RunOutcome]
  private var eventStreamOpen = true
  private var assembledTree = Vector.empty[AssembledNode]

  def isEventStreamOpen: Boolean = eventStreamOpen

  /** How the run ended, from its `runEnded` event alone: absent until that event arrives. */
  def outcome: Option[RunOutcome] = ending

  /** The run's first `RunAborted`, whose message and throwable explain an aborted run's end. */
  def runAborted: Option[DecodedEvent.RunAborted] = abort

  def output: Vector[RunOutput] = received

  def add(event: TestEventLogEvent): Unit = {
    require(eventStreamOpen, s"the event stream of a run under ${label.value} has ended")
    event match {
      case TestEventLogEvent.Structure(nodes) =>
        addStructure(nodes)
      case TestEventLogEvent.ScalaTestEvent(Some(nodeExecution), decoded) =>
        addToExecution(nodeExecution, decoded)
      case TestEventLogEvent.ScalaTestEvent(None, decoded) =>
        addToRun(decoded)
      case TestEventLogEvent.RunEnded(runOutcome) =>
        ending = Some(runOutcome)
      case _: TestEventLogEvent.RunStarted =>
        ()
    }
  }

  def add(line: CapturedLine): Unit = received :+= RunOutput.Captured(line)

  /** Ends the event stream and assembles the run's tree, which every later reader of the tree shares. */
  def endEventStream(): Unit =
    if (eventStreamOpen) {
      eventStreamOpen = false
      assembledTree = new TreeAssembler().topLevelSuites
    }

  /**
   * The run's top-level suites, once for each execution, by name and then by the order the executions started. Attributed
   * nodes are left out. Attribution needs the whole event stream, so this requires it to have ended.
   */
  def topLevelSuites: Vector[AssembledNode] = {
    require(!eventStreamOpen, s"the event stream of a run under ${label.value} has not ended")
    assembledTree
  }

  /**
   * Whether any suite, scope or test execution in the assembled tree is aborted or not run.
   * Attributed nodes are not in the tree, so they never count. Requires the event stream to have ended.
   */
  def holdsAbortedOrNotRunNode: Boolean =
    topLevelSuites.exists(suite => (suite +: suite.descendants).exists(_.execution.outcome.exists(_.isAbortedOrNotRun)))

  /**
   * Assembles the run's tree from its whole event stream, once. It works out once per node whether the node started
   * and which tests are below it.
   */
  private final class TreeAssembler {

    private val runFinished = ending.nonEmpty
    private val runEndedAbnormally = RunAssembly.isAbnormal(ending)
    private val childrenByParent = structure.values.toVector.groupBy(_.parentId)
    private val orderFirstHeld = structure.keysIterator.zipWithIndex.toMap
    private val keysOfTestsThatRan = executions.keysIterator.flatMap(RunAssembly.attributionKeyOf).toSet
    private val startedById = mutable.HashMap.empty[NodeId, Boolean]
    private val testsBelowById = mutable.HashMap.empty[NodeId, Vector[NodeId]]
    private val shownChildrenById = mutable.HashMap.empty[NodeId, OrderedChildren]

    def topLevelSuites: Vector[AssembledNode] =
      inNameAndStartOrder(
        childrenByParent.getOrElse(None, Vector.empty).filterNot(isAttributed).flatMap(suite => assembledNodes(suite, executionIndexesOf(suite.id), insideAbortedSuite = false))
      )

    private def childrenOf(id: NodeId): Vector[StructureNode] = childrenByParent.getOrElse(Some(id), Vector.empty)

    private def started(id: NodeId): Boolean =
      startedById.getOrElseUpdate(id, executions.contains(id) || childrenOf(id).exists(child => started(child.id)))

    private def testsBelow(id: NodeId): Vector[NodeId] =
      testsBelowById.getOrElseUpdate(id, {
        childrenOf(id).flatMap {
          case test: StructureNode.Test => Vector(test.id)
          case suiteOrScope => testsBelow(suiteOrScope.id)
        }
      })

    private def isAttributedTest(id: NodeId): Boolean =
      !executions.contains(id) && RunAssembly.attributionKeyOf(id).exists(keysOfTestsThatRan)

    private def isAttributed(node: StructureNode): Boolean =
      node match {
        case test: StructureNode.Test => isAttributedTest(test.id)
        case suiteOrScope =>
          val id = suiteOrScope.id
          val tests = testsBelow(id)
          !started(id) && tests.nonEmpty && tests.forall(isAttributedTest)
      }

    private def shownChildrenOf(id: NodeId): OrderedChildren =
      shownChildrenById.getOrElseUpdate(id, orderedChildren(id, childrenOf(id).filterNot(isAttributed)))

    // A node's scopes and tests by declared position; at a shared position, one the latest structure drops follows
    // one it holds. A suite's nested suites sit before or after them, as its first structure placed them. A suite a
    // later structure added that shares a suite ID with a placed sibling sits directly after the last sibling with that
    // suite ID, in the order such suites arrived.
    private def orderedChildren(parent: NodeId, children: Vector[StructureNode]): OrderedChildren = {
      val scopesAndTests = children.filter(RunAssembly.isScopeOrTest).sortBy(child => (RunAssembly.declaredPositionOf(child), isDropped(child)))
      val suites = children.collect { case suite: StructureNode.Suite => suite }
      val (placedSuites, suitesWithoutPosition) = suites.partition(_.position.nonEmpty)
      val (furtherRunsOfPlacedSuites, otherSuites) = suitesWithoutPosition.partition(suite => placedSuites.exists(_.suiteId == suite.suiteId))
      val firstScopeOrTest = firstScopeOrTestPosition.get(parent).flatten
      val (suitesBefore, suitesAfter) =
        placedSuites.sortBy(_.position).partition(suite => firstScopeOrTest.exists(first => suite.position.exists(_ < first)))
      OrderedChildren(furtherRunsOfPlacedSuites.foldLeft(suitesBefore ++ scopesAndTests ++ suitesAfter)(RunAssembly.withAddedSuite), otherSuites)
    }

    private def isDropped(node: StructureNode): Boolean =
      RunAssembly.isScopeOrTest(node) &&
        !suiteOf(node).exists(suite => latestScopesAndTests.getOrElse(suite, Set.empty).contains(node.id))

    private def suiteOf(node: StructureNode): Option[NodeId] =
      node match {
        case suite: StructureNode.Suite => Some(suite.id)
        case scopeOrTest => scopeOrTest.parentId.flatMap(structure.get).flatMap(suiteOf)
      }

    private def executionIndexesOf(id: NodeId): Vector[Option[Int]] = {
      val indexes = executionsOf(id).map(_.index)
      if (indexes.isEmpty) Vector(None) else indexes.map(Some(_))
    }

    private def executionOf(id: NodeId, index: Option[Int]): Option[RecordedExecution] =
      index.flatMap(executionIndex => executions.get(id).flatMap(_.get(executionIndex)))

    // An execution sits under the parent execution its path names. A node with no execution, and an execution whose
    // path names no execution of the node's latest parent, sit under the parent's latest.
    private def parentIndexOf(id: NodeId, index: Option[Int], parent: NodeId, parentsLatest: Option[Int]): Option[Int] =
      executionOf(id, index)
        .flatMap(_.parent)
        .filter(named => named.node == parent && executions.get(parent).exists(_.contains(named.index)))
        .map(_.index)
        .orElse(parentsLatest)

    private def assembledNodes(node: StructureNode, indexes: Vector[Option[Int]], insideAbortedSuite: Boolean): Vector[AssembledNode] =
      indexes.map(index => AssembledNode(node, assembledExecution(node, index, insideAbortedSuite)))

    // A suite with no execution follows the executions of its name, in the order the structures first named those
    // suites.
    private def inNameAndStartOrder(suites: Vector[AssembledNode]): Vector[AssembledNode] =
      suites.sortBy { suite =>
        val id = suite.node.id
        val startOrder = executionOf(id, suite.execution.index).map(_.startOrder)
        (suite.name, startOrder.fold((1, orderFirstHeld(id)))((0, _)))
      }

    private def assembledUnder(child: StructureNode, parent: NodeId, parentIndex: Option[Int], parentsLatest: Option[Int], insideAbortedSuite: Boolean): Vector[AssembledNode] = {
      val id = child.id
      val indexesHere = executionIndexesOf(id).filter(index => parentIndexOf(id, index, parent, parentsLatest) == parentIndex)
      assembledNodes(child, indexesHere, insideAbortedSuite)
    }

    private def assembledExecution(node: StructureNode, index: Option[Int], insideAbortedSuite: Boolean): AssembledExecution = {
      val id = node.id
      val execution = executionOf(id, index)
      val abortedSuiteAround = insideAbortedSuite || RunAssembly.isAbortedSuite(node, execution)
      val latest = executionIndexesOf(id).last
      val shownChildren = shownChildrenOf(id)
      def assembled(children: Vector[StructureNode]): Vector[AssembledNode] =
        children.flatMap(assembledUnder(_, id, index, latest, abortedSuiteAround))
      val children = assembled(shownChildren.inDeclaredOrder) ++ inNameAndStartOrder(assembled(shownChildren.unplacedSuites))
      val executionStarted = execution.nonEmpty || children.exists(_.started)
      AssembledExecution(
        index,
        RunAssembly.outcomeOf(
          node,
          execution,
          openScopeOrTestAborted = abortedSuiteAround || runEndedAbnormally,
          runFinished = runFinished,
          runEndedAbnormally = runEndedAbnormally,
          started = executionStarted
        ),
        execution.flatMap(RunAssembly.endingOf),
        execution.fold(Vector.empty[PlacedMessage])(_.messages.map(RunAssembly.placed(_, children))),
        children
      )
    }
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
        case scopeOrTest => scopeOrTest.parentId.flatMap(suites.get).fold(suites)(suites.updated(scopeOrTest.id, _))
      }
    }
    val scopesAndTests = nodes.filter(RunAssembly.isScopeOrTest)
    val positionsByParent = scopesAndTests.groupMap(_.parentId)(RunAssembly.declaredPositionOf)
    val scopesAndTestsBySuite = scopesAndTests.groupMap(node => suiteOfNode.get(node.id))(_.id)
    firstSeenSuites.foreach { suite =>
      firstScopeOrTestPosition(suite) = positionsByParent.getOrElse(Some(suite), Vector.empty).minOption
    }
    suites.foreach { suite =>
      latestScopesAndTests(suite) = scopesAndTestsBySuite.getOrElse(Some(suite), Vector.empty).toSet
    }
  }

  private def addToStructure(node: StructureNode): Unit = {
    val id = node.id
    structure.get(id).flatMap(_.parentId).foreach(previousParent => childIds.get(previousParent).foreach(_ -= id))
    structure(id) = node
    node.parentId.foreach(parent => childIds.getOrElseUpdate(parent, mutable.Set.empty) += id)
  }

  private def executionsOf(id: NodeId): Vector[RecordedExecution] = executions.get(id).fold(Vector.empty[RecordedExecution])(_.values.toVector)

  // An event assigned to the run rather than to a node shows no line, unless it is a message or the run's first
  // `RunAborted`. These include a `DiscoverySuite`'s events, which the emitter assigns to the run.
  private def addToRun(event: DecodedEvent): Unit =
    event match {
      case aborted: DecodedEvent.RunAborted => if (abort.isEmpty) abort = Some(aborted)
      case message if RunAssembly.isMessage(message) => received :+= RunOutput.Message(message)
      case _ => ()
    }

  private def addToExecution(nodeExecution: NodeExecution, event: DecodedEvent): Unit = {
    recordExecutionPath(nodeExecution)
    val node = nodeExecution.node
    val index = nodeExecution.executionIndex
    val execution = executions(node)(index)
    val updated =
      if (RunAssembly.isMessage(event)) execution.added(ReceivedMessage(event, childrenStarted(node, index)))
      else execution.added(event)
    executions(node) = executions(node).updated(index, updated)
  }

  // Reads the execution path against the latest structure. Its last index is the node's, and each index before it
  // belongs to the next node up, counting each scope between a test and its suite. An execution keeps the parent
  // execution it first had. Start order goes from the top down, so a parent's precedes its child's.
  private def recordExecutionPath(nodeExecution: NodeExecution): Unit = {
    val nodesUp = Iterator.unfold(Option(nodeExecution.node))(_.map(node => (node, structure.get(node).flatMap(_.parentId)))).toVector
    val executionsUp = nodesUp.zip(nodeExecution.executionPath.reverse).map((node, index) => ExecutionOfNode(node, index))
    executionsUp.indices.reverse.foreach { position =>
      val execution = executionsUp(position)
      val nodeExecutions = executions.getOrElse(execution.node, SortedMap.empty[Int, RecordedExecution])
      if (!nodeExecutions.contains(execution.index)) {
        executionsStarted += 1
        val recorded = RecordedExecution(execution.index, executionsStarted, executionsUp.lift(position + 1), Vector.empty, Vector.empty)
        executions(execution.node) = nodeExecutions.updated(execution.index, recorded)
      }
    }
  }

  private def childrenStarted(parent: NodeId, parentExecution: Int): Set[ExecutionOfNode] =
    childIds.get(parent).fold(Set.empty[ExecutionOfNode]) { children =>
      children.iterator.flatMap { child =>
        executionsOf(child)
          .filter(_.parent.contains(ExecutionOfNode(parent, parentExecution)))
          .map(execution => ExecutionOfNode(child, execution.index))
      }.toSet
    }
}

private[consolidation] object RunAssembly {

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, decoded) => isFailureEnding(decoded)
      case _ => false
    }

  def endsOtherThanCompleted(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.RunEnded(outcome) => isAbnormal(Some(outcome))
      case _ => false
    }

  private def isAbnormal(outcome: Option[RunOutcome]): Boolean = outcome.exists(_ != RunOutcome.Completed)

  private def isMessage(event: DecodedEvent): Boolean =
    event match {
      case _: DecodedEvent.InfoProvided | _: DecodedEvent.NoteProvided | _: DecodedEvent.AlertProvided | _: DecodedEvent.MarkupProvided => true
      case _ => false
    }

  private def isScopeOrTest(node: StructureNode): Boolean = !node.isInstanceOf[StructureNode.Suite]

  // The place before a test's is its suite's, which gives the suite ID.
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
    child.execution.index.exists(index => message.childrenStarted.contains(ExecutionOfNode(child.node.id, index)))

  private def isAbortedSuite(node: StructureNode, execution: Option[RecordedExecution]): Boolean =
    node.isInstanceOf[StructureNode.Suite] && execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteAborted]))

  private def endingOf(execution: RecordedExecution): Option[DecodedEvent] = execution.events.find(isFailureEnding)

  private def isFailureEnding(event: DecodedEvent): Boolean =
    event match {
      case _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted => true
      case _ => false
    }

  // Marks as aborted:
  // - a scope or test still open when its suite aborted, or when its run aborted or stopped;
  // - a suite still open when its run aborted or stopped;
  // - a suite, scope or test with a start event and no closing event, in a run that ended.
  // A suite run by a test sends no start event, so the last case never marks it aborted.
  private def outcomeOf(
    node: StructureNode,
    execution: Option[RecordedExecution],
    openScopeOrTestAborted: Boolean,
    runFinished: Boolean,
    runEndedAbnormally: Boolean,
    started: Boolean
  ): Option[NodeOutcome] =
    (node, execution) match {
      case (_: StructureNode.Suite, _) =>
        suiteOutcome(node, execution, runFinished = runFinished, runEndedAbnormally = runEndedAbnormally, started = started)
      case (test: StructureNode.Test, None) => Some(if (test.ignored) NodeOutcome.Ignored else NodeOutcome.NotRun)
      case (_, None) => Some(NodeOutcome.NotRun)
      case (_: StructureNode.Scope, Some(execution)) =>
        scopeOutcome(execution, openScopeOrTestAborted = openScopeOrTestAborted || (runFinished && execution.holds(_.isInstanceOf[DecodedEvent.ScopeOpened])))
      case (_: StructureNode.Test, Some(execution)) =>
        testOutcome(execution, openScopeOrTestAborted = openScopeOrTestAborted || (runFinished && execution.holds(_.isInstanceOf[DecodedEvent.TestStarting])))
    }

  private def suiteOutcome(
    node: StructureNode,
    execution: Option[RecordedExecution],
    runFinished: Boolean,
    runEndedAbnormally: Boolean,
    started: Boolean
  ): Option[NodeOutcome] = {
    val completed = execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteCompleted]))
    val opened = execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteStarting]))
    if (!started) Some(NodeOutcome.NotRun)
    else if (isAbortedSuite(node, execution)) Some(NodeOutcome.Aborted)
    else if (!completed && (runEndedAbnormally || (runFinished && opened))) Some(NodeOutcome.Aborted)
    else None
  }

  private def scopeOutcome(execution: RecordedExecution, openScopeOrTestAborted: Boolean): Option[NodeOutcome] =
    if (execution.holds(_.isInstanceOf[DecodedEvent.ScopePending]))
      Some(NodeOutcome.Pending)
    else if (execution.holds(_.isInstanceOf[DecodedEvent.ScopeClosed]))
      None
    else
      abortedWhereOpen(openScopeOrTestAborted)

  private def testOutcome(execution: RecordedExecution, openScopeOrTestAborted: Boolean): Option[NodeOutcome] =
    execution.events.collectFirst {
      case _: DecodedEvent.TestSucceeded => NodeOutcome.Succeeded
      case _: DecodedEvent.TestFailed => NodeOutcome.Failed
      case _: DecodedEvent.TestIgnored => NodeOutcome.Ignored
      case _: DecodedEvent.TestPending => NodeOutcome.Pending
      case _: DecodedEvent.TestCanceled => NodeOutcome.Canceled
    }.orElse(abortedWhereOpen(openScopeOrTestAborted))

  private def abortedWhereOpen(openScopeOrTestAborted: Boolean): Option[NodeOutcome] =
    if (openScopeOrTestAborted) Some(NodeOutcome.Aborted) else None
}
