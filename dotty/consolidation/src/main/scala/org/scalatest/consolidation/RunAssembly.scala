package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import scala.collection.immutable.SortedMap
import scala.collection.immutable.VectorMap
import scala.collection.mutable
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Location
import org.scalatest.events.NodeExecution
import org.scalatest.events.NodeId
import org.scalatest.events.RunOutcome
import org.scalatest.events.SeeStackDepthException
import org.scalatest.events.StructureNode
import org.scalatest.events.Summary
import org.scalatest.events.SuiteId
import org.scalatest.events.TestEventLogEvent

private[consolidation] final case class ExecutionOfNode(node: NodeId, index: ExecutionIndex)

extension (executions: collection.Map[NodeId, SortedMap[ExecutionIndex, RecordedExecution]]) {

  private[consolidation] def of(id: NodeId): Vector[RecordedExecution] =
    executions.get(id).fold(Vector.empty[RecordedExecution])(_.values.toVector)
}

/** A message, with the executions of its node's children that had started in its execution when it arrived. */
private[consolidation] final case class ReceivedMessage(message: ProvidedMessage, childrenStarted: Set[ExecutionOfNode]) {

  // A message sits after the last of the children shown under its execution that had started when it arrived.
  def placedAmong(children: Vector[AssembledNode]): PlacedMessage =
    PlacedMessage(message, children.lastIndexWhere(hadStarted) + 1)

  private def hadStarted(child: AssembledNode): Boolean =
    child.execution.index.exists(index => childrenStarted.contains(ExecutionOfNode(child.node.id, index)))
}

/**
 * @param parent the execution of the node's parent that this one ran in, as the first execution path naming it gives
 */
