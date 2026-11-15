package org.scalatest.consolidation

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.RunOutcome
import org.scalatest.events.StructureNode
import org.scalatest.events.TestEventLogEvent

/** One execution of a node, by the node's ID and the execution's number. */
private[consolidation] final case class ExecutionOfNode(node: NodeId, number: Int)

/** An `info`, `note`, `alert` or `markup` message, with the executions of its node's children that had started in its execution when it arrived. */
private[consolidation] final case class ReceivedMessage(event: DecodedEvent, childrenStarted: Set[ExecutionOfNode])

/**
 * One execution of a node: its events, with its messages kept apart in the order they arrived.
 *
 * @param parent the execution of the node's parent that this one ran in, as the first execution path naming it gives
 */
private[consolidation] final case class Execution(
  number: Int,
  parent: Option[ExecutionOfNode],
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
 * @param number the execution's number, absent where the node has no execution
 * @param ending the `TestFailed`, `TestCanceled` or `SuiteAborted` whose lines sit under the execution's line
 * @param children the nodes shown under this execution, in order: a suite once, holding its executions that ran in
 *                 this one, and a scope or test once for each of its executions that ran in this one
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
 * A node of a run's test structure as the human-readable output shows it: a suite with its executions that ran in one
 * execution of its parent, or a scope or test with exactly one. That execution has no number where the node has none.
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
 * node path, and the node's executions separately, by execution number.
 */
private[consolidation] final class RunAssembly(val label: TestRunLabel) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val childIds = mutable.Map.empty[NodeId, mutable.Set[NodeId]]
  private val latestScopesAndTests = mutable.Map.empty[NodeId, Set[NodeId]]
  private val firstScopeOrTestPosition = mutable.Map.empty[NodeId, Option[Int]]
  private val executions = mutable.Map.empty[NodeId, SortedMap[Int, Execution]]
  private var lines = Vector.empty[CapturedLine]
  private var messages = Vector.empty[DecodedEvent]
  private var abort = Option.empty[DecodedEvent.RunAborted]
  private var ending = Option.empty[RunOutcome]
  private var eventStreamOpen = true

  def isEventStreamOpen: Boolean = eventStreamOpen

  /** How the run ended, from its `runEnded` event alone: absent until that event arrives. */
  def outcome: Option[RunOutcome] = ending

  /** The messages attached to the run rather than to a node, in the order they arrived. */
  def runMessages: Vector[DecodedEvent] = messages

  /** The run's first `RunAborted`, whose message and throwable explain an aborted run's end. */
  def runAborted: Option[DecodedEvent.RunAborted] = abort

  /** The lines captured from the run's processes, stdout and stderr interleaved in the order they were fed. */
  def capturedLines: Vector[CapturedLine] = lines

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

  def add(line: CapturedLine): Unit = lines :+= line

  def endEventStream(): Unit = eventStreamOpen = false

  /** The run's top-level suites, in the order the event stream first held them. */
  def topLevelSuites: Vector[AssembledNode] = {
    val runEndedAbnormally = RunAssembly.isAbnormal(ending)
    val childrenByParent = structure.values.toVector.groupBy(RunAssembly.parentOf)

    def childrenOf(id: NodeId): Vector[StructureNode] = childrenByParent.getOrElse(Some(id), Vector.empty)

    def started(id: NodeId): Boolean = executions.contains(id) || childrenOf(id).exists(child => started(RunAssembly.idOf(child)))

    def shownChildrenOf(id: NodeId): Vector[StructureNode] =
      orderedChildren(id, childrenOf(id).filter(child => !isDropped(child) || started(RunAssembly.idOf(child))))

    def executionNumbersOf(id: NodeId): Vector[Option[Int]] = {
      val numbers = executionsOf(id).map(_.number)
      if (numbers.isEmpty) Vector(None) else numbers.map(Some(_))
    }

    // An execution sits under the parent execution its path names. A node with no execution, and an execution whose
    // path names no execution of the node's latest parent, sit under the parent's latest.
    def parentNumberOf(id: NodeId, number: Option[Int], parent: NodeId, parentsLatest: Option[Int]): Option[Int] =
      number
        .flatMap(executionNumber => executions.get(id).flatMap(_.get(executionNumber)))
        .flatMap(_.parent)
        .filter(named => named.node == parent && executions.get(parent).exists(_.contains(named.number)))
        .map(_.number)
        .orElse(parentsLatest)

    def assembledNodes(node: StructureNode, numbers: Vector[Option[Int]], insideAbortedSuite: Boolean): Vector[AssembledNode] = {
      def assembled(number: Option[Int]): AssembledExecution = assembledExecution(node, number, insideAbortedSuite)
      node match {
        case suite: StructureNode.Suite =>
          if (numbers.isEmpty) Vector.empty else Vector(AssembledNode(suite, numbers.map(assembled)))
        case scopeOrTest =>
          numbers.map(number => AssembledNode(scopeOrTest, Vector(assembled(number))))
      }
    }

    def assembledUnder(child: StructureNode, parent: NodeId, parentNumber: Option[Int], parentsLatest: Option[Int], insideAbortedSuite: Boolean): Vector[AssembledNode] = {
      val id = RunAssembly.idOf(child)
      val numbersHere = executionNumbersOf(id).filter(number => parentNumberOf(id, number, parent, parentsLatest) == parentNumber)
      assembledNodes(child, numbersHere, insideAbortedSuite)
    }

    def assembledExecution(node: StructureNode, number: Option[Int], insideAbortedSuite: Boolean): AssembledExecution = {
      val id = RunAssembly.idOf(node)
      val execution = number.flatMap(executionNumber => executions.get(id).flatMap(_.get(executionNumber)))
      val abortedSuiteAround = insideAbortedSuite || RunAssembly.isAbortedSuite(node, execution)
      val latest = executionNumbersOf(id).last
      val children = shownChildrenOf(id).flatMap(assembledUnder(_, id, number, latest, abortedSuiteAround))
      val started = execution.nonEmpty || children.exists(_.started)
      AssembledExecution(
        number,
        RunAssembly.outcomeOf(node, execution, abortedSuiteAround || runEndedAbnormally, runEndedAbnormally, started),
        execution.flatMap(RunAssembly.endingOf),
        execution.fold(Vector.empty[PlacedMessage])(_.messages.map(RunAssembly.placed(_, children))),
        children
      )
    }

    childrenByParent.getOrElse(None, Vector.empty).flatMap(suite => assembledNodes(suite, executionNumbersOf(RunAssembly.idOf(suite)), false))
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

  // A node's scopes and tests by declared position; at a shared position, one the latest structure drops follows
  // one it holds. A suite's nested suites sit before or after them, as its first structure placed them. Each suite a
  // later structure added, in the order they arrived, sits directly after the last sibling sharing its suite ID, else last.
  private def orderedChildren(parent: NodeId, children: Vector[StructureNode]): Vector[StructureNode] = {
    val scopesAndTests = children.filter(RunAssembly.isScopeOrTest).sortBy(child => (RunAssembly.declaredPositionOf(child), isDropped(child)))
    val suites = children.collect { case suite: StructureNode.Suite => suite }
    val (placedSuites, addedSuites) = suites.partition(_.position.nonEmpty)
    val firstScopeOrTest = firstScopeOrTestPosition.get(parent).flatten
    val (suitesBefore, suitesAfter) =
      placedSuites.sortBy(_.position).partition(suite => firstScopeOrTest.exists(first => suite.position.exists(_ < first)))
    addedSuites.foldLeft(suitesBefore ++ scopesAndTests ++ suitesAfter)(RunAssembly.withAddedSuite)
  }

  private def executionsOf(id: NodeId): Vector[Execution] = executions.get(id).fold(Vector.empty[Execution])(_.values.toVector)

  // An event assigned to the run rather than to a node shows no line, unless it is a message or the run's first
  // `RunAborted`. These include a `DiscoverySuite`'s events, which the emitter assigns to the run.
  private def addToRun(event: DecodedEvent): Unit =
    event match {
      case aborted: DecodedEvent.RunAborted => if (abort.isEmpty) abort = Some(aborted)
      case message if RunAssembly.isMessage(message) => messages :+= message
      case _ => ()
    }

  private def addToExecution(nodeExecution: NodeExecution, event: DecodedEvent): Unit = {
    recordExecutionPath(nodeExecution)
    val node = nodeExecution.node
    val number = nodeExecution.execution
    val execution = executions(node)(number)
    val updated =
      if (RunAssembly.isMessage(event)) execution.added(ReceivedMessage(event, childrenStarted(node, number)))
      else execution.added(event)
    executions(node) = executions(node).updated(number, updated)
  }

  // Reads the path against the latest structure: its last number is the node's, and each before it that of the next
  // node up, each scope between a test and its suite counted. Each execution keeps the parent execution it first had.
  private def recordExecutionPath(nodeExecution: NodeExecution): Unit = {
    val nodesUp = Iterator.unfold(Option(nodeExecution.node))(_.map(node => (node, structure.get(node).flatMap(RunAssembly.parentOf)))).toVector
    val executionsUp = nodesUp.zip(nodeExecution.executions.reverse).map((node, number) => ExecutionOfNode(node, number))
    executionsUp.zipWithIndex.foreach { (execution, index) =>
      val nodeExecutions = executions.getOrElse(execution.node, SortedMap.empty[Int, Execution])
      if (!nodeExecutions.contains(execution.number)) {
        val recorded = Execution(execution.number, executionsUp.lift(index + 1), Vector.empty, Vector.empty)
        executions(execution.node) = nodeExecutions.updated(execution.number, recorded)
      }
    }
  }

  private def childrenStarted(parent: NodeId, parentExecution: Int): Set[ExecutionOfNode] =
    childIds.get(parent).fold(Set.empty[ExecutionOfNode]) { children =>
      children.iterator.flatMap { child =>
        executionsOf(child)
          .filter(_.parent.contains(ExecutionOfNode(parent, parentExecution)))
          .map(execution => ExecutionOfNode(child, execution.number))
      }.toSet
    }
}

private[consolidation] object RunAssembly {

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted) => true
      case _ => false
    }

  /** Whether the event gives its run an outcome other than completed. */
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

  private def idOf(node: StructureNode): NodeId =
    node match {
      case suite: StructureNode.Suite => suite.id
      case scope: StructureNode.Scope => scope.id
      case test: StructureNode.Test => test.id
    }

  // A suite's or scope's node path is its parent's with its own place added. A test's place is directly after its
  // suite's, so its parent scope comes from the structure.
  private def parentOf(node: StructureNode): Option[NodeId] =
    node match {
      case suite: StructureNode.Suite => pathAbove(suite.id)
      case scope: StructureNode.Scope => pathAbove(scope.id)
      case test: StructureNode.Test => Some(test.parent)
    }

  private def pathAbove(id: NodeId): Option[NodeId] =
    if (id.places.size > 1) Some(NodeId(id.places.init)) else None

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
    child.executions.flatMap(_.number).exists(number => message.childrenStarted.contains(ExecutionOfNode(idOf(child.node), number)))

  private def isAbortedSuite(node: StructureNode, execution: Option[Execution]): Boolean =
    node.isInstanceOf[StructureNode.Suite] && execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteAborted]))

  private def endingOf(execution: Execution): Option[DecodedEvent] =
    execution.events.find {
      case _: DecodedEvent.TestFailed | _: DecodedEvent.TestCanceled | _: DecodedEvent.SuiteAborted => true
      case _ => false
    }

  // Marks as aborted:
  // - a scope or test still open when its suite aborted, or when its run aborted or stopped;
  // - a suite still open when its run aborted or stopped.
  private def outcomeOf(
    node: StructureNode,
    execution: Option[Execution],
    openScopeOrTestAborted: Boolean,
    runEndedAbnormally: Boolean,
    started: Boolean
  ): Option[Outcome] =
    (node, execution) match {
      case (_: StructureNode.Suite, _) => suiteOutcome(node, execution, runEndedAbnormally, started)
      case (_, None) => Some(Outcome.NotRun)
      case (_: StructureNode.Scope, Some(execution)) => scopeOutcome(execution, openScopeOrTestAborted)
      case (_: StructureNode.Test, Some(execution)) => testOutcome(execution, openScopeOrTestAborted)
    }

  private def suiteOutcome(node: StructureNode, execution: Option[Execution], runEndedAbnormally: Boolean, started: Boolean): Option[Outcome] =
    if (!started) Some(Outcome.NotRun)
    else if (isAbortedSuite(node, execution)) Some(Outcome.Aborted)
    else if (runEndedAbnormally && !execution.exists(_.holds(_.isInstanceOf[DecodedEvent.SuiteCompleted]))) Some(Outcome.Aborted)
    else None

  private def scopeOutcome(execution: Execution, openScopeOrTestAborted: Boolean): Option[Outcome] =
    if (execution.holds(_.isInstanceOf[DecodedEvent.ScopePending]))
      Some(Outcome.Pending)
    else if (execution.holds(_.isInstanceOf[DecodedEvent.ScopeClosed]))
      None
    else
      abortedWhereOpen(openScopeOrTestAborted)

  private def testOutcome(execution: Execution, openScopeOrTestAborted: Boolean): Option[Outcome] =
    execution.events.collectFirst {
      case _: DecodedEvent.TestSucceeded => Outcome.Succeeded
      case _: DecodedEvent.TestFailed => Outcome.Failed
      case _: DecodedEvent.TestIgnored => Outcome.Ignored
      case _: DecodedEvent.TestPending => Outcome.Pending
      case _: DecodedEvent.TestCanceled => Outcome.Canceled
    }.orElse(abortedWhereOpen(openScopeOrTestAborted))

  private def abortedWhereOpen(openScopeOrTestAborted: Boolean): Option[Outcome] =
    if (openScopeOrTestAborted) Some(Outcome.Aborted) else None
}
