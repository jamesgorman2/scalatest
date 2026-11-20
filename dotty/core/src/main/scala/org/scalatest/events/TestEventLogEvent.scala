package org.scalatest.events

import org.scalatest.tools.StackDepthView
import org.scalatest.tools.ThrowableView

private[scalatest] enum NodePlace {
  case Suite(suiteId: SuiteId, index: Int)
  case Scope(text: String, index: Int)
  case Test(scalaTestName: TestName)
}

private[scalatest] final case class NodeId(places: Vector[NodePlace])

private[scalatest] object NodeId {

  def topLevelSuite(suiteId: SuiteId, index: Int): NodeId = NodeId(Vector(NodePlace.Suite(suiteId, index)))

  def nestedSuite(parent: NodeId, suiteId: SuiteId, index: Int): NodeId = NodeId(parent.places :+ NodePlace.Suite(suiteId, index))

  def scope(parent: NodeId, text: String, index: Int): NodeId = NodeId(parent.places :+ NodePlace.Scope(text, index))

  def test(suite: NodeId, testName: TestName): NodeId = NodeId(suite.places :+ NodePlace.Test(testName))
}

private[scalatest] enum StructureNode {
  case Suite(id: NodeId, parent: Option[NodeId], position: Option[Int], name: String, suiteId: SuiteId, className: String)
  case Scope(id: NodeId, parent: NodeId, position: Int, name: String, label: String)
  case Test(id: NodeId, parent: NodeId, position: Int, name: String, label: String, ignored: Boolean = false)

  def id: NodeId

  def name: String

  def parentId: Option[NodeId] =
    this match {
      case suite: Suite => suite.parent
      case scope: Scope => Some(scope.parent)
      case test: Test => Some(test.parent)
    }

  def declaredPosition: Option[Int] =
    this match {
      case suite: Suite => suite.position
      case scope: Scope => Some(scope.position)
      case test: Test => Some(test.position)
    }
}

private[scalatest] final case class NodeExecution(
  node: NodeId,
  // Also holds an index for each scope a test runs in, though a test's node names no scope.
  executionPath: Vector[Int]
) {

  def executionIndex: Int = executionPath.last
}

private[scalatest] enum TestEventLogEvent {
  case RunStarted(pid: Long, startTime: String)
  case Structure(nodes: IndexedSeq[StructureNode])
  case ScalaTestEvent(execution: Option[NodeExecution], event: DecodedEvent)
  case RunEnded(outcome: RunOutcome)
}

private[scalatest] enum DecodedEvent {
  case TestStarting(override val location: Option[Location], threadName: String, timeStamp: Long)
  case TestSucceeded(override val duration: Option[Long], override val location: Option[Location], threadName: String, timeStamp: Long)
  case TestFailed(
    message: String,
    analysis: IndexedSeq[String],
    throwable: Option[DecodedThrowable],
    override val duration: Option[Long],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case TestIgnored(override val location: Option[Location], threadName: String, timeStamp: Long)
  case TestPending(override val duration: Option[Long], override val location: Option[Location], threadName: String, timeStamp: Long)
  case TestCanceled(
    message: String,
    throwable: Option[DecodedThrowable],
    override val duration: Option[Long],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case SuiteStarting(override val location: Option[Location], threadName: String, timeStamp: Long)
  case SuiteCompleted(override val duration: Option[Long], override val location: Option[Location], threadName: String, timeStamp: Long)
  case SuiteAborted(
    message: String,
    throwable: Option[DecodedThrowable],
    override val duration: Option[Long],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunStarting(
    testCount: Int,
    configMap: Map[String, String],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunCompleted(
    override val duration: Option[Long],
    summary: Option[Summary],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunStopped(
    override val duration: Option[Long],
    summary: Option[Summary],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case RunAborted(
    message: String,
    throwable: Option[DecodedThrowable],
    override val duration: Option[Long],
    summary: Option[Summary],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case InfoProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case AlertProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case NoteProvided(
    message: String,
    throwable: Option[DecodedThrowable],
    override val location: Option[Location],
    threadName: String,
    timeStamp: Long
  )
  case MarkupProvided(text: String, override val location: Option[Location], threadName: String, timeStamp: Long)
  case ScopeOpened(override val location: Option[Location], threadName: String, timeStamp: Long)
  case ScopeClosed(override val location: Option[Location], threadName: String, timeStamp: Long)
  case ScopePending(override val location: Option[Location], threadName: String, timeStamp: Long)
  case DiscoveryStarting(threadName: String, timeStamp: Long)
  case DiscoveryCompleted(override val duration: Option[Long], threadName: String, timeStamp: Long)

  def threadName: String

  def timeStamp: Long

  def location: Option[Location] = None

  def duration: Option[Long] = None
}

private[scalatest] final case class DecodedStackDepth(
  fileName: Option[String],
  filePathname: Option[String],
  lineNumber: Option[Int]
) extends StackDepthView {

  def fileNameAndLineNumber: Option[String] = withLineNumber(fileName)

  def filePathnameAndLineNumber: Option[String] = withLineNumber(filePathname)

  def failedCodeStackDepth: Option[Int] = None

  private def withLineNumber(file: Option[String]): Option[String] =
    for (name <- file; line <- lineNumber) yield name + ":" + line
}

private[scalatest] final case class DecodedStackFrame(
  className: String,
  methodName: String,
  fileName: Option[String],
  lineNumber: Option[Int],
  isNative: Boolean,
  text: String
)

private[scalatest] final class DecodedThrowable(
  val className: String,
  val message: Option[String],
  val stackDepth: Option[DecodedStackDepth],
  val isPropertyCheckFailure: Boolean,
  val stackTrace: IndexedSeq[DecodedStackFrame],
  linkedCause: => Option[DecodedThrowable] = None,
  linkedSuppressed: => IndexedSeq[DecodedThrowable] = Vector.empty
) extends ThrowableView {

  // Not a case class: a throwable can be its own cause or suppressed throwable, so a structural equals, hashCode or
  // toString would never return.

  // Evaluated on first read, so that a decoder can build a throwable before the throwables that link back to it.
  lazy val cause: Option[DecodedThrowable] = linkedCause
  lazy val suppressed: IndexedSeq[DecodedThrowable] = linkedSuppressed

  def frames: IndexedSeq[String] = stackTrace.map(_.text)
}