private[consolidation] final case class RecordedExecution(
  index: ExecutionIndex,
  startOrder: StartOrder,
  parent: Option[ExecutionOfNode],
  events: Vector[DecodedEvent.NodeOpening | DecodedEvent.NodeClosing],
  messages: Vector[ReceivedMessage]
) {

  def added(event: DecodedEvent.NodeOpening | DecodedEvent.NodeClosing): RecordedExecution = copy(events = events :+ event)

  def added(message: ReceivedMessage): RecordedExecution = copy(messages = messages :+ message)

  def holds(isOfKind: DecodedEvent => Boolean): Boolean = events.exists(isOfKind)

  def suiteAborted: Option[DecodedEvent.SuiteAborted] = events.collectFirst { case aborted: DecodedEvent.SuiteAborted => aborted }

  // A `DistributedTestRunnerSuite`'s `SuiteAborted` ends the test it ran even where the test's own closing event came
  // first, as when the suite's `afterEach` throws after the test succeeded.
  def closingEvent: Option[DecodedEvent.NodeClosing] =
    suiteAborted.orElse(events.collectFirst { case closing: DecodedEvent.NodeClosing => closing })

  def opening: Option[NodeEvent] = events.collectFirst { case opening: DecodedEvent.NodeOpening => NodeEvent.of(opening) }

  def closing: Option[NodeEvent] = closingEvent.map(NodeEvent.of)

  def failureEnding: Option[FailureEnding] = closingEvent.collect { case failure: DecodedEvent.NodeFailure => FailureEnding.of(failure) }

  def suiteOutcome(runFinished: Boolean, runEndedAbnormally: Boolean): Option[NodeOutcome] = {
    val completed = holds(_.isInstanceOf[DecodedEvent.SuiteCompleted])
    val opened = holds(_.isInstanceOf[DecodedEvent.SuiteStarting])
    if (suiteAborted.nonEmpty) Some(NodeOutcome.Aborted)
    else NodeOutcome.abortedWhere(!completed && (runEndedAbnormally || (runFinished && opened)))
  }

  def scopeOutcome(abortedWhereOpen: Boolean): Option[NodeOutcome] =
    if (holds(_.isInstanceOf[DecodedEvent.ScopePending]))
      Some(NodeOutcome.Pending)
    else if (holds(_.isInstanceOf[DecodedEvent.ScopeClosed]))
      None
    else
      NodeOutcome.abortedWhere(abortedWhereOpen)

  def testOutcome(abortedWhereOpen: Boolean): Option[NodeOutcome] =
    closingEvent.collect {
      case _: DecodedEvent.TestSucceeded => NodeOutcome.Succeeded
      case _: DecodedEvent.TestFailed => NodeOutcome.Failed
      case _: DecodedEvent.TestIgnored => NodeOutcome.Ignored
      case _: DecodedEvent.TestPending => NodeOutcome.Pending
      case _: DecodedEvent.TestCanceled => NodeOutcome.Canceled
      case _: DecodedEvent.SuiteAborted => NodeOutcome.Aborted
    }.orElse(NodeOutcome.abortedWhere(abortedWhereOpen))
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

private[consolidation] object NodeOutcome {

  def abortedWhere(aborted: Boolean): Option[NodeOutcome] = Option.when(aborted)(Aborted)
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
private[consolidation] final case class PlacedMessage(message: ProvidedMessage, childrenBefore: Int)

/**
 * @param index absent where the node has no execution
 * @param opening the execution's first `SuiteStarting`, `ScopeOpened` or `TestStarting`
 * @param closing the execution's `SuiteAborted`, else its first event that ends it
 * @param abortedAt for an aborted execution with no closing event of its own, the time of the nearest enclosing aborted
 *                  suite's `SuiteAborted`, else of the run's `RunAborted`
 * @param children the nodes shown under this execution, in order: a suite, scope or test once for each of its
 *                 executions that ran in this one
 */
private[consolidation] final case class AssembledExecution(
  index: Option[ExecutionIndex],
  outcome: Option[NodeOutcome],
  ending: Option[FailureEnding],
  opening: Option[NodeEvent],
  closing: Option[NodeEvent],
  abortedAt: Option[Instant],
  messages: Vector[PlacedMessage],
  children: Vector[AssembledNode]
) {

  def started: Boolean = index.nonEmpty || children.exists(_.started)

  // A collapsed execution shows the nodes below it only as a count.
  def isCollapsed: Boolean =
    outcome.exists(_.isAbortedOrNotRun) && !children.exists(_.started)

  def descendants: Vector[AssembledNode] = children.flatMap(_.withDescendants)

  /** The scopes and tests below this execution that belong to its own suite: nested suites, and what they hold, are left out. */
  def ownScopesAndTests: Vector[AssembledNode] =
    children.filter(_.kind.isScopeOrTest).flatMap(child => child +: child.execution.ownScopesAndTests)
}

/** A node of a run's test structure as the human-readable output shows it, once for each of its executions. */
private[consolidation] final case class AssembledNode(node: StructureNode, execution: AssembledExecution) {

  def name: String = node.name

  def kind: NodeKind = NodeKind.of(node)

  def label: Option[String] =
    node match {
      case _: StructureNode.Suite => None
      case scope: StructureNode.Scope => Some(scope.label)
      case test: StructureNode.Test => Some(test.label)
    }

  def suiteId: Option[SuiteId] =
    node match {
      case suite: StructureNode.Suite => Some(suite.suiteId)
      case _ => None
    }

  def started: Boolean = execution.started

  def descendants: Vector[AssembledNode] = execution.descendants

  def withDescendants: Vector[AssembledNode] = this +: descendants

  def startedAt: Option[Instant] = execution.opening.map(_.at)

  def endedAt: Option[Instant] = execution.closing.map(_.at).orElse(execution.abortedAt)

  // A suite's or test's duration comes from its closing event. A scope's closing event carries none, and an aborted node
  // may have no closing event of its own; either way the duration is endedAt less startedAt.
  def duration: Option[Duration] = {
    val measured = for (started <- startedAt; ended <- endedAt) yield Duration.between(started, ended)
    kind match {
      case NodeKind.Scope => measured
      case _ => execution.closing.fold(measured)(_.duration)
    }
  }

  def threadName: Option[String] =
    kind match {
      case NodeKind.Scope => None
      case _ => execution.opening.orElse(execution.closing).map(_.threadName)
    }

  // Where an opening event's location is `SeeStackDepthException`, which defers to the throwable, the closing event's location is used.
  def eventLocation: Option[Location] = {
    val events = kind match {
      case NodeKind.Scope => execution.opening.toVector
      case _ => (execution.opening ++ execution.closing).toVector
    }
    events.flatMap(_.location).find(_ != SeeStackDepthException)
  }
}

/** What the run's closing event, its first `RunCompleted`, `RunStopped` or `RunAborted`, reports. */
private[consolidation] final case class RunClosing(at: Instant, duration: Option[Duration], summary: Option[Summary])

private[consolidation] object RunClosing {

  def of(event: DecodedEvent.RunClosing): RunClosing = {
    val summary = event match {
      case completed: DecodedEvent.RunCompleted => completed.summary
      case stopped: DecodedEvent.RunStopped => stopped.summary
      case aborted: DecodedEvent.RunAborted => aborted.summary
    }
    RunClosing(Instant.ofEpochMilli(event.timeStamp), event.duration.map(Duration.ofMillis), summary)
  }
}

private[consolidation] final case class RunDiscovery(startedAt: Instant, duration: Option[Duration])

/** A message attached to a run rather than to a node, or a line captured from the run's processes. */
private[consolidation] enum RunOutput {
  case Message(message: ProvidedMessage)
  case Captured(line: CapturedLine)
}

/**
 * What a run's whole event stream recorded of the run.
 *
 * @param runStarting the run's first `RunStarting`
 * @param discovery from the run's first `DiscoveryStarting` and first `DiscoveryCompleted`, where it reported both
 * @param outcome how the run ended, from its `runEnded` event alone: absent where the stream held none
 * @param runAborted the run's first `RunAborted`, whose message and throwable explain an aborted run's end
 * @param reportedClasses the class of each suite the run reported
 * @param topLevelSuites the run's top-level suites, once for each execution, by name and then by the order the
 *                       executions started. Attributed nodes are left out.
 */
private[consolidation] final case class AssembledEventStream(
  runStarting: Option[DecodedEvent.RunStarting],
  discovery: Option[RunDiscovery],
  closing: Option[RunClosing],
  outcome: Option[RunOutcome],
  runAborted: Option[DecodedEvent.RunAborted],
  reportedClasses: Set[String],
  topLevelSuites: Vector[AssembledNode]
) {

  def startedAt: Option[Instant] = runStarting.map(started => Instant.ofEpochMilli(started.timeStamp))

  def endedAbnormally: Boolean = outcome.exists(_.isAbnormal)

  /** Whether the run did not finish, or any suite, scope or test execution in its tree aborted or did not run. */
  def failsTestCommand: Boolean =
    outcome.isEmpty || topLevelSuites.exists(_.withDescendants.exists(_.execution.outcome.exists(_.isAbortedOrNotRun)))
}

/** A run, its event stream ended, as the outputs read it. */
private[consolidation] final case class AssembledRun(
  label: TestRunLabel,
  worker: Option[Worker],
  eventStream: AssembledEventStream,
  output: Vector[RunOutput],
  assignedTestClasses: Vector[TestClassName]
) {

  /** The run's sort key among its module's runs: fork group, then worker index. A run given no worker has `None`, which sorts first. */
  def numberingOrder: Option[(Int, Int)] = worker.map(worker => (worker.forkGroup.position, worker.index.value))

  def ranNoneOfItsAssignedTestClasses: Boolean =
    assignedTestClasses.nonEmpty && !assignedTestClasses.exists(assigned => eventStream.reportedClasses.contains(assigned.value))
}

/**
 * Builds one run from its event stream and captured lines as they arrive. It keeps each node's structure by node path,
 * and the node's executions separately, by execution index.
 *
 * @param worker the worker the run ran in, where the caller gave one
 * @param assembleTree assembles the run's top-level suites from what its whole event stream recorded
 */
private[consolidation] final class RunAssembly(
  val label: TestRunLabel,
  val worker: Option[Worker],
  assembleTree: RecordedRun => Vector[AssembledNode]
) {

  private val structure = mutable.LinkedHashMap.empty[NodeId, StructureNode]
  private val childIds = mutable.Map.empty[NodeId, mutable.Set[NodeId]]
  private val latestScopesAndTests = mutable.Map.empty[NodeId, Set[NodeId]]
  private val firstScopeOrTestPosition = mutable.Map.empty[NodeId, Int]
  private val executions = mutable.Map.empty[NodeId, SortedMap[ExecutionIndex, RecordedExecution]]
  private var nextStartOrder = StartOrder.first
  private var received = Vector.empty[RunOutput]
  private var starting = Option.empty[DecodedEvent.RunStarting]
  private var discoveryStart = Option.empty[DecodedEvent.DiscoveryStarting]
  private var discoveryEnd = Option.empty[DecodedEvent.DiscoveryCompleted]
  private var closingEvent = Option.empty[RunClosing]
  private var abort = Option.empty[DecodedEvent.RunAborted]
  private var ending = Option.empty[RunOutcome]
  private var assignedTestClasses = Vector.empty[TestClassName]

  def add(event: TestEventLogEvent): Unit =
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

  def add(line: CapturedLine): Unit = received :+= RunOutput.Captured(line)

  def addAssigned(classes: Iterable[TestClassName]): Unit = assignedTestClasses ++= classes

  /** Assembles the run's tree from the events added so far, which must be the whole event stream. */
  def endEventStream(): AssembledEventStream =
    AssembledEventStream(
      starting,
      discovery,
      closingEvent,
      ending,
      abort,
      structure.values.collect { case suite: StructureNode.Suite => suite.className }.toSet,
      assembleTree(recorded)
    )

  /** The run with its captured lines, messages and assigned test classes as they are now. */
  def assembled(eventStream: AssembledEventStream): AssembledRun = AssembledRun(label, worker, eventStream, received, assignedTestClasses)

  private def discovery: Option[RunDiscovery] =
    for (started <- discoveryStart; completed <- discoveryEnd)
      yield RunDiscovery(Instant.ofEpochMilli(started.timeStamp), completed.duration.map(Duration.ofMillis))

  private def recorded: RecordedRun =
    RecordedRun(
      VectorMap.from(structure),
      executions.toMap,
      latestScopesAndTests.toMap,
      firstScopeOrTestPosition.toMap,
      ending,
      abort
    )

  // A further structure holds its suites' scopes and tests whole. A scope or test it drops keeps the parent, position
  // and label it last had. The emitter numbers a suite's scopes and tests from the same first position in every
  // structure, so the first structure holding any of them gives that position.
  private def addStructure(nodes: IndexedSeq[StructureNode]): Unit = {
    val suites = nodes.collect { case suite: StructureNode.Suite => suite.id }
    nodes.foreach(addToStructure)
    val suiteOfNode = nodes.foldLeft(Map.empty[NodeId, NodeId]) { (suites, node) =>
      node match {
        case suite: StructureNode.Suite => suites.updated(suite.id, suite.id)
        case scopeOrTest => scopeOrTest.parentId.flatMap(suites.get).fold(suites)(suites.updated(scopeOrTest.id, _))
      }
    }
    val scopesAndTests = nodes.filter(node => NodeKind.of(node).isScopeOrTest)
    val positionsByParent = scopesAndTests.groupMap(_.parentId)(_.declaredPosition)
    val scopesAndTestsBySuite = scopesAndTests.groupMap(node => suiteOfNode.get(node.id))(_.id)
    suites.filterNot(firstScopeOrTestPosition.contains).foreach { suite =>
      positionsByParent.get(Some(suite)).flatMap(_.flatten.minOption).foreach(firstScopeOrTestPosition(suite) = _)
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

  // An event assigned to the run rather than to a node is handled by kind:
  // - a run or discovery event records the run's own state; only the first of each kind is kept;
  // - a message is added to the run's output;
  // - a suite's, scope's or test's event, such as a `DiscoverySuite`'s (which the emitter assigns to the run), is dropped.
  private def addToRun(event: DecodedEvent): Unit =
    event match {
      case started: DecodedEvent.RunStarting =>
        if (starting.isEmpty) starting = Some(started)
      case started: DecodedEvent.DiscoveryStarting =>
        if (discoveryStart.isEmpty) discoveryStart = Some(started)
      case completed: DecodedEvent.DiscoveryCompleted =>
        if (discoveryEnd.isEmpty) discoveryEnd = Some(completed)
      case aborted: DecodedEvent.RunAborted =>
        if (abort.isEmpty) abort = Some(aborted)
        close(RunClosing.of(aborted))
      case closed: DecodedEvent.RunClosing =>
        close(RunClosing.of(closed))
      case message: DecodedEvent.Message =>
        received :+= RunOutput.Message(ProvidedMessage.of(message))
      case _: DecodedEvent.NodeOpening | _: DecodedEvent.NodeClosing =>
        ()
    }

  private def close(closed: RunClosing): Unit = if (closingEvent.isEmpty) closingEvent = Some(closed)

  // A run's own event assigned to a node is dropped: the node's execution reads only its suite, scope or test events
  // and its messages.
  private def addToExecution(nodeExecution: NodeExecution, event: DecodedEvent): Unit = {
    recordExecutionPath(nodeExecution)
    val node = nodeExecution.node
    val index = ExecutionIndex(nodeExecution.executionIndex)
    val execution = executions(node)(index)
    val updated = event match {
      case message: DecodedEvent.Message =>
        execution.added(ReceivedMessage(ProvidedMessage.of(message), childrenStarted(node, index)))
      case nodeEvent: (DecodedEvent.NodeOpening | DecodedEvent.NodeClosing) =>
        execution.added(nodeEvent)
      case _: DecodedEvent.RunStarting | _: DecodedEvent.DiscoveryStarting | _: DecodedEvent.DiscoveryCompleted | _: DecodedEvent.RunClosing =>
        execution
    }
    executions(node) = executions(node).updated(index, updated)
  }

  // Reads the execution path against the latest structure:
  // - its last index is the node's own; each earlier index belongs to the parent, then the grandparent, and so on,
  //   counting each scope between a test and its suite;
  // - an execution keeps the parent execution it first had;
  // - start order runs top down, so a parent's start precedes its child's.
  private def recordExecutionPath(nodeExecution: NodeExecution): Unit = {
    val nodesUp = Iterator.unfold(Option(nodeExecution.node))(_.map(node => (node, structure.get(node).flatMap(_.parentId)))).toVector
    val executionsUp = nodesUp.zip(nodeExecution.executionPath.reverse).map((node, index) => ExecutionOfNode(node, ExecutionIndex(index)))
    executionsUp.indices.reverse.foreach { position =>
      val execution = executionsUp(position)
      val nodeExecutions = executions.getOrElse(execution.node, SortedMap.empty[ExecutionIndex, RecordedExecution])
      if (!nodeExecutions.contains(execution.index)) {
        val recorded = RecordedExecution(execution.index, nextStartOrder, executionsUp.lift(position + 1), Vector.empty, Vector.empty)
        nextStartOrder = nextStartOrder.next
        executions(execution.node) = nodeExecutions.updated(execution.index, recorded)
      }
    }
  }

  private def childrenStarted(parent: NodeId, parentExecution: ExecutionIndex): Set[ExecutionOfNode] =
    childIds.get(parent).fold(Set.empty[ExecutionOfNode]) { children =>
      children.iterator.flatMap { child =>
        executions.of(child)
          .filter(_.parent.contains(ExecutionOfNode(parent, parentExecution)))
          .map(execution => ExecutionOfNode(child, execution.index))
      }.toSet
    }
}

private[consolidation] object RunAssembly {

  def apply(label: TestRunLabel, worker: Option[Worker]): RunAssembly = new RunAssembly(label, worker, TreeAssembler.topLevelSuitesOf)

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.NodeFailure) => true
      case _ => false
    }

  def endsOtherThanCompleted(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.RunEnded(outcome) => outcome.isAbnormal
      case _ => false
    }
}
