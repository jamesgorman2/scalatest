package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import scala.collection.immutable.SortedMap
import org.scalatest.events.DecodedEvent
import org.scalatest.events.Location
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

private[consolidation] object RunDiscovery {

  def of(started: DecodedEvent.DiscoveryStarting, completed: DecodedEvent.DiscoveryCompleted): RunDiscovery =
    RunDiscovery(Instant.ofEpochMilli(started.timeStamp), completed.duration.map(Duration.ofMillis))
}

/** A message attached to a run rather than to a node, or a line captured from the run's processes. */
private[consolidation] enum RunOutput {
  case Message(message: ProvidedMessage)
  case Captured(line: CapturedLine)
}

private[consolidation] final case class AssembledEventStream(
  starting: Option[DecodedEvent.RunStarting],
  discovery: Option[RunDiscovery],
  closing: Option[RunClosing],
  // From the runEnded event alone.
  outcome: Option[RunOutcome],
  abort: Option[DecodedEvent.RunAborted],
  reportedClasses: Set[String],
  // Once per execution, by name then start order; attributed nodes are left out.
  topLevelSuites: Vector[AssembledNode]
) {

  def startedAt: Option[Instant] = starting.map(started => Instant.ofEpochMilli(started.timeStamp))

  def endedAbnormally: Boolean = outcome.exists(_.isAbnormal)

  def failsTestCommand: Boolean =
    outcome.isEmpty || topLevelSuites.exists(_.withDescendants.exists(_.execution.outcome.exists(_.isAbortedOrNotRun)))
}

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

private[consolidation] final case class RunAssembly private (
  label: TestRunLabel,
  worker: Option[Worker],
  // Assembles the run's top-level suites.
  assembleTree: RecordedRun => Vector[AssembledNode],
  recorded: RecordedRun,
  output: Vector[RunOutput],
  assignedTestClasses: Vector[TestClassName]
) {

  def added(event: TestEventLogEvent): RunAssembly =
    copy(recorded = recorded.added(event), output = output ++ RunAssembly.runMessageOf(event))

  def added(line: CapturedLine): RunAssembly = copy(output = output :+ RunOutput.Captured(line))

  def withAssigned(classes: Iterable[TestClassName]): RunAssembly = copy(assignedTestClasses = assignedTestClasses ++ classes)

  // Call only once the whole event stream has been added.
  def assembledEventStream: AssembledEventStream =
    AssembledEventStream(
      recorded.starting,
      recorded.discovery,
      recorded.closing,
      recorded.outcome,
      recorded.abort,
      recorded.suiteClassNames,
      assembleTree(recorded)
    )

  def assembled(eventStream: AssembledEventStream): AssembledRun = AssembledRun(label, worker, eventStream, output, assignedTestClasses)
}

private[consolidation] object RunAssembly {

  def apply(label: TestRunLabel, worker: Option[Worker]): RunAssembly = apply(label, worker, TreeAssembler.topLevelSuitesOf)

  def apply(label: TestRunLabel, worker: Option[Worker], assembleTree: RecordedRun => Vector[AssembledNode]): RunAssembly =
    new RunAssembly(label, worker, assembleTree, RecordedRun.empty, Vector.empty, Vector.empty)

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.NodeFailure) => true
      case _ => false
    }

  def endsRunAbnormally(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.RunEnded(outcome) => outcome.isAbnormal
      case _ => false
    }

  private def runMessageOf(event: TestEventLogEvent): Option[RunOutput] =
    event match {
      case TestEventLogEvent.ScalaTestEvent(None, message: DecodedEvent.Message) => Some(RunOutput.Message(ProvidedMessage.of(message)))
      case _ => None
    }
}
