package org.scalatest.consolidation

import java.time.Duration
import java.time.Instant
import scala.collection.immutable.SortedMap
import org.scalatest.events.DecodedEvent
import org.scalatest.events.JsonValue
import org.scalatest.events.Location
import org.scalatest.events.NodeId
import org.scalatest.events.RunOutcome
import org.scalatest.events.SeeStackDepthException
import org.scalatest.events.StructureNode
import org.scalatest.events.Summary
import org.scalatest.events.SuiteId
import org.scalatest.events.TestClassName
import org.scalatest.events.TestEventLogEvent

private[consolidation] final case class ExecutionOfNode(node: NodeId, index: ExecutionIndex)

private[consolidation] final case class RecordedExecutions(byNode: Map[NodeId, SortedMap[ExecutionIndex, RecordedExecution]]) {

  def nodes: Iterator[NodeId] = byNode.keysIterator

  def contains(id: NodeId): Boolean = byNode.contains(id)

  def contains(execution: ExecutionOfNode): Boolean = byNode.get(execution.node).exists(_.contains(execution.index))

  def of(id: NodeId): Vector[RecordedExecution] = byNode.get(id).fold(Vector.empty[RecordedExecution])(_.values.toVector)

  def get(execution: ExecutionOfNode): Option[RecordedExecution] = byNode.get(execution.node).flatMap(_.get(execution.index))

  def apply(execution: ExecutionOfNode): RecordedExecution = byNode(execution.node)(execution.index)

  def updated(execution: ExecutionOfNode, recorded: RecordedExecution): RecordedExecutions = {
    val executionsOfNode = byNode.getOrElse(execution.node, SortedMap.empty[ExecutionIndex, RecordedExecution])
    RecordedExecutions(byNode.updated(execution.node, executionsOfNode.updated(execution.index, recorded)))
  }
}

private[consolidation] object RecordedExecutions {

  val none: RecordedExecutions = RecordedExecutions(Map.empty)
}

private[consolidation] final case class ReceivedMessage(message: ProvidedMessage, childrenStarted: Set[ExecutionOfNode]) {

  def placedAmong(children: Vector[AssembledNode]): PlacedMessage =
    PlacedMessage(message, children.lastIndexWhere(hadStarted) + 1)

  private def hadStarted(child: AssembledNode): Boolean =
    child.execution.index.exists(index => childrenStarted.contains(ExecutionOfNode(child.node.id, index)))
}

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

  def closingEvent: Option[DecodedEvent.NodeClosing] =
    // The `SuiteAborted` of the `DistributedTestRunnerSuite` that ran a test ends that test even when the test's own
    // closing event came first, as when the suite's `afterEach` throws after the test succeeded.
    suiteAborted.orElse(events.collectFirst { case closing: DecodedEvent.NodeClosing => closing })

  def opening: Option[NodeEvent] = events.collectFirst { case opening: DecodedEvent.NodeOpening => NodeEvent.of(opening) }

  def closing: Option[NodeEvent] = closingEvent.map(NodeEvent.of)

  def failureEnding: Option[FailureEnding] = closingEvent.collect { case failure: DecodedEvent.NodeFailure => FailureEnding.of(failure) }

  def suiteOutcome(runFinished: Boolean, runEndedAbnormally: Boolean): Option[NodeOutcome] = {
    val completed = holds(_.isInstanceOf[DecodedEvent.SuiteCompleted])
    // A suite run by a test sends no `SuiteStarting`, so it is not marked aborted when its run ends normally.
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

private[consolidation] final case class OrderedChildren(inDeclaredOrder: Vector[StructureNode], unplacedSuites: Vector[StructureNode])

private[consolidation] final case class PlacedMessage(message: ProvidedMessage, childrenBefore: Int)

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

  def isCollapsed: Boolean =
    outcome.exists(_.isAbortedOrNotRun) && !children.exists(_.started)

  def descendants: Vector[AssembledNode] = children.flatMap(_.withDescendants)

  def ownScopesAndTests: Vector[AssembledNode] =
    children.filter(_.kind.isScopeOrTest).flatMap(child => child +: child.execution.ownScopesAndTests)
}

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

  def duration: Option[Duration] = {
    val measured = for (started <- startedAt; ended <- endedAt) yield Duration.between(started, ended)
    kind match {
      // A scope's closing event carries no duration.
      case NodeKind.Scope => measured
      case _ => execution.closing.fold(measured)(_.duration)
    }
  }

  def threadName: Option[String] =
    kind match {
      case NodeKind.Scope => None
      case _ => execution.opening.orElse(execution.closing).map(_.threadName)
    }

  def eventLocation: Option[Location] = {
    val events = kind match {
      case NodeKind.Scope => execution.opening.toVector
      case _ => (execution.opening ++ execution.closing).toVector
    }
    events.flatMap(_.location).find(_ != SeeStackDepthException)
  }
}

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

private[consolidation] final case class RunDiscovery(startedAt: Instant, duration: Option[Duration]) {

  def toJson: JsonValue =
    JsonOutputWriter.objectOf(
      Vector(
        "startedAt" -> Some(JsonOutputWriter.epochMillis(startedAt)),
        "duration" -> duration.map(JsonOutputWriter.millis)
      )
    )
}

private[consolidation] object RunDiscovery {

  def of(started: DecodedEvent.DiscoveryStarting, completed: DecodedEvent.DiscoveryCompleted): RunDiscovery =
    RunDiscovery(Instant.ofEpochMilli(started.timeStamp), completed.duration.map(Duration.ofMillis))
}

private[consolidation] enum RunOutput {
  case Message(message: ProvidedMessage)
  case Captured(line: CapturedLine)
}

private[consolidation] final case class AssembledEventStream(
  starting: Option[DecodedEvent.RunStarting],
  discovery: Option[RunDiscovery],
  closing: Option[RunClosing],
  outcome: Option[RunOutcome],
  abort: Option[DecodedEvent.RunAborted],
  suiteClasses: Set[TestClassName],
  topLevelSuites: Vector[AssembledNode]
) {

  def startedAt: Option[Instant] = starting.map(started => Instant.ofEpochMilli(started.timeStamp))

  def endedAbnormally: Boolean = outcome.exists(_.isAbnormal)

  // A stream without the run's outcome is one the consolidation aborts with reason process failed.
  def failsItsModule: Boolean = outcome.forall(_.isAbnormal)

  def failsTestCommand: Boolean =
    outcome.isEmpty || topLevelSuites.exists(_.withDescendants.exists(_.execution.outcome.exists(_.isAbortedOrNotRun)))
}

private[consolidation] final case class AssembledRun(
  module: ModuleName,
  worker: Option[Worker],
  eventStream: AssembledEventStream,
  output: Vector[RunOutput],
  assignedTestClasses: Vector[TestClassName]
) {

  def ranNoneOfItsAssignedTestClasses: Boolean =
    assignedTestClasses.nonEmpty && !assignedTestClasses.exists(eventStream.suiteClasses.contains)
}

private[consolidation] object AssembledRun {

  val numberingOrder: Ordering[Option[Worker]] =
    Ordering.Option(Ordering.by[Worker, (Int, Int)](worker => (worker.forkGroup.position, worker.index.value)))
}

private[consolidation] final case class RunAssembly private (
  module: ModuleName,
  worker: Option[Worker],
  assembleTopLevelSuites: RecordedRun => Vector[AssembledNode],
  recorded: RecordedRun,
  output: Vector[RunOutput],
  assignedTestClasses: Vector[TestClassName]
) {

  def added(event: TestEventLogEvent): RunAssembly =
    copy(recorded = recorded.added(event), output = output ++ RunAssembly.runMessageOf(event))

  def added(line: CapturedLine): RunAssembly = copy(output = output :+ RunOutput.Captured(line))

  def withAssigned(classes: Iterable[TestClassName]): RunAssembly = copy(assignedTestClasses = assignedTestClasses ++ classes)

  def assembledWholeEventStream: AssembledEventStream =
    AssembledEventStream(
      recorded.starting,
      recorded.discovery,
      recorded.closing,
      recorded.outcome,
      recorded.abort,
      recorded.suiteClasses,
      assembleTopLevelSuites(recorded)
    )

  def assembled(eventStream: AssembledEventStream): AssembledRun = AssembledRun(module, worker, eventStream, output, assignedTestClasses)
}

private[consolidation] object RunAssembly {

  def apply(module: ModuleName, worker: Option[Worker]): RunAssembly = apply(module, worker, TreeAssembler.topLevelSuitesOf)

  def apply(module: ModuleName, worker: Option[Worker], assembleTopLevelSuites: RecordedRun => Vector[AssembledNode]): RunAssembly =
    new RunAssembly(module, worker, assembleTopLevelSuites, RecordedRun.empty, Vector.empty, Vector.empty)

  def reportsFailure(event: TestEventLogEvent): Boolean =
    event match {
      case TestEventLogEvent.ScalaTestEvent(_, _: DecodedEvent.NodeFailure) => true
      case _ => false
    }

  def reportsTestResult(event: TestEventLogEvent): Boolean =
    reportsFailure(event) || {
      event match {
        case TestEventLogEvent.ScalaTestEvent(_, _: (DecodedEvent.TestSucceeded | DecodedEvent.TestIgnored | DecodedEvent.TestPending)) => true
        case _ => false
      }
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
